/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The replay's test-only mix model on a made-up library. The copy has to draw exactly as the
 * engine does when there is no split, or the replay would compare the mix with an engine the app
 * no longer runs; and with a split, the ledger has to give each source what the spec says.
 */
class SourceMixModelTest {
    private val now = 1_789_135_200_000L

    /**
     * Twenty albums of eight songs, each song by its own artist so the artist caps never decide
     * which source gets a place; each song lists the next album on YouTube and the one after on
     * Last.fm, so every seed has both lists.
     */
    private fun world(): Pair<EngineInput, Map<Edge, Int>> {
        val songs = LinkedHashMap<String, SongRow>()
        for (a in 0 until 20) for (i in 0 until 8) songs["a${a}s$i"] = SongRow("a${a}s$i", "Song $a $i", "art_${a}_$i", "artist $a $i", inLibrary = true)
        val bits = LinkedHashMap<Edge, Int>()
        for (a in 0 until 20) for (i in 0 until 8) for (j in 0 until 8) {
            bits[Edge("a${a}s$i", "a${(a + 1) % 20}s$j")] = MixBits.YOUTUBE
            bits[Edge("a${a}s$i", "a${(a + 2) % 20}s$j")] = MixBits.LASTFM
        }
        val listens = ArrayList<ListenRow>()
        var session = 1L
        for (d in 1..30) { session++; for (a in 0 until 8) { val start = now - (d * 24L + a) * 3_600_000L; listens += ListenRow("a${a}s${d % 4}", start, start + 200_000, 200_000, 200_000, EndReason.ENDED, PlayOrigin.SEARCH.code, 0, session, 0) } }
        return EngineInput(now, songs, listens, bits.keys.toList()) to bits
    }

    @Test
    fun `without a split the copy draws exactly as the engine`() {
        val (input, bits) = world()
        for (k in 0 until 20) {
            for ((newOnly, neverPlayed) in listOf(false to false, false to true)) {
                val plain = EngineRow.build(input, newOnly = newOnly, neverPlayed = neverPlayed, random = Random(k.toLong()))
                val copy = MixRow.build(input, bits, newOnly = newOnly, neverPlayed = neverPlayed, random = Random(k.toLong()))
                assertEquals(plain.cards.map { it.songId to it.lane }, copy.row.cards.map { it.songId to it.lane })
                assertEquals(plain.pool.map { it.songId }, copy.row.pool.map { it.songId })
                assertEquals(plain.seeds, copy.row.seeds)
            }
        }
    }

    /** Plenty of both sources in both lanes, each card its own artist, group and no seed, so no cap decides a place. */
    private fun plenty(random: Random): Map<Lane, List<Candidate>> = listOf(Lane.RELATED, Lane.EXPLORE).associateWith { lane ->
        (0 until 40).map { k -> Candidate("${lane}_$k", lane, DoubleArray(Features.names.size), random.nextDouble(), null, "art_${lane}_$k", "g_${lane}_$k") }.sortedByDescending { it.z }
    }

    @Test
    fun `a split gives Last-fm floor of s times n plus U of the edge places`() {
        val quotas = mapOf(Lane.RELATED to 7, Lane.EXPLORE to 2, Lane.AGAIN to 0, Lane.ARTIST to 0, Lane.REDISCOVER to 0)
        for (s in listOf(0.2, 0.5, 0.8)) {
            var fraction = 0.0
            repeat(200) { k ->
                val lanes = plenty(Random(k.toLong()))
                // Even-numbered cards are Last.fm's, every fifth is on both lists.
                fun bits(c: Candidate) = c.songId.substringAfter('_').toInt().let { if (it % 5 == 0) 3 else if (it % 2 == 0) 2 else 1 } or MixBits.CONTESTED
                val assembly = MixAssembly(lanes, quotas, Weights.PRIORS, EngineParams.DEFAULT, Random(k.toLong()), s, ::bits)
                val placed = assembly.run()
                assertEquals(9, placed.size)
                val lastFm = assembly.credited.count { it == MixBits.LASTFM }
                assertTrue("s $s draw $k: $lastFm of 9", abs(lastFm - s * 9) < 1)
                if (s == 0.2) assertTrue(lastFm >= 1)
                if (s == 0.8) assertTrue(9 - lastFm >= 1)
                fraction += lastFm / 9.0
            }
            assertEquals(s, fraction / 200, 0.03)
        }
    }

    @Test
    fun `on a real-shaped graph the split lands near its share`() {
        val (input, bits) = world()
        for (s in listOf(0.2, 0.8)) {
            var lastFm = 0; var places = 0
            repeat(50) { k ->
                val row = MixRow.build(input, bits, random = Random(k.toLong()), lastFmShare = s)
                assertEquals(s, row.lastFmShare)
                places += row.credited.count { it != 0 }; lastFm += row.credited.count { it == MixBits.LASTFM }
            }
            // Caps can leave the due source with nothing open, and then the place goes to the
            // other, so on a small graph the split only lands near its aim.
            assertEquals(s, lastFm.toDouble() / places, 0.1)
        }
    }

    @Test
    fun `with no Last-fm candidate a share changes nothing`() {
        val (input, bits) = world()
        val youTubeOnly = bits.filterValues { it == MixBits.YOUTUBE }
        val yInput = input.copy(edges = youTubeOnly.keys.toList())
        for (k in 0 until 10) {
            val plain = EngineRow.build(yInput, random = Random(k.toLong()))
            val split = MixRow.build(yInput, youTubeOnly, random = Random(k.toLong()), lastFmShare = 0.8)
            assertEquals(null, split.lastFmShare)
            assertEquals(plain.cards.map { it.songId }, split.row.cards.map { it.songId })
        }
    }

    private fun ev(song: String, bits: Int, y: Double, day: Long, lane: Int = 1) =
        MixEvidence(song, 1, lane, bits or MixBits.CONTESTED, day * 86_400_000L + 3_600_000L, if (y > 0) Outcome.PLAYED else Outcome.IGNORED, y.toFloat())

    @Test
    fun `the share starts at half, mirrors, and stays inside a fifth`() {
        assertEquals(0.5, MixShare.share(emptyList(), 0).share, 0.0)
        val lastFm = (0 until 20).map { ev("l$it", MixBits.LASTFM, if (it < 4) 0.9 else 0.0, 1) }
        val youTube = (0 until 20).map { ev("y$it", MixBits.YOUTUBE, if (it < 2) 0.9 else 0.0, 1) }
        assertEquals(0.5, MixShare.share(lastFm, 0).share, 1e-12)
        val a = MixShare.share(lastFm + youTube, 0).share
        val swapped = (lastFm + youTube).map { it.copy(sources = (it.sources and MixBits.CONTESTED) or (3 - (it.sources and MixBits.SOURCES))) }
        assertTrue(a > 0.5)
        assertEquals(1.0, a + MixShare.share(swapped, 0).share, 1e-12)
        val strong = (0 until 1000).map { ev("l$it", MixBits.LASTFM, 1.0, 1) } + (0 until 1000).map { ev("y$it", MixBits.YOUTUBE, 0.0, 1) }
        assertEquals(0.8, MixShare.share(strong, 0).share, 0.0)
        // Five sightings of one song on one day are one card, at its best grade.
        val repeated = (0 until 5).map { ev("x", MixBits.LASTFM, 0.0, 1) } + ev("x", MixBits.LASTFM, 0.8, 1)
        val side = MixShare.share(repeated, 0).lastFm
        assertEquals(1.0, side.cards, 1e-12); assertEquals(0.8, side.heard, 1e-6)
    }
}
