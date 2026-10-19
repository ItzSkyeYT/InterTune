/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.Features
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a run remembers of itself, by run rather than by queue. See AdaptiveQueue.Heard. */
class AdaptiveHeardTest {

    private var now = 5_000_000_000L
    private fun heard() = AdaptiveQueue.Heard(clock = { now })

    @Test
    fun `a radio's queue of one and its full queue are one run, and the seed is kept`() {
        val h = heard()
        // The queue of one, then the queue YouTube's answer names: another queue, the same run.
        h.note(1L, "S", "Seed", listOf("Band"), 200, runId = 9L)
        h.note(2L, "A", "After", listOf("Band"), 200, runId = 9L, radio = true)

        assertEquals(listOf("S", "A"), h.reached.toList())
        assertEquals(listOf("After", "Seed"), h.titles(5))
    }

    @Test
    fun `a new run starts over, and a run of 0 is its queue's`() {
        val h = heard()
        h.note(1L, "S", "Seed", runId = 9L, radio = true)
        h.note(1L, "X", "Other", runId = 10L, radio = true)
        assertEquals(setOf("X"), h.reached)

        h.note(3L, "Q", "Old queue")
        h.note(3L, "R", "Old queue too")
        assertEquals("a queue saved before runs were kept is one run", setOf("Q", "R"), h.reached)
    }

    @Test
    fun `a song noted while it is already playing is not noted again`() {
        val h = heard()
        h.note(1L, "A", "Alpha", runId = 9L, radio = true)
        h.note(1L, "A", "Alpha", runId = 9L, radio = true)

        assertEquals(listOf("Alpha"), h.titles(5))
        assertEquals(1, h.played)
        assertTrue("repeat one is not a song heard again", h.keptTitles(5).isEmpty())
    }

    @Test
    fun `a radio left for a playlist and come back to picks up where it was`() {
        val h = heard()
        h.note(1L, "A", "Alpha", listOf("Band"), 200, runId = 9L, radio = true)
        h.left(AdaptiveQueue.Leave.SKIPPED)
        h.note(1L, "B", "Beta", listOf("Band"), 200, runId = 9L, radio = true)
        h.note(2L, "P", "Playlist song", runId = 20L)
        assertEquals(setOf("P"), h.reached)

        h.note(1L, "B", "Beta", listOf("Band"), 200, runId = 9L, radio = true)
        assertEquals(listOf("A", "B"), h.reached.toList())
        assertEquals(setOf("band"), h.view().artistSkips)
    }

    @Test
    fun `a radio come back to after its sitting is over keeps what it played and forgets what it learned`() {
        val h = heard()
        h.note(1L, "A", "Alpha", listOf("Band"), 200, runId = 9L, radio = true)
        h.left(AdaptiveQueue.Leave.SKIPPED)
        h.note(1L, "B", "Beta", listOf("Band"), 200, runId = 9L, radio = true)
        h.note(2L, "P", "Playlist song", runId = 20L)
        now += Features.RUN_WINDOW_MS + 1
        h.note(1L, "C", "Gamma", listOf("Band"), 200, runId = 9L, radio = true)

        assertEquals(listOf("A", "B", "C"), h.reached.toList())
        assertTrue(h.view().artistSkips.isEmpty())
    }

    @Test
    fun `songs put in by hand before play go into the saved run`() {
        val first = heard()
        first.note(5L, "A", "Alpha", runId = 77L, radio = true)
        val text = AdaptiveMemory.encode(first.saved())

        val h = heard()
        h.offer(AdaptiveMemory.decode(text))
        // Play next before play: the run is not the one playing yet.
        h.putInByHand(5L, 77L, listOf("H"), radio = true)
        h.note(5L, "A", "Alpha", runId = 77L, radio = true)

        assertEquals(setOf("H"), h.byHand)
        assertEquals(setOf("A"), h.reached)
    }

    @Test
    fun `a swiped song is turned down with its versions and its artists take a skip`() {
        val h = heard()
        h.note(1L, "A", "Alpha", listOf("Band"), 200, runId = 9L, radio = true)
        h.picked(mapOf("P" to "A"))
        h.removedByHand("P", "Paper Moon (Slowed)", listOf("Uploader"))

        val view = h.view()
        assertTrue("paper moon" in view.turnedDown.songs)
        assertEquals(setOf("uploader"), view.artistSkips)
        assertTrue("a pick taken out is not picked again", "P" in view.dismissed)
        assertTrue(h.unreachedPicks().isEmpty())
    }

    @Test
    fun `the restored song is not counted as heard again`() {
        val first = heard()
        first.note(5L, "A", "Alpha", runId = 77L, radio = true)
        first.note(5L, "B", "Beta", runId = 77L, radio = true)
        val text = AdaptiveMemory.encode(first.saved())
        now += Features.RUN_WINDOW_MS + 1

        val h = heard()
        h.offer(AdaptiveMemory.decode(text))
        h.note(5L, "B", "Beta", runId = 77L, radio = true)

        assertTrue("no replay, no seed from it", h.view().seeds == listOf("B"))
        assertNull(h.lastLeave)
        assertEquals(2, h.played)
    }
}
