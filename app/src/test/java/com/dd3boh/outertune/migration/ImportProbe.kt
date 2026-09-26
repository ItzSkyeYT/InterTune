/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The import end to end against the real search: twenty well known songs written the way
 * exportify.app writes them, through the parser, the paced matcher and the real anonymous
 * InnerTube search, with the outcome of each printed.
 *
 * MatchRateProbe answers how good the scoring is against a library with known video ids. This
 * answers the plainer question of what somebody importing a mainstream playlist would see: how
 * much goes straight in, how much waits for them, and whether what went straight in looks right.
 * "Looks right" is the same name after normalising and a length within five seconds, since there
 * is no video id to compare with.
 *
 * Read only and anonymous: no account, no cookies, searches only. Skipped unless IMPORT_PROBE=1.
 *
 *     IMPORT_PROBE=1 ./gradlew :app:testCoreDebugUnitTest --tests "*ImportProbe*" -i
 */
class ImportProbe {

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("IMPORT_PROBE") == "1")
        val text = requireNotNull(javaClass.getResourceAsStream("/import/probe_exportify.csv")).readBytes()
        val parsed = ImportFile.parse(ImportFile.decode(text), "Probe") as ImportParse.Parsed
        val run = ImportRun(parsed)
        val matcher = ImportMatcher(search = { query ->
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
        })

        // A dead connection is not a finding about matching, so it ends the probe without failing
        // whatever suite it was run inside.
        try {
            probeRun(matcher, run)
        } catch (e: SearchUnavailable) {
            println("IMPORT stopped: the search could not be reached (${e.cause})")
            return@runBlocking
        }

        val snapshot = run.snapshot()
        val autoRight = outcomes.count { (i, outcome) ->
            outcome is Outcome.Matched && looksRight(run.tracks[i], outcome.best.candidate)
        }
        val reviewRight = snapshot.review.count { item -> looksRight(item.track, item.candidates.first().candidate) }
        val total = snapshot.total
        fun pct(n: Int) = "%.0f%%".format(100.0 * n / total)
        println("")
        println("IMPORT $total tracks")
        println("IMPORT   imported without review : ${snapshot.matched} (${pct(snapshot.matched)}), of which look right: $autoRight")
        println("IMPORT   to review               : ${snapshot.review.size} (${pct(snapshot.review.size)}), top candidate looks right: $reviewRight")
        println("IMPORT   not found               : ${snapshot.notFound.size} (${pct(snapshot.notFound.size)})")
        println("IMPORT   right answer reachable  : ${pct(autoRight + reviewRight)}")
    }

    private val outcomes = HashMap<Int, Outcome>()

    private suspend fun probeRun(matcher: ImportMatcher, run: ImportRun) {
        matcher.run(run.tracks, run::isDone) { index, outcome ->
            run.record(index, outcome)
            outcomes[index] = outcome
            val track = run.tracks[index]
            val line = when (outcome) {
                is Outcome.Matched -> "auto   %.2f %s".format(outcome.best.total, describe(track, outcome.best.candidate))
                is Outcome.Review -> "review %.2f %s".format(
                    outcome.candidates.first().total, describe(track, outcome.candidates.first().candidate)
                )
                Outcome.NotFound -> "none        '${track.title}'"
            }
            println("IMPORT $line")
        }
    }

    private fun describe(track: ImportedTrack, got: SongItem) =
        "'${track.title}' (${track.durationSeconds}s) -> '${got.title}' by ${got.artists.joinToString { it.name }} (${got.duration}s)" +
                if (looksRight(track, got)) "" else "  <- differs"

    private fun looksRight(track: ImportedTrack, got: SongItem): Boolean {
        val length = got.duration ?: return false
        val seconds = track.durationSeconds ?: return false
        return normalise(got.title) == normalise(track.title) && abs(length - seconds) <= 5
    }
}
