/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.outertune.constants.RELATED_RETRY_COOLDOWN_MS
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.engine.SimilarMatch
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.SourceMix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * ListenBrainz's similar songs for the songs being played, in the place Last.fm's had (Unreleased.LB_SIMILAR), kept
 * in related_song_map as a source of their own (RelatedSongMap.SOURCE_LISTENBRAINZ). From 1 December 2026 Last.fm
 * takes nothing more from this app (LastFmEnd); ListenBrainz's data is open and free.
 *
 * It does what LastFmSimilar did, where LastFmSimilar was asked (MusicService, Home): while similar songs come from
 * Both or the second source only (similarSourceOf), a song played is asked about when its list is missing or a
 * month old, and the songs a row is built around are caught up on once. Through ListenBrainz's open labs API
 * (ListenBrainzLabs), with no account and no token: only the song's title and artist go, one request a second at
 * most. The song is named first (acr-lookup) by the names Last.fm was asked with, in the same order
 * (SimilarMatch.questions), and the first recording found is the song, as in the trial that measured it; then the
 * recordings people play near it (similar-recordings) become songs on the phone by Last.fm's matcher (SimilarMatch).
 *
 * A song ListenBrainz does not know, or knows with nothing played near it, writes nothing, and the engine reads
 * YouTube's list for it (RelatedSql.ENGINE_EDGES), as for a song Last.fm did not know: about two songs in three of
 * today's (experiments/recs-trial-1118/REPORT.md). Labs promises nothing, so an answer of the wrong shape is a failed
 * request, asked again after the cooldown, never a crash.
 */
@Singleton
class ListenBrainzSimilar @Inject constructor(
    private val database: MusicDatabase,
) {
    /** As LastFmSimilar's: songs asked about in this process and settled, stored or not. */
    private val settled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = FailureMemo(RELATED_RETRY_COOLDOWN_MS)

    private val indexLock = Mutex()
    @Volatile private var index: SimilarMatch.Index? = null
    @Volatile private var indexBuiltAt = 0L

    private val _caughtUpAt = MutableStateFlow(0L)

    /** When the last catch-up finished, for Home to rebuild its row then, as LastFmSimilar.caughtUpAt. */
    val caughtUpAt: StateFlow<Long> = _caughtUpAt

    /** Nothing to wait for, no key and no account: the flag alone. */
    val isAvailable get() = Unreleased.LB_SIMILAR

    private enum class Outcome { SETTLED, WAITING, FAILED }

    /**
     * Stores the song's similar songs unless they are stored and fresh. True once there is nothing left to do for it
     * in this process; false while a request is failing, cooling down or running for another chunk.
     */
    suspend fun ensure(songId: String, title: String, artist: String?): Boolean =
        attempt(songId, title, artist) == Outcome.SETTLED

    /**
     * Asks about the songs a row is most likely to be built around, once, when the second source has just been
     * chosen, as LastFmSimilar.catchUp: three failures in a row and it stops, false, to be tried again another time.
     */
    suspend fun catchUp(): Boolean {
        if (!isAvailable) return false
        var failing = 0
        var gaveUp = false
        for (song in database.listenBrainzCatchUp(System.currentTimeMillis() - CATCH_UP_WINDOW_MS, CATCH_UP_LIMIT)) {
            when (attempt(song.id, song.title, song.artist)) {
                Outcome.FAILED -> if (++failing >= 3) { gaveUp = true; break }
                Outcome.SETTLED -> failing = 0
                Outcome.WAITING -> {}
            }
        }
        // Only a catch-up that finished tells Home it has new neighbours to build from, as Last.fm's.
        if (!gaveUp) _caughtUpAt.value = System.currentTimeMillis()
        return !gaveUp
    }

    private suspend fun attempt(songId: String, title: String, artist: String?): Outcome {
        if (!isAvailable || songId in settled) return Outcome.SETTLED
        val now = System.currentTimeMillis()
        val fetchedAt = database.listenBrainzSimilarFetchedAt(songId)
        if ((fetchedAt != null && now - fetchedAt < STALE_MS) || SimilarMatch.firstArtist(artist).isEmpty() || title.isBlank()) {
            settled.add(songId)
            return Outcome.SETTLED
        }
        if (!failures.none(songId) || !inFlight.add(songId)) return Outcome.WAITING
        try {
            val neighbours = ask(title, artist)
                ?: return Outcome.FAILED.also { failures.note(songId) }
            val ids = SimilarMatch.match(songId, title, artist, neighbours, index(now))
            // What a walk on a phone reads (logcat -s ListenBrainzSimilar): how much ListenBrainz knew, and how much
            // of it the phone can play.
            Log.d(TAG, "$songId: ${neighbours.size} similar from ListenBrainz, ${ids.size} on the phone")
            // With nothing matched, an older list is kept rather than replaced by nothing, as Last.fm's is.
            if (ids.isNotEmpty()) database.transaction {
                // Caught because an exception on Room's executor ends the process; the inserts check both songs first.
                runCatching {
                    deleteListenBrainzSimilar(songId)
                    for (id in ids) insertListenBrainzEdge(songId, id, now)
                }.onFailure { Log.w(TAG, "Storing similar songs failed", it) }
            }
            settled.add(songId)
            return Outcome.SETTLED
        } finally {
            inFlight.remove(songId)
        }
    }

    /** Null when a request failed; empty when ListenBrainz answered and has nothing for the song. */
    private suspend fun ask(title: String, artist: String?): List<SimilarMatch.Neighbour>? = try {
        LbSimilar.ask(title, artist, { a, t -> ListenBrainzLabs.recordingOf(a, t) }, { ListenBrainzLabs.similarTo(it) })
    } catch (e: CancellationException) {
        // A switch turned off mid-request is not ListenBrainz failing, and puts the song on no cooldown.
        throw e
    } catch (e: Exception) {
        null.also { Log.w(TAG, "ListenBrainz similar failed: ${e.message?.let(ErrorText::withoutAddresses)}") }
    }

    /** The song table by key, rebuilt at most hourly, for the reason LastFmSimilar's is. */
    private suspend fun index(now: Long): SimilarMatch.Index = indexLock.withLock {
        index?.takeIf { now - indexBuiltAt < INDEX_TTL_MS }
            ?: SimilarMatch.Index(database.engineSongs().map { SongRow(it.id, it.title, it.artistId, it.artistName) })
                .also { index = it; indexBuiltAt = now }
    }

    companion object {
        private const val TAG = "ListenBrainzSimilar"
        /** ListenBrainz's lists change slowly too; what changes is how many of them the song table can match. */
        private const val STALE_MS = 30L * 24 * 60 * 60 * 1000
        private const val INDEX_TTL_MS = 60L * 60 * 1000
        private const val CATCH_UP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val CATCH_UP_LIMIT = 300
    }
}

/** The parts of [ListenBrainzSimilar] that need no network and no database, apart so they can be tested. */
internal object LbSimilar {
    /**
     * ListenBrainz's neighbours of a song, most similar first. The song is looked for by each of the names Last.fm
     * was asked with, in turn ([SimilarMatch.questions]), and the first recording found is the song: its neighbours
     * are the answer, none included. Empty when no name finds a recording: ListenBrainz does not know the song. A
     * request that failed throws, which is not an answer about the song.
     */
    suspend fun ask(
        title: String,
        artist: String?,
        recordingOf: suspend (artist: String, title: String) -> String?,
        similarTo: suspend (mbid: String) -> List<SimilarMatch.Neighbour>,
    ): List<SimilarMatch.Neighbour> {
        for ((a, t) in SimilarMatch.questions(title, artist)) {
            val mbid = recordingOf(a, t) ?: continue
            return similarTo(mbid)
        }
        return emptyList()
    }

    /**
     * From when How similar songs are split learns, under Both: its window, and with ListenBrainz in Last.fm's place
     * ([listenBrainz]) only from when it took that place on this install ([since], ListenBrainzSimilarSinceKey), so
     * cards Last.fm proposed before are not counted as ListenBrainz's. Unset, ListenBrainz has proposed nothing yet.
     */
    fun evidenceSince(now: Long, since: Long?, listenBrainz: Boolean = Unreleased.LB_SIMILAR): Long =
        if (!listenBrainz) now - SourceMix.WINDOW_MS else maxOf(now - SourceMix.WINDOW_MS, since ?: now)
}
