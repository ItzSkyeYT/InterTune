/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.utils.ErrorText
import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.HeldTail
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.MixRender
import com.dd3boh.outertune.mix.PlanInput
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Style
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max

/**
 * Decides, for each change of song, whether and how it is mixed, and hands the stage what it
 * needs when it needs it. A listener on the phone's own player, on the main thread, as
 * TransitionFade is; reading the next song's opening on Dispatchers.IO; analysing, planning and
 * rendering on a worker thread of its own, at a low priority.
 *
 * For each change, while the song plays:
 * 1. With [AutomixSchedule.READ_LEFT_MS] left, the next song's opening is read and analysed.
 * 2. Between 40 and 25 s left, with the opening analysed, both songs loaded and every guard
 *    passing, the stage is asked to hold the end. Later than that, the change is today's.
 * 3. When the song's stream ends, the end is all held: it is analysed exactly as it will be heard,
 *    the transition planned and the outgoing part rendered, and the plan handed to the stage, which
 *    takes it only if it is in time.
 * 4. In a beat mix, the incoming song's first two seconds are held against its analysis, and the
 *    outgoing song fades away at once if they do not line up.
 *
 * Every decision is one line in the log, starting "AutoMix:". Nothing here seeks, so a song that
 * ends by itself is still the player's own automatic change, and its listen is ENDED.
 */
@UnstableApi
class AutomixController(
    private val player: ExoPlayer,
    private val stage: AutomixAudioProcessor,
    private val decoder: HeadDecoder,
    private val scope: CoroutineScope,
    /** What the service knows that the player does not; read on the main thread. */
    private val conditions: () -> Conditions,
    /**
     * A song's loudness normalisation factor from what is stored about it, and whether that is
     * final: not final while no loudness is stored for the song, which reading it will bring.
     * Blocking, so called on IO.
     */
    private val normalizationOf: (String) -> Pair<Float, Boolean>?,
) : Player.Listener, AutomixAudioProcessor.Events {

    class Conditions(
        val casting: Boolean,
        val sleepTimer: Boolean,
        val headTracking: Boolean,
        val upmix: Boolean,
        val ambience: Boolean,
        val ffmpeg: Boolean,
        /** The service's normalisation of the song playing, for a song whose own is not read yet. */
        val normalizeNow: Float,
        val volume: Float,
        /** A style asked for in the developer options, debug builds only. */
        val forcedStyle: Style?,
    )

    /** Whether the stage scales each song by its normalisation, so that the volume must leave it out (AutomixVolume). */
    private val _stageNormalises = MutableStateFlow(false)
    val stageNormalises: StateFlow<Boolean> = _stageNormalises.asStateFlow()

    /** The setting. Off costs nothing: no tick, no read, no worker. */
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            stage.wanted = value
            if (!value) {
                transition?.let { abort(it, "AutoMix was switched off") }
                stopWork()
                say("off; it leaves the chain at the next song")
            } else {
                say("on; it joins the chain at the next song")
            }
            refresh()
        }

    private val main = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var tickJob: Job? = null
    private var transition: Transition? = null

    /** The change whose blend still plays after the player has moved on to the next song. */
    private var mixing: Transition? = null
    private var nextTransitionId = 1L

    /** Openings read before, without their decimated copy, for a song that comes next again. */
    private val heads = object : LinkedHashMap<String, RegionAnalysis>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RegionAnalysis>?) = size > HEAD_CACHE
    }

    private val normalizations = HashMap<String, Float>()
    private val normalizationsAsked = HashSet<String>()
    private val lastStyles = ArrayDeque<Style>()
    private var lastWorkMs: Long? = null

    /** The song the listener moved within its last minute; its end is not mixed. */
    private var seekedLateId: String? = null

    private val window = Timeline.Window()

    init {
        stage.events = this
    }

    /** One change of song, from [a] into [b]. */
    private inner class Transition(val id: Long, val a: AutomixGuards.Song, val b: AutomixGuards.Song?) {
        var head: RegionAnalysis? = null
        var headWithGuide = false
        var reading: Job? = null
        val cancelled = AtomicBoolean(false)
        var headFailed = false
        var refusal: String? = null
        var lateSaid = false
        var armTries = 0
        var armRequested = false
        var armedAtMs = 0L
        var expectedTailMs = 0L
        var tailStartMs = 0L
        var sampleRate = 0
        var holding = false
        var plan: MixPlan? = null
        var finished = false
    }

    // ---- the player

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncNormalising()
        if (!enabled) return
        if (mediaItem?.mediaId != seekedLateId) seekedLateId = null
        refresh()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        syncNormalising()
        if (!enabled) return
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            val duration = player.duration
            val id = newPosition.mediaItem?.mediaId
            seekedLateId = if (duration > 0 && duration - newPosition.positionMs <= AutomixSchedule.SEEK_LATE_MS) id else null
            // The seek flushed the stage, which let go of anything it held. The change starts over
            // from where the music is now, with the opening already read.
            stage.cancelArm()
            mixing = null
            transition?.takeIf { it.armRequested || it.holding }?.let { old ->
                if (old.holding) say("dropped: a seek")
                transition = Transition(nextTransitionId++, old.a, old.b).also {
                    it.head = old.head
                    it.headWithGuide = old.headWithGuide
                    it.headFailed = old.headFailed
                }
            }
        }
        refresh()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        syncNormalising()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!enabled) return
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
        ) {
            refresh()
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (playbackParameters.speed != 1f || playbackParameters.pitch != 1f) parametersChanged("the speed or the pitch changed")
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        if (skipSilenceEnabled) parametersChanged("skip silence was turned on")
    }

    private fun parametersChanged(why: String) {
        if (!enabled) return
        val t = transition?.takeIf { it.holding } ?: mixing?.takeIf { it.holding } ?: return
        abort(t, why)
        // The sink applies new parameters only once it has drained, and with an end held that is
        // the whole end: up to forty seconds before the change is heard. Going to where the music
        // is now drops what is held, and the change is heard at once.
        if (stage.state == HeldTail.State.CAPTURE || stage.state == HeldTail.State.DRAIN) player.seekTo(player.currentPosition)
    }

    /** Settings the service follows changed (the normalisation, the volume): the levels are worked out again. */
    fun levelsChanged(normalizationSettingChanged: Boolean) {
        if (normalizationSettingChanged) {
            normalizations.clear()
            normalizationsAsked.clear()
        }
        publishLevels()
    }

    fun release() {
        enabled = false
        tickJob?.cancel()
        transition?.reading?.cancel()
        stopWork()
    }

    // ---- what happens for each song

    /** Looks again at what plays and what comes next, and at what is due. */
    private fun refresh() {
        tickJob?.cancel()
        tickJob = null
        if (!enabled) return
        val current = player.currentMediaItem
        val next = nextItem()
        val t = transition
        if (current == null) {
            transition = null
            publishLevels()
            return
        }
        if (t == null || t.a.id != current.mediaId || t.b?.id != next?.mediaId) {
            t?.let { old ->
                old.cancelled.set(true)
                if (old.a.id == current.mediaId) abort(old, "the next song changed")
            }
            transition = Transition(nextTransitionId++, song(current, player.duration), next?.let { song(it, -1) })
        }
        publishLevels()
        tick()
    }

    private fun tick() {
        val t = transition ?: return
        if (t.finished) return
        if (t.holding) {
            // What may change while the end is held, and stops it: a sleep timer, casting, the
            // setting. The queue and the playback parameters have their own events.
            AutomixGuards.whyNot(snapshot(t))?.let { abort(t, it) } ?: later(HOLDING_LOOK_MS)
            return
        }
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return later(AutomixSchedule.nextLookMs(Long.MAX_VALUE, 1f))
        val left = duration - player.currentPosition
        val why = AutomixGuards.whyNot(snapshot(t))
        if (why != null) {
            if (why != t.refusal) {
                t.refusal = why
                say("not mixing ${t.a.id} into ${t.b?.id ?: "nothing"}: $why")
            }
            // Conditions can change (a sleep timer cancelled, skip silence turned off), and each
            // is looked at again when its event comes; nothing ticks for a change that cannot be.
            return later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed))
        }
        t.refusal = null
        val b = t.b!!
        when (AutomixSchedule.due(left)) {
            AutomixSchedule.Step.WAIT -> Unit
            AutomixSchedule.Step.READ -> readHead(t, b)
            AutomixSchedule.Step.ARM -> {
                readHead(t, b)
                if (!t.armRequested && t.head != null) {
                    if (AutomixSchedule.loaded(duration, player.currentPosition, player.bufferedPosition, player.totalBufferedDuration)) arm(t, left)
                }
            }
            AutomixSchedule.Step.TOO_LATE -> if (!t.armRequested && !t.lateSaid) {
                t.lateSaid = true
                val what = when {
                    t.headFailed -> "its opening could not be read"
                    t.head == null -> "its opening was not read in time"
                    else -> "the songs were not loaded in time"
                }
                say("not mixing ${t.a.id} into ${b.id}: $what")
            }
        }
        if (!t.armRequested) later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed))
    }

    private fun later(ms: Long?) {
        if (ms == null || !player.isPlaying) return
        tickJob = scope.launch {
            delay(ms)
            tickJob = null
            tick()
        }
    }

    private fun readHead(t: Transition, b: AutomixGuards.Song) {
        if (t.head != null || t.reading != null || t.headFailed) return
        heads[b.id]?.let {
            t.head = it
            say("opening of ${b.id} known from before: ${describe(it)}")
            return
        }
        val cancelled = t.cancelled
        t.reading = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { decoder.read(b.id) { cancelled.get() } }
            }
            val read = result.getOrElse { e ->
                if (!cancelled.get()) {
                    t.headFailed = true
                    // Through ErrorText: a read that failed on the network names the address it was given.
                    say("could not read the opening of ${b.id}: " + ErrorText.withoutAddresses("${e.javaClass.simpleName}: ${e.message?.take(160)}"))
                }
                t.reading = null
                return@launch
            }
            val work = work() ?: return@launch
            work.execute {
                val analysis = runCatching { RegionAnalysis.analyseHead(read.front) }
                main.post {
                    t.reading = null
                    val head = analysis.getOrElse { e ->
                        t.headFailed = true
                        say("could not analyse the opening of ${b.id}: ${e.javaClass.simpleName}")
                        return@post
                    }
                    heads[b.id] = withoutGuide(head)
                    if (cancelled.get()) return@post
                    t.head = head
                    t.headWithGuide = true
                    say("opening of ${b.id}: ${describe(head)}, read in ${read.readMs} ms, analysed in ${head.analysisMillis.toLong()} ms")
                    tick()
                }
            }
        }
    }

    private fun arm(t: Transition, left: Long) {
        val format = player.audioFormat ?: return
        val rate = format.sampleRate
        val channels = format.channelCount
        if (rate <= 0 || channels <= 0) return
        if (!stage.activeForStream || t.armTries >= ARM_TRIES) return
        val head = t.head ?: return
        if (head.sampleRate != rate || head.channels != channels) {
            // The sink cannot carry one track into a track of another format: it would play the
            // whole end out and start the next song afresh, which is today's change anyway.
            t.armRequested = true
            t.finished = true
            say("not mixing ${t.a.id} into ${t.b?.id}: ${rate} Hz, $channels channels into ${head.sampleRate} Hz, ${head.channels}")
            return
        }
        // The level of the next song as stored by now, which reading its opening has just brought.
        publishLevels()
        t.armRequested = true
        t.armTries++
        t.sampleRate = rate
        t.expectedTailMs = left
        val work = work() ?: return
        val id = t.id
        work.execute {
            val ring = try {
                ShortArray(AutomixSchedule.capacityFrames(rate) * channels)
            } catch (e: OutOfMemoryError) {
                main.post { say("not holding the end of ${t.a.id}: no memory for it") }
                return@execute
            }
            stage.requestArm(AutomixAudioProcessor.Arm(id, ring, rate, channels, System.nanoTime()))
        }
    }

    /**
     * Gives the change up: before anything planned is heard it is today's, after that the outgoing
     * song fades out over a beat. Once, and for good: nothing is held for this change again.
     */
    private fun abort(t: Transition, why: String) {
        if (!t.holding && !t.armRequested) return
        stage.cancelArm()
        if (t.holding) stage.requestAbort(t.id, why, beatFrames(t))
        t.holding = false
        t.armRequested = true
        t.finished = true
        say("abort: $why")
    }

    /** A beat of the incoming song, which an abort fades the outgoing one over; half a second where no beat is known. */
    private fun beatFrames(t: Transition): Long {
        val rate = t.sampleRate.takeIf { it > 0 } ?: 44_100
        return t.head?.periodFrames?.toLong() ?: (rate / 2L)
    }

    // ---- what the stage says, on the player's thread

    override fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long) {
        main.post {
            // A request taken after it was given up on, or for a change a seek has replaced: the
            // stage lets go of the end at once, and the change is today's.
            val t = transition?.takeIf { it.id == id && !it.finished }
            if (t == null) {
                stage.requestAbort(id, "held for a change that is no longer coming", 0)
                return@post
            }
            armedFor(t, tailStartUs, positionUs)
        }
    }

    private fun armedFor(t: Transition, tailStartUs: Long, positionUs: Long) {
        t.holding = true
        t.armedAtMs = SystemClock.elapsedRealtime()
        // The end starts where the sink was handed its last buffer, a little ahead of what was
        // heard. Both times are the sink's own, so their difference is good to a buffer.
        val ahead = ((tailStartUs - positionUs) / 1000).coerceIn(0, 2_000)
        val position = player.currentPosition
        t.tailStartMs = position + ahead
        t.expectedTailMs = (player.duration - position - ahead).coerceAtLeast(0)
        say("holding the end of ${t.a.id}, %.1f s left".format(t.expectedTailMs / 1000.0))
    }

    override fun tailHeld(id: Long, held: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean) = main.postFor(id) { t ->
        if (t.finished) return@postFor
        val heldMs = frames * 1000 / held.sampleRate
        say("end held, %.1f s in %.1f s".format(heldMs / 1000.0, (SystemClock.elapsedRealtime() - t.armedAtMs) / 1000.0))
        val why = when {
            !songChange -> "no song came after it"
            !formatMatches -> "the next song's format differs"
            !AutomixSchedule.tailComplete(heldMs, t.expectedTailMs) -> "%.1f s were held where %.1f s were left: not the end of the song".format(heldMs / 1000.0, t.expectedTailMs / 1000.0)
            t.head == null -> "the next song's opening is not known"
            else -> null
        }
        if (why != null) {
            say("gapless: $why")
            t.finished = true
            return@postFor
        }
        plan(t, held, ring, frames)
    }

    override fun note(id: Long, text: String) {
        main.post {
            say(text)
            // A request to hold the end that the stage could not take: tried again at the next
            // look, while there is still time.
            val t = transition
            if (t != null && t.id == id && text.startsWith("not holding the end")) {
                t.armRequested = false
                t.holding = false
                tick()
            }
        }
    }

    override fun songChanged(id: Long, mixing: Boolean) = main.postFor(id) { t ->
        t.holding = mixing
        t.finished = true
        this.mixing = if (mixing) t else null
        val style = if (mixing) t.plan?.style ?: Style.GAPLESS else Style.GAPLESS
        lastStyles.addFirst(style)
        while (lastStyles.size > 2) lastStyles.removeLast()
        say(if (mixing) "song change: ${t.b?.id} comes in under ${t.a.id}, ${style.name.lowercase()}" else "song change: ${t.b?.id} after the whole of ${t.a.id}")
    }

    override fun blendDone(id: Long, clipped: Long, peak: Int) = main.postFor(id) { t ->
        t.holding = false
        if (mixing === t) mixing = null
        val db = if (peak <= 0) "-inf" else "%.1f".format(20 * log10(peak / 32768.0))
        say("done, peak $db dBFS, $clipped clipped")
    }

    override fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long) = main.postFor(id) { t ->
        val head = t.head
        if (head == null || !t.headWithGuide) {
            say("no lock check: the opening was read before, without its decimated copy")
            return@postFor
        }
        val work = work() ?: return@postFor
        val channels = head.channels
        work.execute {
            val result = runCatching { AutomixLock.measure(head, frames, fromFrame, channels) }
            main.post {
                val r = result.getOrNull()
                if (r == null) {
                    say("no lock check: ${result.exceptionOrNull()?.javaClass?.simpleName ?: "too short"}")
                    return@post
                }
                say("lock %.1f ms, match %.2f".format(r.offsetMs, r.match))
                if (!AutomixLock.holds(r)) {
                    stage.requestAbort(t.id, "the incoming song does not line up with its opening (%.1f ms, match %.2f)".format(r.offsetMs, r.match), beatFrames(t))
                }
            }
        }
    }

    override fun activeChanged(active: Boolean) {
        main.post {
            // At a change of song by itself the next song's frames are in the stage a quarter of
            // a second before they are heard, so the volume follows at the change the player
            // reports. A first start or a seek is heard at once.
            if (!player.isPlaying || player.currentPosition < 1_000) syncNormalising()
        }
    }

    // ---- the worker

    private fun plan(t: Transition, held: HeldTail, ring: ShortArray, frames: Long) {
        val head = t.head ?: return
        val work = work() ?: return
        val c = conditions()
        val levels = stage.levels
        val input = { readMark: Long, tail: RegionAnalysis ->
            PlanInput(
                tail = tail,
                head = head,
                readMark = readMark,
                earliestChange = AutomixSchedule.earliestChange(readMark, held.sampleRate, AutomixSchedule.workAllowanceMs(lastWorkMs)),
                lastStyles = lastStyles.toList(),
                attenuationA = levels.now.attenuation,
                attenuationB = levels.next.attenuation,
                aDurationMs = t.a.durationMs,
                bDurationMs = t.b?.durationMs ?: -1,
                aId = t.a.id,
                bId = t.b?.id,
                aAlbum = t.a.albumId,
                bAlbum = t.b?.albumId,
                tailStartMs = t.tailStartMs,
                forced = c.forcedStyle,
            )
        }
        work.execute {
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                val tail = RegionAnalysis.analyse(FrontEnd.of(ring, held.sampleRate, held.channels, 0, frames.toInt()), RegionKind.TAIL)
                val plan = Planner.plan(input(stage.readMark, tail))
                val rendered = MixRender.renderOutgoing(plan, ring, tail.guide, tail.decimation, if (plan.style == Style.BEAT) tail.beatFrames() else DoubleArray(0))
                stage.offer(held, plan, rendered)
                plan
            }
            val workMs = SystemClock.elapsedRealtime() - started
            main.post {
                val plan = outcome.getOrElse { e ->
                    Log.w(TAG, "AutoMix: planning failed", e)
                    say("gapless: planning failed (${e.javaClass.simpleName})")
                    return@post
                }
                lastWorkMs = workMs
                t.plan = plan
                say("plan ${plan.describe()}${timesOf(t, plan)}, worked out in $workMs ms")
            }
        }
    }

    private fun work(): ExecutorService? {
        if (!enabled) return null
        return worker ?: Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                r.run()
            }, "AutoMix")
        }.also { worker = it }
    }

    private fun stopWork() {
        worker?.shutdown()
        worker = null
    }

    // ---- levels

    /**
     * Each song's normalisation as the stage applies it: the song playing and the one after it,
     * each read once from what is stored about it, and the service's own for the song playing
     * until its own is read.
     */
    private fun publishLevels() {
        val c = conditions()
        val now = player.currentMediaItem?.mediaId
        val next = nextItem()?.mediaId
        val nNow = now?.let { normalizationFor(it) } ?: c.normalizeNow
        val nNext = next?.let { normalizationFor(it) } ?: nNow
        stage.levels = AutomixAudioProcessor.Levels(
            AutomixAudioProcessor.Level(now, AutomixVolume.songAttenuation(nNow, c.volume)),
            AutomixAudioProcessor.Level(next, AutomixVolume.songAttenuation(nNext, c.volume)),
        )
    }

    private val provisional = HashMap<String, Float>()

    private fun normalizationFor(id: String): Float? {
        normalizations[id]?.let { return it }
        if (normalizationsAsked.add(id)) {
            scope.launch {
                val answer = withContext(Dispatchers.IO) { runCatching { normalizationOf(id) }.getOrNull() }
                normalizationsAsked.remove(id)
                if (answer == null) return@launch
                val (n, final) = answer
                if (final) {
                    normalizations[id] = n
                    provisional.remove(id)
                    if (normalizations.size > NORMALIZATION_CACHE) normalizations.keys.firstOrNull()?.let { if (it != id) normalizations.remove(it) }
                } else {
                    if (provisional[id] == n) return@launch
                    provisional[id] = n
                    if (provisional.size > NORMALIZATION_CACHE) provisional.keys.firstOrNull()?.let { if (it != id) provisional.remove(it) }
                }
                publishLevels()
            }
        }
        return provisional[id]
    }

    private fun syncNormalising() {
        _stageNormalises.value = stage.activeForStream
    }

    // ---- small things

    private fun snapshot(t: Transition): AutomixGuards.Snapshot {
        val c = conditions()
        val parameters = player.playbackParameters
        return AutomixGuards.Snapshot(
            settingOn = enabled,
            stageInChain = stage.activeForStream,
            casting = c.casting,
            repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            current = t.a.copy(durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: -1),
            next = t.b,
            speed = parameters.speed,
            pitch = parameters.pitch,
            skipSilence = player.skipSilenceEnabled,
            headTracking = c.headTracking,
            upmix = c.upmix,
            ambience = c.ambience,
            ffmpeg = c.ffmpeg,
            sleepTimer = c.sleepTimer,
            seekedLate = seekedLateId != null && seekedLateId == t.a.id,
        )
    }

    private fun AutomixGuards.Song.copy(durationMs: Long) = AutomixGuards.Song(id, albumId, durationMs)

    /**
     * What follows the song playing, asked of the timeline with the real repeat mode, as
     * TransitionFade asks it: nextMediaItemIndex treats repeat one as off.
     */
    private fun nextItem(): MediaItem? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val current = player.currentMediaItemIndex
        if (current !in 0 until timeline.windowCount) return null
        val next = timeline.getNextWindowIndex(current, player.repeatMode, player.shuffleModeEnabled)
        if (next == C.INDEX_UNSET || next !in 0 until timeline.windowCount) return null
        return timeline.getWindow(next, window).mediaItem
    }

    private fun song(item: MediaItem, durationMs: Long): AutomixGuards.Song {
        val meta = item.metadata
        val length = durationMs.takeIf { it > 0 } ?: meta?.duration?.takeIf { it > 0 }?.let { it * 1000L } ?: -1
        return AutomixGuards.Song(item.mediaId, meta?.album?.id, length)
    }

    private fun describe(r: RegionAnalysis): String {
        val firstDown = r.firstDownbeat?.let { "%.2f s".format(r.beatFrame(it) / r.sampleRate) } ?: "none"
        return "${r.describe()}, first sound %.2f s, first downbeat %s".format(r.envelope.firstSound.toDouble() / r.sampleRate, firstDown)
    }

    private fun timesOf(t: Transition, plan: MixPlan): String {
        if (plan.isGapless || t.sampleRate <= 0) return ""
        val rate = plan.sampleRate.toDouble()
        val b = t.tailStartMs / 1000.0 + plan.bStart / rate
        val gone = t.tailStartMs / 1000.0 + plan.aMap.tailAt(plan.aGone.toDouble()) / rate
        return ", B at ${clock(b)}, A gone at ${clock(gone)}"
    }

    private fun clock(seconds: Double): String {
        val hundredths = Math.round(seconds * 100)
        return "%d:%02d.%02d".format(hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
    }

    private fun withoutGuide(r: RegionAnalysis) = RegionAnalysis(
        kind = r.kind, sampleRate = r.sampleRate, channels = r.channels, frames = r.frames, envelope = r.envelope,
        rhythm = r.rhythm, phrases = r.phrases, onset = r.onset, hopPeak = r.hopPeak, hopFrames = r.hopFrames,
        guide = FloatArray(0), decimation = r.decimation, analysisMillis = r.analysisMillis,
    )

    /** Runs [block] on the main thread with the transition [id] names, if it is still the current one or still being mixed. */
    private inline fun Handler.postFor(id: Long, crossinline block: (Transition) -> Unit) {
        post {
            val t = transition?.takeIf { it.id == id } ?: mixing?.takeIf { it.id == id }
            if (t != null) block(t)
        }
    }

    private fun say(text: String) = Log.i(TAG, "AutoMix: $text")

    private companion object {
        const val TAG = "AutoMix"
        const val HEAD_CACHE = 8
        const val NORMALIZATION_CACHE = 64
        const val ARM_TRIES = 3
        const val HOLDING_LOOK_MS = 1_000L
    }
}
