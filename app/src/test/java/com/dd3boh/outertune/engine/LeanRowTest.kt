/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lean's rules on Home: what the tidy pass keeps in place, the heading, a pull, the rebuild, Try both. */
class LeanRowTest {
    private data class S(val id: String, val lane: Lane, val artist: String)

    private fun compose(cards: List<S>, tidied: List<S>, lead: Lane = Lane.EXPLORE) =
        LeanRow.compose(cards, tidied, lead, 20, 4, { it.id }, { it.lane }, { it.artist }).map { it.id }

    /** A leaned row: four lead cards, then the rest alternating, each card its own artist. */
    private val cards = List(20) { i -> S("c$i", if (i < 4 || i % 3 == 0) Lane.EXPLORE else Lane.RELATED, "artist$i") }
    private val pool = List(10) { i -> S("p$i", if (i % 2 == 0) Lane.EXPLORE else Lane.AGAIN, "pool$i") }

    @Test
    fun `with nothing dropped the row is the engine's, in its order`() {
        assertEquals(cards.map { it.id }, compose(cards, cards + pool))
    }

    @Test
    fun `a dropped lead card is replaced in its place by the next lead card, any other by the next other`() {
        // The pass dropped c1 (a lead card in the first column) and c5 (a related card).
        val tidied = (cards + pool).filter { it.id != "c1" && it.id != "c5" }
        val row = compose(cards, tidied)
        assertEquals(20, row.size)
        assertEquals("p0", row[1])          // the first lead card of the pool, in c1's place
        assertEquals("p1", row[5])          // the first other card, in c5's place
        assertEquals(cards.map { it.id }.filter { it != "c1" && it != "c5" }, row.filter { it.startsWith("c") })
        // The ordinary pass would have closed the gaps from the left and pulled c4, a related
        // card's neighbour, into the first column; the lean keeps four lead cards there.
        assertEquals(List(4) { Lane.EXPLORE }, row.take(4).map { id -> (cards + pool).first { it.id == id }.lane })
    }

    @Test
    fun `with no lead card left in the pool, another card fills the place, and the first column is refilled from later lead cards`() {
        val tidied = (cards + pool.filter { it.lane != Lane.EXPLORE }).filter { it.id != "c2" }
        val row = compose(cards, tidied)
        assertEquals(20, row.size)
        // c2's place went to an Again card, so the first column takes the next lead card, c6, instead.
        assertEquals(listOf("c0", "c1", "c3", "c6"), row.take(4))
        assertTrue("p1" in row)
    }

    @Test
    fun `the first column holds one card an artist`() {
        val same = cards.mapIndexed { i, c -> if (i == 1) c.copy(artist = "artist0") else c }
        val row = compose(same, same + pool)
        val firstColumnArtists = row.take(4).map { id -> (same + pool).first { it.id == id }.artist }
        assertEquals(4, firstColumnArtists.toSet().size)
        assertTrue("c1" !in row.take(4))
    }

    @Test
    fun `a short pass is topped up from the pool and never runs past twenty`() {
        val tidied = (cards + pool).filter { it.id !in setOf("c7", "c8", "c9") }
        val row = compose(cards, tidied)
        assertEquals(20, row.size); assertEquals(row.size, row.toSet().size)
    }

    @Test
    fun `the heading says short or nothing only when the engine itself ran short`() {
        assertNull(LeanRow.heading(Lean.AUTO, 0, 0, 0))
        assertEquals(LeanHeadingKind.LEANING, LeanRow.heading(Lean.NEW, 12, 12, 11))   // one lost to tidy: not announced
        assertEquals(LeanHeadingKind.LEANING, LeanRow.heading(Lean.NEW, 12, 13, 13))
        assertEquals(LeanHeadingKind.SHORT, LeanRow.heading(Lean.FORGOTTEN, 12, 5, 5))
        assertEquals(LeanHeadingKind.SHORT, LeanRow.heading(Lean.FORGOTTEN, 12, 5, 4))
        assertEquals(LeanHeadingKind.NOTHING, LeanRow.heading(Lean.FORGOTTEN, 12, 0, 0))
        assertEquals(LeanHeadingKind.NOTHING, LeanRow.heading(Lean.SIMILAR, 12, 1, 0))
        assertEquals(LeanHeadingKind.SHORT, LeanOnScreen(Lean.SIMILAR, Lean.SIMILAR, 12, 6, 6, 20).heading)
        assertNull(LeanOnScreen(Lean.SIMILAR, Lean.AUTO, 0, 0, 0, 20).heading)
    }

    @Test
    fun `a lean that gave way to Auto's row says nothing yet, and Forgotten names its 45 days only when it had nothing at all`() {
        for (lean in Lean.entries.filter { it != Lean.AUTO }) for (nothing in listOf(true, false)) {
            val state = LeanOnScreen(lean, lean, 0, 0, 0, 19, gaveWay = LeanGaveWay(lean, nothing))
            assertEquals(LeanHeadingKind.NOTHING, state.heading)
            assertEquals(lean == Lean.FORGOTTEN && nothing, state.forgottenHasNothing)
        }
        // A row that follows Forgotten and lost its cards to the tidy pass had some, so the 45 days are not named.
        val lost = LeanOnScreen(Lean.FORGOTTEN, Lean.FORGOTTEN, 12, 2, 0, 20)
        assertEquals(LeanHeadingKind.NOTHING, lost.heading); assertFalse(lost.forgottenHasNothing)
        // What a build was for: the lean it followed, or the one it gave way on.
        val cards = emptyList<Card>()
        assertEquals(Lean.ARTIST, BuiltRow(cards, emptyList(), cards, emptyMap(), gaveWay = LeanGaveWay(Lean.ARTIST, true)).asked)
        assertEquals(Lean.NEW, BuiltRow(cards, emptyList(), cards, emptyMap(), lean = Lean.NEW).asked)
        assertEquals(Lean.AUTO, BuiltRow(cards, emptyList(), cards, emptyMap()).asked)
    }

    @Test
    fun `a pull sets aside the whole row, except Playing now's lead cards past the first column`() {
        val row = List(20) { i -> Card("c$i", if (i % 2 == 0) Lane.RELATED else Lane.AGAIN, 0.0, 0.1, DoubleArray(Features.COUNT), emptyList()) }
        val all = row.mapTo(HashSet()) { it.songId }
        assertEquals(all, LeanRow.pullBanned(row, Lean.AUTO, 4))
        assertEquals(all, LeanRow.pullBanned(row, Lean.ARTIST, 4))
        assertEquals(all, LeanRow.pullBanned(row, Lean.NEW, 4))
        val similar = LeanRow.pullBanned(row, Lean.SIMILAR, 4)
        assertEquals(setOf("c0", "c1", "c2", "c3") + row.drop(4).filter { it.lane != Lane.RELATED }.map { it.songId }, similar)
        assertFalse("c4" in similar)
    }

    private fun listen(song: String, origin: PlayOrigin = PlayOrigin.SEARCH, ratio: Double = 1.0, duration: Long = 200_000L, end: Int = EndReason.ENDED, depth: Int = 0, learn: Boolean = true): ListenRow {
        val played = if (duration > 0) (duration * ratio).toLong() else (ratio * 200_000).toLong()
        return ListenRow(song, 1_000L, 1_000L + played, played, duration, end, origin.code, depth, 1, 0, learn)
    }

    @Test
    fun `the session has moved on after listens heard well from anywhere but the row`() {
        val listens = listOf(
            listen("a"), listen("b"),
            listen("q", origin = PlayOrigin.QUICK_PICKS), listen("q2", origin = PlayOrigin.QUICK_PICKS, depth = 3),
            listen("w", origin = PlayOrigin.WIDGET),
            listen("skip", ratio = 0.1, end = EndReason.SKIPPED),
            listen("open", end = EndReason.OPEN),
            listen("forgotten", learn = false),
            // A play that failed, most of the way through: the stream died, the listener chose nothing.
            listen("failed", ratio = 0.9, end = EndReason.ERROR),
            // A length never learned: a minute is not enough, two minutes is.
            listen("u1", duration = -1, ratio = 0.3), listen("u2", duration = -1, ratio = 0.7),
        )
        assertEquals(3, LeanRow.heardWellSince(listens, { null }))
        // A like during the listen makes a short one count.
        val liked = listOf(listen("l", ratio = 0.2))
        assertEquals(0, LeanRow.heardWellSince(liked, { null }))
        assertEquals(1, LeanRow.heardWellSince(liked, { 1_500L }))
        val p = EngineParams.DEFAULT
        assertTrue(LeanRow.similarRebuildDue(Lean.SIMILAR, 3, p))
        assertFalse(LeanRow.similarRebuildDue(Lean.SIMILAR, 2, p))
        assertFalse(LeanRow.similarRebuildDue(Lean.ARTIST, 30, p))
        assertFalse(LeanRow.similarRebuildDue(Lean.AUTO, 30, p))
        assertFalse(LeanRow.similarRebuildDue(Lean.SIMILAR, 30, p.copy(leanSimilarRebuildListens = 0)))
    }

    @Test
    fun `the cached input is behind once a listen has started since its newest`() {
        assertTrue(LeanRow.inputBehind(200, 100))
        assertFalse(LeanRow.inputBehind(100, 100))
        assertFalse(LeanRow.inputBehind(null, 100))
        assertTrue(LeanRow.inputBehind(100, null))
    }

    @Test
    fun `Try both holds about six of a lean's cards and two of the first four`() {
        val p = EngineParams.DEFAULT.withFamiliarity(0.25)
        for (lean in Lean.entries.filter { it != Lean.AUTO }) assertEquals(lean.name, 6 to 2, LeanRow.compareShare(lean, 0.5, p))
        assertEquals(0 to 0, LeanRow.compareShare(Lean.AUTO, 0.5, p))
    }
}
