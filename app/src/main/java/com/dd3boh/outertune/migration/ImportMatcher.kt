/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** What searching for one track came to. */
sealed interface Outcome {
    /** Scored at or above [Scored.CONFIDENT] with a length to go by ([Scored.confident]): imported without anybody looking. */
    data class Matched(val best: Scored) : Outcome

    /**
     * Something came back, but not confidently, or with no length to tell it from another version.
     * The best few, best first, for a person to pick.
     */
    data class Review(val candidates: List<Scored>) : Outcome

    /** Nothing plausible came back, even searching for the title alone. */
    data object NotFound : Outcome
}

/**
 * A search failed through every retry. The run stops and can be resumed. [answered] says that
 * YouTube was reached each time and answered with an error, which is not a connection to check.
 */
class SearchUnavailable(cause: Throwable, val answered: Boolean = false) : Exception(cause)

/**
 * YouTube refused a search, or the app is already backing off because YouTube is refusing this
 * connection. Not a failure to try again: asking again is what keeps a refusal up. The run stops
 * where it is, with what it found, and waits for somebody to say continue.
 */
class SearchRefused(cause: Throwable? = null) : Exception(cause)

/**
 * Searches YouTube for each imported track and decides what it is, using TrackMatch's scoring.
 *
 * [search] is YouTube.search with the songs filter in the app and a fake in tests. It throws when
 * the request fails; an empty list means YouTube answered and had nothing. A request that fails
 * is tried again, twice. One that YouTube refused is not: [search] says so with [SearchRefused],
 * which is passed on as it is. And one that YouTube answered, though not with songs ([answered]:
 * a 400, a 500), is tried four more times over most of a minute: the connection is there, and
 * what is wrong on YouTube's side takes longer to pass than a dropped packet.
 *
 * Paced: one request every [spacingMs], never a burst, because a library is hundreds of searches
 * and anything faster starts to look like scraping. Everything waits through [wait], so a
 * cancelled run stops at the next pause rather than at the end of the file.
 *
 * And before each request it asks [backingOff], which in the app is the back-off the downloads,
 * the sync and the feeds already keep to (Throttle): YouTube is refusing this connection for now,
 * and nothing that nobody is waiting on should be asking. An import is the largest pile of
 * requests the app ever makes, and it used to go on through that.
 */
class ImportMatcher(
    private val search: suspend (String) -> List<SongItem>,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val spacingMs: Long = SPACING_MS,
    private val backingOff: () -> Boolean = { false },
    private val answered: (Throwable) -> Boolean = { false },
) {
    private var searched = false

    /**
     * Goes through [tracks], skipping any [isDone] already has an answer for, which is what makes a
     * stopped run resumable, and hands each answer to [onResult] as soon as it is known.
     */
    suspend fun run(tracks: List<ImportedTrack>, isDone: (Int) -> Boolean, onResult: (Int, Outcome) -> Unit) {
        for (i in tracks.indices) {
            if (isDone(i)) continue
            onResult(i, resolve(tracks[i]))
        }
    }

    suspend fun resolve(track: ImportedTrack): Outcome {
        var ranked = rank(track, paced(queryFor(track)))
        // An artist spelled differently, or in another script, can empty a search that the title
        // alone would have answered. Only asked when the first found nothing plausible, so a
        // normal import costs one request a track.
        if (ranked.isEmpty() && track.artists.isNotEmpty()) ranked = rank(track, paced(track.title))
        if (ranked.isEmpty()) return Outcome.NotFound

        val top = ranked.first()
        return if (top.confident) Outcome.Matched(top) else Outcome.Review(ranked.take(REVIEW_CANDIDATES))
    }

    private fun rank(track: ImportedTrack, found: List<SongItem>): List<Scored> =
        found.take(CANDIDATES_SCORED)
            .map { best(track, it) }
            .filter { it.total >= REVIEW_FLOOR }
            .sortedByDescending { it.total }

    /**
     * The better of the track scored with every artist and with the first alone.
     *
     * Services disagree about who is on a song. Spotify lists all three artists on 'Get Lucky' and
     * YouTube usually does too, which only the joined string matches; but where YouTube carries
     * the lead artist alone and moves the rest into the title, only the first matches. Scoring
     * both and keeping the better is still TrackMatch's scoring, just asked twice.
     */
    private fun best(track: ImportedTrack, candidate: SongItem): Scored {
        val all = score(track.wanted(), candidate)
        if (track.artists.size < 2) return all
        val first = score(track.primary(), candidate)
        return if (first.total > all.total) first else all
    }

    private suspend fun paced(query: String): List<SongItem> {
        var attempt = 0
        while (true) {
            if (searched) wait(spacingMs)
            // After the pause and not before it: the back-off may have begun while this waited.
            if (backingOff()) throw SearchRefused()
            searched = true
            try {
                return search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchRefused) {
                throw e
            } catch (e: Exception) {
                val waits = if (answered(e)) PATIENT_WAITS_MS else RETRY_WAITS_MS
                if (attempt >= waits.size) throw SearchUnavailable(e, answered = answered(e))
                wait(waits[attempt])
                attempt++
            }
        }
    }

    companion object {
        /** About two and a half searches a second. */
        const val SPACING_MS = 400L

        /** The waits before each new try of a search that did not get through: two more tries. */
        val RETRY_WAITS_MS = listOf(2_000L, 4_000L)

        /**
         * And of one that YouTube answered with something other than songs. Seven seconds used
         * to be all a search got: YouTube answered one search of a 493-song import with "invalid
         * argument" three times in those seconds, was itself again within the minute, and the
         * import had stopped by then, saying YouTube could not be reached, with nobody there to
         * tap Continue.
         */
        val PATIENT_WAITS_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L)

        /** The first eight results, as MatchRateProbe scored them when the threshold was measured. */
        const val CANDIDATES_SCORED = 8
        const val REVIEW_CANDIDATES = 4

        /**
         * Below this a candidate is not worth anybody's time on the review screen. One that shares
         * the artist always clears it, since artist alone is 0.4 of the naming score and duration
         * can take a quarter of that away, which is what keeps the right answer in reach when the
         * title is written in another script. So does the right title under a wrong artist, which
         * may be a cover worth seeing. A result that shares neither is noise, and a track with
         * nothing but noise is listed as not found rather than handed over as a question.
         * A hair under 0.3 so that the same-artist floor survives floating point.
         */
        const val REVIEW_FLOOR = 0.299

        /** Title and the first artist, the query the match rate was measured with. */
        fun queryFor(track: ImportedTrack): String =
            listOfNotNull(track.title, track.artists.firstOrNull()?.ifBlank { null }).joinToString(" ")
    }
}
