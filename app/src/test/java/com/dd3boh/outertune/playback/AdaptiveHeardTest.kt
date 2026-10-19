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
    fun `a run that begins on a song already playing takes that song as its first, with its like`() {
        val h = heard()
        h.note(1L, "P", "Playlist song", listOf("Band"), 200, runId = 3L)
        h.note(1L, "S", "Seed", listOf("Band S"), 200, runId = 3L)
        h.liked("S")
        // Start radio on S: its radio is loaded under it, with no song change.
        h.carriedOn(2L, "S", "Seed", listOf("Band S"), 200, runId = 9L, radio = true)

        assertEquals(AdaptiveQueue.runKey(2L, 9L), h.runKey)
        assertEquals(setOf("S"), h.reached)
        assertEquals(1, h.played)
        assertFalse("only the song it began on is heard", h.begun)
        assertEquals("the song the radio was started from is its seed", listOf("S"), h.view().seeds)
        assertNull("the like's picks were the run before's", h.view().likePending)
        h.left(AdaptiveQueue.Leave.SKIPPED)
        assertEquals("liked, so leaving it early is no skip", AdaptiveQueue.Leave.NEITHER, h.lastLeave)
        assertTrue(h.view().artistSkips.isEmpty())
        h.note(2L, "R1", "First of the radio", listOf("Band R"), 200, runId = 9L, radio = true)
        assertTrue(h.begun)
        assertEquals(listOf("S"), h.view().seeds)
    }

    @Test
    fun `a like on the song a radio was started from, once it started, is the radio's`() {
        val h = heard()
        h.note(1L, "S", "Seed", listOf("Band S"), 200, runId = 3L)
        h.carriedOn(2L, "S", "Seed", listOf("Band S"), 200, runId = 9L, radio = true)
        h.liked("S")
        assertEquals("S", h.view().likePending)
        // The full radio loads under it in the same run: nothing changes.
        h.carriedOn(4L, "S", "Seed", listOf("Band S"), 200, runId = 9L, radio = true)
        assertEquals(1, h.played)
        assertEquals("S", h.view().likePending)
        assertTrue("saved as a radio's run", h.saved().any { it.key == "run:9" })
    }

    @Test
    fun `a queue just loaded has not begun until a song of it is left or a second one starts`() {
        val h = heard()
        h.note(1L, "A", "Alpha", runId = 9L, radio = true)
        assertFalse(h.begun)
        h.left(AdaptiveQueue.Leave.RAN_OUT)
        assertTrue("came round again on repeat one", h.begun)
        h.note(2L, "B", "Beta", runId = 10L, radio = true)
        assertFalse("another run starts over", h.begun)
        h.note(2L, "C", "Gamma", runId = 10L, radio = true)
        assertTrue(h.begun)
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

/** How a song that was left is read, and what a run counts as wanted. */
class AdaptiveLeaveTest {

    private fun heard() = AdaptiveQueue.Heard(clock = { 0L })

    private fun AdaptiveQueue.Heard.play(id: String, how: AdaptiveQueue.Leave? = null, artist: String = "Artist $id") {
        note(1L, id, "Song $id", listOf(artist), 200, runId = 9L, radio = true)
        how?.let { left(it) }
    }

    @Test
    fun `before half is a skip, from half on a keep, a song scrubbed to its end neither`() {
        assertEquals(AdaptiveQueue.Leave.SKIPPED, AdaptiveQueue.leave(60_000, 200))
        assertEquals(AdaptiveQueue.Leave.KEPT, AdaptiveQueue.leave(100_000, 200))
        assertEquals(AdaptiveQueue.Leave.KEPT, AdaptiveQueue.leave(150_000, 200, heardThroughMs = 120_000))
        assertEquals("seeked to the end and left", AdaptiveQueue.Leave.NEITHER, AdaptiveQueue.leave(190_000, 200, heardThroughMs = 20_000))
        assertEquals(AdaptiveQueue.Leave.NEITHER, AdaptiveQueue.leave(null, 200))
        assertEquals(AdaptiveQueue.Leave.SKIPPED, AdaptiveQueue.leave(20_000, -1))
        assertEquals("length unknown, past half a minute: could be anywhere", AdaptiveQueue.Leave.NEITHER, AdaptiveQueue.leave(45_000, -1))
    }

    @Test
    fun `the seeds are the newest songs kept, and the song a run began with while there are none`() {
        val h = heard()
        h.play("A")
        assertEquals("the song the radio was started from", listOf("A"), h.view().seeds)
        h.left(AdaptiveQueue.Leave.SKIPPED)
        h.play("B", AdaptiveQueue.Leave.SKIPPED)
        h.play("C")
        assertEquals("not once it was skipped: picks like it are what the listener turned from", emptyList<String>(), h.view().seeds)

        val n = heard()
        n.play("A", AdaptiveQueue.Leave.NEITHER)
        n.play("B")
        assertEquals("left by going back or by a tap, it still stands in", listOf("A"), n.view().seeds)

        val k = heard()
        k.play("A", AdaptiveQueue.Leave.RAN_OUT)
        k.play("B", AdaptiveQueue.Leave.SKIPPED)
        k.play("C", AdaptiveQueue.Leave.KEPT)
        k.play("D")
        assertEquals(listOf("C", "A"), k.view().seeds)
    }

    @Test
    fun `a like is a positive at once and asks for picks once`() {
        val h = heard()
        h.play("A", AdaptiveQueue.Leave.RAN_OUT)
        h.play("L")
        h.liked("L")
        val view = h.view()
        assertEquals(listOf("L", "A"), view.seeds)
        assertEquals("L", view.likePending)
        h.likeAnswered("L")
        h.liked("L")
        assertNull("liking it again adds nothing", h.view().likePending)
    }

    @Test
    fun `a like taken back puts nothing in`() {
        val h = heard()
        h.play("L")
        h.liked("L")
        h.unliked("L")
        assertNull(h.view().likePending)
        assertTrue(h.keptTitles(5).isEmpty())
    }

    @Test
    fun `a song moved past by skip on error, or left by a tap on another, says nothing`() {
        val h = heard()
        h.play("A", AdaptiveQueue.Leave.NEITHER, artist = "Band A")
        h.play("B", AdaptiveQueue.Leave.NEITHER, artist = "Band A")
        h.play("C")
        val view = h.view()
        assertTrue(view.artistSkips.isEmpty())
        assertTrue(view.turnedDown.songs.isEmpty())
        assertEquals(AdaptiveQueue.Leave.NEITHER, h.lastLeave)
    }

    @Test
    fun `a liked song left early is not a skip`() {
        val h = heard()
        h.play("L", artist = "Band L")
        h.liked("L")
        h.left(AdaptiveQueue.Leave.SKIPPED)
        assertEquals(AdaptiveQueue.Leave.NEITHER, h.lastLeave)
        assertTrue(h.view().artistSkips.isEmpty())
    }

    @Test
    fun `a song gone back to is heard again, and skipped soon after it is no positive any more`() {
        val h = heard()
        h.play("Z", AdaptiveQueue.Leave.RAN_OUT)
        h.play("A", AdaptiveQueue.Leave.SKIPPED, artist = "Band A")
        h.play("B")
        h.left(AdaptiveQueue.Leave.NEITHER)
        h.play("A", artist = "Band A")
        val view = h.view()
        assertEquals("heard again: a positive at once", listOf("A", "Z"), view.seeds)
        assertTrue("and what it was skipped for is forgiven", view.artistSkips.isEmpty())

        h.left(AdaptiveQueue.Leave.SKIPPED)
        h.play("C")
        assertEquals(listOf("Z"), h.view().seeds)
    }

    @Test
    fun `the run's sound is what was kept, not the songs skipped nor the one just started`() {
        val h = heard()
        h.play("P1", AdaptiveQueue.Leave.RAN_OUT)
        h.note(1L, "S1", "S1 (Slowed)", runId = 9L, radio = true); h.left(AdaptiveQueue.Leave.SKIPPED)
        h.play("P2", AdaptiveQueue.Leave.KEPT)
        h.note(1L, "S2", "S2 (Slowed)", runId = 9L, radio = true)
        assertEquals(listOf("Song P2", "Song P1"), h.keptTitles(5))
        assertEquals("the old reading counts them all", listOf("S2 (Slowed)", "Song P2", "S1 (Slowed)", "Song P1"), h.titles(5))
    }
}
