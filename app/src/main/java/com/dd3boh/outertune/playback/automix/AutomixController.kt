/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.engine.StoredChange
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
import com.dd3boh.outertune.mix.WholeSongGrid
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.AutoOnBands
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.mix.engine.ChangeRender
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.LanePlan
import com.dd3boh.outertune.mix.engine.Placing
import com.dd3boh.outertune.mix.engine.SpecPlan
import com.dd3boh.outertune.mix.engine.Engine
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneSet
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
 * Crossfade (Unreleased.CROSSFADE) works the same way, through the same steps and guards, with
 * Planner.crossfade for the plan: no copy, no saved change, no whole song read, no bands. With
 * AutoMix on as well, Auto plans first and a change it would leave gapless is crossfaded. A queue
 * that plays as a mix gets no crossfade at all (Crossfade.whyOff): what the rule knows of the
 * queue playing is kept until another queue starts.
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
    /** Each song's beats read from the whole song once, which its opening and end are laid on; null for none. Blocking, on IO. */
    private val wholeSongs: WholeSongStore? = null,
    /**
     * Whether a song with no grid yet may be read whole: only with the player's cache on, where
     * the next song's bytes read now are the ones it plays from, and the song playing is read from
     * what was already fetched. With it off, a whole read would fetch the song twice.
     */
    private val readWhole: Boolean = false,
    /** The queue playing, for Crossfade's rule; null when the service has none. Main thread. */
    private val queue: () -> QueueNow? = { null },
    /** Every pair of songs with a change saved for it, for Crossfade's rule. Blocking, so called on IO. */
    private val savedPairs: () -> Set<SongPair> = { emptySet() },
    /** A change far from the end forced on every pair, for trying the engine: the debug app only. Blocking, so called on IO. */
    private val entryTrial: () -> EntryTrial? = { null },
    /** Asks the player to load on past its load control's rest, for a change held far from the end (AutomixLoadControl). Main thread. */
    private val loadAhead: (Boolean) -> Unit = {},
    /** This phone's pace as last kept (AutomixPace), or null for none yet. Blocking, so called on IO. */
    private val loadPace: () -> AutomixPace? = { null },
    /** Keeps this phone's pace for the next start. Blocking, so called on IO. */
    private val savePace: (AutomixPace) -> Unit = {},
    /**
     * Where the editor keeps its windows and their parts (the app's cache), for a change saved on
     * parts to play on them ([PartsForPlay]); null for bands only.
     */
    private val partsCache: java.io.File? = null,
    /** The changes the editor rendered when they were saved, played as kept where the music is the same (KeptChanges). */
    private val keptChanges: KeptChanges? = null,
) : Player.Listener, AutomixAudioProcessor.Events {

    /**
     * The queue playing as the service has it: [key] names one start of it, so another value is
     * another queue, or the same one started again; [songs] gives its songs in its own order,
     * shuffle aside, as the playlist it came from has them.
     */
    class QueueNow(val key: Any, val songs: () -> List<String>)

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
     * whenever AutoMix is off. Main thread. Compared by what it says, so a refusal said again at
     * every look (twice a second for the last 15 s of a song) is not a new value: each one used to
     * rewrite heard.json and plan every pair of an open playlist page again.
     */
    data class Decided(val pair: SongPair, val choice: AutoChoice)

    private val _decided = MutableStateFlow<Decided?>(null)
    val decided: StateFlow<Decided?> = _decided.asStateFlow()

    /**
     * Either setting, AutoMix or Crossfade (see [setModes]): the stage is wanted in the chain and
     * the changes are looked at. Off costs nothing: no tick, no read, no worker.
     */
    var enabled = false
        private set(value) {
            if (field == value) return
            field = value
            stage.wanted = value
            if (!value) {
                _decided.value = null
                transition?.let { abort(it, "AutoMix and Crossfade were switched off") }
                stopWork()
                say("off; it leaves the chain at the next song started by hand, levelling the songs till then")
            } else {
                say("on; it joins the song playing")
                joinNow()
            }
            refresh()
        }

    /**
     * The stage joins a stream only as the sink configures it, so AutoMix switched on in the middle
     * of a song waited for the next one, and the change out of the song playing stayed plain, while
     * a change has to play in the playlist it was made in (7 Nov 2026). A seek to where the music
     * is has the sink configure the song again with the stage in it (AutomixAudioSink): a moment's
     * gap as it comes on, then the change out of this song is mixed.
     */
    private fun joinNow() {
        val why = when {
            stage.activeForStream -> "already in the chain for the song playing"
            player.currentMediaItem == null -> "nothing is playing"
            player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING -> "the player is not playing (state ${player.playbackState})"
            else -> null
        }
        if (why != null) {
            say("no join: $why")
            return
        }
        joining = true
        joiningSince = SystemClock.elapsedRealtime()
        seekInPlace()
    }

    /**
     * A seek to where the music is, which the player really makes: it drops a seek to the very
     * millisecond it is at as one to nowhere (ExoPlayerImplInternal.seekToInternal), and the sink is
     * then never flushed. A millisecond back is not heard; the seek's own gap is.
     */
    private fun seekInPlace() {
        val at = player.currentPosition
        player.seekTo(if (at > 0) at - 1 else 1)
    }

    /** The stage was asked to join the song playing and has not yet: its guard waits rather than refuses, for [JOIN_WAIT_MS]. */
    private var joining = false
    private var joiningSince = 0L

    /** Whether a join asked for is still to be waited for: a seek the player did not make must not hold the guard for good. */
    /** Joining, and still in time: the song may load for longer than that before the sink takes it. */
    private fun stillJoining(): Boolean =
        joining && (player.playbackState == Player.STATE_BUFFERING || SystemClock.elapsedRealtime() - joiningSince < JOIN_WAIT_MS)

    /** AutoMix's setting for the queue playing: with Mix ([lanes]), the switch of the playlist it came from. */
    private var auto = false

    /** Crossfade's length in seconds while it is on (Unreleased.CROSSFADE), null while it is off. */
    private var crossfadeSeconds: Int? = null

    /** Whether the queue playing comes from a playlist whose Mix switch is on: no crossfade in it. */
    private var mixPlaylist = false

    /**
     * What is on for the queue playing: AutoMix ([automix]), Crossfade at [crossfadeSeconds] (null
     * while it is off), and whether the queue comes from a playlist whose Mix switch is on
     * ([mixPlaylist]). Main thread.
     */
    fun setModes(automix: Boolean, crossfadeSeconds: Int?, mixPlaylist: Boolean) {
        if (automix == auto && crossfadeSeconds == this.crossfadeSeconds && mixPlaylist == this.mixPlaylist) return
        auto = automix
        this.crossfadeSeconds = crossfadeSeconds
        this.mixPlaylist = mixPlaylist
        say(
            "AutoMix ${if (automix) "on" else "off"}" + (if (mixPlaylist) " for this playlist" else "") +
                ", Crossfade " + (crossfadeSeconds?.let { "$it s" } ?: "off"),
        )
        val was = enabled
        enabled = automix || crossfadeSeconds != null
        if (enabled == was) refresh()
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

    private val window = Timeline.Window()

    // What Crossfade's rule knows of the queue playing (Crossfade.whyOff), kept until another
    // queue starts. Main thread.

    /** The start of the queue it is about, as [QueueNow.key] names it. */
    private var queueKey: Any? = null

    /** Auto has mixed two songs of this queue since it started. */
    private var queueAutoMixed = false

    /** Two songs side by side in it with a change saved for them, once [queueSavedRead]. */
    private var queueSavedPair: SongPair? = null
    private var queueSavedRead = false
    private var queueSavedJob: Job? = null

    /** The player's timeline the saved changes were last read for: another means other songs. */
    private var queueTimeline: Timeline? = null

    /** Why Crossfade is off for this queue, as last said, so each reason is said once. */
    private var queueOffSaid: String? = null

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

        /** Whether [saved] is the debug app's forced trial ([EntryTrial]), not the listener's. */
        var trial = false

        /** The next song's frame the end was held for it to come in on, 0 for its first. */
        var entryFrames = 0L

        /** The last reason given for not holding the end yet, so each is said once. */
        var waitingSaid: String? = null

        /** Each song's beats as read from all of it (WholeSongGrid), where they were; the read under way for one that has none. */
        var gridA: WholeSongGrid? = null
        var gridB: WholeSongGrid? = null
        var wholeRead: Job? = null

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

        /** Its plan is a crossfade, not Auto's: set on the worker before the plan is handed over. */
        @Volatile var crossfaded = false

        /**
         * The saved change is kept as rendered for the pair (KeptChanges), as read with the opening:
         * it needs no work once the end is held, so the end may be held later than Auto's window,
         * and a change whose start has gone by (a jump close to it or into it) is joined part-way.
         */
        var keptFound = false

        /** That change, read from its file as the end begins to be held, or before a jump. Set on the main thread or the worker. */
        @Volatile var kept: StoredChange? = null
        var keptReading: Job? = null

        /** The change it was last tried for as kept, as written, so the change on bands does not try it again. Worker only. */
        @Volatile var keptTried: String? = null

        /** The saved change's first frame, this far before the end of the song, and the next song's entry, in seconds into it; -1 for none. */
        var changeLeftMs = -1L
        var entrySeconds = 0.0

        /** Held too late for the kept change to be heard whole: joined part-way, the next song coming in where the change says. Main thread. */
        var entryOpen = false

        /** Its change is being worked out on the worker: a jump leaves that worker to finish it alone. */
        @Volatile var planning = false

        /**
         * This change started over after a seek, with everything read with the opening: the
         * opening, its copy and the songs' beats read whole, the saved change and whether it is the
         * debug app's trial, the queue's shuffle as it was read, and how early the end is held.
         * The opening is not read again, so whatever is not carried over here is lost for good.
         */
        fun startedOver(id: Long): Transition = Transition(id, a, b).also {
            it.head = head
            it.headFailed = headFailed
            it.copy = copy
            it.copyFrames = copyFrames
            it.saved = saved
            it.trial = trial
            it.shuffled = shuffled
            it.gridA = gridA
            it.gridB = gridB
            it.armFromMs = armFromMs
            it.keptFound = keptFound
            it.kept = kept
            it.changeLeftMs = changeLeftMs
            it.entrySeconds = entrySeconds
        }
    }

    // ---- the player

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncNormalising()
        if (!enabled) return levelsWhileOff()
        // A song that starts with AutoMix on is set up with the stage as the sink takes it, a moment
        // after this. Until then the stage counts as joining, so the song's change is not called off
        // as from a song that started before AutoMix was on: a playlist started from silence 4 s
        // after AutoMix came on played its first change plain (Mr. Saxobeat, 7 Nov 2026).
        joining = stage.wanted && !stage.activeForStream
        joiningSince = SystemClock.elapsedRealtime()
        refresh()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        syncNormalising()
        if (!enabled) return levelsWhileOff()
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // The seek flushed the stage, which let go of anything it held. The change starts over
            // from where the music is now, with the opening already read: a seek into the last
            // minute, to hear the change, is mixed as long as there is time to hold the end and
            // work it out (AutomixSchedule.due).
            stage.cancelArm()
            // A jump inside the next song while the blend plays: the stage may take the blend up
            // again where it went (HeldTail.resume), and says so when it is done.
            if (stage.state != HeldTail.State.MIX) mixing = null
            transition?.takeIf { it.armRequested || it.holding }?.let { old ->
                if (old.holding) say("dropped: a seek")
                // Nothing worked out for the end let go is handed to the stage, and a ring made for
                // it is never taken, so it cannot be mistaken for the change started over; whatever
                // is still being worked out for it does not hold that one up: the worker it runs on
                // is left to finish it alone.
                old.abandoned.set(true)
                stage.withdrawArmsBefore(old.id + 1)
                if (old.planning) stopWork()
                transition = old.startedOver(nextTransitionId++)
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
        if (stage.state == HeldTail.State.CAPTURE || stage.state == HeldTail.State.DRAIN) seekInPlace()
    }

    /**
     * With AutoMix off the stage can still be in the chain for the song playing: the song that
     * started under an end held as it was switched off, one configured just before, and every
     * song that came by itself since. It scales that song by the level it was last told and the
     * volume leaves normalisation out, so the levels must keep following the songs until the
     * stage leaves the chain at a song started by hand or after a stop.
     */
    private fun levelsWhileOff() {
        setLoadAhead(false)
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
        setLoadAhead(false)
        tickJob?.cancel()
        transition?.reading?.cancel()
        queueSavedJob?.cancel()
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
        val newPair = t == null || t.a.id != current.mediaId || t.b?.id != next?.mediaId
        lookAtQueue(newPair)
        if (newPair) {
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
        val t = transition ?: return setLoadAhead(false)
        keepLoading(t)
        if (t.finished) return
        if (t.holding) {
            // What may change while the end is held, and stops it: a sleep timer, casting, the
            // settings, Crossfade's rule for the queue. The queue's songs and the playback
            // parameters have their own events.
            (AutomixGuards.whyNot(snapshot(t)) ?: wayGone(t))?.let { abort(t, it) } ?: later(HOLDING_LOOK_MS)
            return
        }
        val way = way()
        sayCrossfadeOff()
        // Crossfade alone waits for the queue's saved changes to be read, which looks again.
        if (way == Crossfade.Way.CROSSFADE && !queueSavedRead) return
        // Neither applies to this queue: the change is today's, and there is nothing to look at
        // until the settings or the queue change, which look again.
        if (way == Crossfade.Way.NONE) return decide(t, null)
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return later(AutomixSchedule.nextLookMs(Long.MAX_VALUE, 1f))
        val left = duration - player.currentPosition
        val doing = if (way == Crossfade.Way.CROSSFADE) "crossfading" else "mixing"
        // A plain change saved for the pair is refused like any guard, with its own reason.
        val refusal = AutomixGuards.refusal(snapshot(t))
            ?: t.saved?.takeIf { it.plain }?.let { AutomixGuards.Refusal(PlainReason.YOUR_CHOICE, "a plain change was saved for these two") }
        val why = refusal?.log
        if (refusal != null) decide(t, AutoChoice.Plain(refusal.reason))
        else if (t.refusal != null) decide(t, null)
        if (why != null) {
            if (why != t.refusal) {
                t.refusal = why
                say("not $doing ${t.a.id} into ${t.b?.id ?: "nothing"}: $why")
            }
            // Conditions can change (a sleep timer cancelled, skip silence turned off), and each
            // is looked at again when its event comes; nothing ticks for a change that cannot be.
            return later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed, t.armFromMs, holdUntil(t)))
        }
        t.refusal = null
        val b = t.b!!
        when (AutomixSchedule.due(left, t.armFromMs, holdUntil(t))) {
            AutomixSchedule.Step.WAIT -> Unit
            AutomixSchedule.Step.READ -> readHead(t, b, way)
            AutomixSchedule.Step.ARM -> {
                readHead(t, b, way)
                if (!t.armRequested && t.head != null) {
                    if (AutomixSchedule.loaded(duration, player.currentPosition, player.bufferedPosition, player.totalBufferedDuration)) arm(t, left)
                    else waiting(t, "the songs are not loaded yet: this one to %.1f s of %.1f, %.1f s ahead in all".format(
                        player.bufferedPosition / 1000.0, duration / 1000.0, player.totalBufferedDuration / 1000.0,
                    ))
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
                say("not $doing ${t.a.id} into ${b.id}: $what")
            }
        }
        if (!t.armRequested) later(AutomixSchedule.nextLookMs(left, player.playbackParameters.speed, t.armFromMs, holdUntil(t)))
    }

    /**
     * How late the end may still be held for [t], in ms before the song's end: a saved change kept
     * as rendered needs no work once held, and is joined part-way where its start has gone by.
     */
    private fun holdUntil(t: Transition): Long =
        if (keptFirst(t)) AutomixSchedule.KEPT_UNTIL_LEFT_MS else AutomixSchedule.ARM_UNTIL_LEFT_MS

    /** Whether [t]'s change is played as kept before anything is worked out: the listener's saved change, kept as rendered, on bands or parts. */
    private fun keptFirst(t: Transition): Boolean =
        t.keptFound && keptChanges != null && lanes && way().auto && t.saved?.plain == false && !t.trial && !t.shuffled

    /** Whether the player was last asked to load on ([loadAhead]), so each change is said once. */
    private var loadingAhead = false

    private fun setLoadAhead(on: Boolean) {
        if (on == loadingAhead) return
        loadingAhead = on
        loadAhead(on)
    }

    /**
     * A change held earlier than Auto's window needs the song playing loaded to its end and the
     * next one's first seconds before its end can be held; the player's load control rests short
     * of that until 30 s are left, so it is asked to load on until they are in. Auto's own
     * changes never ask.
     */
    private fun keepLoading(t: Transition) {
        val duration = player.duration
        val want = enabled && !t.finished && !t.holding && t.saved != null && duration != C.TIME_UNSET && duration > 0 &&
            AutomixSchedule.loadAhead(
                t.armFromMs, duration - player.currentPosition,
                loaded = AutomixSchedule.loaded(duration, player.currentPosition, player.bufferedPosition, player.totalBufferedDuration),
                armRequested = t.armRequested,
                holdUntilMs = holdUntil(t),
                saved = true,
            )
        if (want && !loadingAhead) say("loading on for the change of ${t.a.id}: " + "this one to %.1f s of %.1f, %.1f s ahead in all".format(
            player.bufferedPosition / 1000.0, duration / 1000.0, player.totalBufferedDuration / 1000.0,
        ))
        setLoadAhead(want)
    }

    private fun later(ms: Long?) {
        if (ms == null || !player.isPlaying) return
        tickJob = scope.launch {
            delay(ms)
            tickJob = null
            tick()
        }
    }

    private fun readHead(t: Transition, b: AutomixGuards.Song, way: Crossfade.Way) {
        if (t.head != null || t.reading != null || t.headFailed) return
        // A crossfade needs the opening alone: no copy for bands, no saved change, no whole song.
        val bands = lanes && way.auto
        // On bands the opening is read every time: its full-rate copy is not kept between songs.
        if (!bands) heads[b.id]?.let {
            t.head = it
            say("opening of ${b.id} known from before: ${describe(it)}")
            return
        }
        val cancelled = t.cancelled
        val aId = t.a.id
        t.shuffled = player.shuffleModeEnabled
        val shuffled = t.shuffled
        val grids = way.auto
        val aDurationMs = t.a.durationMs.takeIf { it > 0 } ?: player.duration
        t.reading = scope.launch {
            if (!paceRead) {
                val kept = withContext(Dispatchers.IO) { runCatching { loadPace() }.getOrNull() }
                if (!paceRead) {
                    paceRead = true
                    kept?.let { pace = it }
                }
            }
            val listeners = if (bands && !shuffled) withContext(Dispatchers.IO) { runCatching { savedChange(aId, b.id) }.getOrNull() } else null
            // Whether that change is kept as rendered, which lets its end be held later.
            val keptFor = listeners?.takeIf { !it.plain }?.let { spec ->
                keptChanges?.let { k -> withContext(Dispatchers.IO) { runCatching { k.keeps(aId, b.id, spec) }.getOrDefault(false) } }
            } ?: false
            val (gridA, gridB) = wholeSongs?.takeIf { grids }?.let { store ->
                withContext(Dispatchers.IO) { runCatching { store.get(aId) }.getOrNull() to runCatching { store.get(b.id) }.getOrNull() }
            } ?: (null to null)
            // The debug app's forced change far from the end, where nothing is saved for the pair.
            val trial = if (bands && !shuffled && listeners == null) {
                withContext(Dispatchers.IO) { runCatching { entryTrial() }.getOrNull() }?.spec(aId, b.id, aDurationMs, gridA, gridB)
            } else null
            val saved = listeners ?: trial
            val copySeconds = if (bands) copySecondsFor(saved) else 0.0
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
                // Read from the song's first frame as its whole was, so laid on its line exactly.
                val analysis = runCatching { RegionAnalysis.analyseHead(read.front).let { h -> gridB?.let { h.onSong(it, 0.0, exact = true) } ?: h } }
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
                    t.gridA = gridA
                    t.gridB = gridB
                    t.copy = read.copy
                    t.copyFrames = read.copyFrames
                    val trim = if (read.trimmed > 0) ", its encoder delay of ${read.trimmed} frames trimmed" else ""
                    say("opening of ${b.id}: ${describe(head)}, read in ${read.readMs} ms, analysed in ${head.analysisMillis.toLong()} ms$trim")
                    t.trial = saved != null && saved === trial
                    t.keptFound = keptFor && saved === listeners
                    if (saved != null) savedFor(t, saved)
                    // A song with no grid yet is read whole now, the next one first: its opening is
                    // what a change reads most of. Not for a crossfade, which has no beat to lay.
                    when {
                        !grids -> Unit
                        gridB == null -> readWholeSong(t, b.id, b.durationMs, next = true)
                        gridA == null -> readWholeSong(t, t.a.id, t.a.durationMs, next = false)
                    }
                    tick()
                }
            }
        }
    }

    /**
     * Reads [songId] whole, once, and keeps its beats (WholeSongGrid), as Mixxx analyses a track
     * when it is loaded: the next song's opening and the playing song's end are then laid at the
     * tempo of all of it, not of the 30 or 40 s read. Through the player's own source, so with its
     * cache on the next song's bytes are fetched once. A read cut short by the network is not kept.
     */
    private fun readWholeSong(t: Transition, songId: String, durationMs: Long, next: Boolean) {
        val store = wholeSongs ?: return
        // A song longer than a whole read (a DJ set) keeps its windows as they are: one tempo for
        // its first minutes says nothing of its end.
        if (!readWhole || t.wholeRead != null || durationMs > WholeSongGrid.MAX_SECONDS * 1000) return
        val cancelled = t.cancelled
        t.wholeRead = scope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) { decoder.read(songId, { cancelled.get() }, wholeSeconds = WholeSongGrid.MAX_SECONDS) }
            }.getOrElse { e ->
                if (!cancelled.get()) say("could not read all of $songId: " + ErrorText.withoutAddresses("${e.javaClass.simpleName}: ${e.message?.take(160)}"))
                t.wholeRead = null
                return@launch
            }
            val whole = read.whole
            val expected = minOf(durationMs.toDouble(), WholeSongGrid.MAX_SECONDS * 1000)
            if (whole == null || cancelled.get() || (durationMs > 0 && whole.seconds * 1000 < expected - WHOLE_SHORT_MS)) {
                if (whole != null && !cancelled.get()) say("all of " + songId + " came short, %.1f s of %.1f: not kept".format(java.util.Locale.ROOT, whole.seconds, durationMs / 1000.0))
                t.wholeRead = null
                return@launch
            }
            val work = work() ?: run { t.wholeRead = null; return@launch }
            work.execute {
                val grid = runCatching { WholeSongGrid.analyse(whole) }.getOrNull()
                if (grid != null) runCatching { store.put(songId, grid) }
                main.post {
                    t.wholeRead = null
                    if (grid == null) {
                        say("all of $songId read in ${read.readMs} ms: no beat found")
                        return@post
                    }
                    say("all of $songId read in ${read.readMs} ms: ${grid.describe()}")
                    if (next) {
                        t.gridB = grid
                        layHead(t, grid)
                        if (t.gridA == null) readWholeSong(t, t.a.id, t.a.durationMs, next = false)
                    } else {
                        t.gridA = grid
                    }
                }
            }
        }
    }

    /** The next song's opening laid on its grid, read whole after it, while the end is not held yet. */
    private fun layHead(t: Transition, grid: WholeSongGrid) {
        val head = t.head ?: return
        val b = t.b ?: return
        if (t.holding || t.plan != null) return
        val work = work() ?: return
        work.execute {
            val laid = runCatching { head.onSong(grid, 0.0, exact = true) }.getOrNull() ?: return@execute
            main.post {
                if (t.holding || t.plan != null || t.head !== head) return@post
                t.head = laid
                heads[b.id] = keptOpening(laid)
                say("opening of ${b.id} laid on the whole song: ${describe(laid)}")
            }
        }
    }

    /** How much of the next song's opening to copy; see [AutomixSchedule.copySecondsFor]. */
    private fun copySecondsFor(saved: TransitionSpec?): Double = AutomixSchedule.copySecondsFor(saved)

    /** Takes the pair's saved change: the end is held early enough for it, if it can be. */
    private fun savedFor(t: Transition, saved: TransitionSpec) {
        t.saved = saved
        if (saved.plain) {
            say("saved for ${t.a.id} into ${t.b?.id}: a plain change")
            return
        }
        val duration = t.a.durationMs.takeIf { it > 0 } ?: player.duration.takeIf { it > 0 } ?: return
        // The end is held as early as this phone needs to hold it, skip the next song to its entry
        // and work the change out before its first frame, from what it measured on its own changes:
        // worked out where the editor works out how far it lets a change go.
        val lead = AutomixSchedule.savedLead(saved, duration.toDouble(), pace) ?: return
        val changeLeft = lead.changeLeftMs
        val entrySeconds = lead.entrySeconds
        t.changeLeftMs = changeLeft
        t.entrySeconds = entrySeconds
        val whose = if (t.trial) "forced for a trial" else "saved"
        val from = if (entrySeconds > 0) ", the next song in from %.1f s".format(entrySeconds) else ""
        // Kept as rendered, it needs no work once held: where a render would come too late, the
        // kept change is still held for.
        val keptNeed = if (keptFirst(t)) pace.keptArmFromMs(changeLeft, entrySeconds) else Long.MAX_VALUE
        val need = if (lead.fits || keptNeed > AutomixSchedule.ARM_FROM_LEFT_MAX_MS) lead.armFromMs else keptNeed
        if (!lead.fits && keptNeed > AutomixSchedule.ARM_FROM_LEFT_MAX_MS) {
            // Never held to be refused late: today's change, said now.
            t.finished = true
            decide(t, AutoChoice.Plain(PlainReason.TOO_FAR))
            say("$whose for ${t.a.id} into ${t.b?.id}: ${saved.label}, it starts " + "%.1f s before the end".format(changeLeft / 1000.0) + from +
                ", too far ahead for this phone: " + AutomixPace.tooFarWords(need) + "; a plain change")
            return
        }
        t.armFromMs = max(need, AutomixSchedule.ARM_FROM_LEFT_MS)
        say("$whose for ${t.a.id} into ${t.b?.id}: ${saved.label}, it starts " + "%.1f s before the end".format(changeLeft / 1000.0) + from +
            ", held from " + "%.1f s before it".format(t.armFromMs / 1000.0) +
            if (keptFirst(t)) ", kept as rendered: held until %.1f s before it".format(AutomixSchedule.KEPT_UNTIL_LEFT_MS / 1000.0) else "")
    }

    /** This phone's pace, as measured on its own changes and kept between starts; the Pixel 5's until then. */
    @Volatile
    private var pace = AutomixPace()
    private var paceRead = false

    /** One measurement more: taken on the main thread, kept for the next start on IO. */
    private fun measured(update: (AutomixPace) -> AutomixPace) {
        val next = update(pace)
        if (next == pace) return
        pace = next
        scope.launch(Dispatchers.IO) { runCatching { savePace(next) } }
    }

    private fun arm(t: Transition, left: Long) {
        val format = player.audioFormat ?: return
        val rate = format.sampleRate
        val channels = format.channelCount
        if (rate <= 0 || channels <= 0) return
        if (!stage.activeForStream) return waiting(t, "the stage is not in the chain for this song")
        if (t.armTries >= ARM_TRIES) return
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
        setLoadAhead(false)
        t.armTries++
        t.sampleRate = rate
        t.expectedTailMs = left
        // A change that brings the next song in at a later bar: only on bands, where its plan is made for that bar.
        // The frame the song as it plays comes in on: the change's own entry, whichever song is adjusted.
        t.entryFrames = if (lanes && way().auto) t.saved?.takeIf { !it.plain }?.let { SpecPlan.liveEntryFrames(Presets.onBands(it), rate) } ?: 0L else 0L
        val entry = t.entryFrames
        // Held too late for a change kept as rendered to be heard from its start (a jump close to it
        // or into it): it is joined where it can still be heard, and where the next song comes in is
        // the change's to say once it is found in what is held.
        val open = keptFirst(t) && t.changeLeftMs >= 0 && left < pace.keptArmFromMs(t.changeLeftMs, t.entrySeconds)
        t.entryOpen = open
        if (open) say("held late for the kept change of ${t.a.id}, " + "%.1f s before its end: joined where it can still be heard".format(left / 1000.0))
        val ringFrames = AutomixSchedule.ringFrames(rate, max(t.armFromMs, left))
        val work = work() ?: return
        val id = t.id
        work.execute {
            val ring = try {
                ShortArray(ringFrames * channels)
            } catch (e: OutOfMemoryError) {
                main.post { say("not holding the end of ${t.a.id}: no memory for it") }
                return@execute
            }
            stage.requestArm(AutomixAudioProcessor.Arm(id, ring, rate, channels, System.nanoTime(), entry, entryOpen = open))
        }
        readKept(t)
    }

    /** The kept change read from its file while the end is held, off the worker, which may be busy. */
    private fun readKept(t: Transition) {
        val kept = keptChanges ?: return
        val b = t.b ?: return
        if (!keptFirst(t) || t.kept != null || t.keptReading != null) return
        val a = t.a.id
        t.keptReading = scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { kept.get(a, b.id) }.getOrNull() }
            t.keptReading = null
            if (read != null && t.kept == null) t.kept = read
        }
    }

    /** Why the end is not held yet though it is due, said once for each reason. */
    private fun waiting(t: Transition, why: String) {
        val key = why.substringBefore(':')
        if (key == t.waitingSaid) return
        t.waitingSaid = key
        say("the end of ${t.a.id} is due to be held, not yet: $why")
    }

    /**
     * Gives the change up: before anything planned is heard it is today's, after that the outgoing
     * song fades out over a beat. Once, and for good: nothing is held for this change again.
     */
    private fun abort(t: Transition, why: String) {
        setLoadAhead(false)
        if (!t.holding && !t.armRequested) return
        t.abandoned.set(true)
        stage.cancelArm()
        stage.withdrawArmsBefore(t.id + 1)
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
        val holdMs = SystemClock.elapsedRealtime() - t.armedAtMs
        say("end held, %.1f s in %.1f s".format(heldMs / 1000.0, holdMs / 1000.0))
        measured { it.withHold(heldMs / 1000.0, holdMs) }
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

    override fun skipTook(id: Long, seconds: Double, millis: Long) {
        main.post { measured { it.withSkip(seconds, millis) } }
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
        // Auto has mixed two songs of this queue: no crossfade in it from now on (Crossfade.whyOff).
        if (mixing && !t.crossfaded) queueAutoMixed = true
        val how = if (t.crossfaded) "crossfade" else style.name.lowercase()
        say(if (mixing) "song change: ${t.b?.id} comes in under ${t.a.id}, $how" else "song change: ${t.b?.id} after the whole of ${t.a.id}")
    }

    override fun blendDone(id: Long, clipped: Long, peak: Int) = main.postFor(id) { t ->
        t.holding = false
        if (mixing === t) mixing = null
        val db = if (peak <= 0) "-inf" else "%.1f".format(20 * log10(peak / 32768.0))
        say("done, peak $db dBFS, $clipped clipped")
    }

    override fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long) = main.postFor(id) { t ->
        if (t.entryFrames > 0L) return@postFor entryCheck(t, frames, fromFrame)
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

    /**
     * Where the next song, brought in at a later bar, really came in: its first frames as they
     * reached the mixer held against the copy around its planned entry, frame by frame. Logged
     * only; the held end's own check against the copy crosses over to the song where they differ.
     */
    private fun entryCheck(t: Transition, frames: ShortArray, fromFrame: Long) {
        val copy = t.copy ?: return
        val channels = t.head?.channels ?: return
        val copyFrames = t.copyFrames.toLong()
        val entry = t.entryFrames
        val work = work() ?: return
        work.execute {
            val n = minOf(frames.size / channels, ENTRY_CHECK_FRAMES)
            val live = DoubleArray(n) { i -> var s = 0.0; for (c in 0 until channels) s += frames[i * channels + c]; s }
            val liveEnergy = live.sumOf { it * it }
            var best = 0
            var bestMatch = -2.0
            var atPlan = 0.0
            for (lag in -ENTRY_CHECK_REACH..ENTRY_CHECK_REACH) {
                val at = entry + fromFrame + lag
                if (at < 0 || at + n > copyFrames) continue
                var dot = 0.0
                var energy = 0.0
                for (i in 0 until n) {
                    var v = 0.0
                    val base = ((at + i) * channels).toInt()
                    for (c in 0 until channels) v += copy[base + c]
                    dot += v * live[i]
                    energy += v * v
                }
                val match = if (energy > 0 && liveEnergy > 0) dot / kotlin.math.sqrt(energy * liveEnergy) else 0.0
                if (lag == 0) atPlan = match
                if (match > bestMatch) {
                    bestMatch = match
                    best = lag
                }
            }
            main.post { say("entry check: the next song came in %d frames from its planned one (match %.4f there, %.4f on it)".format(best, bestMatch, atPlan)) }
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
            // In the chain for the song playing: as AutoMix came on in its middle, or as a queue's
            // first song started, a moment after the guard had looked while nothing played yet. Its
            // change is looked at again now, not at the next look the guard's wait had set, which
            // could be minutes away with the change called plain until then.
            if (active) {
                if (joining) say("in the chain for the song playing")
                joining = false
                if (enabled) refresh()
            }
        }
    }

    // ---- the worker

    private fun plan(t: Transition, held: HeldTail, ring: ShortArray, frames: Long) {
        val head = t.head ?: return
        val way = way()
        if (way == Crossfade.Way.NONE) {
            // Crossfade's rule turned it off for this queue while the end was being held.
            say("gapless: crossfade off for this queue: ${crossfadeOff() ?: "AutoMix is off"}")
            t.finished = true
            return
        }
        val work = work() ?: return
        val gridA = t.gridA
        val tailStartMs = t.tailStartMs.toDouble()
        val c = conditions()
        val levels = stage.levels
        val seconds = crossfadeSeconds?.toDouble()
        // Crossfade alone, or Crossfade where Auto would leave the change gapless. A style asked
        // for in the developer options is Auto's, and stays as it comes out, gapless included.
        val crossfadeFirst = way == Crossfade.Way.CROSSFADE
        val crossfadeAfter = way == Crossfade.Way.AUTO_THEN_CROSSFADE && c.forcedStyle == null
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
        val kept = keptFirst(t) && !c.soundBeforeMix
        t.planning = true
        work.execute {
            val started = SystemClock.elapsedRealtime()
            // The saved change as kept first: no analysis, no render, so it is in time where nothing
            // else would be (a jump close to it or into it).
            if (kept) {
                val played = runCatching { playKept(t, held, ring, frames, levels.now.attenuation, levels.next.attenuation, started) }
                    .onFailure { Log.w(TAG, "AutoMix: the kept change failed", it) }.getOrDefault(false)
                if (played) {
                    t.planning = false
                    return@execute
                }
                // Not as kept: the next song comes in where the change held for brings it in, as
                // it would have without the kept change, and its frames before are skipped meanwhile.
                if (t.entryOpen) stage.decideEntry(held, t.entryFrames, ring)
            }
            val outcome = runCatching {
                // Its place in the song is known to a buffer or so: enough to count beats and bars, not to lay them.
                val tail = RegionAnalysis.analyse(FrontEnd.of(ring, held.sampleRate, held.channels, 0, frames.toInt()), RegionKind.TAIL)
                    .let { r -> gridA?.let { r.onSong(it, tailStartMs, exact = false) } ?: r }
                val planInput = input(stage.readMark, tail)
                // Held for the next song to come in at a later bar, only the change on bands can play:
                // no first plan is worked out, which would only cost the time that change needs.
                val forEntry = t.entryFrames > 0L
                val autoPlan = if (crossfadeFirst || forEntry) null else Planner.plan(planInput)
                val crossfadeNow = crossfadeFirst || (crossfadeAfter && autoPlan?.isGapless == true)
                val crossfade = if (crossfadeNow && seconds != null) Planner.crossfade(planInput, seconds) else null
                // A crossfade that cannot be made either leaves Auto's own word in the log.
                val plan = crossfade?.takeIf { autoPlan == null || !it.isGapless } ?: autoPlan
                    ?: MixPlan.gapless(held.sampleRate, held.channels, frames, planInput.attenuationA, planInput.attenuationB, "held for the next song to come in at a later bar")
                val crossfaded = plan === crossfade
                // Before it is handed over: the song change it makes reads it.
                t.crossfaded = crossfaded
                val rendered = if (forEntry) EMPTY_PART else MixRender.renderOutgoing(plan, ring, tail.guide, tail.decimation, plan.anchors)
                // Given up while it was worked out (a sleep timer set, AutoMix switched off): the
                // stage would refuse it anyway, so it is not handed over. Nor where the end was held
                // for the next song to come in at a later bar, which only the change on bands plays.
                if (!t.abandoned.get() && t.entryFrames == 0L) stage.offer(held, plan, rendered, ring = ring)
                // Then Auto's same change on bands, from copies, to take the place of that one if in
                // time; never for a crossfade.
                val bands = lanes && !crossfaded
                val onBands = if (bands && c.soundBeforeMix) {
                    BandsOutcome("not on bands: the boost, the equalizer or spatial audio would be on one song and not the other")
                } else if (bands && !t.abandoned.get()) {
                    runCatching { onBands(t, held, ring, frames, tail, head, planInput.earliestChange, planInput.attenuationA, planInput.attenuationB, started) }
                        .getOrElse { e ->
                            Log.w(TAG, "AutoMix: the change on bands failed", e)
                            BandsOutcome("not on bands: ${e.javaClass.simpleName}")
                        }
                } else null
                Planned(plan, onBands, declined = autoPlan?.takeIf { crossfaded }?.reason)
            }
            val workMs = SystemClock.elapsedRealtime() - started
            t.planning = false
            main.post {
                val (plan, onBands, declined) = outcome.getOrElse { e ->
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
                // Held for a later entry, the first plan was never handed over: the change on bands is the one that plays.
                t.plan = onBands?.plan?.takeIf { t.entryFrames > 0L } ?: plan
                // What was handed over last is what plays, unless it came too late (the log says so):
                // the saved change, which its own pill names, Auto's change on bands, or the first plan.
                // A crossfade is not Auto's, and Mix's line says nothing of it.
                when {
                    t.crossfaded -> decide(t, null)
                    onBands?.saved == true -> decide(t, null)
                    onBands?.auto != null -> decide(t, onBands.auto)
                    else -> decide(t, AutoChoice.of(plan.style, plan.bars, plan.seconds, Pills.reasonOfPlanner(plan.reason)))
                }
                val after = declined?.let { "; Auto stays gapless: $it" } ?: ""
                say("plan ${plan.describe()}${timesOf(t, plan)}, worked out in $workMs ms$after")
            }
        }
    }

    /**
     * The pair's saved change as kept (KeptChanges), handed to the stage before anything is worked
     * out on the held end: whole, or joined part-way where its start has gone by (his word of 8 Nov
     * 2026: "that's unacceptable that it drops the transition just cuz i jumped through the
     * song"), the next song coming in where it would have been. False where it does not play as
     * kept here, and the change is worked out as before. On the worker.
     */
    private fun playKept(t: Transition, held: HeldTail, ring: ShortArray, frames: Long, levelA: Double, levelB: Double, started: Long): Boolean {
        val saved = t.saved ?: return false
        val b = t.b ?: return false
        val copy = t.copy ?: return false
        val written = KeptChanges.written(saved)
        t.keptTried = written
        val stored = t.kept ?: runCatching { keptChanges?.get(t.a.id, b.id) }.getOrNull()?.also { t.kept = it } ?: run {
            Log.i(TAG, "AutoMix: the kept change could not be read")
            return false
        }
        val rate = held.sampleRate
        val readMark = stage.readMark
        val earliest = AutomixSchedule.earliestChange(readMark, rate, AutomixSchedule.KEPT_WORK_MS)
        // Held late, the next song comes in where it would have been by the time the change is
        // heard from, and is skipped to there at this phone's pace, a tenth slower to be safe.
        val entry = if (t.entryOpen) StoredChange.EntryRule.Open(
            from = readMark + AutomixSchedule.KEPT_WORK_MS * rate / 1000,
            speed = pace.skipSpeed / (1 + AutomixPace.MARGIN_SHARE),
            margin = AutomixSchedule.ENTRY_SKIP_MARGIN_MS * rate / 1000,
        ) else StoredChange.EntryRule.Fixed(t.entryFrames)
        val check = if (t.entryFrames > 0L) ENTRY_CHECK_FRAMES.toLong() else 0L
        val play = stored.replayFrom(written, saved, ring, frames, t.tailStartMs.toDouble(), copy, t.copyFrames, earliest, levelA, levelB, entry, check)
            .getOrElse { e ->
                Log.i(TAG, "AutoMix: the kept change does not play here: ${e.message}")
                return false
            }
        if (t.abandoned.get()) return true
        stage.offer(held, play.plan, play.outgoing, play.incoming, ring = ring)
        val on = if (play.lanePlan.spec.lanes == LaneSet.PARTS) "parts" else "bands"
        Log.i(TAG, "AutoMix: the saved change plays as kept, on $on" + if (play.joinedAt != null) ", joined part-way" else "")
        // Kept before it held A's music across the change: kept again with it, from what is held,
        // so that a jump into the change finds A there next time.
        if (stored.probes.isEmpty() && play.joinedAt == null) keptChanges?.let { kept ->
            runCatching { stored.withProbes(ring, frames, play.shift) }.getOrNull()?.takeIf { it.probes.isNotEmpty() }?.let { more ->
                t.kept = more
                KeptChanges.scope.launch { if (kept.put(t.a.id, b.id, more)) Log.i(TAG, "AutoMix: the kept change is kept again with the music across it") }
            }
        }
        val workMs = SystemClock.elapsedRealtime() - started
        main.post {
            if (t.abandoned.get()) return@post
            t.plan = play.plan
            t.entryFrames = play.plan.bEntry
            decide(t, null)
            val joined = play.joinedAt?.let { at ->
                val first = play.lanePlan.mixFrom - play.shift
                ", joined %.1f s into it after a jump, the next song in from %.1f s".format((at - first).toDouble() / rate, play.plan.bEntry.toDouble() / rate)
            } ?: ""
            say("kept, saved: " + play.lanePlan.describe() + ", headroom %.1f dB, peak %.2f".format(play.headroomDb, play.peakAfter) + joined)
            say("plan ${play.plan.describe()}${timesOf(t, play.plan)}, worked out in $workMs ms")
        }
        return true
    }

    /**
     * The saved change [render] played, kept (KeptChanges) unless one kept for it on parts is there
     * already: the probe of A's music is taken now, while [ring] still holds it, the file written
     * off the worker.
     */
    private fun keepPlayed(kept: KeptChanges, from: String, to: String, saved: TransitionSpec, render: ChangeRender, ring: ShortArray, ringStartMs: Double, copy: ShortArray) {
        if (runCatching { kept.holds(from, to, saved, parts = true) }.getOrDefault(true)) return
        val change = runCatching { StoredChange.of(KeptChanges.written(saved), render, ring, ringStartMs, copy) }
            .onFailure { Log.w(TAG, "AutoMix: the change played could not be kept", it) }.getOrNull() ?: return
        KeptChanges.scope.launch {
            if (kept.put(from, to, change)) Log.i(TAG, "AutoMix: the saved change is kept as played, on parts")
        }
    }

    /** What the worker came to: the plan handed over, the change on bands, and Auto's reason where a crossfade took its place. */
    private data class Planned(val plan: MixPlan, val onBands: BandsOutcome?, val declined: String?)

    /** What the change on bands came to: a line for the log, what plays if it is taken, and the plan handed over for it. */
    private class BandsOutcome(val log: String, val saved: Boolean = false, val auto: AutoChoice? = null, val plan: MixPlan? = null)

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
        /** When the work on the held end began, for this phone's pace. */
        workStarted: Long = SystemClock.elapsedRealtime(),
    ): BandsOutcome {
        val copy = t.copy ?: return BandsOutcome("not on bands: the next song's opening was not copied")
        val b = t.b ?: return BandsOutcome("not on bands: no next song")
        val rate = held.sampleRate
        var why = ""
        // Read again here, so a change saved in the editor while this song played is the one
        // heard, if what was read of the next song's opening holds it; otherwise it plays next time.
        // Shuffled, Auto alone.
        val latest = when {
            t.shuffled -> Result.success(null)
            t.trial -> Result.success(t.saved)
            else -> runCatching { savedChange(t.a.id, b.id) }
        }
        val latestSaved = if (latest.isSuccess) latest.getOrNull() else t.saved
        if (latestSaved?.plain == true) {
            // Plain saved for these two after the end began to be held: they meet as they would
            // without Mix, as the line now says, not as Auto would mix them.
            if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
            stage.offer(held, MixPlan.gapless(rate, held.channels, frames, attenuationA, attenuationB, "a plain change, saved"), EMPTY_PART, replaces = true, ring = ring)
            return BandsOutcome("on bands: a plain change, saved for these two", auto = AutoChoice.Plain(PlainReason.YOUR_CHOICE))
        }
        latestSaved?.let { saved ->
            // The change as the editor rendered it on Save, where what is held of A and copied of B is
            // the music it was rendered from, to the sample, at the same levels: what was heard in the
            // editor, with no render here. Rendered again below otherwise.
            keptChanges?.takeIf { t.keptTried != KeptChanges.written(saved) }?.let { kept ->
                val replay = runCatching { kept.get(t.a.id, b.id) }.getOrNull()?.replay(
                    KeptChanges.written(saved), saved, ring, frames, t.tailStartMs.toDouble(), copy, t.copyFrames,
                    earliest, attenuationA, attenuationB,
                )
                replay?.onSuccess { render ->
                    if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
                    val check = if (t.entryFrames > 0L) ENTRY_CHECK_FRAMES.toLong() else 0L
                    val heldPlan = render.heldPlan(check)
                    stage.offer(held, heldPlan, render.outgoing, render.incoming, replaces = true, ring = ring)
                    Log.i(TAG, "AutoMix: the saved change plays as kept, on " + if (render.plan.spec.lanes == LaneSet.PARTS) "parts" else "bands")
                    return BandsOutcome(
                        "kept, saved: " + render.plan.describe() + ", headroom %.1f dB, peak %.2f".format(render.headroom.deepestDb, render.peakAfter),
                        saved = true,
                        plan = heldPlan,
                    )
                }?.onFailure { e -> Log.i(TAG, "AutoMix: the kept change does not play here: ${e.message}") }
            }
            // A change saved on parts plays on them where both songs' parts line up with the music
            // held to the sample (PartsForPlay); on bands otherwise, as before.
            // A change saved on bands plays on parts too where both songs' parts line up: the bands
            // were the cut until parts were there (his ask of 8 Nov 2026).
            val parts = partsCache?.let { dir ->
                runCatching {
                    val channels = held.channels
                    val aParts = PartsForPlay.lineUp(dir, t.a.id, "tail", ring, frames.toInt(), channels, rate, t.tailStartMs.toDouble())
                    val bParts = aParts?.let { PartsForPlay.lineUp(dir, b.id, "head", copy, t.copyFrames, channels, rate, 0.0) }
                    if (aParts != null && bParts != null) aParts to bParts else null
                }.onFailure { Log.w(TAG, "AutoMix: the parts could not be read", it) }.getOrNull()
            }
            Log.i(TAG, if (parts != null) "AutoMix: the saved change plays on parts" else "AutoMix: the saved change plays on bands: its parts are not at hand or do not line up")
            val spec = if (parts != null) Presets.onParts(saved) else Presets.onBands(saved)
            var lanePlan: LanePlan? = null
            Placing.facts(spec, rate, held.channels, t.tailStartMs.toDouble(), frames, tail, t.copyFrames.toLong(), earliest).fold(
                onSuccess = { found: ChangeFacts ->
                    val facts = if (parts != null) found.withParts() else found
                    when (val r = SpecPlan.plan(spec, facts)) {
                        is SpecPlan.Result.Planned -> lanePlan = r.plan
                        is SpecPlan.Result.Refused -> why = "; the saved change cannot play: ${r.why}"
                    }
                },
                onFailure = { e -> why = "; the saved change cannot play: ${e.message}" },
            )
            lanePlan?.let { p ->
                val renderStarted = SystemClock.elapsedRealtime()
                val render = LaneMixer(p).render(ring, copy, attenuationA, attenuationB, parts?.first, parts?.second)
                // This phone's pace: the work before the change, per second held, and the change's own, per second of it.
                val analyseMs = renderStarted - workStarted
                val spanSeconds = (p.mixTo - p.mixFrom).toDouble() / rate
                val renderMs = render.renderMillis.toLong()
                main.post { measured { it.withWork(frames.toDouble() / rate, analyseMs, spanSeconds, renderMs) } }
                if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
                // A later entry hands its first frames back, for the check that it came in where the copy has it.
                val check = if (t.entryFrames > 0L) ENTRY_CHECK_FRAMES.toLong() else 0L
                val heldPlan = render.heldPlan(check)
                stage.offer(held, heldPlan, render.outgoing, render.incoming, replaces = true, ring = ring)
                // Played on parts, kept as played: the next time it plays from here, whatever the
                // editor's own cache (a few pairs) has let go of since (8 Nov 2026: a change heard on
                // bands because its parts were being made again as it came).
                if (parts != null) keptChanges?.let { kept -> keepPlayed(kept, t.a.id, b.id, saved, render, ring, t.tailStartMs.toDouble(), copy) }
                return BandsOutcome(
                    "on bands, saved: ${p.describe()}, " + "rendered in %.0f ms, headroom %.1f dB, peak %.2f".format(
                        render.renderMillis, render.headroom.deepestDb, render.peakAfter,
                    ),
                    saved = true,
                    plan = heldPlan,
                )
            }
        }
        if (t.entryFrames > 0L) {
            // The end was held for the next song to come in at a later bar, and Auto's change
            // brings it in from its first: the end plays out as it is, the next song from its entry.
            if (t.abandoned.get()) return BandsOutcome("on bands, not used: the change was given up")
            stage.offer(held, MixPlan.gapless(rate, held.channels, frames, attenuationA, attenuationB, "the change held for cannot play"), EMPTY_PART, replaces = true, ring = ring)
            return BandsOutcome("on bands: gapless, the next song from its entry$why", auto = AutoChoice.Plain(PlainReason.NO_FIT))
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
            stage.offer(held, MixPlan.gapless(rate, held.channels, frames, attenuationA, attenuationB, "Auto: ${pick.describe()}"), EMPTY_PART, replaces = true, ring = ring)
            return BandsOutcome(
                "on bands: Auto stays gapless, ${pick.describe()}, " + "%d proofs in %.0f ms".format(chosen.proofs, chosen.millis) + why,
                auto = if (pick.isPlain) AnalysedPlanner.choiceOf(pick) else AutoChoice.Plain(PlainReason.NO_FIT),
            )
        }
        // The same change on parts where both songs' parts line up with the music held: each part
        // takes its band's move (Presets.onParts), and the parts are the song's own sounds where the
        // bands were filters.
        val onParts = autoOnParts(t, b, render, ring, frames, copy, rate, attenuationA, attenuationB)
        val played = onParts ?: render
        stage.offer(held, played.heldPlan(), played.outgoing, played.incoming, replaces = true, ring = ring)
        recentMoves.addFirst(pick.move)
        while (recentMoves.size > 2) recentMoves.removeLast()
        return BandsOutcome(
            "on ${if (onParts != null) "parts" else "bands"}: Auto ${pick.describe()}; ${played.plan.describe()}, " +
                "%d proofs in %.0f ms, headroom %.1f dB, peak %.2f".format(chosen.proofs, chosen.millis, played.headroom.deepestDb, played.peakAfter) + why,
            auto = AnalysedPlanner.choiceOf(pick),
        )
    }

    /** Auto's [render] on bands made again on parts, where both songs' parts line up with the music held ([PartsForPlay]); null otherwise. */
    private fun autoOnParts(
        t: Transition, b: AutomixGuards.Song, render: ChangeRender, ring: ShortArray, frames: Long, copy: ShortArray,
        rate: Int, attenuationA: Double, attenuationB: Double,
    ): ChangeRender? {
        val dir = partsCache ?: return null
        return runCatching {
            val channels = render.plan.channels
            val aParts = PartsForPlay.lineUp(dir, t.a.id, "tail", ring, frames.toInt(), channels, rate, t.tailStartMs.toDouble())
            val bParts = aParts?.let { PartsForPlay.lineUp(dir, b.id, "head", copy, t.copyFrames, channels, rate, 0.0) }
            if (aParts == null || bParts == null) {
                Log.i(TAG, "AutoMix: Auto plays on bands: the parts are not at hand or do not line up")
                return null
            }
            val spec = Presets.onParts(render.plan.spec)
            when (val r = SpecPlan.plan(spec, render.plan.facts.withParts())) {
                is SpecPlan.Result.Refused -> {
                    Log.i(TAG, "AutoMix: Auto plays on bands: on parts ${r.why}")
                    null
                }
                is SpecPlan.Result.Planned -> LaneMixer(r.plan).render(ring, copy, attenuationA, attenuationB, aParts, bParts).also {
                    Log.i(TAG, "AutoMix: Auto's change plays on parts")
                }
            }
        }.onFailure { Log.w(TAG, "AutoMix: Auto on parts failed", it) }.getOrNull()
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

    /**
     * Says what [t] has come to, while it is the change after the song playing. Only while AutoMix
     * is on for this queue: Mix's line and pills name Auto's changes, never a crossfade.
     */
    private fun decide(t: Transition, choice: AutoChoice?) {
        val said = choice?.takeIf { auto }
        t.decision = said
        if (transition !== t) return
        val b = t.b
        _decided.value = if (said == null || b == null) null else Decided(SongPair(t.a.id, b.id), said)
    }

    // ---- Crossfade's rule

    /** How the change after the song playing is made, from the settings and the queue playing. */
    private fun way(): Crossfade.Way = Crossfade.way(auto, crossfadeSeconds != null, crossfadeOff() != null)

    /** Why the queue playing gets no crossfade at all, or null while it may (Crossfade.whyOff). */
    private fun crossfadeOff(): String? = Crossfade.whyOff(mixPlaylist, queueAutoMixed, queueSavedPair)

    /** Says why Crossfade is off for the queue playing, once for each reason. */
    private fun sayCrossfadeOff() {
        if (crossfadeSeconds == null) return
        val why = crossfadeOff() ?: return
        if (why == queueOffSaid) return
        queueOffSaid = why
        say("crossfade off for this queue: $why")
    }

    /**
     * Why the end held for [t] is let go now that the settings or the queue's rule changed, or
     * null: no change applies to the queue any more, Auto's mix was handed over before AutoMix was
     * switched off, or a crossfade before Crossfade was.
     */
    private fun wayGone(t: Transition): String? {
        val way = way()
        val plan = t.plan
        return when {
            way == Crossfade.Way.NONE -> "crossfade off for this queue: ${crossfadeOff() ?: "AutoMix is off"}"
            plan == null || plan.isGapless -> null
            t.crossfaded && crossfadeSeconds == null -> "Crossfade was switched off"
            !t.crossfaded && !way.auto -> "AutoMix was switched off"
            else -> null
        }
    }

    /**
     * Keeps what Crossfade's rule knows of the queue playing: forgotten when another queue starts,
     * and its saved changes read again on IO whenever its songs or the change after the song
     * playing may have changed ([newPair]), so a change saved meanwhile counts from then on.
     */
    private fun lookAtQueue(newPair: Boolean) {
        val now = queue()
        if (now?.key != queueKey) {
            queueKey = now?.key
            queueAutoMixed = false
            queueSavedPair = null
            queueSavedRead = false
            queueSavedJob?.cancel()
            queueSavedJob = null
            queueTimeline = null
            queueOffSaid = null
        }
        if (crossfadeSeconds == null) return
        val timeline = player.currentTimeline
        val sameSongs = timeline === queueTimeline
        if (sameSongs && !newPair && (queueSavedRead || queueSavedJob != null)) return
        queueTimeline = timeline
        val songs = now?.songs?.invoke() ?: (0 until timeline.windowCount).map { timeline.getWindow(it, window).mediaItem.mediaId }
        val key = queueKey
        queueSavedJob?.cancel()
        queueSavedJob = scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Crossfade.savedSideBySide(songs, savedPairs()) } }
            if (key != queueKey) return@launch
            queueSavedJob = null
            found.exceptionOrNull()?.let { say("could not read the saved changes for Crossfade's rule: ${it.javaClass.simpleName}") }
            queueSavedPair = found.getOrNull()
            queueSavedRead = true
            refresh()
        }
    }

    private fun snapshot(t: Transition): AutomixGuards.Snapshot {
        val c = conditions()
        val parameters = player.playbackParameters
        return AutomixGuards.Snapshot(
            settingOn = auto,
            crossfadeOn = crossfadeSeconds != null,
            stageInChain = stage.activeForStream || stillJoining(),
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

        /** How many of a later entry's first frames are held against the copy, and how far either side. */
        const val ENTRY_CHECK_FRAMES = 8192
        const val ENTRY_CHECK_REACH = 4096
        const val HOLDING_LOOK_MS = 1_000L

        /** How long a join asked for keeps the guard waiting before the change is called plain after all. */
        const val JOIN_WAIT_MS = 3_000L
        const val KEPT_GUIDE_S = 4.5
        const val LOCK_FAILED_KEPT = 256

        /** How much shorter than the song a whole read may come before it counts as cut short. */
        const val WHOLE_SHORT_MS = 5_000.0


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
