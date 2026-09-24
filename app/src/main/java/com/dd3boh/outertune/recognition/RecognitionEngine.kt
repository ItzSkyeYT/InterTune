/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

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

    private var job: Job? = null
    private var playlist: Playlist? = null
    private var known = mutableSetOf<String>()

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

        job = scope.launch {
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
                if (outcome.reason == "silence") publish(null)
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
            }

            is RecognitionOutcome.Match -> {
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

                // The setting is about adding to a playlist. A run with no playlist adds nothing
                // either way, and reading it there sent every confident match to the unsure list,
                // so with it off the screen never listed a single song it had placed.
                val autoAdd = context.dataStore.data.first()[RecogniseAutoAddKey] ?: true
                if (keepGoing && certain && best != null && (autoAdd || playlist == null)) {
                    if (best.id in known) {
                        Log.i(TAG, "Still '${best.title}', carrying on")
                        pending = null
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
                        _state.value = State.Confirming(outcome.track)
                        return
                    }

                    val apart = (heardAtMs - waiting.second) / 1000.0
                    val variant = PlaybackVariant.between(waiting.first, outcome.track, apart)
                    val chosen = pickBest(outcome.track, candidates, variant) ?: best
                    Log.i(
                        TAG,
                        "Confirmed '${outcome.track.title}' over ${"%.1f".format(apart)}s, " +
                                "variant=$variant, adding '${chosen.title}'"
                    )
                    pending = null
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
            // Only if nothing has replaced it since, which a later match would have.
            _nowPlaying.compareAndSet(playing, null)
        }
    }

    /** Adds [song] to [recognised] unless a song with its id is already there. */
    private fun record(song: SongItem) {
        _recognised.update { list -> if (list.any { it.id == song.id }) list else list + song }
    }

    /** Adds a song and remembers it, from either the automatic path or a person's choice. */
    fun add(song: SongItem) {
        // A run with no playlist is the screen's, and lists what it confirmed. A playlist's run
        // records nothing there: its songs went into that playlist already.
        val target = playlist ?: return record(song)
        if (!known.add(song.id)) return
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
            }
            target.playlist.browseId?.let { runCatching { YouTube.addToPlaylist(it, song.id) } }
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
        publish(null)
    }

    fun reset() {
        stop()
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
    }

    companion object {
        private const val TAG = "RecognitionEngine"

        /**
         * Listen windows a first sighting stays confirmable for. The second listen normally lands
         * one window later; three leaves room for a failed request or a slow search in between.
         */
        private const val PENDING_LIFETIME_WINDOWS = 3
    }
}
