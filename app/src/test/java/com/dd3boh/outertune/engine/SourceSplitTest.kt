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
import kotlin.math.floor
import kotlin.random.Random

/**
 * The source split in the real Assembly and EngineRow. The ledger has to give Last.fm its share of
 * the similar-song places without shortening the row or breaking a cap, a null share has to draw
 * exactly as the engine did before it knew about sources, and only a seed with lists from both
 * sources may mark a card contested, since those cards are the only evidence the share learns from.
 */
class SourceSplitTest {
    private val now = 1_789_135_200_000L
    private val yt = Provenance.YOUTUBE or Provenance.CONTESTED
    private val lf = Provenance.LASTFM or Provenance.CONTESTED
    private val both = Provenance.SOURCES or Provenance.CONTESTED
    private val quotas = mapOf(Lane.RELATED to 7, Lane.EXPLORE to 2, Lane.AGAIN to 0, Lane.ARTIST to 0, Lane.REDISCOVER to 0)

    /** Sixty candidates a lane, each its own artist and version group so no cap decides a place, with falling z so the order is fixed. */
    private fun lanes(seed: (Lane, Int) -> String? = { _, _ -> null }, bits: (Lane, Int) -> Int) = listOf(Lane.RELATED, Lane.EXPLORE).associateWith { lane ->
        (0 until 60).map { k -> Candidate("${lane}_$k", lane, DoubleArray(Features.COUNT), -0.1 * k, seed(lane, k), "art_${lane}_$k", "g_${lane}_$k", bits(lane, k)) }
    }

    private fun assembly(lanes: Map<Lane, List<Candidate>>, k: Int, share: Double?) =
        Assembly(lanes, quotas, Weights.PRIORS, EngineParams.DEFAULT, Random(k.toLong()), share)

    /** The source the ledger owes each of the first [n] places when the due one always has something open; [u] is the row's offset, its first draw. */
    private fun ledger(s: Double, u: Double, n: Int) =
        (0 until n).map { k -> if (floor(s * (k + 1) + u) > floor(s * k + u)) Provenance.LASTFM else Provenance.YOUTUBE }

    @Test
    fun `with plenty of both sources Last-fm gets its share of every row`() {
        // Ranks alternate between the sources, so the ledger and not the scores decides who gets each place.
        val lanes = lanes { _, k -> if (k % 2 == 0) yt else lf }
        for (s in listOf(0.2, 0.5, 0.8)) {
            var fraction = 0.0
            repeat(200) { k ->
                val a = assembly(lanes, k, s)
                val placed = a.run()
                val n = placed.count { it.first.sources and Provenance.SOURCES != 0 }
                val l = a.credited.count { it == Provenance.LASTFM }
                assertEquals(9, n)
                // A card only one list proposed is always credited to that list.
                assertEquals(placed.map { it.first.sources and Provenance.SOURCES }, a.credited)
                assertTrue("s $s draw $k: $l of $n", abs(l - s * n) < 1)
                if (s == 0.2) assertTrue("no Last.fm card at 0.2", l >= 1)
                if (s == 0.8) assertTrue("no YouTube card at 0.8", n - l >= 1)
                fraction += l.toDouble() / n
            }
            assertEquals(s, fraction / 200, 0.03)
        }
    }

    @Test
    fun `a null share draws exactly as the assembly did before it had sources`() {
        // Every kind of bits is present, so a null share that read them anywhere would move a card.
        val mixed = lanes { _, k -> listOf(0, yt, lf, both)[k % 4] }
        val plain = lanes { _, _ -> 0 }
        repeat(50) { k ->
            val dice = List(3) { Random(k.toLong()) }
            val before = Assembly(plain, quotas, Weights.PRIORS, EngineParams.DEFAULT, dice[0]).run()
            val old = Assembly(mixed, quotas, Weights.PRIORS, EngineParams.DEFAULT, dice[1]).run()
            val none = Assembly(mixed, quotas, Weights.PRIORS, EngineParams.DEFAULT, dice[2], null)
            val row = none.run().map { it.first.songId to it.second }
            assertEquals(before.map { it.first.songId to it.second }, row)
            assertEquals(old.map { it.first.songId to it.second }, row)
            assertTrue(none.credited.all { it == 0 })
            // No offset was drawn, so the dice are exactly where the old assembly left them.
            assertEquals(dice[0].nextLong(), dice[2].nextLong())
        }
    }

    @Test
    fun `a due source with nothing open gives its place away and the row keeps its length`() {
        // There is one Last.fm song in the whole row, so at 0.8 Last.fm is owed places it cannot fill.
        val one = lanes { lane, k -> if (lane == Lane.RELATED && k == 5) lf else yt }
        val youTube = lanes { _, _ -> yt }
        repeat(50) { k ->
            val a = assembly(one, k, 0.8)
            val placed = a.run()
            assertEquals(assembly(youTube, k, null).run().size, placed.size)
            assertEquals(9, placed.size)
            // Its one song still gets in, and every place it could not fill went to YouTube.
            assertTrue(placed.any { it.first.songId == "RELATED_5" })
            assertEquals(1, a.credited.count { it == Provenance.LASTFM })
        }
    }

    @Test
    fun `a song both lists proposed fills whichever source was due`() {
        // Every third rank is on both lists in the first world, and every song is in the second.
        val mixed = lanes { _, k -> listOf(both, yt, lf)[k % 3] }
        val allBoth = lanes { _, _ -> both }
        val bothCreditedTo = HashSet<Int>()
        for (s in listOf(0.2, 0.5, 0.8)) repeat(100) { k ->
            for (world in listOf(mixed, allBoth)) {
                val a = assembly(world, k, s)
                val placed = a.run()
                // Each source always has something open here, so every place goes where the ledger owes it.
                assertEquals(ledger(s, Random(k.toLong()).nextDouble(), 9), a.credited)
                placed.zip(a.credited).filter { it.first.first.sources == both }.forEach { bothCreditedTo += it.second }
                val l = a.credited.count { it == Provenance.LASTFM }
                assertTrue("s $s draw $k: $l of 9", abs(l - s * 9) < 1)
            }
        }
        // Songs on both lists were credited to each source in turn and never left out of the ledger.
        assertEquals(setOf(Provenance.YOUTUBE, Provenance.LASTFM), bothCreditedTo)
    }

    @Test
    fun `a song no list proposed is never pushed out by the split`() {
        // The top three related songs and the top explore song came from no list, as a library song does.
        val lanes = lanes { lane, k -> if (k < (if (lane == Lane.RELATED) 3 else 1)) 0 else if (k % 2 == 0) yt else lf }
        val neutral = listOf("RELATED_0", "RELATED_1", "RELATED_2", "EXPLORE_0")
        for (s in listOf(0.2, 0.5, 0.8)) repeat(50) { k ->
            val a = assembly(lanes, k, s)
            val placed = a.run()
            assertEquals(9, placed.size)
            assertTrue("s $s draw $k", placed.map { it.first.songId }.containsAll(neutral))
            placed.zip(a.credited).filter { it.first.first.sources == 0 }.forEach { assertEquals(0, it.second) }
            // They take places without moving the ledger, which still owes Last.fm its share of the rest.
            assertEquals(ledger(s, Random(k.toLong()).nextDouble(), 5), a.credited.filter { it != 0 })
        }
    }

    @Test
    fun `the split never takes a seed past its related cap`() {
        // Every Last.fm related song came from one seed, so at 0.8 Last.fm is owed more related places than that seed may fill.
        val lanes = lanes({ lane, k -> if (lane != Lane.RELATED) null else if (k % 2 == 1) "lastfm_seed" else "yt_seed_${k % 3}" }) { _, k -> if (k % 2 == 0) yt else lf }
        repeat(200) { k ->
            val placed = assembly(lanes, k, 0.8).run()
            assertEquals(9, placed.size)
            val perSeed = placed.filter { it.first.lane == Lane.RELATED }.groupingBy { it.first.seedId }.eachCount()
            perSeed.forEach { (seed, n) -> assertTrue("$seed has $n related cards", n <= EngineParams.DEFAULT.maxPerSeed) }
            // The seed was filled to its cap, so the ledger really did push against it.
            assertEquals(EngineParams.DEFAULT.maxPerSeed, perSeed["lastfm_seed"])
        }
    }

    private fun listen(id: String, hoursAgo: Double, session: Long): ListenRow {
        val start = now - (hoursAgo * 3_600_000).toLong()
        return ListenRow(id, start, start + 200_000, 200_000, 200_000, EndReason.ENDED, PlayOrigin.SEARCH.code, 0, session, 0)
    }

    /**
     * Twelve albums of eight songs, one artist each: albums 0 to 7 played over the last month and
     * 8 to 11 two months ago, so every lane has something. Each song lists the next album on
     * YouTube and the one after on Last.fm, so the familiar lanes' songs are on seeds' lists too,
     * and four songs of its own on each list that nobody has played, each by its own artist, so no
     * cap decides which source gets an explore place. [src] gives an edge's bits from its seed's
     * album and the list the default world puts it on.
     */
    private fun world(src: (album: Int, lastFm: Boolean) -> Int = { _, lastFm -> if (lastFm) Provenance.LASTFM else Provenance.YOUTUBE }): EngineInput {
        val songs = LinkedHashMap<String, SongRow>()
        val edges = ArrayList<Edge>()
        for (a in 0 until 12) for (i in 0 until 8) {
            val seed = "a${a}s$i"
            songs[seed] = SongRow(seed, "Song $a $i", "art$a", "artist $a")
            for (n in 1..2) for (j in 0 until 8) edges += Edge(seed, "a${(a + n) % 12}s$j", src(a, n == 2))
            for (j in 0 until 4) for (lastFm in listOf(false, true)) {
                val id = (if (lastFm) "l" else "y") + "${a}_${i}_$j"
                songs[id] = SongRow(id, id, "art_$id", id)
                edges += Edge(seed, id, src(a, lastFm))
            }
        }
        val listens = ArrayList<ListenRow>()
        for (d in 1..30) for (a in 0 until 8) listens += listen("a${a}s${d % 4}", d * 24.0 + a, d.toLong())
        for (d in 50..70) for (a in 8 until 12) listens += listen("a${a}s${d % 8}", d * 24.0 + a, d.toLong())
        // Yesterday evening, for the again lane, and a session going on now.
        listens += listOf(listen("a1s1", 20.0, 998), listen("a2s2", 19.5, 998), listen("a3s3", 19.0, 998), listen("a0s1", 0.5, 999), listen("a0s2", 0.2, 999))
        return EngineInput(now, songs, listens, edges)
    }

    @Test
    fun `a graph with nothing from Last-fm draws the same row whatever the share`() {
        val input = world { _, _ -> Provenance.YOUTUBE }
        for (neverPlayed in listOf(false, true)) repeat(10) { k ->
            val plain = EngineRow.build(input, neverPlayed = neverPlayed, random = Random(k.toLong()))
            val split = EngineRow.build(input, neverPlayed = neverPlayed, random = Random(k.toLong()), lastFmShare = 0.8)
            assertEquals(plain.cards.map { it.songId to it.lane }, split.cards.map { it.songId to it.lane })
            assertEquals(plain.pool.map { it.songId }, split.pool.map { it.songId })
            assertEquals(null, split.lastFmShare)
        }
    }

    @Test
    fun `a pair both sources list is one referrer and carries both bits`() {
        // One song has been played, so it is the only seed, and it lists one song.
        fun card(sources: Int): Card {
            val songs = listOf(SongRow("seed", "Seed", "art_seed", "seed artist"), SongRow("c", "Candidate", "art_c", "candidate artist")).associateBy { it.id }
            val listens = (1..3).map { d -> listen("seed", d * 24.0, d.toLong()) }
            val row = EngineRow.build(EngineInput(now, songs, listens, listOf(Edge("seed", "c", sources))), random = Random(1), lastFmShare = 0.5)
            assertEquals(listOf("seed"), row.seeds)
            return row.cards.single { it.songId == "c" }
        }
        val both = card(Provenance.SOURCES)
        val one = card(Provenance.YOUTUBE)
        assertTrue(one.features[Features.SEED] > 0)
        assertEquals(one.features[Features.SEED], both.features[Features.SEED], 0.0)
        // The seed has lists from both sources, so its card is contested as well; with one list it is not.
        assertEquals(Provenance.SOURCES or Provenance.CONTESTED, both.sources)
        assertEquals(Provenance.YOUTUBE, one.sources)
    }

    @Test
    fun `again, artist and rediscover cards carry no sources even when a seed lists them`() {
        val input = world()
        val familiar = setOf(Lane.AGAIN, Lane.ARTIST, Lane.REDISCOVER)
        val seen = HashSet<Lane>()
        var listed = 0
        repeat(10) { k ->
            val row = EngineRow.build(input, random = Random(k.toLong()), lastFmShare = 0.5)
            assertEquals(0.5, row.lastFmShare)
            val onSeedLists = input.edges.filter { it.seedId in row.seeds }.mapTo(HashSet()) { it.songId }
            for (c in row.cards + row.pool) if (c.lane in familiar) {
                assertEquals("${c.songId} in ${c.lane}", 0, c.sources)
                seen += c.lane
                if (c.songId in onSeedLists) listed++
            }
        }
        assertEquals(familiar, seen)
        // Some of them were on a seed's list, so the zero comes from the lane and not from a song nobody listed.
        assertTrue(listed > 0)
    }

    @Test
    fun `never played splits explore and records the share`() {
        val input = world()
        for (s in listOf(0.2, 0.5, 0.8)) repeat(10) { k ->
            val row = EngineRow.build(input, neverPlayed = true, random = Random(k.toLong()), lastFmShare = s)
            assertEquals(s, row.lastFmShare)
            assertEquals(20, row.cards.size)
            assertTrue(row.cards.all { it.lane == Lane.EXPLORE })
            // Every song here is on one list and each source has plenty open, so the ledger holds to the card.
            val n = row.cards.count { it.sources and Provenance.SOURCES != 0 }
            val l = row.cards.count { it.sources and Provenance.SOURCES == Provenance.LASTFM }
            assertEquals(20, n)
            assertTrue("s $s draw $k: $l of $n", abs(l - s * n) < 1)
        }
    }

    @Test
    fun `lists from one source never mark a card contested`() {
        // Last.fm only, then a mix in which each seed's lists all come from one source: even albums YouTube's, odd albums Last.fm's.
        val lastFmOnly = world { _, _ -> Provenance.LASTFM }
        val perSeed = world { a, _ -> if (a % 2 == 0) Provenance.YOUTUBE else Provenance.LASTFM }
        var lastFmCards = 0
        var agreed = 0
        for (input in listOf(lastFmOnly, perSeed)) repeat(10) { k ->
            val row = EngineRow.build(input, random = Random(k.toLong()), lastFmShare = 0.5)
            for (c in row.cards + row.pool) {
                assertEquals("${c.songId} marked contested", 0, c.sources and Provenance.CONTESTED)
                if (input === lastFmOnly) assertTrue(c.sources == 0 || c.sources == Provenance.LASTFM)
                if (input === lastFmOnly && c.sources == Provenance.LASTFM) lastFmCards++
                if (c.sources == Provenance.SOURCES) agreed++
            }
        }
        assertTrue(lastFmCards > 0)
        // Songs an even and an odd album both list carry both bits, and still nothing competed for them.
        assertTrue(agreed > 0)
    }
}
