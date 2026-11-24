/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive queue's run and a listening room (his word of 23 Nov 2026: "for songs added to queue by users make it not
 * count towards your engine"): a song someone else put in the room's queue is heard in the run and teaches it nothing,
 * however it goes. No keep, skip or like, no seed for picks like it, nothing of its sound, no turn against its artist
 * when it is swiped out. The listener's own songs teach the run as ever.
 */
class AdaptiveTheirsTest {

    private fun AdaptiveQueue.Heard.play(id: String, title: String, artist: String, learns: Boolean = true) =
        note(1L, id, title, listOf(artist), 200, runId = 9L, radio = true, learns = learns)

    @Test
    fun `someone else's song heard to its end or liked is no seed, and its sound is not the run's`() {
        val heard = AdaptiveQueue.Heard()
        heard.play("own1", "Own One", "Band A")
        heard.left(AdaptiveQueue.Leave.RAN_OUT)
        heard.play("g1", "Guest One (Slowed + Reverb)", "Band G", learns = false)
        heard.left(AdaptiveQueue.Leave.RAN_OUT)
        heard.play("g2", "Guest Two (Sped Up)", "Band G", learns = false)
        heard.liked("g2")
        val view = heard.view()
        assertEquals(listOf("own1"), view.seeds)
        assertFalse("band g" in view.positiveArtists)
        assertNull("a like on it puts no picks in", view.likePending)
        assertEquals(listOf("Own One"), view.keptTitles)
        assertEquals(listOf("Own One"), heard.titles(5))
        // and the listener's own like is answered as ever
        heard.left(AdaptiveQueue.Leave.KEPT)
        heard.play("own2", "Own Two", "Band B")
        heard.liked("own2")
        assertEquals("own2", heard.view().likePending)
    }

    @Test
    fun `someone else's songs skipped turn nothing down`() {
        val heard = AdaptiveQueue.Heard()
        heard.play("own1", "Own One", "Band A")
        heard.play("g1", "Guest One", "Band G", learns = false)
        heard.left(AdaptiveQueue.Leave.SKIPPED)
        assertEquals(AdaptiveQueue.Leave.NEITHER, heard.lastLeave)
        heard.play("g2", "Guest Two", "Band G", learns = false)
        heard.left(AdaptiveQueue.Leave.SKIPPED)
        assertTrue(heard.turnedDown().artists.isEmpty())
        assertTrue(heard.turnedDown().songs.isEmpty())
        assertTrue(heard.view().artistSkips.isEmpty())
        assertTrue(heard.view().skippedIds.isEmpty())
        // the listener's own skips still count
        heard.play("own2", "Own Two", "Band B")
        heard.left(AdaptiveQueue.Leave.SKIPPED)
        heard.play("own3", "Own Three", "Band B")
        heard.left(AdaptiveQueue.Leave.SKIPPED)
        assertEquals(setOf("band b"), heard.turnedDown().artists)
    }

    @Test
    fun `someone else's song swiped out says nothing against its artist, and Undo takes back nothing`() {
        val heard = AdaptiveQueue.Heard()
        heard.play("own1", "Own One", "Band A")
        val one = heard.removedByHand("g1", "Guest One", listOf("Band G"), learns = false)
        val two = heard.removedByHand("g2", "Guest Two", listOf("Band G"), learns = false)
        assertTrue(heard.turnedDown().artists.isEmpty())
        assertTrue(heard.turnedDown().songs.isEmpty())
        heard.putBackByHand(two)
        heard.putBackByHand(one)
        assertTrue(heard.turnedDown().artists.isEmpty())
        // the listener's own removals as ever
        heard.removedByHand("x1", "Mine One", listOf("Band X"))
        heard.removedByHand("x2", "Mine Two", listOf("Band X"))
        assertEquals(setOf("band x"), heard.turnedDown().artists)
    }

    @Test
    fun `a run that begins on someone else's song begins, for its seeds, on the listener's first own song`() {
        val heard = AdaptiveQueue.Heard()
        heard.play("g1", "Guest One", "Band G", learns = false)
        heard.play("own1", "Own One", "Band A")
        val view = heard.view()
        assertEquals(listOf("own1"), view.seeds)
        assertFalse("band g" in view.positiveArtists)
        assertTrue("band a" in view.positiveArtists)
    }

    @Test
    fun `heard again, someone else's song is still no seed`() {
        val heard = AdaptiveQueue.Heard()
        heard.play("own1", "Own One", "Band A")
        heard.play("g1", "Guest One", "Band G", learns = false)
        heard.play("own2", "Own Two", "Band B")
        heard.play("g1", "Guest One", "Band G", learns = false)
        assertFalse("g1" in heard.view().seeds)
        assertFalse("band g" in heard.view().positiveArtists)
    }
}
