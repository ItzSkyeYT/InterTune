/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.SystemClock
import android.util.Log
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.lastfm.SimilarTrack
import com.dd3boh.outertune.constants.RELATED_RETRY_COOLDOWN_MS
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.engine.SimilarMatch
import com.dd3boh.outertune.engine.SongRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Last.fm's similar tracks for the songs being played, stored in related_song_map as source 1,
 * for a listener who has chosen Last.fm over YouTube for similar songs.
 *
 * Needs only the API key built into the app, since track.getSimilar is a public read, so it works
 * with no account connected. A build without a key (F-Droid's) never gets here: the setting is
 * not shown.
 */
@Singleton
class LastFmSimilar @Inject constructor(
    private val scrobbler: Scrobbler,
    private val database: MusicDatabase,
) {
    /**
     * Songs asked about in this process and settled, stored or not. A song Last.fm does not know
     * writes no rows, so without this every chunk of it would ask again. In memory for the same
     * reason as MusicService's failure memo: a note about this process, not a fact about the song.
     */
    private val settled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = FailureMemo(RELATED_RETRY_COOLDOWN_MS)

    private val indexLock = Mutex()
    @Volatile private var index: SimilarMatch.Index? = null
    @Volatile private var indexBuiltAt = 0L

    private val requestLock = Mutex()
    private var lastRequestAt = 0L

    private val _caughtUpAt = MutableStateFlow(0L)

    /**
     * When the last catch-up finished, for Home to rebuild its row then rather than when the
     * switch was pressed, which is before Last.fm has been asked anything.
     */
    val caughtUpAt: StateFlow<Long> = _caughtUpAt

    val isAvailable get() = scrobbler.canFindSimilar

    private enum class Outcome { SETTLED, WAITING, FAILED }

    /**
     * Stores the song's similar tracks unless they are stored and fresh. True once there is
     * nothing left to do for it in this process; false while a request is failing, cooling down
     * or running for another chunk, so the caller tries again later.
     */
    suspend fun ensure(songId: String, title: String, artist: String?): Boolean =
        attempt(songId, title, artist) == Outcome.SETTLED

    /**
     * Asks about the songs a row is most likely to be built around, once, when Last.fm has just
     * been chosen. Without it the switch would do almost nothing for days: a song gets Last.fm's
     * list only when it is next played, and until then the engine uses YouTube's. Stops after a
     * few requests fail in a row rather than walking a dead network through hundreds of songs.
     */
    suspend fun catchUp() {
        if (!isAvailable) return
        var failing = 0
        for (song in database.lastFmCatchUp(System.currentTimeMillis() - CATCH_UP_WINDOW_MS, CATCH_UP_LIMIT)) {
            when (attempt(song.id, song.title, song.artist)) {
                Outcome.FAILED -> if (++failing >= 3) break
                Outcome.SETTLED -> failing = 0
                Outcome.WAITING -> {}
            }
        }
        _caughtUpAt.value = System.currentTimeMillis()
    }

    private suspend fun attempt(songId: String, title: String, artist: String?): Outcome {
        if (!isAvailable || songId in settled) return Outcome.SETTLED
        val now = System.currentTimeMillis()
        val fetchedAt = database.lastFmSimilarFetchedAt(songId)
        if ((fetchedAt != null && now - fetchedAt < STALE_MS) || SimilarMatch.firstArtist(artist).isEmpty() || title.isBlank()) {
            settled.add(songId)
            return Outcome.SETTLED
        }
        if (!failures.none(songId) || !inFlight.add(songId)) return Outcome.WAITING
        try {
            val neighbours = ask(title, artist)
                ?: return Outcome.FAILED.also { failures.note(songId) }
            val ids = SimilarMatch.match(
                songId, title, artist,
                neighbours.map { SimilarMatch.Neighbour(it.name, it.artist?.name) },
                index(now),
            )
            // With nothing matched, an older list is kept rather than replaced by nothing: the
            // question failing to find anything today says little about yesterday's answer.
            if (ids.isNotEmpty()) database.transaction {
                // Caught because an exception on Room's executor ends the process. The inserts
                // themselves cannot fail on a missing song: INSERT_LASTFM_EDGE checks first.
                runCatching {
                    deleteLastFmSimilar(songId)
                    for (id in ids) insertLastFmEdge(songId, id, now)
                }.onFailure { Log.w(TAG, "Storing similar tracks failed", it) }
            }
            settled.add(songId)
            return Outcome.SETTLED
        } finally {
            inFlight.remove(songId)
        }
    }

    /** Null when a request failed; empty when Last.fm answered and knows nothing like it. */
    private suspend fun ask(title: String, artist: String?): List<SimilarTrack>? {
        for ((a, t) in SimilarMatch.questions(title, artist)) {
            val answer = answer(request(a, t)) ?: return null
            if (answer.isNotEmpty()) return answer
        }
        return emptyList()
    }

    private fun answer(result: Result<List<SimilarTrack>>): List<SimilarTrack>? =
        result.fold(
            onSuccess = { it },
            onFailure = { t ->
                // The client catches everything, cancellation included; a switch turned off
                // mid-request is not Last.fm failing, and must not put the song on a cooldown.
                if (t is CancellationException) throw t
                // "Track not found" is an answer, not a failure. Anything else (the network, a
                // rate limit, a revoked key) is tried again after the cooldown.
                if (t is LastFmException && t.code == TRACK_NOT_FOUND) emptyList()
                else null.also { Log.w(TAG, "Last.fm similar failed: ${(t as? LastFmException)?.code ?: t.message}") }
            },
        )

    /** One at a time and spaced out, well under the five a second Last.fm allows a key. */
    private suspend fun request(artist: String, title: String): Result<List<SimilarTrack>> = requestLock.withLock {
        val wait = lastRequestAt + REQUEST_SPACING_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            scrobbler.similar(artist, title)
        } finally {
            lastRequestAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * The song table by key, rebuilt at most hourly. It grows with every related list YouTube
     * returns, and a neighbour becomes matchable as soon as its song is in it, but keying the whole
     * table on every play would cost more than the request it serves: about 0.3 s for 38,000
     * songs on a desktop JVM.
     */
    private suspend fun index(now: Long): SimilarMatch.Index = indexLock.withLock {
        index?.takeIf { now - indexBuiltAt < INDEX_TTL_MS }
            ?: SimilarMatch.Index(database.engineSongs().map { SongRow(it.id, it.title, it.artistId, it.artistName) })
                .also { index = it; indexBuiltAt = now }
    }

    companion object {
        private const val TAG = "LastFmSimilar"
        private const val TRACK_NOT_FOUND = 6
        /** Last.fm's lists change slowly; what changes is how many of them the song table can match. */
        private const val STALE_MS = 30L * 24 * 60 * 60 * 1000
        private const val INDEX_TTL_MS = 60L * 60 * 1000
        private const val REQUEST_SPACING_MS = 300L
        /** Seeds come from the last fortnight's plays and from likes; a month covers them with room. */
        private const val CATCH_UP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val CATCH_UP_LIMIT = 300
    }
}
