/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the queue is allowed to change behind the listener's back, and what it is not. */
class AdaptiveQueueTest {

    private fun runOf(vararg titles: String): TagFit.Context =
        TagFit.context(titles.map { SongTags.of(it) })

    private val nothingPlaying = TagFit.context(emptyList())

    @Test
    fun `a calm run drops the nightcore waiting further down`() {
        val tail = listOf("A (Slowed)", "B (Nightcore)", "C (Slowed + Reverb)", "D (Sped Up)", "E")
        val plan = AdaptiveQueue.plan(tail, { it }, runOf("X (Slowed)", "Y (Slowed)", "Z (Reverb)"))

        assertTrue(plan.changed)
        assertTrue("B (Nightcore)" in plan.dropped)
        assertTrue("A (Slowed)" in plan.keep)
        assertTrue("C (Slowed + Reverb)" in plan.keep)
    }

    @Test
    fun `order is never rearranged, only shortened`() {
        val tail = listOf("A (Slowed)", "B (Nightcore)", "C (Slowed)", "D (Sped Up)", "E (Slowed)")
        val plan = AdaptiveQueue.plan(tail, { it }, runOf("X (Slowed)", "Y (Slowed)"))

        // whatever survives must still be in its original relative order
        val originalOrder = tail.filter { it in plan.keep }
        assertEquals(originalOrder, plan.keep)
    }

    @Test
    fun `it never takes more than its share, however wrong the tail is`() {
        val tail = (1..10).map { "S$it (Nightcore)" }
        val plan = AdaptiveQueue.plan(tail, { it }, runOf("X (Slowed)", "Y (Slowed)"))

        assertTrue("must leave most of the queue alone", plan.dropped.size <= 4)
        assertTrue(plan.keep.size >= 6)
    }

    @Test
    fun `with no idea what is playing it changes nothing`() {
        // The honest answer after a long gap. Rearranging on a guess is worse than leaving it.
        val tail = listOf("A (Nightcore)", "B (Slowed)", "C (Sped Up)")
        val plan = AdaptiveQueue.plan(tail, { it }, nothingPlaying)

        assertFalse(plan.changed)
        assertEquals(tail, plan.keep)
    }

    @Test
    fun `a tail that all fits is left entirely alone`() {
        val tail = listOf("A (Slowed)", "B (Slowed + Reverb)", "C (Slowed)")
        val plan = AdaptiveQueue.plan(tail, { it }, runOf("X (Slowed)", "Y (Slowed)"))

        assertFalse(plan.changed)
        assertEquals(tail, plan.keep)
    }

    @Test
    fun `songs with nothing to say about themselves are never dropped`() {
        // Most music carries no qualifier. Dropping it for that would empty the queue of
        // everything except the labelled quarter of the library.
        val tail = listOf("Plain One", "Plain Two", "Plain Three", "Loud (Nightcore)")
        val plan = AdaptiveQueue.plan(tail, { it }, runOf("X (Slowed)", "Y (Slowed)", "Z (Slowed)"))

        assertTrue(plan.dropped.none { it.startsWith("Plain") })
    }

    @Test
    fun `the locked head is respected`() {
        // Nothing within four songs of the current one is even offered for planning.
        assertNull("no tail yet", AdaptiveQueue.tailStart(currentIndex = 0, size = 4))
        assertEquals(5, AdaptiveQueue.tailStart(currentIndex = 0, size = 20))
        assertEquals(15, AdaptiveQueue.tailStart(currentIndex = 10, size = 20))
        assertNull("nothing left past the lock", AdaptiveQueue.tailStart(currentIndex = 18, size = 20))
    }
}
