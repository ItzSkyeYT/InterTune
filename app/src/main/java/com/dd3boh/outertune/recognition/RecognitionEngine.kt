/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.dd3boh.outertune.R
import android.Manifest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.constants.RecogniseListenSecondsKey
import com.dd3boh.outertune.constants.RecogniseAutoAddKey
import android.content.Context
import androidx.annotation.RequiresPermission
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listening loop, owned by nothing that can be closed.
 *
 * This used to live in the view model, which was wrong for the case the feature is actually for:
 * the device is put down with the screen off and left to fill a playlist. A view model dies with
 * its screen, so the loop has to live somewhere that outlives it, and something has to keep the
 * process in the foreground while the microphone is open. That is [RecognitionService]; this is the
 * part it drives.
 *
 * Continuous mode does not stop for anything short of being told to. A song already in the playlist
 * is skipped in silence, a no-match is ignored, and a failed request is retried on the next window,
 * because none of those are worth waking somebody to tap a button. Single-shot mode still surfaces
 * everything, since there is a person looking at it.
 */
@Singleton
class RecognitionEngine @Inject constructor(
    private val microphone: MicrophoneListener,
    private val shazam: ShazamClient,
    private val database: MusicDatabase,
    private val history: RecognitionHistory,
    @ApplicationContext private val context: Context,
) {
    data class Added(val title: String, val artist: String, val auto: Boolean)

    /**
     * What the room is playing, and where in it.
     *
     * Shazam does not merely name the track, it says where in the reference recording the sample
     * landed. Add the time that has passed since, scaled by however fast the copy is running, and
     * the result is a genuine playback position for audio this app is not playing and has no
     * control over. [durationSeconds] comes from the YouTube match, so it is only as right as that
     * match, and is null when nothing plausible was found or the search has not answered yet.
     *
     * [sampledAtMs] is when the first sample of the matched window was heard, because that is the
     * moment the offset describes: a clip cut from 60 s into a track matches at 60, however long
     * the clip. It used to be the moment the answer arrived, a whole listen window and a network
     * round trip later, so the position ran twelve or more seconds behind the room.
     */
    data class NowPlaying(
        val title: String,
        val artist: String?,
        val artworkUrl: String?,
        private val offsetAtMatch: Double,
        private val sampledAtMs: Long,
        val durationSeconds: Int?,
        private val rate: Double,
    ) {
        fun positionSeconds(nowMs: Long = System.currentTimeMillis()): Int {
            val elapsed = (nowMs - sampledAtMs) / 1000.0
            val position = offsetAtMatch + elapsed * rate
            // Past the end means the track finished and the next recognition has not landed yet.
            return position.toInt().coerceAtLeast(0).let {
                if (durationSeconds != null) it.coerceAtMost(durationSeconds) else it
            }
        }

        /** When the estimate reaches the end of the track, or null when there is no end to reach. */
        fun endsAtMs(): Long? {
            val duration = durationSeconds ?: return null
            if (rate <= 0.0) return null
            return sampledAtMs + ((duration - offsetAtMatch) / rate * 1000).toLong()
        }

        /** Whether [track] is this song, by name, since a name is all the two have in common. */
        fun isOf(track: Recognised): Boolean = title == track.title && artist == track.artist
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val level: Float, val identifying: Boolean) : State
        data class Found(
            val track: Recognised,
            val candidates: List<SongItem>,
            val certain: Boolean,
        ) : State
        /** Heard once, listening again to be sure and to measure the playback rate. */
        data class Confirming(val track: Recognised) : State
        data object NoMatch : State
        data class Failed(val reason: String, val heardNothing: Boolean) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private val _added = MutableStateFlow<List<Added>>(emptyList())
    val added = _added.asStateFlow()

    val continuous = MutableStateFlow(false)

    /** True while the microphone is open, which is what the service watches. */
    val running = MutableStateFlow(false)

    /** When the current run began, so the sheet can show how long it has been listening. */
    val startedAt = MutableStateFlow(0L)

    /**
     * Recognised, but not confidently enough to add without asking.
     *
     * Kept and shown rather than dropped. Skipping in silence is what made a working run look
     * broken: Shazam identified the same track five times over two minutes and the screen said
     * nothing at all, so there was no way to tell listening from failure.
     */
    private val _skipped = MutableStateFlow<List<Added>>(emptyList())
    val skipped = _skipped.asStateFlow()

    /**
     * The track currently in the room, refreshed on every recognition of it, and null whenever
     * there is no current reason to think anything in particular is playing.
     *
     * That last part is the engine's call rather than each screen's, so the notification, the
     * sheet and the screen cannot disagree about it. It is dropped when the estimate runs past the
     * end of the track, and when a listen after it hears silence or something Shazam cannot name.
     * Before that it only ever changed when a new song matched, so the notification went on
     * showing a finished song, its bar sat at the end, until the next match, however far off.
     */
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    /** Clears [nowPlaying] when its track runs out. Replaced with every [publish]. */
    private var expiry: Job? = null

    /**
     * Every song this session recognised and placed on YouTube with confidence, oldest first,
     * each once.
     *
     * Apart from [added], which is what went into a playlist and so stays empty on the dedicated
     * screen however much it names: that screen has no playlist. This is what the screen lists and
     * can save as one, so it holds the whole [SongItem] rather than two strings, which is what a
     * row needs to draw a cover and open the song menu.
     *
     * Unlike [added] and [skipped] it is not emptied when a run starts. Listen once is one run per
     * song, and a list that only ever held the latest could never reach the two songs it takes to
     * be worth saving. It lasts until [clearRecognised].
     */
    private val _recognised = MutableStateFlow<List<SongItem>>(emptyList())
    val recognised = _recognised.asStateFlow()

    /**
     * The playlist the screen's list is also being written into, once somebody created one from it
     * or added it to one. Every song recognised after that goes straight in, so a Keep listening run
     * left on a table fills the playlist by itself. Here rather than in the screen's view model
     * because the run outlives the screen.
     */
    private val _following = MutableStateFlow<PlaylistEntity?>(null)
    val following = _following.asStateFlow()

    private var followWatch: Job? = null

    /**
     * Sends every song recognised from now on into [playlist] as well, or stops, given null.
     *
     * @param wrote the songs the save or the picker itself just put in [playlist], which are this
     * run's to take out again if one turns out to be a piece of a mashup.
     */
    fun follow(playlist: PlaylistEntity?, wrote: Collection<String> = emptyList()) {
        followWatch?.cancel()
        _following.value = playlist
        if (playlist == null) return
        synchronized(written) { wrote.forEach { written += it to playlist.id } }
        // Deleted from the library while followed: the screen stops claiming new songs go there,
        // and the writes, which would all fail on the missing playlist, stop being attempted. Both
        // callers follow a playlist already in the database, so the first value is never null.
        followWatch = scope.launch {
            database.playlist(playlist.id).first { it == null }
            _following.compareAndSet(playlist, null)
        }
    }

    private var job: Job? = null
    private var playlist: Playlist? = null

    /** Whether this run adds what it hears to a playlist, or only lists it on the screen. */
    val addsToPlaylist: Boolean get() = playlist != null
    private var known = mutableSetOf<String>()

    /**
     * What this run confirmed, by Shazam key. A song carrying on is then just carrying on: before
     * this, a run with no playlist had nothing to remember it by and confirmed the same song every
     * two windows for as long as it played, and when the pick flipped between the original and a
     * sped-up upload of it, both ended up in the list.
     */
    private val confirmed = mutableMapOf<String, SongItem>()

    /** When each of [confirmed] was confirmed, so taking a piece out spares an earlier real play. */
    private val confirmedAt = mutableMapOf<String, Long>()

    /** The speed the last pair of listens measured, when it was not the original's. */
    private var pendingVariant: PlaybackVariant? = null

    /** The [pending] listen already held back once for another version of its song. */
    private var heldForRival: Pair<Recognised, Long>? = null

    private val mixWatch = MixWatch()
    private val cutWatch = CutWatch()
    private val versionWatch = VersionWatch()

    /**
     * A mashup being heard: the keys of its pieces, which are not added while it lasts, and what it
     * was found to be, if anything. Ends once none of its pieces has been heard for a while.
     */
    private var mixIds = 0L
    private inner class ActiveMix(
        val keys: MutableSet<String> = mutableSetOf(),
        /** When any piece was last heard. The mashup is over once none has been for a while. */
        var lastHeardMs: Long = 0,
        /** When it last cut back to a piece. Pieces are held back for a while after each cut. */
        var lastCutMs: Long = 0,
        var strong: Boolean = false,
        /** Sure enough to act on without asking: see [MixWatch.Mix.sure]. */
        var sure: Boolean = false,
        /** Its pieces as last seen, for taking out the ones a picked upload names. */
        var pieces: List<MixWatch.Sighting> = emptyList(),
        var found: SongItem? = null,
        /** Answered: a clear winner was taken, or the person picked one or said none of these. */
        var settled: Boolean = false,
        /** A search went through. A failed one is tried again the next time a piece comes back. */
        var searched: Boolean = false,
        /** When the first of its pieces was heard, since the last mashup ended. */
        var startedMs: Long = 0,
        /** When it has to be over, once it is known which upload it is and so how long it runs. */
        var endsAtMs: Long? = null,
        /** Everything the search found that it could be, best first, for the choice to be drawn from. */
        var candidates: List<SongItem> = emptyList(),
    ) { val id = ++mixIds }
    private var mix: ActiveMix? = null

    /**
     * Pieces of mashups this run already answered for, so the same mashup heard again, or going on
     * after a quiet spell ended it, is not asked about a second time.
     */
    private val answered = mutableSetOf<String>()
    private val answeredAlone = mutableSetOf<String>()

    /**
     * When each song was first heard this time round: reset once it has not been heard for a while,
     * so a song played on its own earlier in the evening does not make a mashup of it later look
     * twenty minutes long.
     */
    private val firstHeard = mutableMapOf<String, Long>()
    private val lastHeard = mutableMapOf<String, Long>()

    /**
     * Songs that went back to their top partway through (CutWatch's RESTART), watched to see whether
     * they then play to their end. One that stops well short was an edit.
     */
    /**
     * @param furthest the furthest into the song any window has landed since it went back to its
     * top. Not the last window's place: a song replayed to its end can have its last window put in
     * an earlier chorus, and Delirious went from its restart back onto its usual timeline fifty
     * seconds ahead, and following the restart's own timeline read both as stopping short.
     */
    private class Restart(val sighting: MixWatch.Sighting, var furthest: Double, var atMs: Long, val durationS: Int)
    private val restarts = mutableMapOf<String, Restart>()

    /** Windows in a row with nothing Shazam knows, while a mashup is on. */
    private var unmatchedRun = 0

    /** The length of one listen, for how long something has been heard up to its last window. */
    private var windowMs = MicrophoneListener.DEFAULT_SECONDS * 1000L

    /** Playlist rows this run wrote, song id to playlist id, so a piece of a mashup can come out. */
    private val written = mutableListOf<Pair<String, String>>()

    /**
     * Songs this run put in the list or a playlist itself. Taking a piece of a mashup out only
     * touches these: the list lasts across runs, and a song an earlier run placed is not this run's
     * to remove.
     */
    private val owned = mutableSetOf<String>()

    /**
     * A mashup that was heard but could not be told apart from another on YouTube, with the uploads
     * it could be. For the person to pick, since two mashups of the same songs sound alike to a
     * search that only knows the songs.
     */
    data class MixChoice(
        val id: Long,
        /** The Shazam keys of its pieces, which is what ties the choice to its mashup. */
        val keys: Set<String>,
        val pieces: List<String>,
        val candidates: List<SongItem>,
    )
    /** Every choice still waiting, newest first. More than one when mashups follow each other. */
    private val _mixChoices = MutableStateFlow<List<MixChoice>>(emptyList())
    val mixChoices = _mixChoices.asStateFlow()

    /**
     * Where everything about mashups is changed: one thread at a time, so a pick on the screen and
     * the listening loop cannot both be in the middle of it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)

    /**
     * The first sighting of a track, held until a second one confirms it.
     *
     * One window is enough to name a song and not enough to be sure which recording of it is
     * playing. A second recognition of the same track, a known number of seconds later, says how
     * fast the room's copy is running: the offset into the reference should advance by exactly the
     * time that passed, and when it does not, the thing playing is a slowed or sped-up edit and the
     * original would be the wrong song to add.
     */
    private var pending: Pair<Recognised, Long>? = null

    /**
     * How long [pending] can still be confirmed, set from the listen length at the start of each
     * run. The length is a setting, so a flat number of seconds would be three windows at one
     * setting and barely two at another. See [isSecondListen] for why there is a limit at all.
     */
    private var pendingLifetimeMs =
        PENDING_LIFETIME_WINDOWS * MicrophoneListener.DEFAULT_SECONDS * 1000L

    /**
     * @param playlist where confident matches are added, or null to only name things.
     *
     * Null is the dedicated screen, which is reached from the search bar with no playlist in mind.
     * Everything else is unchanged: the same listening, the same confirmation pass, the same list
     * of what was heard. Only the adding is skipped, which [add] already guards for.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(playlist: Playlist?, continuous: Boolean) {
        if (job?.isActive == true) return
        this.playlist = playlist
        this.continuous.value = continuous
        _added.value = emptyList()
        _skipped.value = emptyList()
        pending = null
        pendingVariant = null
        confirmed.clear()
        mixWatch.clear()
        cutWatch.clear()
        versionWatch.clear()
        mix = null
        answered.clear()
        answeredAlone.clear()
        firstHeard.clear()
        lastHeard.clear()
        restarts.clear()
        confirmedAt.clear()
        unmatchedRun = 0
        _mixChoices.value = emptyList()
        synchronized(written) { written.clear() }
        synchronized(owned) { owned.clear() }

        job = scope.launch(serial) {
            running.value = true
            startedAt.value = System.currentTimeMillis()
            known = playlist?.let {
                database.playlistSongs(it.id).first().map { s -> s.song.song.id }.toMutableSet()
            } ?: mutableSetOf()
            Log.i(
                TAG,
                "Listening for ${playlist?.playlist?.name ?: "no playlist"}, " +
                        "continuous=$continuous, ${known.size} already in it",
            )

            try {
                // One microphone session for the whole run. Each window is identified while the
                // next is already being captured, so nothing between songs is missed.
                // Read per run rather than cached, so changing it in settings takes effect on the
                // next listen instead of the next launch.
                val seconds = context.dataStore.data.first()[RecogniseListenSecondsKey]
                    ?: MicrophoneListener.DEFAULT_SECONDS
                pendingLifetimeMs = PENDING_LIFETIME_WINDOWS * seconds * 1000L
                windowMs = seconds * 1000L
                microphone.stream(seconds = seconds) { level ->
                    // An atomic update rather than a read and a write, because this runs on the
                    // microphone's IO thread while identify() writes the state from the collector
                    // below, on another. A copy read just before identify() changed the state could
                    // land on top of the change and undo it, bringing a cleared "identifying"
                    // straight back for a whole window.
                    _state.update { current ->
                        when (current) {
                            is State.Listening -> current.copy(level = level)
                            State.Idle -> State.Listening(level, false)
                            else -> current
                        }
                    }
                }.collect { window ->
                    identify(window)
                    if (!this@RecognitionEngine.continuous.value && _state.value is State.Found) {
                        // Single shot stops on a result and waits for the person to choose. halt,
                        // not stop: stop ends by writing Idle, so it replaced the Found it was meant
                        // to wait on within the same instant, and StateFlow only hands its
                        // collectors the latest value. The sheet saw Idle and never the answer.
                        // Listen once heard the song, named it, and showed nothing, which is exactly
                        // the "single shot never identified anything" that continuous mode did not
                        // share.
                        return@collect halt()
                    }
                }
            } catch (t: CancellationException) {
                // Not a failure. Every stop cancels this job, so catching it with everything else
                // meant the person was told "StandaloneCoroutine was cancelled" each time they
                // pressed stop. Rethrown so the parent scope still unwinds properly.
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Listening failed", t)
                _state.value = State.Failed(t.message ?: "microphone", heardNothing = false)
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun identify(window: MicrophoneListener.Window) {
        val keepGoing = continuous.value
        _state.value = State.Listening(0f, identifying = true)
        try {
            respondTo(shazam.identify(window.samples), keepGoing, window.startedAtMs)
        } finally {
            // Back to plain listening once the answer has been dealt with, unless dealing with it
            // moved the state somewhere else. Nothing used to clear this. Every arm that returns
            // quietly in continuous mode left it set, and the level updates only copy what is
            // there, so after the first window the sheet said "identifying" for the whole run,
            // hours of it, while all it was doing was recording. Cleared here rather than when
            // Shazam replies, because the YouTube search after a match is still part of the same
            // answer and the flag would drop for its duration and then jump to Confirming.
            // An atomic update, so a stop() that has already reset the state to Idle stays Idle.
            _state.update {
                if (it is State.Listening && it.identifying) it.copy(identifying = false) else it
            }
        }
    }

    /**
     * Acts on Shazam's answer. Kept apart from [identify] so that every early return in here still
     * passes through its finally.
     *
     * @param heardAtMs when the first sample of the window was heard. Every time below is this
     * rather than the clock when the answer came back, for the position and for the gap between
     * two sightings alike: the answer's arrival moves with the network, and the audio does not.
     */
    private suspend fun respondTo(outcome: RecognitionOutcome, keepGoing: Boolean, heardAtMs: Long) {
        when (outcome) {
            is RecognitionOutcome.Failed -> {
                // Silence means the room has stopped, so whatever was on show has finished. The
                // other failures are this end or the network and say nothing about the room, so
                // the estimate is left to run.
                if (outcome.reason == "silence") {
                    publish(null)
                    // The music stopped, so whatever mashup was on has ended with it, a song that
                    // went back to its top stopped where it was, and neither watch has anything left
                    // to go on: a pause is not a cut, and what comes after is new.
                    endMix("silence")
                    mixWatch.clear()
                    cutWatch.clear()
                    versionWatch.clear()
                }
                // Nothing to say in continuous mode. A quiet stretch or one refused request is not
                // a reason to stop listening or to ask somebody to press try again.
                if (!keepGoing) {
                    _state.value = State.Failed(outcome.reason, outcome.reason == "silence")
                }
            }

            RecognitionOutcome.NoMatch -> {
                // Something is playing and it is not the song on show, or that would have matched
                // again. Showing it running on regardless is how a finished song stayed up.
                publish(null)
                if (!keepGoing) _state.value = State.NoMatch
                // Two windows in a row of something Shazam does not know is the gap between two
                // tracks: the Damage mashup had one unmatched window in five minutes, and two at its
                // end. Whatever came before is over.
                if (keepGoing && ++unmatchedRun >= 2) {
                    endMix("two windows of nothing Shazam knows")
                }
            }

            is RecognitionOutcome.Match -> {
                // Every window, with where in the track it landed and Shazam's own speed and pitch
                // estimates, so a run over something odd can be read back afterwards. The mashup
                // run of 24 Sep had only titles to go on.
                outcome.track.let { t ->
                    Log.i(
                        TAG,
                        "Window: '${t.title}' by '${t.artist}' key=${t.shazamKey} " +
                                "offset=${"%.1f".format(t.offsetSeconds)} " +
                                "skew=${"%.4f".format(t.timeSkew)}/${"%.4f".format(t.frequencySkew)}",
                    )
                }
                // A different song from the one on show, so the old one comes down now rather
                // than after the YouTube search below, which takes a second or two that were
                // spent counting through a track the room had left. The new one goes up without
                // a length until the search says how long it is.
                if (_nowPlaying.value?.isOf(outcome.track) != true) {
                    publish(
                        NowPlaying(
                            title = outcome.track.title,
                            artist = outcome.track.artist,
                            artworkUrl = outcome.track.artworkUrl,
                            offsetAtMatch = outcome.track.offsetSeconds,
                            sampledAtMs = heardAtMs,
                            durationSeconds = null,
                            rate = 1.0,
                        )
                    )
                }

                // Shazam names the song but hands back no video id, only a search URL, so the
                // match has to be resolved against YouTube before it can be played or added.
                val query = outcome.track.searchQuery
                val found = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                val items = found.getOrNull()?.items
                val candidates = items?.filterIsInstance<SongItem>()?.take(4).orEmpty()
                // An empty list here was one line in the log and three different bugs underneath
                // it. A probe sends this exact query under this exact filter, in this locale, and
                // gets the right track back first, so the one explanation now ruled out is that
                // YouTube does not have the song. What remains is a request that failed, a
                // response that parsed to nothing, or a response whose items were all something
                // other than songs, and the next run should not have to guess which of the three
                // it hit. See ResolutionProbe in innertube.
                if (candidates.isEmpty()) {
                    val failure = found.exceptionOrNull()
                    when {
                        failure != null -> Log.w(TAG, "Search for '$query' failed", failure)
                        items.isNullOrEmpty() -> Log.w(TAG, "Search for '$query' parsed to no items")
                        else -> Log.w(
                            TAG,
                            "Search for '$query' returned ${items.size} items, none a song: " +
                                    items.joinToString { it::class.simpleName ?: "?" },
                        )
                    }
                }
                val best = candidates.firstOrNull()
                val certain = best != null && corresponds(outcome.track, best)

                // Recorded the moment Shazam names it, before anything is decided about adding
                // it or even placing it on YouTube. The history is a record of what the room was
                // playing, which is true whether or not a video turned up for it.
                scope.launch {
                    history.add(
                        Heard(
                            title = outcome.track.title,
                            artist = outcome.track.artist.orEmpty(),
                            videoId = best?.id,
                            at = System.currentTimeMillis(),
                        )
                    )
                }

                // Measured only against a first sighting this can really be the second listen to.
                // A stale one set the rate near zero, and the notification's position all but
                // stopped for the rest of the track; an unkeyed one measured across two different
                // songs. The bounds are the ones PlaybackVariant.between uses.
                val waitingFor = partnerOf(outcome.track, heardAtMs)
                val measuredRate = if (waitingFor != null) {
                    val apart = (heardAtMs - waitingFor.second) / 1000.0
                    val advanced = outcome.track.offsetSeconds - waitingFor.first.offsetSeconds
                    if (apart > 0 && advanced > apart / 3 && advanced < apart * 3) advanced / apart
                    else 1.0
                } else 1.0

                // Position is known from this alone, so it is published before any decision about
                // adding. Even a track that will not be added is worth showing while it plays.
                // A length the room is already past belongs to a shorter upload than the one
                // playing, and would only pin the bar at the end, so the song is shown without one.
                val now = System.currentTimeMillis()
                val position = outcome.track.offsetSeconds + (now - heardAtMs) / 1000.0 * measuredRate
                publish(
                    NowPlaying(
                        title = outcome.track.title,
                        artist = outcome.track.artist,
                        artworkUrl = outcome.track.artworkUrl,
                        offsetAtMatch = outcome.track.offsetSeconds,
                        sampledAtMs = heardAtMs,
                        durationSeconds = best?.duration?.takeIf { it > position },
                        rate = measuredRate,
                    )
                )

                // A piece of a mashup is not added, and neither is anything while it is being
                // worked out. Only in Keep listening, the one mode that hears enough windows.
                val key = outcome.track.shazamKey
                if (keepGoing && key != null) {
                    unmatchedRun = 0
                    mix?.let { m ->
                        when {
                            heardAtMs - m.lastHeardMs > MIX_QUIET_MS -> endMix("none of it heard for a while")
                            m.endsAtMs?.let { heardAtMs > it } == true -> endMix("past the length of the upload")
                            heardAtMs - m.startedMs > MIX_LONGEST_MS -> endMix("longer than any mashup runs")
                        }
                    }
                    // As far back as a return still counts, so a piece away that long is still the
                    // same appearance to both.
                    if (lastHeard[key]?.let { heardAtMs - it > MixWatch.SPAN_MS } != false) firstHeard[key] = heardAtMs
                    lastHeard[key] = heardAtMs
                    checkRestarts(heardAtMs, ended = false, playing = key)
                    val sighting = MixWatch.Sighting(
                        key, outcome.track.title, outcome.track.artist, heardAtMs,
                        outcome.track.offsetSeconds, outcome.track.timeSkew,
                    )
                    mixWatch.observe(sighting)?.let { onMix(it, heardAtMs, key) }
                    val length = best?.duration ?: confirmed[key]?.duration
                    val verdict = cutWatch.observe(key, outcome.track.offsetSeconds, outcome.track.timeSkew, heardAtMs, length)
                    restarts[key]?.let {
                        it.furthest = maxOf(it.furthest, outcome.track.offsetSeconds)
                        it.atMs = heardAtMs
                    }
                    when (verdict) {
                        CutWatch.Verdict.FIRST, CutWatch.Verdict.AGAIN -> {
                            val active = mix
                            if (active != null && key in active.keys) {
                                // Still cutting about, so still not the song: the hold starts over.
                                active.lastCutMs = heardAtMs
                            } else if (mixWatch.steadyHost(heardAtMs).let { host ->
                                    // Only while that steady song is still playing: one that ended
                                    // just before a chopped edit began is no reason to let it be.
                                    host == null || host == key || heardAtMs - (mixWatch.lastHeard(host) ?: 0L) > 2 * windowMs
                                }) {
                                // Unless another song is playing straight through it: then this is
                                // that song's original, or a sample in it, which is cut up because
                                // that is what a remix does. A verdict held back like that is not
                                // lost: the next cut, once nothing is playing straight, counts.
                                onCuts(sighting, heardAtMs)
                            }
                        }
                        CutWatch.Verdict.RESTART -> length?.let {
                            restarts[key] = Restart(sighting, outcome.track.offsetSeconds, heardAtMs, it)
                        }
                        CutWatch.Verdict.NONE -> {}
                    }
                    // A version Shazam does not know: it keeps matching the ones it does. Unless one
                    // of those is playing straight through, which makes it the one playing.
                    versionWatch.observe(sighting)?.let { versions ->
                        val host = mixWatch.steadyHost(heardAtMs)
                        if (versions.none { it.key == host }) {
                            Log.i(TAG, "'${outcome.track.title}' comes through as ${versions.size} versions: one Shazam does not know")
                            val first = versions.first()
                            onCuts(first.copy(title = MixSearch.bareTitle(first.title)), heardAtMs, versions)
                        }
                    }
                    // Only a return after a cut keeps a mashup going (onMix). A piece playing
                    // straight on used to refresh it on every window and so stayed blocked for as
                    // long as it played; now it is confirmed as usual once the cuts stop.
                    mix?.takeIf { key in it.keys }?.let {
                        // Heard keeps the mashup alive; held back only for a while after the last
                        // cut. A piece that plays on well past that is confirmed as usual, and if the
                        // mashup cuts away and back again, onMix takes it out once more.
                        it.lastHeardMs = heardAtMs
                        refreshChoice(it)
                        if (heardAtMs - it.lastCutMs <= MIX_HOLD_MS) {
                            Log.i(TAG, "'${outcome.track.title}' is part of a mashup, not adding it")
                            pending = null
                            pendingVariant = null
                            return
                        }
                    }
                }

                // Already confirmed this run, so a search that came back empty this time says nothing
                // about the song. Noting it as unsure put a song that was in the list into the unsure
                // list as well, which the Damage run of 24 Sep did at 12:58.
                // The same recording under another of its Shazam entries is the same song carrying on.
                if (keepGoing && key != null && key !in confirmed) {
                    versionWatch.twinOf(key, confirmed.keys)?.let { twin ->
                        confirmed[key] = confirmed.getValue(twin)
                        confirmedAt[key] = confirmedAt[twin] ?: heardAtMs
                    }
                }
                if (keepGoing && key != null && key in confirmed) {
                    Log.i(TAG, "Still '${outcome.track.title}', carrying on")
                    pending = null
                    pendingVariant = null
                    return
                }

                // The setting is about adding to a playlist. A run with no playlist adds nothing
                // either way, and reading it there sent every confident match to the unsure list,
                // so with it off the screen never listed a single song it had placed.
                val autoAdd = context.dataStore.data.first()[RecogniseAutoAddKey] ?: true
                if (keepGoing && certain && best != null && (autoAdd || playlist == null)) {
                    if (best.id in known || (key != null && key in confirmed)) {
                        Log.i(TAG, "Still '${best.title}', carrying on")
                        pending = null
                        pendingVariant = null
                        return
                    }

                    val waiting = partnerOf(outcome.track, heardAtMs)
                    if (waiting == null) {
                        // First sighting, or the only earlier one is too old or has no key to
                        // match on and so counts for nothing (see isSecondListen). Listen once
                        // more before committing, both to be sure and to get a second offset to
                        // measure the playback rate against.
                        Log.i(TAG, "Heard '${outcome.track.title}', listening again to confirm")
                        pending = outcome.track to heardAtMs
                        // A speed read off another song says nothing about this one.
                        pendingVariant = null
                        _state.value = State.Confirming(outcome.track)
                        return
                    }

                    val apart = (heardAtMs - waiting.second) / 1000.0
                    val advanced = outcome.track.offsetSeconds - waiting.first.offsetSeconds
                    // Sure means the two listens agree. If the track moved on by far more or far
                    // less than the clock did, or went backwards, the room is not playing it from
                    // start to end: a mashup or an edit cutting between its sections, or a repeated
                    // chorus matched at the wrong one. Neither is a second listen, so this one
                    // starts a confirmation of its own.
                    if (advanced < apart * STEADY_MIN || advanced > apart * STEADY_MAX) {
                        Log.i(
                            TAG,
                            "'${outcome.track.title}' moved ${"%.1f".format(advanced)}s in " +
                                    "${"%.1f".format(apart)}s, listening again",
                        )
                        pending = outcome.track to heardAtMs
                        pendingVariant = null
                        _state.value = State.Confirming(outcome.track)
                        return
                    }
                    val variant = PlaybackVariant.between(waiting.first, outcome.track, apart)
                    // A slowed or sped-up copy has to measure so twice in a row. The Damage mashup
                    // measured Faint as sped up once and as the original after, and the sped-up
                    // reading alone would have picked the wrong upload.
                    if (variant != PlaybackVariant.ORIGINAL && pendingVariant != variant) {
                        Log.i(TAG, "'${outcome.track.title}' measured $variant once, listening again")
                        pending = outcome.track to heardAtMs
                        pendingVariant = variant
                        _state.value = State.Confirming(outcome.track)
                        return
                    }
                    // Another version of the song somewhere else on its timeline lately: one more window
                    // on this one first. A third version in the meantime means neither is playing.
                    if (key != null && waiting !== heldForRival && versionWatch.rivalled(key)) {
                        Log.i(TAG, "Another version of '${outcome.track.title}' came through lately, listening again")
                        pending = outcome.track to heardAtMs
                        heldForRival = pending
                        _state.value = State.Confirming(outcome.track)
                        return
                    }
                    val chosen = pickBest(outcome.track, candidates, variant) ?: best
                    Log.i(
                        TAG,
                        "Confirmed '${outcome.track.title}' over ${"%.1f".format(apart)}s, " +
                                "variant=$variant, adding '${chosen.title}'"
                    )
                    pending = null
                    pendingVariant = null
                    // A song that is not part of the mashup, from its first seconds and on for two
                    // listens, is the next track. Memories Anthem came straight after Beggin' For DNA
                    // with Party Rock Anthem 23 s in, and without this the new mashup's pieces were
                    // taken for new pieces of the old one.
                    mix?.let { m ->
                        if (key != null && key !in m.keys && waiting.first.offsetSeconds < MixWatch.RESTART_S + windowMs / 1000.0) {
                            endMix("'${outcome.track.title}' started from the top")
                        }
                    }
                    // Both entries, when the pair was one recording under two of them.
                    listOfNotNull(key, waiting.first.shazamKey).forEach {
                        confirmed[it] = chosen
                        confirmedAt[it] = heardAtMs
                    }
                    add(chosen)
                    return
                }
                if (keepGoing) {
                    // Not confident enough to add unasked, and a background run must not stop to
                    // consult anybody. It is recorded rather than discarded so the sheet can say
                    // what it heard and passed over, and so does not sit there looking dead.
                    val name = outcome.track.title
                    if (_skipped.value.none { it.title == name }) {
                        Log.i(TAG, "Unsure about '$name' (best was '${best?.title}' by " +
                                "'${best?.artists?.joinToString { a -> a.name }}'), noting it")
                        _skipped.value += Added(name, outcome.track.artist.orEmpty(), auto = false)
                    }
                    return
                }
                // Placed with confidence, so on the screen it joins the session's list without waiting
                // to be picked; the screen hides the candidates once it is listed. Not on a
                // playlist's sheet, which shows the candidates and lets the person pick another:
                // listing this one there put the upload they turned down in the screen's list, and
                // Save as playlist saved it. [add] records whatever they do pick.
                if (certain && playlist == null) record(best)
                _state.value = State.Found(outcome.track, candidates, certain)
            }
        }
    }

    /** [pending], if [track] heard at [now] is its second listen, else null. */
    private fun partnerOf(track: Recognised, now: Long): Pair<Recognised, Long>? =
        pending?.takeIf { (first, at) -> isSecondListen(first, at, track, now, pendingLifetimeMs) }

    /**
     * Shows [playing], or nothing, and arranges for it to come down when its track runs out.
     *
     * A timer here rather than a check wherever it is drawn, so everything watching [nowPlaying]
     * sees the song end at the same moment instead of each deciding for itself.
     */
    private fun publish(playing: NowPlaying?) {
        expiry?.cancel()
        _nowPlaying.value = playing
        val endsAt = playing?.endsAtMs() ?: return
        expiry = scope.launch {
            delay(endsAt - System.currentTimeMillis())
            // The length is the YouTube upload's, and the room's copy can run longer: an extended
            // mix, a live intro. Taken down on the dot, a song like that vanished and came back a
            // window later. So it stays without a length until the next window has had its say,
            // which a match, a miss or silence all replace. Only if nothing has replaced it since.
            val open = playing.copy(durationSeconds = null)
            if (!_nowPlaying.compareAndSet(playing, open)) return@launch
            delay(windowMs + LENGTH_GRACE_MS)
            _nowPlaying.compareAndSet(open, null)
        }
    }

    /**
     * Adds [song] to [recognised] unless a song with its id is already there, and to the playlist
     * being [following] if it is new.
     */
    private fun record(song: SongItem) {
        var appended = false
        _recognised.update { list ->
            // Set on every attempt, since update runs this again if another write got in first.
            appended = list.none { it.id == song.id }
            if (appended) list + song else list
        }
        if (!appended) return
        synchronized(owned) { owned += song.id }
        _following.value?.let { writeInto(it, song) }
    }

    /** Puts [song] at the end of [playlist], unless it is in there already. */
    private fun writeInto(playlist: PlaylistEntity, song: SongItem) {
        scope.launch(Dispatchers.IO) {
            val added = runCatching {
                database.transactionNow {
                    if (playlistDuplicates(playlist.id, listOf(song.id)).isNotEmpty()) return@transactionNow false
                    insert(song.toMediaMetadata())
                    insert(
                        PlaylistSongMap(
                            songId = song.id,
                            playlistId = playlist.id,
                            position = nextPlaylistPosition(playlist.id),
                        )
                    )
                    synchronized(written) { written += song.id to playlist.id }
                    true
                }
            }.onFailure { Log.w(TAG, "Could not add '${song.title}' to ${playlist.name}", it) }
                .getOrDefault(false)
            if (added && !playlist.isLocal) {
                playlist.browseId?.let { browseId ->
                    YouTube.addToPlaylist(browseId, song.id)
                        .onFailure { Log.w(TAG, "Could not push '${song.title}' to ${playlist.name}", it) }
                }
            }
        }
    }

    /** Adds a song and remembers it, from either the automatic path or a person's choice. */
    fun add(song: SongItem) {
        // A run with no playlist is the screen's, and lists what it confirmed. A playlist's run
        // records nothing there: its songs went into that playlist already.
        val target = playlist ?: return record(song)
        if (!known.add(song.id)) return
        synchronized(owned) { owned += song.id }
        _added.value += Added(song.title, song.artists.joinToString { it.name }, auto = continuous.value)
        Log.i(TAG, "Added '${song.title}'")

        scope.launch(Dispatchers.IO) {
            database.transaction {
                insert(song.toMediaMetadata())
                // Not addSongToPlaylist, which takes the position from the Playlist it is handed.
                // This one is a snapshot taken in start() and held for the whole run, so its count
                // is however many songs there were when listening began. Every song added over an
                // evening got that same position, came back in an unspecified order, and removing
                // one moved all the others to the bottom, because the move matches on position.
                // Read inside the transaction so the read and the write cannot interleave.
                insert(
                    PlaylistSongMap(
                        songId = song.id,
                        playlistId = target.id,
                        position = nextPlaylistPosition(target.id),
                    )
                )
                synchronized(written) { written += song.id to target.id }
            }
            target.playlist.browseId?.let { runCatching { YouTube.addToPlaylist(it, song.id) } }
        }
    }

    /**
     * A song came back after being interrupted by others: see [MixWatch]. Looks for the mashup on
     * YouTube, and only if that finds one naming two of the pieces does anything happen: the pieces
     * come back out, and the mashup goes in, or is offered to pick from when two uploads tie.
     *
     * The order alone was too little. Skipping back to the previous song, a playlist that repeats,
     * a DJ blending two tracks and one recording under two Shazam keys all come back to a song in
     * the same way, and each of them took correctly named songs out of the list.
     *
     * Inline on the listening loop rather than launched, because it reads and writes the same state
     * the loop does. The microphone keeps capturing meanwhile, so no audio is lost to the search.
     */
    private suspend fun onMix(found: MixWatch.Mix, now: Long, returning: String) {
        val songs = MixSearch.distinctSongs(found.pieces)
        if (songs.size < 2) return
        val keys = found.pieces.map { it.key }
        // None of these pieces is the mashup that was on, so that one is over and this is another.
        mix?.let { if (keys.none { key -> key in it.keys }) endMix("a different mashup started") }
        // A song confirmed in between, over two listens moving at the speed of the clock, was a
        // song that played: somebody skipped back, or a playlist came round. Only for a first, weak
        // detection. Inside a mashup a piece can play long enough to be confirmed too, and ruling
        // out every later return because of it meant the mashup was never found.
        if (mix == null && !found.strong && found.pieces.any { it.key != returning && it.key in confirmed }) return

        // The same mashup again after a quiet spell ended it: already answered, so its pieces only
        // come back out.
        val holding = mix?.takeIf { !it.settled && it.searched && it.candidates.isEmpty() }
        if ((mix == null || holding != null) && answered.containsAll(keys)) {
            mix = (holding ?: ActiveMix(startedMs = startOf(keys, now))).apply {
                this.keys += keys; lastHeardMs = now; lastCutMs = now; strong = true; settled = true; searched = true
            }
            retract(found.pieces)
            return
        }

        val active = mix
        if (active != null && (active.settled || (active.searched && active.keys.containsAll(keys) &&
                    (active.strong || !found.strong) && (active.sure || !found.sure)))) {
            active.keys += keys
            active.lastHeardMs = now
            active.lastCutMs = now
            // Answered already, so a new piece is only taken out of the list, not asked about.
            if (active.settled) retract(found.pieces)
            return
        }

        val started = active?.startedMs?.takeIf { it > 0 } ?: startOf(keys, now)
        val ranked = searchMix(songs)?.filter { MixSearch.couldBe(it.first, heardSeconds(started, now)) }
        // Stopped or cleared while the search ran: YouTube.search catches the cancellation, so
        // without this the rest would carry on against a run that no longer exists.
        currentCoroutineContext().ensureActive()
        val winner = ranked?.let { MixSearch.clearWinner(it) }
        val titles = songs.map { it.title }
        Log.i(
            TAG,
            "Mashup of ${titles.joinToString(" + ")}? strong=${found.strong}, " +
                    (ranked?.take(3)?.joinToString { "'${it.first.title}' ${it.second}" } ?: "search failed") +
                    ", taking ${winner?.title?.let { "'$it'" } ?: "nothing"}",
        )
        // Nothing on YouTube names two of the pieces, so there is no mashup to point at, and the
        // songs heard stay as they are. One odd window with only a toss-up is left alone too.
        // A failed search changes nothing either; the next time a piece comes back it runs again.
        if (ranked.isNullOrEmpty() || (winner == null && !found.strong)) return

        val current = (active ?: ActiveMix().also { mix = it }).apply {
            this.keys += keys
            lastHeardMs = now
            lastCutMs = now
            strong = strong || found.strong
            sure = sure || found.sure
            pieces = (found.pieces + pieces).distinctBy { it.key }
            searched = true
            startedMs = started
            candidates = ranked.take(MAX_CANDIDATES).map { it.first }
        }
        // Only when sure. Suspicious is somebody skipping about a playlist as easily as a mashup,
        // and asking costs nothing; taking three right songs out of the list does.
        if (current.sure) retract(found.pieces)
        val autoAdd = playlist == null || (context.dataStore.data.first()[RecogniseAutoAddKey] ?: true)
        if (current.settled) return
        when {
            winner != null && autoAdd && current.sure -> {
                current.settled = true
                answered += current.keys
                current.found = winner
                current.endsAtMs = endOf(current.startedMs, winner)
                dropChoice(current.id)
                add(winner)
            }
            // The choice is drawn by the screen, whose runs have no playlist. The sheet shows the
            // unsure list instead, so a playlist's run notes the mashup there.
            playlist == null -> offerChoice(current, titles)
            else -> {
                val name = winner?.title ?: titles.joinToString(" + ")
                if (_skipped.value.none { it.title == name }) {
                    _skipped.value += Added(name, context.getString(R.string.recognise_mashup), auto = false)
                }
            }
        }
    }

    /**
     * A song whose windows keep landing in the wrong part of it: see [CutWatch]. Whatever is playing
     * is an edit, a remix or a mashup of it, not the song, so it comes out of the list and is held
     * back, and the remixes and mashups that name it are offered to pick from. Unless a mashup is
     * already being worked out from other songs, whose search knows more than this one can.
     */
    private suspend fun onCuts(piece: MixWatch.Sighting, now: Long, versions: List<MixWatch.Sighting> = emptyList()) {
        // The piece and, for a version Shazam does not know, every version it was matched as.
        val all = (listOf(piece) + versions).distinctBy { it.key }
        val allKeys = all.map { it.key }
        mix?.let { m ->
            // Another song cut up, while a mashup already answered is on. If which upload it is,
            // and so its length, is known and not yet run out, this is more of it. Otherwise it is
            // over and this is something new.
            if (piece.key !in m.keys && m.settled && m.endsAtMs.let { it == null || now > it }) {
                endMix("another song is cut up")
            }
        }
        // Answered already, the same edit or mashup on again: only its piece comes back out.
        if (mix == null && piece.key in answered) {
            // Answered as an edit of itself: settled. Answered as part of a mashup: held back, and the
            // mashup check says which mashup this is once another piece shows up.
            mix = ActiveMix(allKeys.toMutableSet(), now, now, strong = true, settled = piece.key in answeredAlone, searched = true, startedMs = startOf(allKeys, now))
            retract(all)
            return
        }
        val active = mix
        if (active != null && active.keys.containsAll(allKeys)) {
            active.lastHeardMs = now
            active.lastCutMs = now
            return
        }
        Log.i(TAG, "'${piece.title}' keeps landing in other parts of the song: an edit, a remix or a mashup")
        val current = (active ?: ActiveMix().also { mix = it }).apply {
            keys += allKeys
            lastHeardMs = now
            lastCutMs = now
            strong = true
            sure = true
            pieces = (pieces + all).distinctBy { it.key }
            if (startedMs == 0L) startedMs = startOf(allKeys, now)
        }
        retract(all)
        if (current.settled || current.searched) return

        var failed = false
        val results = MixSearch.singleQueries(piece).map { query ->
            YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
                .onFailure {
                    failed = true
                    Log.w(TAG, "Search for '$query' failed", it)
                }
                .getOrNull()?.items?.filterIsInstance<SongItem>()?.take(10).orEmpty()
        }
        currentCoroutineContext().ensureActive()
        // Answered while the search ran, or replaced by another: what the person said stands.
        if (current.settled || mix !== current) return
        val heard = heardSeconds(current.startedMs, now)
        val found = MixSearch.rankSingle(piece, results).filter { MixSearch.couldBe(it, heard) }
        current.candidates = found.take(MAX_CANDIDATES)
        val choices = found.take(CHOICES)
        Log.i(TAG, "Remixes and mashups of '${piece.title}': ${found.take(MAX_CANDIDATES).joinToString { "'${it.title}' ${it.duration}s" }}")
        when {
            choices.isNotEmpty() && playlist == null -> offerChoice(current, listOf(piece.title))
            choices.isNotEmpty() || !failed -> {
                if (_skipped.value.none { it.title == piece.title }) {
                    _skipped.value += Added(piece.title, context.getString(R.string.recognise_edit), auto = false)
                }
            }
        }
    }

    /**
     * The mashup is over: nothing heard from now on is matched against it. Both watches forget what
     * they saw, so the next song's windows are not paired with the last one's.
     *
     * If it ended with the choice still waiting, how long it played is now known, near enough, and
     * the uploads that long go to the top of it. On 24 Sep that alone would have put the two right
     * uploads first, which the words in their titles could not.
     */
    private fun endMix(why: String) {
        val over = mix ?: return
        Log.i(TAG, "Mashup over: $why")
        mix = null
        // Only its own pieces are forgotten. What ended it, a new song from its top or the first
        // pieces of the next mashup, is what the next verdict needs.
        mixWatch.forget(over.keys)
        cutWatch.forget(over.keys)
        versionWatch.forget(over.keys)
        unmatchedRun = 0
        over.keys.forEach { firstHeard.remove(it); lastHeard.remove(it) }
        if (!over.settled && over.candidates.isNotEmpty()) {
            val heard = heardSeconds(over.startedMs, over.lastHeardMs)
            val reordered = MixSearch.byLength(over.candidates, heard).ifEmpty { over.candidates }
            Log.i(TAG, "Heard it for ${"%.0f".format(heard)} s: ${reordered.take(CHOICES).joinToString { "'${it.title}' ${it.duration}s" }}")
            updateChoice(over.id) { it.copy(candidates = reordered.take(CHOICES)) }
        }
    }

    /**
     * Drops the choices shorter than what has already been heard of the mashup, as long as that
     * leaves something: a choice with nothing under "Which one is it?" but None of these asks nothing.
     */
    private fun refreshChoice(active: ActiveMix) {
        if (active.settled || active.candidates.isEmpty()) return
        val heard = heardSeconds(active.startedMs, active.lastHeardMs)
        val still = active.candidates.filter { MixSearch.couldBe(it, heard) }
        if (still.size == active.candidates.size || still.isEmpty()) return
        active.candidates = still
        updateChoice(active.id) { it.copy(candidates = still.take(CHOICES)) }
    }

    /** Shows the choice for [active], in place of one it already had, alongside any others. */
    private fun offerChoice(active: ActiveMix, titles: List<String>) {
        val choice = MixChoice(active.id, active.keys.toSet(), titles, active.candidates.take(CHOICES))
        _mixChoices.update { list -> listOf(choice) + list.filterNot { it.id == active.id || active.keys.containsAll(it.keys) } }
    }

    private fun updateChoice(id: Long, change: (MixChoice) -> MixChoice) {
        _mixChoices.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun dropChoice(id: Long) {
        _mixChoices.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Settles the songs that went back to their top partway through, once they have stopped: the
     * one [playing] now is still going and is left alone, the rest have not been heard for two
     * windows, or [ended] says the music stopped. One that stopped well short of its end was an
     * edit, and is handled as a cut-up song would be, now that it is over.
     */
    private suspend fun checkRestarts(now: Long, ended: Boolean, playing: String? = null) {
        val over = restarts.filter { (key, r) -> key != playing && (ended || now - r.atMs >= 2 * windowMs) }
        for ((key, r) in over) {
            restarts.remove(key)
            val reached = r.furthest + windowMs / 1000.0
            if (reached < r.durationS - EARLY_END_S) {
                Log.i(TAG, "'${r.sighting.title}' went back to its top and stopped at ${"%.0f".format(reached)} s of ${r.durationS}: an edit")
                onCuts(r.sighting.copy(atMs = r.atMs), r.atMs)
            }
        }
    }

    private fun startOf(keys: Collection<String>, now: Long): Long =
        keys.mapNotNull { firstHeard[it] }.minOrNull() ?: now

    /** From the first window of it to the end of the last, in seconds. */
    private fun heardSeconds(startedMs: Long, lastMs: Long): Double = (lastMs - startedMs + windowMs) / 1000.0

    /**
     * When a mashup that started being heard at [startedMs] has to be over, given which upload it
     * is. Late rather than early, since what played before its first recognised window is unknown.
     */
    private fun endOf(startedMs: Long, song: SongItem): Long? =
        song.duration?.let { startedMs + it * 1000L + MIX_END_MARGIN_MS }

    /** Ranked mashups naming two of [songs], or null when a search failed and found nothing. */
    private suspend fun searchMix(songs: List<MixWatch.Sighting>): List<Pair<SongItem, Int>>? {
        var failed = false
        val results = MixSearch.queries(songs).map { query ->
            YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
                .onFailure {
                    failed = true
                    Log.w(TAG, "Mashup search for '$query' failed", it)
                }
                .getOrNull()?.items?.filterIsInstance<SongItem>()?.take(10).orEmpty()
        }
        return MixSearch.rank(songs, results).takeUnless { failed && it.isEmpty() }
    }

    /**
     * Takes the songs this run added for [pieces] back out of the list and out of any playlist it
     * put them in. Only what this run placed: a song that was in the list or the playlist before
     * stays. A YouTube playlist keeps its copy, which the API here has no way to remove.
     */
    private suspend fun retract(pieces: List<MixWatch.Sighting>) {
        // Only what was confirmed during this appearance of the song. The same song played on its
        // own earlier in the evening was a real play, and stays.
        val songs = pieces.mapNotNull { piece ->
            val since = (firstHeard[piece.key] ?: 0L) - windowMs
            if ((confirmedAt[piece.key] ?: Long.MIN_VALUE) >= since) {
                confirmedAt.remove(piece.key)
                confirmed.remove(piece.key)
            } else null
        }
        val ids = synchronized(owned) { songs.map { it.id }.filter { it in owned }.toSet().also { owned.removeAll(it) } }
        if (ids.isEmpty()) return
        Log.i(TAG, "Taking out ${songs.filter { it.id in ids }.joinToString { "'${it.title}'" }}, pieces of a mashup")
        _recognised.update { list -> list.filterNot { it.id in ids } }
        _added.update { list -> list.filterNot { added -> songs.any { it.id in ids && it.title == added.title } } }
        known.removeAll(ids)
        val rows = synchronized(written) { written.filter { it.first in ids }.also { written.removeAll(it) } }
        if (rows.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                database.transactionNow { rows.forEach { (songId, playlistId) -> removeSongFromPlaylist(playlistId, songId) } }
            }.onFailure { Log.w(TAG, "Could not take the mashup's pieces out of the playlist", it) }
        }
    }

    /**
     * The person picked the upload it was, from [choice]. Settles that mashup, if it is still the one
     * on, and remembers its pieces as answered either way, so it is not asked about again.
     */
    fun acceptMix(choice: MixChoice, song: SongItem) {
        scope.launch(serial) {
            answered += choice.keys
            if (choice.keys.size == 1) answeredAlone += choice.keys
            mix?.takeIf { it.id == choice.id }?.apply {
                // Including pieces that joined it after the choice was offered.
                answered += keys
                found = song
                settled = true
                endsAtMs = endOf(startedMs, song)
                // Picked, so it was a mashup: the songs it names come out, and only those.
                retract(pieces.filter { MixSearch.names(it, song) })
            }
            dropChoice(choice.id)
            add(song)
        }
    }

    fun dismissMix(choice: MixChoice) {
        scope.launch(serial) {
            answered += choice.keys
            if (choice.keys.size == 1) answeredAlone += choice.keys
            mix?.takeIf { it.id == choice.id }?.let {
                answered += it.keys
                it.settled = true
            }
            dropChoice(choice.id)
        }
    }

    fun stop() {
        halt()
        _state.value = State.Idle
    }

    /**
     * Stops listening and leaves whatever the state says on screen.
     *
     * [stop] is "put everything down": it is what the stop button and dismissing the sheet mean, and
     * Idle is right for both. A single-shot run finishing is different. It has an answer the person
     * has not seen yet, so the microphone closes and the service can go, but the answer stays until
     * they act on it or dismiss it, which reaches [stop] through the view model.
     */
    private fun halt() {
        job?.cancel()
        job = null
        running.value = false
        pending = null
        pendingVariant = null
        publish(null)
    }

    fun reset() {
        stop()
        _mixChoices.value = emptyList()
        _added.value = emptyList()
        // Cleared with the rest, because the button that calls this sits over the list of what
        // was heard, and a clear that left the near misses standing did not clear anything.
        _skipped.value = emptyList()
    }

    /**
     * Empties [recognised]. Separate from [reset] because the playlist sheet resets whenever it is
     * dismissed, and that emptied the screen's unsaved list along with it.
     */
    fun clearRecognised() {
        _recognised.value = emptyList()
        _mixChoices.value = emptyList()
        // A fresh list is not the one that playlist was made from, so it stops filling it.
        follow(null)
    }

    companion object {
        private const val TAG = "RecognitionEngine"

        /**
         * Listen windows a first sighting stays confirmable for. The second listen normally lands
         * one window later; three leaves room for a failed request or a slow search in between.
         */
        private const val PENDING_LIFETIME_WINDOWS = 3

        /**
         * How far a second listen may have moved through the track for the clock that passed, as
         * a ratio. Wide enough for any slowed or sped-up edit, which run between about 0.7 and 1.4,
         * and nothing like a cut to another section.
         */
        /** How long past the next window a song outliving its upload's length stays up. */
        private const val LENGTH_GRACE_MS = 5_000L
        private const val STEADY_MIN = 0.6
        private const val STEADY_MAX = 1.6

        /** A mashup ends once none of its pieces has been heard for this long. */
        private const val MIX_QUIET_MS = 90_000L

        /** No mashup runs longer than this; past it, whatever is heard is something else. */
        private const val MIX_LONGEST_MS = 600_000L

        /** Slack after a known upload's length before it is over. */
        private const val MIX_END_MARGIN_MS = 20_000L

        /** Uploads offered to pick from, and how many more are kept to reorder them from. */
        private const val CHOICES = 3
        private const val MAX_CANDIDATES = 8

        /** A song that went back to its top and then stopped this far short of its end was an edit. */
        private const val EARLY_END_S = 30

        /**
         * How long after a cut its pieces are held back. Longer than a section of a mashup: the
         * Damage run played Faint straight through for over two minutes before its first cut.
         */
        private const val MIX_HOLD_MS = 180_000L
    }
}
