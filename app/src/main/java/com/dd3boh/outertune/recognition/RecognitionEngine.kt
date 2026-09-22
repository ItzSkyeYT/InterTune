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
     * match, and is null when nothing plausible was found.
     */
    data class NowPlaying(
        val title: String,
        val artist: String?,
        val artworkUrl: String?,
        private val offsetAtMatch: Double,
        private val matchedAtMs: Long,
        val durationSeconds: Int?,
        private val rate: Double,
    ) {
        fun positionSeconds(nowMs: Long = System.currentTimeMillis()): Int {
            val elapsed = (nowMs - matchedAtMs) / 1000.0
            val position = offsetAtMatch + elapsed * rate
            // Past the end means the track finished and the next recognition has not landed yet.
            return position.toInt().coerceAtLeast(0).let {
                if (durationSeconds != null) it.coerceAtMost(durationSeconds) else it
            }
        }
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

    /** The track currently in the room, refreshed on every recognition of it. */
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

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

    private suspend fun identify(window: ShortArray) {
        val keepGoing = continuous.value
        _state.value = State.Listening(0f, identifying = true)
        try {
            respondTo(shazam.identify(window), keepGoing)
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
     */
    private suspend fun respondTo(outcome: RecognitionOutcome, keepGoing: Boolean) {
        when (outcome) {
            is RecognitionOutcome.Failed -> {
                // Nothing to say in continuous mode. A quiet stretch or one refused request is not
                // a reason to stop listening or to ask somebody to press try again.
                if (!keepGoing) {
                    _state.value = State.Failed(outcome.reason, outcome.reason == "silence")
                }
            }

            RecognitionOutcome.NoMatch -> if (!keepGoing) _state.value = State.NoMatch

            is RecognitionOutcome.Match -> {
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

                // Position is known from this alone, so it is published before any decision about
                // adding. Even a track that will not be added is worth showing while it plays.
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
                val waitingFor = partnerOf(outcome.track, System.currentTimeMillis())
                val measuredRate = if (waitingFor != null) {
                    val apart = (System.currentTimeMillis() - waitingFor.second) / 1000.0
                    val advanced = outcome.track.offsetSeconds - waitingFor.first.offsetSeconds
                    if (apart > 0 && advanced > apart / 3 && advanced < apart * 3) advanced / apart
                    else 1.0
                } else 1.0
                _nowPlaying.value = NowPlaying(
                    title = outcome.track.title,
                    artist = outcome.track.artist,
                    artworkUrl = outcome.track.artworkUrl,
                    offsetAtMatch = outcome.track.offsetSeconds,
                    matchedAtMs = System.currentTimeMillis(),
                    durationSeconds = best?.duration,
                    rate = measuredRate,
                )

                val autoAdd = context.dataStore.data.first()[RecogniseAutoAddKey] ?: true
                if (keepGoing && certain && best != null && autoAdd) {
                    if (best.id in known) {
                        Log.i(TAG, "Still '${best.title}', carrying on")
                        pending = null
                        return
                    }

                    val now = System.currentTimeMillis()
                    val waiting = partnerOf(outcome.track, now)
                    if (waiting == null) {
                        // First sighting, or the only earlier one is too old or has no key to
                        // match on and so counts for nothing (see isSecondListen). Listen once
                        // more before committing, both to be sure and to get a second offset to
                        // measure the playback rate against.
                        Log.i(TAG, "Heard '${outcome.track.title}', listening again to confirm")
                        pending = outcome.track to now
                        _state.value = State.Confirming(outcome.track)
                        return
                    }

                    val apart = (now - waiting.second) / 1000.0
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
                _state.value = State.Found(outcome.track, candidates, certain)
            }
        }
    }

    /** [pending], if [track] heard at [now] is its second listen, else null. */
    private fun partnerOf(track: Recognised, now: Long): Pair<Recognised, Long>? =
        pending?.takeIf { (first, at) -> isSecondListen(first, at, track, now, pendingLifetimeMs) }

    /** Adds a song and remembers it, from either the automatic path or a person's choice. */
    fun add(song: SongItem) {
        val target = playlist ?: return
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
        _nowPlaying.value = null
    }

    fun reset() {
        stop()
        _added.value = emptyList()
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
