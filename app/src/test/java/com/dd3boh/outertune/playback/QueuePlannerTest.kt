/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.QueuePlanner.Pick
import com.dd3boh.outertune.playback.QueuePlanner.Slot
import com.dd3boh.outertune.playback.QueuePlanner.Unwanted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which songs ahead make way for which picks, and the rules no pass may break. See QueuePlanner. */
class QueuePlannerTest {

    /** The song playing and the four locked after it: places 0 to 4. The tail starts at 5. */
    private val head = listOf("now", "l1", "l2", "l3", "l4")

    private fun tail(n: Int = 10, fit: Double = 0.0) = (0 until n).map { i -> Slot(5 + i, "s$i", "artist $i", fit) }

    private fun pick(id: String, fit: Double = 0.5, seed: String = "seed", artist: String? = "pick $id") = Pick(id, seed, artist, fit)

    private fun List<Slot>.with(i: Int, f: (Slot) -> Slot) = mapIndexed { j, s -> if (j == i) f(s) else s }

    @Test
    fun `with nothing against anything ahead, nothing changes`() {
        val plan = QueuePlanner.plan(tail(), head, listOf(pick("p1", 5.0), pick("p2", 5.0)), share = 0.75)
        assertFalse(plan.changed)
        assertTrue(plan.changes.isEmpty())
    }

    @Test
    fun `an unwanted song is replaced in its own place, and the rest stay where they are`() {
        val slots = tail().with(2) { it.copy(unwanted = Unwanted.ARTIST_TURNED_DOWN) }.with(6) { it.copy(unwanted = Unwanted.ARTIST_TURNED_DOWN) }
        val plan = QueuePlanner.plan(slots, head, listOf(pick("p1"), pick("p2")), share = 0.4)

        assertEquals(listOf(7 to "p1", 11 to "p2"), plan.replaced.map { it.slot.index to it.pick.id })
        assertTrue(plan.dropped.isEmpty())
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `with no pick an unwanted song is taken out and a song out of place by its words stays`() {
        val slots = tail().with(1) { it.copy(unwanted = Unwanted.LASTING_DISLIKE) }.with(3) { it.copy(byWords = true) }
        val plan = QueuePlanner.plan(slots, head, emptyList(), share = 0.4)

        assertEquals(listOf(6), plan.dropped.map { it.slot.index })
        assertEquals(listOf(8), plan.keptWithoutPick.map { it.slot.index })
    }

    @Test
    fun `a pick must be no worse than what is already coming to take a place a rule decided on`() {
        val slots = tail(fit = 1.0).with(1) { it.copy(fit = 0.8) }.with(3) { it.copy(byWords = true, fit = -1.0) }
        val weak = QueuePlanner.plan(slots, head, listOf(pick("p1", fit = 0.5)), share = 0.4)
        assertTrue("below every song of the near part that stays", weak.replaced.isEmpty())
        val good = QueuePlanner.plan(slots, head, listOf(pick("p1", fit = 0.8)), share = 0.4)
        assertEquals("as good as the weakest of them is enough", listOf("p1"), good.replaced.map { it.pick.id })
    }

    @Test
    fun `a like's picks need no bar beyond being usable`() {
        val slots = tail(fit = 3.0)
        val plan = QueuePlanner.plan(slots, head, listOf(pick("l1", fit = 0.1, seed = "liked"), pick("l2", fit = -0.2, seed = "liked")), share = 0.4, liked = "liked")
        assertEquals(listOf("l1", "l2"), plan.inserts.map { it.id })
    }

    @Test
    fun `nothing in the locked head and no song put in by hand is ever changed`() {
        val slots = tail().map { it.copy(unwanted = Unwanted.BANNED) }.with(0) { it.copy(byHand = true, unwanted = null) }
        val plan = QueuePlanner.plan(slots, head, (1..20).map { pick("p$it") }, share = 0.75, liked = "seed")

        assertTrue(plan.changes.none { it.slot.index < 5 || it.slot.byHand })
        assertTrue(plan.changes.none { it.slot.index == 5 })
        assertEquals("a like's picks go in at the tail's first place, never inside the lock", 5, plan.insertAt)
    }

    @Test
    fun `one pass changes no more than its share, and never takes the last song out`() {
        val all = tail().map { it.copy(unwanted = Unwanted.ARTIST_TURNED_DOWN) }
        val plan = QueuePlanner.plan(all, head, emptyList(), share = 0.4)
        assertEquals(4, plan.dropped.size)
        assertEquals("the nearest first", listOf(5, 6, 7, 8), plan.dropped.map { it.slot.index })

        val one = QueuePlanner.plan(tail(1).map { it.copy(unwanted = Unwanted.BANNED) }, head, emptyList(), share = 1.0)
        assertTrue("the tail is never emptied", one.dropped.isEmpty())
        val two = QueuePlanner.plan(tail(2).map { it.copy(unwanted = Unwanted.BANNED) }, head, emptyList(), share = 1.0)
        assertEquals(1, two.dropped.size)
    }

    @Test
    fun `unwanted songs go before songs that are only out of place`() {
        val slots = tail().with(0) { it.copy(byWords = true) }.with(8) { it.copy(unwanted = Unwanted.PREVIEW) }
        // A share of one change: the preview further down goes first.
        val plan = QueuePlanner.plan(slots, head, listOf(pick("p1", 5.0)), share = 0.1)
        assertEquals(listOf(13), plan.replaced.map { it.slot.index })
    }

    @Test
    fun `a song out of place by its score alone needs a pick a logit better, and at most two go a pass`() {
        val slots = tail(fit = 0.0).map { it.copy(byScore = "skipped artist") }.take(5) + tail().drop(5)
        val close = QueuePlanner.plan(slots, head, listOf(pick("p1", fit = 0.9)), share = 0.75)
        assertTrue(close.replaced.isEmpty())
        assertTrue("and stays: a song that may not suit is better than a gap", close.dropped.isEmpty())

        val better = QueuePlanner.plan(slots, head, (1..5).map { pick("p$it", fit = 1.5) }, share = 0.75)
        assertEquals(QueuePlanner.SCORE_CHANGES, better.replaced.size)
    }

    @Test
    fun `after a like two of its picks go in at the start of the tail, and only its own`() {
        val picks = listOf(pick("other", 2.0, seed = "kept"), pick("l1", 1.0, seed = "liked"), pick("l2", 0.9, seed = "liked"), pick("l3", 0.8, seed = "liked"))
        val plan = QueuePlanner.plan(tail(), head, picks, share = 0.75, liked = "liked")

        assertEquals(listOf("l1", "l2"), plan.inserts.map { it.id })
        assertEquals(5, plan.insertAt)
        assertTrue(plan.replaced.isEmpty())
        assertFalse("without a like nothing goes in", QueuePlanner.plan(tail(), head, picks, share = 0.75).changed)
        assertTrue("a like whose list is not there yet puts in nothing", QueuePlanner.plan(tail(), head, picks.take(1), share = 0.75, liked = "liked").inserts.isEmpty())
    }

    @Test
    fun `no pick goes next to a song by its artist`() {
        val slots = tail().with(3) { it.copy(unwanted = Unwanted.BANNED) }
        // The places either side of #8 are by artist 2 and artist 4.
        val plan = QueuePlanner.plan(slots, head, listOf(pick("p1", artist = "artist 2"), pick("p2", artist = "artist 4"), pick("p3", artist = "artist 9", fit = 0.1)), share = 0.4)
        assertEquals(listOf("p3"), plan.replaced.map { it.pick.id })
    }

    @Test
    fun `at most two songs of one artist from the song playing to the end of the near part`() {
        val near = listOf("now", "x", "l2", "x", "l4")
        val slots = tail().with(2) { it.copy(unwanted = Unwanted.BANNED) }
        val plan = QueuePlanner.plan(slots, near, listOf(pick("p1", artist = "x"), pick("p2", artist = "y")), share = 0.4)
        assertEquals(listOf("p2"), plan.replaced.map { it.pick.id })
    }

    @Test
    fun `at most three picks from one seed are waiting at a time`() {
        val slots = tail().map { it.copy(unwanted = Unwanted.BANNED) }
        val picks = (1..6).map { pick("p$it", seed = "same", artist = "a$it") }
        val plan = QueuePlanner.plan(slots, head, picks, share = 1.0, picksBySeed = mapOf("same" to 1))
        assertEquals(2, plan.replaced.size)
    }

    @Test
    fun `planned again on its own result, it changes nothing`() {
        val slots = tail().with(1) { it.copy(unwanted = Unwanted.ARTIST_TURNED_DOWN) }.with(4) { it.copy(byWords = true) }.with(7) { it.copy(unwanted = Unwanted.BANNED) }
        val picks = (1..4).map { pick("p$it", fit = 0.5) }
        val first = QueuePlanner.plan(slots, head, picks, share = 0.75)
        assertTrue(first.changed)
        // The queue as the plan left it: each replaced place now holds its pick, which has nothing
        // against it, and the places taken out are gone.
        val replacedAt = first.replaced.associate { it.slot.index to it.pick }
        val droppedAt = first.dropped.map { it.slot.index }.toSet()
        val after = slots.filter { it.index !in droppedAt }.map { s -> replacedAt[s.index]?.let { p -> Slot(s.index, p.id, p.artist, p.fit) } ?: s }
        val used = first.replaced.map { it.pick.id }.toSet()
        val again = QueuePlanner.plan(after, head, picks.filter { it.id !in used }, share = 0.75)
        assertFalse(again.changed)
    }
}
