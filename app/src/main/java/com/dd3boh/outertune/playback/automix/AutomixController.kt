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
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.AutoOnBands
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.LanePlan
import com.dd3boh.outertune.mix.engine.Placing
import com.dd3boh.outertune.mix.engine.SpecPlan
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.pill.AutoChoice
import com.dd3boh.outertune.mix.pill.AnalysedPlanner
import com.dd3boh.outertune.mix.pill.PlainReason
import com.dd3boh.outertune.mix.pill.Pills
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
 * With [lanes] on, the next song's opening is also kept as a full-rate copy, and once the plan of
 * step 3 is handed over, the change is worked out again on lows, mids and highs from both songs'
 * copies: the pair's saved change if it has one and it can play, Auto's otherwise, AutoPlanner's
 * pick rendered and judged before it is kept (and gapless when no change it could make is clean).
 * It takes the place of the first plan if it is in before anything planned is heard, so the
 * change is never worse for it. A saved change that starts further from the end has the end held
 * earlier.
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
    /** Whether changes are worked out on lows, mids and highs from copies of both songs (Unreleased.AUTOMIX_EDITOR). */
    private val lanes: Boolean = false,
    /** The change saved by hand from one song into another, or null for Auto. Blocking, so called on IO. */
    private val savedChange: (from: String, to: String) -> TransitionSpec? = { _, _ -> null },
    /** The listener's fix to a song's beat (Fix the beat), which Auto uses too. Blocking, called on the worker. */
    private val beatFix: (songId: String) -> BeatFix? = { null },
    /** What was found of a song's end or opening, kept for the playlist's pills. Called on the worker. */
    private val analysed: (songId: String, end: SongEnd) -> Unit = { _, _ -> },
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
        /**
         * The song whose stored loudness [normalizeNow] was worked out from, or null while the
         * service has no stored loudness for the song playing. The service follows that row as it
         * is written, so for this song its value is the one the volume would apply.
         */
        val normalizeNowId: String?,
        val volume: Float,
        /** A style asked for in the developer options, debug builds only. */
        val forcedStyle: Style?,
        /**
         * Whether the boost, the equalizer or spatial audio change the sound before it reaches the
         * stage. The held end has them in it, a copy of the next song does not, so a change on bands
         * would play the next song without them and then with them: until they come after the
         * mixer, such a change keeps to the first plan.
         */
        val soundBeforeMix: Boolean = false,
    )

    /** Whether the stage scales each song by its normalisation, so that the volume must leave it out (AutomixVolume). */
    private val _stageNormalises = MutableStateFlow(false)
    val stageNormalises: StateFlow<Boolean> = _stageNormalises.asStateFlow()

    /**
     * Mix: what the change after the song playing has come to so far, for the queue's line (a
     * reason it stays plain, or the style its plan took), or null while nothing is decided and
     * whenever AutoMix is off. Main thread.
     */
    class Decided(val pair: SongPair, val choice: AutoChoice)

    private val _decided = MutableStateFlow<Decided?>(null)
    val decided: StateFlow<Decided?> = _decided.asStateFlow()

    /** The setting. Off costs nothing: no tick, no read, no worker. */
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            stage.wanted = value
            if (!value) {
                _decided.value = null
                transition?.let { abort(it, "AutoMix was switched off") }
                stopWork()
                say("off; it leaves the chain at the next song started by hand, levelling the songs till then")
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

    /**
     * Openings read before, for a song that comes next again, each with its decimated copy kept
     * over the first seconds of its music only, where a beat mix checks that it lines up.
     */
    private val heads = object : LinkedHashMap<String, RegionAnalysis>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RegionAnalysis>?) = size > HEAD_CACHE
    }

    /** Songs that did not line up with their opening when they came in under a beat mix: no beat mix goes into them again. */
    private val lockFailed = HashSet<String>()

    private val normalizations = HashMap<String, Float>()
    private val normalizationsAsked = HashSet<String>()
    private val lastStyles = ArrayDeque<Style>()
    private var lastWorkMs: Long? = null

    /** Auto's last moves on bands, newest first, so it does not make the same one three times running. Worker only. */
    private val recentMoves = ArrayDeque<Preset>()

    /** How long the last change on bands took to work out, Auto's proofs included. Worker only. */
    @Volatile private var lastBandsMs: Long? = null

    /** The song the listener moved within its last minute; its end is not mixed. */
    private var seekedLateId: String? = null

    private val window = Timeline.Window()

    init {
        stage.events = this
    }

    /** One change of song, from [a] into [b]. */
    private inner class Transition(val id: Long, val a: AutomixGuards.Song, val b: AutomixGuards.Song?) {
        var head: RegionAnalysis? = null
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

        /** The next song's opening at the full rate, for its part of a change on bands. */
        var copy: ShortArray? = null
        var copyFrames = 0

        /** The change saved for this pair, read with the opening. */
        var saved: TransitionSpec? = null

        /**
         * Whether the queue was shuffled as the opening was read: shuffled playback mixes by Auto
         * alone, as the queue's line says, so no saved change is read for it, a plain one included.
         */
        var shuffled = false

        /** How much of the song is left when its end begins to be held: earlier for a saved change that starts further from the end. */
        var armFromMs = AutomixSchedule.ARM_FROM_LEFT_MS

        /** What the queue's line says of this change; see [decided]. */
        var decision: AutoChoice? = null

        /** Given up: a plan still being worked out for it is not handed to the stage. Read on the worker. */
        val abandoned = AtomicBoolean(false)
    }

    // ---- the player

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncNormalising()
        if (!enabled) return levelsWhileOff()
        if (mediaItem?.mediaId != seekedLateId) seekedLateId = null
        refresh()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        syncNormalising()
        if (!enabled) return levelsWhileOff()
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
                    it.headFailed = old.headFailed
                    it.copy = old.copy
                    it.copyFrames = old.copyFrames
                    it.saved = old.saved
                    it.armFromMs = old.armFromMs
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
        val t = transition?.takeIf { it.holding } ?: mixing?.takeIf { it.holding }
        if (enabled && t != null) abort(t, why)
        // The sink applies new parameters only once it has drained, and with an end held that is
        // the whole end: up to forty seconds before the change is heard. That holds for an end
        // still playing out after its change was given up, AutoMix switched off included, so
        // whatever the stage holds, going to where the music is now drops it, and the change is
        // heard at once.
        if (stage.state == HeldTail.State.CAPTURE || stage.state == HeldTail.State.DRAIN) player.seekTo(player.currentPosition)
    }

    /**
     * With AutoMix off the stage can still be in the chain for the song playing: the song that
     * started under an end held as it was switched off, one configured just before, and every
     * song that came by itself since. It scales that song by the level it was last told and the
     * volume leaves normalisation out, so the levels must keep following the songs until the
     * stage leaves the chain at a song started by hand or after a stop.
     */
    private fun levelsWhileOff() {
        if (stage.activeForStream) publishLevels()
    }

    /**
     * Settings the service follows changed (the normalisation, the volume): the levels are worked
     * out again, but only while the stage is in the chain or about to be, the only time they are read.
     */
    fun levelsChanged(normalizationSettingChanged: Boolean) {
        if (normalizationSettingChanged) {
            normalizations.clear()
            normalizationsAsked.clear()
            provisional.clear()
        }
        if (enabled || stage.activeForStream) publishLevels()
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
        if (!enabled) return levelsWhileOff()
        val current = player.currentMediaItem
        val next = nextItem()
        val t = transition
        if (current == null) {
            transition = null
            _decided.value = null
            publishLevels()
            return
        }
        if (t == null || t.a.id != current.mediaId || t.b?.id != next?.mediaId) {
            t?.let { old ->
                old.cancelled.set(true)
                if (old.a.id == current.mediaId) abort(old, "the next song changed")
            }
            val fresh = Transition(nextTransitionId++, song(current, player.duration), next?.let { song(it, -1) })
            transition = fresh
            decide(fresh, null)
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
        // A plain change saved for the pair is refused like any guard, with its own reason.
        val refusal = AutomixGuards.refusal(snapshot(t))
            ?: t.saved?.takeIf { it.plain }?.let { AutomixGuards.Refusal(PlainReason.YOUR_CHOICE, "a plain change was saved for these two") }
        val why = refusal?.log
        if (refusal != null) decide(t, AutoChoice.Plain(refusal.reason))
        else if (t.refusal != null) decide(t, null)
        if (why != null) {
            if (why != t.refusal) {
                t.refusal = why
                say("not mixing ${t.a.id} into ${t.b?.id ?: "nothing"}: $why")
            }
            // Conditions can change (a sleep timer cancelled, skip silence turned off), and each
            // is looked at again when its event comes; nothing ticks for a change that cannot be.
            return later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed, t.armFromMs))
        }
        t.refusal = null
        val b = t.b!!
        when (AutomixSchedule.due(left, t.armFromMs)) {
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
                decide(t, AutoChoice.Plain(PlainReason.NOT_READY))
                val what = when {
                    t.headFailed -> "its opening could not be read"
                    t.head == null -> "its opening was not read in time"
                    else -> "the songs were not loaded in time"
                }
                say("not mixing ${t.a.id} into ${b.id}: $what")
            }
        }
        if (!t.armRequested) later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed, t.armFromMs))
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
        // On bands the opening is read every time: its full-rate copy is not kept between songs.
        if (!lanes) heads[b.id]?.let {
            t.head = it
            say("opening of ${b.id} known from before: ${describe(it)}")
            return
        }
        val cancelled = t.cancelled
        val aId = t.a.id
        t.shuffled = player.shuffleModeEnabled
        val shuffled = t.shuffled
        t.reading = scope.launch {
            val saved = if (lanes && !shuffled) withContext(Dispatchers.IO) { runCatching { savedChange(aId, b.id) }.getOrNull() } else null
            val copySeconds = if (lanes) copySecondsFor(saved) else 0.0
            val result = runCatching {
                withContext(Dispatchers.IO) { decoder.read(b.id, { cancelled.get() }, copySeconds) }
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
                    heads[b.id] = keptOpening(head)
                    if (cancelled.get()) return@post
                    t.head = head
                    t.copy = read.copy
                    t.copyFrames = read.copyFrames
                    say("opening of ${b.id}: ${describe(head)}, read in ${read.readMs} ms, analysed in ${head.analysisMillis.toLong()} ms")
                    if (saved != null) savedFor(t, saved)
                    tick()
                }
            }
        }
    }

    /** How much of the next song's opening to copy: what Auto can reach, or what the saved change needs of it, a minute at most. */
    private fun copySecondsFor(saved: TransitionSpec?): Double {
        val need = saved?.takeIf { !it.plain }?.let { (it.b.atMs + it.lengthMs) / 1000.0 + COPY_MARGIN_S } ?: 0.0
        return need.coerceAtLeast(AutomixSchedule.HEAD_SECONDS + COPY_MARGIN_S).coerceAtMost(COPY_MAX_S)
    }

    /** Takes the pair's saved change: the end is held early enough for it, if it can be. */
    private fun savedFor(t: Transition, saved: TransitionSpec) {
        t.saved = saved
        if (saved.plain) {
            say("saved for ${t.a.id} into ${t.b?.id}: a plain change")
            return
        }
        val duration = t.a.durationMs.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 } ?: return
        val firstMs = runCatching { SpecPlan.firstChangeMs(Presets.onBands(saved)) }.getOrNull() ?: return
        val changeLeft = (duration - firstMs).toLong()
        t.armFromMs = AutomixSchedule.armFromFor(changeLeft)
        val reach = if (changeLeft + AutomixSchedule.SAVED_LEAD_MS > AutomixSchedule.ARM_FROM_LEFT_MAX_MS) ", which is further from the end than the end can be held: Auto plays" else ""
        say("saved for ${t.a.id} into ${t.b?.id}: ${saved.label}, it starts " + "%.1f s before the end".format(changeLeft / 1000.0) + reach)
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
            decide(t, AutoChoice.Plain(PlainReason.FORMATS))
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
        t.abandoned.set(true)
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
        say("holding the end of ${t.a.id}, " + "%.1f s left".format(t.expectedTailMs / 1000.0))
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
        if (head == null) {
            say("no lock check: the opening is not known")
            return@postFor
        }
        val work = work() ?: return@postFor
        val channels = head.channels
        work.execute {
            val result = runCatching { AutomixLock.measure(head, frames, fromFrame, channels) }
            main.post {
                val r = result.getOrNull()
                if (r == null) {
                    say("no lock check: ${result.exceptionOrNull()?.javaClass?.simpleName ?: "the stretch is not in what was kept of the opening"}")
                    return@post
                }
                say("lock %.1f ms, match %.2f".format(r.offsetMs, r.match))
                if (!AutomixLock.holds(r)) {
                    t.b?.id?.let { if (lockFailed.size < LOCK_FAILED_KEPT) lockFailed += it }
                    stage.requestAbort(t.id, "the incoming song does not line up with its opening (%.1f ms, match %.2f)".format(r.offsetMs, r.match), beatFrames(t))
                }
            }
        }
    }

    override fun dropped(id: Long) = main.postFor(id) { t ->
        // A seek has already started the change over with a new transition, and an abort has
        // finished it. Anything else that flushes the renderer while an end is held is the player
        // starting the song over from where it is heard: what comes next changed (a song played
        // next, the next one moved or taken out, the queue sent again), or shuffle or repeat did,
        // after the next song had begun to be read. The music stops for a moment there, and this
        // change is today's.
        if (t.finished) return@postFor
        t.holding = false
        t.finished = true
        t.abandoned.set(true)
        if (mixing === t) mixing = null
        say("dropped: the player started ${t.a.id} over from where it was heard, as a change to what comes next does once the next song is being read")
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
                beatRefused = if (t.b?.id in lockFailed) "the next song did not line up with its opening when it last came in on the beat" else null,
            )
        }
        work.execute {
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                val tail = RegionAnalysis.analyse(FrontEnd.of(ring, held.sampleRate, held.channels, 0, frames.toInt()), RegionKind.TAIL)
                val planInput = input(stage.readMark, tail)
                val plan = Planner.plan(planInput)
                val rendered = MixRender.renderOutgoing(plan, ring, tail.guide, tail.decimation, plan.anchors)
                // Given up while it was worked out (a sleep timer set, AutoMix switched off): the
                // stage would refuse it anyway, so it is not handed over.
                if (!t.abandoned.get()) stage.offer(held, plan, rendered)
                // Then the same change on bands, from copies, to take the place of that one if in time.
                val onBands = if (lanes && c.soundBeforeMix) {
                    BandsOutcome("not on bands: the boost, the equalizer or spatial audio would be on one song and not the other")
                } else if (lanes && !t.abandoned.get()) {
                    runCatching { onBands(t, held, ring, frames, tail, head, planInput.earliestChange, planInput.attenuationA, planInput.attenuationB) }
                        .getOrElse { e ->
                            Log.w(TAG, "AutoMix: the change on bands failed", e)
                            BandsOutcome("not on bands: ${e.javaClass.simpleName}")
                        }
                } else null
                plan to onBands
            }
            val workMs = SystemClock.elapsedRealtime() - started
            main.post {
                val (plan, onBands) = outcome.getOrElse { e ->
                    Log.w(TAG, "AutoMix: planning failed", e)
                    decide(t, AutoChoice.Plain(PlainReason.NO_FIT))
                    say("gapless: planning failed (${e.javaClass.simpleName})")
                    return@post
                }
                onBands?.let { say(it.log) }
                lastWorkMs = workMs
                if (t.abandoned.get()) {
                    say("plan ${plan.style.name.lowercase()} worked out in $workMs ms, not used: the change was given up")
                    return@post
                }
                t.plan = plan
                // What was handed over last is what plays, unless it came too late (the log says so):
                // the saved change, which its own pill names, Auto's change on bands, or the first plan.
                when {
                    onBands?.saved == true -> decide(t, null)
                    onBands?.auto != null -> decide(t, onBands.auto)
                    else -> decide(t, AutoChoice.of(plan.style, plan.bars, plan.seconds, Pills.reasonOfPlanner(plan.reason)))
                }
                say("plan ${plan.describe()}${timesOf(t, plan)}, worked out in $workMs ms")
            }
        }
    }

    /** What the change on bands came to: a line for the log, and what plays if it is taken. */
    private class BandsOutcome(val log: String, val saved: Boolean = false, val auto: AutoChoice? = null)

    /**
     * The change on lows, mids and highs, from the held end [ring] and the next song's copy, handed
     * to the stage to take the place of the first plan: the pair's saved change where it can play
     * here, Auto's otherwise. Auto is AutoPlanner's pick, each candidate rendered as it would play
     * and judged before it is kept; when none is clean, the change is today's, gapless. On the
     * worker.
     */
    private fun onBands(
        t: Transition, held: HeldTail, ring: ShortArray, frames: Long, tail: RegionAnalysis, head: RegionAnalysis,
        earliest: Long, attenuationA: Double, attenuationB: Double,
    ): BandsOutcome {
        val copy = t.copy ?: return BandsOutcome("not on bands: the next song's opening was not copied")
        val b = t.b ?: return BandsOutcome("not on bands: no next song")
        val rate = held.sampleRate
        var why = ""
        // Read again here, so a change saved in the editor while this song played is the one
        // heard, if what was read of the next song's opening holds it; otherwise it plays next time.
        // Shuffled, Auto alone.
        val latest = if (t.shuffled) Result.success(null) else runCatching { savedChange(t.a.id, b.id) }
        val latestSaved = if (latest.isSuccess) latest.getOrNull() else t.saved
        if (latestSaved?.plain == true) {
            // Plain saved for these two after the end began to be held: they meet as they would
            // without Mix, as the line now says, not as Auto would mix them.
            if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
            stage.offer(held, MixPlan.gapless(rate, held.channels, frames, attenuationA, attenuationB, "a plain change, saved"), EMPTY_PART, replaces = true)
            return BandsOutcome("on bands: a plain change, saved for these two", auto = AutoChoice.Plain(PlainReason.YOUR_CHOICE))
        }
        latestSaved?.let { saved ->
            val spec = Presets.onBands(saved)
            var lanePlan: LanePlan? = null
            Placing.facts(spec, rate, held.channels, t.tailStartMs.toDouble(), frames, tail, t.copyFrames.toLong(), earliest).fold(
                onSuccess = { facts: ChangeFacts ->
                    when (val r = SpecPlan.plan(spec, facts)) {
                        is SpecPlan.Result.Planned -> lanePlan = r.plan
                        is SpecPlan.Result.Refused -> why = "; the saved change cannot play: ${r.why}"
                    }
                },
                onFailure = { e -> why = "; the saved change cannot play: ${e.message}" },
            )
            lanePlan?.let { p ->
                val render = LaneMixer(p).render(ring, copy, attenuationA, attenuationB)
                if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
                stage.offer(held, render.heldPlan(), render.outgoing, render.incoming, replaces = true)
                return BandsOutcome(
                    "on bands, saved: ${p.describe()}, " + "rendered in %.0f ms, headroom %.1f dB, peak %.2f".format(
                        render.renderMillis, render.headroom.deepestDb, render.peakAfter,
                    ),
                    saved = true,
                )
            }
        }
        // Auto on bands. By now the first plan has taken its time: the change may start only past
        // what will have gone out by the time this one is worked out, proofs and all.
        val started = SystemClock.elapsedRealtime()
        val allowance = ((lastBandsMs ?: BANDS_FIRST_WORK_MS) * 3 / 2).coerceIn(BANDS_FIRST_WORK_MS, BANDS_MAX_WORK_MS)
        val ready = max(earliest, AutomixSchedule.earliestChange(stage.readMark, rate, allowance))
        val a = AutoOnBands.Song(
            id = t.a.id, copy = Copy(ring, rate, held.channels, t.tailStartMs.toDouble(), frames.toInt()), analysis = tail,
            analysisStartMs = t.tailStartMs.toDouble(), songMs = t.a.durationMs.toDouble(), album = t.a.albumId,
            fix = runCatching { beatFix(t.a.id) }.getOrNull(),
        )
        val bSong = AutoOnBands.Song(
            id = b.id, copy = Copy(copy, rate, held.channels, 0.0, t.copyFrames), analysis = head,
            analysisStartMs = 0.0, songMs = b.durationMs.toDouble(), album = b.albumId,
            fix = runCatching { beatFix(b.id) }.getOrNull(),
        )
        val chosen = AutoOnBands.choose(a, bSong, ready, recentMoves.toList(), attenuationA, attenuationB, maxProofs = PHONE_PROOFS)
        lastBandsMs = SystemClock.elapsedRealtime() - started
        // What was found is kept for the playlist's pills, whatever the change came to.
        runCatching {
            analysed(t.a.id, a.end)
            analysed(b.id, bSong.end)
        }.onFailure { Log.w(TAG, "AutoMix: the analysis could not be kept", it) }
        val pick = chosen.pick
        if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
        val render = chosen.render
        if (pick.isPlain || render == null) {
            stage.offer(held, MixPlan.gapless(rate, held.channels, frames, attenuationA, attenuationB, "Auto: ${pick.describe()}"), EMPTY_PART, replaces = true)
            return BandsOutcome(
                "on bands: Auto stays gapless, ${pick.describe()}, " + "%d proofs in %.0f ms".format(chosen.proofs, chosen.millis) + why,
                auto = if (pick.isPlain) AnalysedPlanner.choiceOf(pick) else AutoChoice.Plain(PlainReason.NO_FIT),
            )
        }
        stage.offer(held, render.heldPlan(), render.outgoing, render.incoming, replaces = true)
        recentMoves.addFirst(pick.move)
        while (recentMoves.size > 2) recentMoves.removeLast()
        return BandsOutcome(
            "on bands: Auto ${pick.describe()}; ${render.plan.describe()}, " + "%d proofs in %.0f ms, headroom %.1f dB, peak %.2f".format(
                chosen.proofs, chosen.millis, render.headroom.deepestDb, render.peakAfter,
            ) + why,
            auto = AnalysedPlanner.choiceOf(pick),
        )
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
     *
     * For the song playing, the service's own wins whenever it comes from the song's stored
     * loudness: the service follows that row as it is written (a song started by hand has it
     * written as it starts, a stand-in later), so the stage scales the song as the volume would,
     * where a read of the row made a moment too early would hold the provisional level.
     */
    private fun publishLevels() {
        val c = conditions()
        val now = player.currentMediaItem?.mediaId
        val next = nextItem()?.mediaId
        val nNow = when {
            now == null || c.normalizeNowId == now -> c.normalizeNow
            else -> normalizationFor(now) ?: c.normalizeNow
        }
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

    /** Says what [t] has come to, while it is the change after the song playing. */
    private fun decide(t: Transition, choice: AutoChoice?) {
        t.decision = choice
        if (transition !== t) return
        val b = t.b
        _decided.value = if (choice == null || b == null) null else Decided(SongPair(t.a.id, b.id), choice)
    }

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
        return r.describe() + ", first sound %.2f s, first downbeat %s".format(r.envelope.firstSound.toDouble() / r.sampleRate, firstDown)
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

    /**
     * [r] as it is kept for the next time its song comes next: its decimated copy only from just
     * before its first sound to [KEPT_GUIDE_S] after it, which holds every stretch a beat mix
     * checks, its first downbeat being within two seconds of its first sound and the check two
     * seconds long.
     */
    private fun keptOpening(r: RegionAnalysis): RegionAnalysis {
        val first = r.envelope.firstSound
        return r.keepingGuide(first - r.sampleRate / 4, first + (KEPT_GUIDE_S * r.sampleRate).toLong())
    }

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
        const val KEPT_GUIDE_S = 4.5
        const val LOCK_FAILED_KEPT = 256

        /** The opening is copied this much past what a change needs of it, and a minute at most. */
        const val COPY_MARGIN_S = 2.0
        const val COPY_MAX_S = 60.0

        /**
         * Auto's changes on bands rendered and judged at most per change on the phone: each costs a
         * render of a second or two on a slow phone, and the first passes nearly always.
         */
        const val PHONE_PROOFS = 4

        /** What Auto on bands is given to be worked out in, before it has been timed, and at most. */
        const val BANDS_FIRST_WORK_MS = 3_000L
        const val BANDS_MAX_WORK_MS = 10_000L

        /** The outgoing part handed over with a gapless plan, which plays none of it. */
        private val EMPTY_PART = RenderedOutgoing(0, 0, 2, ShortArray(0), Headroom.NONE)
    }
}
