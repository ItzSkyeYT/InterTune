/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.SimilarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The Last.fm share in Both, worked out from graded impressions: the properties the spec promises. */
class SourceMixTest {
    private val day = 86_400_000L
    private val l = Provenance.LASTFM or Provenance.CONTESTED
    private val y = Provenance.YOUTUBE or Provenance.CONTESTED

    private fun ev(song: String, sources: Int, grade: Float, d: Int = 0, lane: Int = 1, team: Int = 1, at: Long = 0, outcome: Int = if (grade > 0) Outcome.PLAYED else Outcome.IGNORED) =
        SourceEvidence(song, team, lane, sources, d * day + at, outcome, grade)

    /** [n] cards from one source on day [d], the first [played] of them heard at [grade]. */
    private fun side(sources: Int, n: Int, played: Int = 0, grade: Float = 1f, d: Int = 0, lane: Int = 1, tag: String = "") =
        (0 until n).map { i -> ev("$tag$sources-$d-$lane-$i", sources, if (i < played) grade else 0f, d, lane, at = i * 1000L) }

    private fun share(rows: List<SourceEvidence>) = SourceMix.share(rows, 0)

    private fun swapped(rows: List<SourceEvidence>) = rows.map { r ->
        r.copy(sources = (r.sources and Provenance.CONTESTED) or when (r.sources and Provenance.SOURCES) { Provenance.LASTFM -> Provenance.YOUTUBE; Provenance.YOUTUBE -> Provenance.LASTFM; else -> r.sources and Provenance.SOURCES })
    }

    @Test
    fun `no evidence gives exactly half`() {
        val s = share(emptyList())
        assertEquals(0.5, s.share, 0.0)
        assertEquals(0, s.days)
        assertTrue(!s.compared)
    }

    @Test
    fun `identical evidence on both sides gives half`() {
        assertEquals(0.5, share(side(l, 20, played = 3, grade = 0.8f) + side(y, 20, played = 3, grade = 0.8f)).share, 1e-12)
    }

    @Test
    fun `swapping the sources mirrors the share`() {
        val rows = side(l, 30, played = 5, grade = 0.9f) + side(y, 25, played = 1, grade = 0.4f) + side(l, 10, played = 1, d = 3, lane = 4) + side(y, 12, d = 5, lane = 4)
        val a = share(rows).share
        val b = share(swapped(rows)).share
        assertTrue("the case must not be symmetric already", abs(a - 0.5) > 0.01)
        assertEquals(1.0, a + b, 1e-12)
    }

    @Test
    fun `evidence on one side only gives half`() {
        assertEquals(0.5, share(side(l, 40, played = 10)).share, 1e-12)
        assertEquals(0.5, share(side(y, 40, played = 0)).share, 1e-12)
    }

    @Test
    fun `the share rises with how much of Last-fm's cards were heard`() {
        val others = side(y, 30, played = 3, grade = 0.6f)
        var last = 0.0
        for (g in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            val s = share(side(l, 30, played = 3, grade = g) + others).share
            assertTrue("share $s at grade $g after $last", s > last)
            last = s
        }
    }

    @Test
    fun `the share is clamped to one in five either way`() {
        val lastFmWins = side(l, 1000, played = 1000) + side(y, 1000)
        assertEquals(0.8, share(lastFmWins).share, 1e-12)
        assertEquals(0.2, share(swapped(lastFmWins)).share, 1e-12)
    }

    @Test
    fun `one play against none on the first day moves the share by less than a tenth`() {
        val s = share(side(l, 20, played = 1) + side(y, 20)).share
        assertTrue("share $s", s > 0.5 && s - 0.5 < 0.1)
    }

    @Test
    fun `each lane is its own baseline, so landing in the easier lane earns nothing`() {
        // Last.fm's cards sit mostly in explore, where plays are rarer, and YouTube's mostly in
        // related. Lane for lane they do exactly as well.
        val lastFm = side(l, 80, played = 80, grade = 0.02f, lane = 4) + side(l, 20, played = 20, grade = 0.10f, lane = 1)
        val youTube = side(y, 20, played = 20, grade = 0.02f, lane = 4) + side(y, 80, played = 80, grade = 0.10f, lane = 1)
        val s = share(lastFm + youTube)
        assertEquals(0.5, s.share, 1e-9)
        // Pooled over both lanes, YouTube would have looked more than twice as good.
        assertTrue(s.lastFm.per100 < s.youTube.per100 / 2)
    }

    @Test
    fun `days without listening age nothing, and fourteen newer days halve a day's weight`() {
        val days = listOf(0, 1, 2, 3, 4)
        val gapped = listOf(0, 3, 10, 11, 30)
        fun rows(on: List<Int>) = on.withIndex().flatMap { (k, d) -> side(l, 10, played = k % 3, d = d, tag = "k$k-") + side(y, 10, played = (k + 1) % 2, d = d, tag = "k$k-") }
        val a = share(rows(days)); val b = share(rows(gapped))
        assertEquals(a.share, b.share, 1e-12)
        assertEquals(a.lastFm.cards, b.lastFm.cards, 1e-12)
        // One Last.fm card, then fourteen days of YouTube evidence after it, some of them far apart.
        val old = listOf(ev("old", l, 0f, d = 0))
        val newer = (1..14).map { k -> ev("new$k", y, 0f, d = k * 3) }
        val st = share(old + newer)
        assertEquals(0.5, st.lastFm.cards, 1e-12)
        assertEquals(15, st.days)
    }

    @Test
    fun `a card seen in several builds on one day counts once, at its best grade`() {
        val rows = (0 until 5).map { ev("song", l, 0f, at = it * 60_000L) } + ev("song", l, 0.8f, at = 400_000L)
        val s = share(rows)
        assertEquals(1.0, s.lastFm.cards, 1e-12)
        assertEquals(0.8, s.lastFm.heard, 1e-6)
        // The day is the listener's own: two sightings either side of UTC midnight, one local day.
        val late = ev("song2", l, 0f, d = 0, at = day - 30 * 60_000L)
        val early = ev("song2", l, 0f, d = 1, at = 30 * 60_000L)
        assertEquals(2, SourceMix.share(listOf(late, early), 0).days)
        val local = SourceMix.share(listOf(late, early), 60)
        assertEquals(1, local.days)
        assertEquals(1.0, local.lastFm.cards, 1e-12)
    }

    @Test
    fun `a pending card counts as seen and not heard`() {
        val s = share(listOf(ev("song", l, 0.9f, outcome = Outcome.PENDING)))
        assertEquals(1.0, s.lastFm.cards, 1e-12)
        assertEquals(0.0, s.lastFm.heard, 0.0)
    }

    @Test
    fun `cards the spec does not count are ignored`() {
        val ignored = listOf(
            ev("t2", l, 1f, team = 2), ev("t3", l, 1f, team = 3),
            ev("l2", l, 1f, lane = 2), ev("l3", l, 1f, lane = 3), ev("l5", l, 1f, lane = 5),
            ev("b1", 1, 1f), ev("b2", 2, 1f), ev("b3", 3, 1f), ev("b7", 7, 1f), ev("b0", 0, 1f),
            ev("o4", l, 1f, outcome = 4), ev("o6", l, 1f, outcome = 6), ev("o7", l, 1f, outcome = 7),
        )
        for (r in ignored) {
            val s = share(listOf(r))
            assertEquals(r.songId, 0.0, s.lastFm.cards + s.youTube.cards, 0.0)
        }
        // And the two teams and lanes that do count, do.
        assertEquals(2.0, share(listOf(ev("a", l, 1f, team = 1, lane = 1), ev("b", l, 1f, team = 4, lane = 4))).lastFm.cards, 1e-12)
    }

    @Test
    fun `without a key or with the engine held back the app reads YouTube only`() {
        for (stored in SimilarSource.entries) {
            assertEquals(SimilarSource.YOUTUBE, SimilarSources.effective(stored, hasKey = false, engineOn = true))
            assertEquals(SimilarSource.YOUTUBE, SimilarSources.effective(stored, hasKey = true, engineOn = false))
            assertEquals(stored, SimilarSources.effective(stored, hasKey = true, engineOn = true))
        }
    }

    @Test
    fun `the old Last-fm switch keeps its meaning until the new choice is made`() {
        // Never asked: nothing goes to Last.fm until the first-run question is answered.
        assertEquals(SimilarSource.YOUTUBE, SimilarSources.stored(null, null))
        assertEquals(SimilarSource.LASTFM, SimilarSources.stored(null, true))
        assertEquals(SimilarSource.YOUTUBE, SimilarSources.stored(null, false))
        assertEquals(SimilarSource.BOTH, SimilarSources.stored("BOTH", true))
        assertEquals(SimilarSource.YOUTUBE, SimilarSources.stored("YOUTUBE", null))
        assertEquals(SimilarSource.LASTFM, SimilarSources.stored("nonsense", true))
    }

    @Test
    fun `the Last-fm question counts as asked once anything was chosen`() {
        assertEquals(false, SimilarSources.asked(null, null))
        assertEquals(true, SimilarSources.asked(null, true))
        assertEquals(true, SimilarSources.asked(null, false))
        assertEquals(true, SimilarSources.asked("BOTH", null))
        assertEquals(true, SimilarSources.asked("YOUTUBE", null))
    }
}
