/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * Does the matcher work well enough to justify building an import screen?
 *
 * Section 12 says to answer this before any of it is built, because the whole feature rests on a
 * number nobody has measured: how often searching YouTube for somebody else's metadata lands on
 * the right recording. If that is 60 percent then a review screen is the feature and the matcher
 * is decoration; if it is 95 then the reverse.
 *
 * Ground truth comes from a library the app already holds, where every song's real video id is
 * known. A track is read out of the database, handed to the matcher as though another service had
 * exported it, and the id that comes back is compared with the one it started as.
 *
 * Two passes, and the second is the honest one:
 *
 *  - **verbatim** uses the library's own title and artist. That is YouTube's metadata being
 *    matched against YouTube, so it flatters: exact strings, same punctuation, same featured-artist
 *    form. It is still worth running, as a ceiling and as a check that search and ranking work at
 *    all. A failure here is a fault in the pipeline rather than in the matching.
 *  - **perturbed** rewrites each track the way a real export differs: a remaster tag appended,
 *    accents stripped, the featured artist moved out of the title or into it, "Artist - Title"
 *    order. That is the number the decision should be made on.
 *
 * The counts that matter are not one rate. A silent wrong import is far worse than a review, so
 * they are reported apart: confident-and-right can be imported, unconfident goes to a screen and
 * costs a tap, and confident-and-wrong is the only truly bad outcome.
 *
 * Skipped unless MATCH_PROBE=1 so an ordinary test run and CI never touch the network.
 *
 *     MATCH_DB=/path/to/song.db MATCH_PROBE=1 MATCH_SAMPLE=40 \
 *       ./gradlew :app:testCoreDebugUnitTest --tests "*MatchRateProbe*" -i
 */
class MatchRateProbe {

    private data class Known(val id: String, val title: String, val artist: String, val seconds: Int?)

    private fun library(path: String, sample: Int): List<Known> {
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            // Songs that have been played are the fair corpus: they are real listening rather than
            // whatever a search happened to cache, which is most of the table.
            val sql = """
                SELECT s.id, s.title, s.duration,
                       (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId
                        WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artist
                FROM song s
                WHERE s.title IS NOT NULL AND s.duration IS NOT NULL
                  AND EXISTS (SELECT 1 FROM event e WHERE e.songId = s.id)
                ORDER BY s.id
                LIMIT $sample
            """
            db.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    return buildList {
                        while (rs.next()) {
                            val artist = rs.getString("artist") ?: continue
                            add(Known(rs.getString("id"), rs.getString("title"), artist, rs.getInt("duration")))
                        }
                    }
                }
            }
        }
    }

    /** How another service would have written the same track. */
    private fun perturb(track: Known, i: Int): WantedTrack = when (i % 4) {
        0 -> WantedTrack("${track.title} - Remastered 2011", track.artist, track.seconds)
        1 -> WantedTrack(stripAccentsFor(track.title), stripAccentsFor(track.artist), track.seconds)
        2 -> WantedTrack(track.title.substringBefore(" (").trim(), track.artist, track.seconds)
        // Duration omitted entirely, which some exporters do.
        else -> WantedTrack(track.title, track.artist.substringBefore(",").trim(), null)
    }

    private fun stripAccentsFor(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /**
     * Whether the match is the recording that was wanted, which is not the same as the same id.
     *
     * The first run of this compared video ids and called an eighth of its results wrong. Reading
     * them, almost none were: 'Bad Romance' 295s had been matched to 'Bad Romance' 295s under a
     * different id, because YouTube carries the same recording several times over, as an official
     * video, a topic channel upload and an auto-generated one. Counting those as failures measures
     * which upload a search happens to rank first, which is not what an import cares about.
     *
     * So the id is the best answer and same-name-same-length is an equally good one. What stays
     * wrong is a different length, which is what a remix, a live take or an edit actually is, and
     * that is the failure this feature has to avoid.
     */
    private fun sameRecording(wanted: Known, got: SongItem): Boolean {
        if (got.id == wanted.id) return true
        val length = got.duration ?: return false
        val seconds = wanted.seconds ?: return false
        return normalise(got.title) == normalise(wanted.title) && kotlin.math.abs(length - seconds) <= 3
    }

    private class Tally(val label: String) {
        var confidentRight = 0
        var confidentAlternate = 0
        var confidentWrong = 0
        var reviewRight = 0
        var reviewWrong = 0
        var nothing = 0
        val total
            get() = confidentRight + confidentAlternate + confidentWrong + reviewRight + reviewWrong + nothing

        fun report() {
            if (total == 0) return println("MATCH $label: nothing ran")
            fun pct(n: Int) = "%.0f%%".format(100.0 * n / total)
            println("MATCH $label ($total tracks)")
            println("MATCH   imported, exact id      : $confidentRight (${pct(confidentRight)})")
            println("MATCH   imported, other upload  : $confidentAlternate (${pct(confidentAlternate)})  same name and length")
            println("MATCH   imported and WRONG      : $confidentWrong (${pct(confidentWrong)})   <- the only bad one")
            println("MATCH   to review, top right    : $reviewRight (${pct(reviewRight)})")
            println("MATCH   to review, top wrong    : $reviewWrong (${pct(reviewWrong)})")
            println("MATCH   found nothing           : $nothing (${pct(nothing)})")
            println("MATCH   usable without review   : ${pct(confidentRight + confidentAlternate)}")
            println("MATCH   silently wrong          : ${pct(confidentWrong)}")
        }
    }


    /**
     * What the confidence threshold should be, read off the data rather than chosen.
     *
     * The number in Scored.CONFIDENT was picked by feel before any of this had been measured, and
     * it is the single knob that decides how much of an import happens silently. Changing how
     * candidates are scored moves the whole distribution under it, so the threshold has to be
     * re-read every time the scoring changes, which is what this prints.
     *
     * The column to optimise is not the highest usable figure. A silently wrong import is the
     * expensive outcome and a review is one tap, so the right threshold is the lowest one that
     * still keeps the wrong column at nothing.
     */
    private fun sweep(label: String, scores: List<Pair<Double, Boolean>>) {
        if (scores.isEmpty()) return
        println("MATCH $label threshold sweep:")
        println("MATCH     thr   auto-imported   of those wrong")
        var t = 0.60
        while (t <= 0.96) {
            val above = scores.filter { it.first >= t }
            val wrong = above.count { !it.second }
            val pct = 100.0 * above.size / scores.size
            val flag = if (wrong == 0) "" else "  <-"
            println("MATCH    %.2f   %5.0f%%          %d%s".format(t, pct, wrong, flag))
            t += 0.04
        }
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("MATCH_PROBE") == "1")
        val path = System.getenv("MATCH_DB") ?: error("MATCH_DB must name a song.db")
        val sample = System.getenv("MATCH_SAMPLE")?.toIntOrNull() ?: 40

        val corpus = library(path, sample)
        println("MATCH corpus: ${corpus.size} played songs from $path")

        for (mode in listOf("verbatim", "perturbed")) {
            val tally = Tally(mode)
            val scores = mutableListOf<Pair<Double, Boolean>>()
            corpus.forEachIndexed { i, track ->
                val wanted =
                    if (mode == "verbatim") WantedTrack(track.title, track.artist, track.seconds)
                    else perturb(track, i)

                val found = YouTube.search(
                    listOfNotNull(wanted.title, wanted.artist.ifBlank { null }).joinToString(" "),
                    YouTube.SearchFilter.FILTER_SONG,
                )
                val candidates = found.getOrNull()?.items?.filterIsInstance<SongItem>()?.take(8).orEmpty()
                val best = match(wanted, candidates)

                if (best != null) scores += best.total to sameRecording(track, best.candidate)
                when {
                    best == null -> {
                        tally.nothing++
                        println("MATCH   [none] '${wanted.title}' by '${wanted.artist}'")
                    }
                    best.candidate.id == track.id && best.confident -> tally.confidentRight++
                    best.confident && sameRecording(track, best.candidate) -> tally.confidentAlternate++
                    sameRecording(track, best.candidate) -> tally.reviewRight++
                    best.confident -> {
                        tally.confidentWrong++
                        println(
                            "MATCH   [WRONG, imported] wanted '${track.title}' (${track.seconds}s, ${track.id}) " +
                                    "got '${best.candidate.title}' (${best.candidate.duration}s, ${best.candidate.id}) " +
                                    "score %.2f".format(best.total)
                        )
                    }
                    else -> tally.reviewWrong++
                }
                // Politeness, and it keeps a long run from looking like scraping.
                delay(350)
            }
            println("")
            tally.report()
            sweep(mode, scores)
            println("")
        }
    }
}
