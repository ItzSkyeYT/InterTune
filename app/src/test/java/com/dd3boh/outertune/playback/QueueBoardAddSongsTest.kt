/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play next and Add to queue must put the songs where they were asked for and leave the current
 * song current, shuffled or not, and whether or not the player holds the queue yet.
 *
 * Exercises MultiQueueObject.insertAtPlayIndex and playNextIndex directly: the methods
 * QueueBoard.addSongsToQueue and MusicService.enqueueNext call, not a copy of their logic.
 * QueueBoard itself needs a live MusicService to construct and cannot be unit tested directly.
 */
class QueueBoardAddSongsTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    /**
     * Ten songs s0 to s9, shuffled so that the play order is the stored order reversed, restored
     * on its last song in play order: s0, stored first and heard last.
     */
    private fun restoredOnLastSong(): MultiQueueObject {
        val songs = MutableList(10) { i -> song("s$i").apply { shuffleIndex = 9 - i } }
        return MultiQueueObject(id = 1, title = "Q", queue = songs, shuffled = true, queuePos = 0, index = 0)
    }

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }

    @Test
    fun `Play next on a restored shuffled queue goes straight after the restored song`() {
        val q = restoredOnLastSong()

        // The player is still empty after the restart, so it has no index to give.
        val at = q.playNextIndex(playerIndex = null)
        assertEquals(10, at)
        q.insertAtPlayIndex(at, listOf(song("N")))

        assertTrue("shuffle must stay on", q.shuffled)
        assertEquals("the restored song is still current", "s0", q.queue[q.queuePos].id)
        assertEquals(9, q.getQueuePosShuffled())
        assertEquals(listOf("s9", "s8", "s7", "s6", "s5", "s4", "s3", "s2", "s1", "s0", "N"), q.playOrder())
    }

    @Test
    fun `inserting before the current song of a shuffled queue leaves it current`() {
        val q = restoredOnLastSong()

        // Where an empty player's index 0 used to put Play next. This threw once the current
        // song's place was counted twice: no song held the index it looked for.
        q.insertAtPlayIndex(1, listOf(song("N")))

        assertTrue("shuffle must stay on", q.shuffled)
        assertEquals("s0", q.queue[q.queuePos].id)
        assertEquals(10, q.getQueuePosShuffled())
        assertEquals(listOf("s9", "N", "s8", "s7", "s6", "s5", "s4", "s3", "s2", "s1", "s0"), q.playOrder())
    }

    @Test
    fun `several songs inserted before a shuffled queue's current song leave the same song current`() {
        // Stored A,B,C,D,E,F, played B,E,D,A,F,C. D is current: stored index 3, played third.
        val a = song("A").apply { shuffleIndex = 3 }
        val b = song("B").apply { shuffleIndex = 0 }
        val c = song("C").apply { shuffleIndex = 5 }
        val d = song("D").apply { shuffleIndex = 2 }
        val e = song("E").apply { shuffleIndex = 1 }
        val f = song("F").apply { shuffleIndex = 4 }
        val q = MultiQueueObject(
            id = 1, title = "Q", queue = mutableListOf(a, b, c, d, e, f), shuffled = true, queuePos = 3, index = 0,
        )

        q.insertAtPlayIndex(1, listOf(song("N1"), song("N2")))

        assertTrue(q.shuffled)
        // Counting the two new songs twice named F, two places further down.
        assertEquals("D", q.queue[q.queuePos].id)
        assertEquals(3, q.queuePos)
        assertEquals(4, q.getQueuePosShuffled())
        assertEquals(listOf("B", "N1", "N2", "E", "D", "A", "F", "C"), q.playOrder())
    }

    @Test
    fun `Play next with a song playing goes after the player's song`() {
        val q = restoredOnLastSong()
        // Once the player holds the queue its index is the one to trust.
        assertEquals(4, q.playNextIndex(playerIndex = 3))
    }

    @Test
    fun `a song the shuffled queue already holds is added again, not moved`() {
        // Stored A,B,C,D, played B,D,A,C. A is current, and B, played first, is selected in the
        // queue sheet before play and put next: the sheet hands back the queue's own object.
        val a = song("A").apply { shuffleIndex = 2 }
        val b = song("B").apply { shuffleIndex = 0 }
        val c = song("C").apply { shuffleIndex = 3 }
        val d = song("D").apply { shuffleIndex = 1 }
        val q = MultiQueueObject(
            id = 1, title = "Q", queue = mutableListOf(a, b, c, d), shuffled = true, queuePos = 0, index = 0,
        )

        q.insertAtPlayIndex(q.playNextIndex(playerIndex = null), listOf(q.queue[1]))

        // Inserting the object itself numbered it for the new place: B left the front, and the
        // queue held B twice under one number.
        assertEquals(listOf("B", "D", "A", "B", "C"), q.playOrder())
        assertSame(b, q.getCurrentQueueShuffled().first())
        assertEquals((0..4).toList(), q.queue.map { it.shuffleIndex }.sorted())
        assertDistinctRows(q)
        assertEquals("A", q.queue[q.queuePos].id)
    }

    @Test
    fun `the song playing added again to its own queue gets a row of its own`() {
        // Add to queue on the song playing, with its own queue chosen.
        val q = MultiQueueObject(
            id = 1, title = "Q", queue = mutableListOf(song("A"), song("B"), song("C")),
            shuffled = false, queuePos = 1, index = 0,
        )

        q.insertAtPlayIndex(Int.MAX_VALUE, listOf(q.queue[q.queuePos]))

        assertEquals(listOf("A", "B", "C", "B"), q.playOrder())
        assertEquals(1, q.queuePos)
        assertDistinctRows(q)
    }

    /** Every row its own object and its own key: the queue sheet keys its rows on hashCode. */
    private fun assertDistinctRows(q: MultiQueueObject) {
        val rows = q.getCurrentQueueShuffled()
        for (i in rows.indices) for (j in rows.indices) {
            if (i != j) assertNotSame("rows $i and $j are one object", rows[i], rows[j])
        }
        assertEquals(rows.size, rows.map { it.hashCode() }.toSet().size)
    }

    @Test
    fun `an unshuffled insert moves the current song along only when it lands at or before it`() {
        val q = MultiQueueObject(
            id = 1, title = "Q", queue = mutableListOf(song("A"), song("B"), song("C"), song("D")),
            shuffled = false, queuePos = 2, index = 0,
        )

        // Before the current song, C.
        q.insertAtPlayIndex(1, listOf(song("N")))
        assertEquals(listOf("A", "N", "B", "C", "D"), q.playOrder())
        assertEquals("C", q.queue[q.queuePos].id)
        assertEquals(3, q.queuePos)

        // Play next with an empty player: straight after C.
        q.insertAtPlayIndex(q.playNextIndex(playerIndex = null), listOf(song("M")))
        assertEquals(listOf("A", "N", "B", "C", "M", "D"), q.playOrder())
        assertEquals(3, q.queuePos)

        // Add to queue: past the end appends.
        q.insertAtPlayIndex(Int.MAX_VALUE, listOf(song("Z")))
        assertEquals(listOf("A", "N", "B", "C", "M", "D", "Z"), q.playOrder())
        assertEquals("C", q.queue[q.queuePos].id)
        assertFalse(q.shuffled)
    }
}
