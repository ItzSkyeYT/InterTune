/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    fun start(playlist: Playlist, continuous: Boolean) {
        if (job?.isActive == true) return
        this.playlist = playlist
        this.continuous.value = continuous
        _added.value = emptyList()
        _skipped.value = emptyList()
        pending = null

        job = scope.launch {
            running.value = true
            startedAt.value = System.currentTimeMillis()
            known = database.playlistSongs(playlist.id).first().map { it.song.song.id }.toMutableSet()
            Log.i(TAG, "Listening for playlist '${playlist.playlist.name}', continuous=$continuous, ${known.size} already in it")

            try {
                // One microphone session for the whole run. Each window is identified while the
                // next is already being captured, so nothing between songs is missed.
                microphone.stream { level ->
                    val current = _state.value
                    if (current is State.Listening) _state.value = current.copy(level = level)
                    else if (current is State.Idle) _state.value = State.Listening(level, false)
                }.collect { window ->
                    identify(window)
                    if (!this@RecognitionEngine.continuous.value && _state.value is State.Found) {
                        // Single shot stops on a result and waits for the person to choose.
                        return@collect stop()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Listening stopped", t)
                _state.value = State.Failed(t.message ?: "microphone", heardNothing = false)
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun identify(window: ShortArray) {
        val keepGoing = continuous.value
        _state.value = State.Listening(0f, identifying = true)

        when (val outcome = shazam.identify(window)) {
            is RecognitionOutcome.Failed -> {
                // Nothing to say in continuous mode. A quiet stretch or one refused request is not
                // a reason to stop listening or to ask somebody to press try again.
                if (!keepGoing) {
                    _state.value = State.Failed(outcome.reason, outcome.reason == "silence")
                }
            }

            RecognitionOutcome.NoMatch -> if (!keepGoing) _state.value = State.NoMatch

            is RecognitionOutcome.Match -> {
                val candidates = YouTube.search(outcome.track.searchQuery, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()?.items?.filterIsInstance<SongItem>()?.take(4).orEmpty()
                val best = candidates.firstOrNull()
                val certain = best != null && corresponds(outcome.track, best)

                // Position is known from this alone, so it is published before any decision about
                // adding. Even a track that will not be added is worth showing while it plays.
                val waitingFor = pending
                val measuredRate = if (waitingFor != null && waitingFor.first.shazamKey == outcome.track.shazamKey) {
                    val apart = (System.currentTimeMillis() - waitingFor.second) / 1000.0
                    val advanced = outcome.track.offsetSeconds - waitingFor.first.offsetSeconds
                    if (apart > 0 && advanced > 0 && advanced < apart * 3) advanced / apart else 1.0
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

                if (keepGoing && certain && best != null) {
                    if (best.id in known) {
                        Log.i(TAG, "Still '${best.title}', carrying on")
                        pending = null
                        return
                    }

                    val now = System.currentTimeMillis()
                    val waiting = pending
                    if (waiting == null || waiting.first.shazamKey != outcome.track.shazamKey) {
                        // First sighting. Listen once more before committing, both to be sure and
                        // to get a second offset to measure the playback rate against.
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

    /** Adds a song and remembers it, from either the automatic path or a person's choice. */
    fun add(song: SongItem) {
        val target = playlist ?: return
        if (!known.add(song.id)) return
        _added.value += Added(song.title, song.artists.joinToString { it.name }, auto = continuous.value)
        Log.i(TAG, "Added '${song.title}'")

        scope.launch(Dispatchers.IO) {
            database.transaction {
                insert(song.toMediaMetadata())
                addSongToPlaylist(target, listOf(song.id))
            }
            target.playlist.browseId?.let { runCatching { YouTube.addToPlaylist(it, song.id) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running.value = false
        pending = null
        _nowPlaying.value = null
        _state.value = State.Idle
    }

    fun reset() {
        stop()
        _added.value = emptyList()
    }

    companion object {
        private const val TAG = "RecognitionEngine"
    }
}
