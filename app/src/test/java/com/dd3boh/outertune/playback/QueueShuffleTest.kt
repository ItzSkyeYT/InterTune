/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order a queue plays in once the board has been asked for a list (QueueBoard.addQueue, whose
 * steps are on MultiQueueObject so that they can be held here: the board needs a live
 * MusicService).
 *
 * Issue #42: with shuffle on, a song tapped in a playlist started the playlist in its own order
 * from that song. QueueStart now asks for the list shuffled with the picked song first
 * (QueueStartTest); this holds what the queue makes of that ask, and of the others.
 */
class QueueShuffleTest {

    private fun songs(vararg ids: String) = ids.map { MediaMetadata(id = it, title = it, artists = emptyList(), duration = 180, genre = null) }

    /** A queue of [ids] on its song at [pos], in their order, as the board makes a new one. */
    private fun queue(vararg ids: String, pos: Int = 0) =
        MultiQueueObject(1, "Playlist", songs(*ids).onEachIndexed { i, s -> s.shuffleIndex = i }.toMutableList(), queuePos = pos, index = 0)

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }
    private fun MultiQueueObject.playing() = getCurrentSong()?.id

    /** Every song once, numbered 0 until the size in play order, with no gap and no number twice. */
    private fun assertWhole(q: MultiQueueObject, ids: List<String>) {
        assertEquals(ids.sorted(), q.playOrder().sorted())
        assertEquals(ids.indices.toList(), q.queue.map { it.shuffleIndex }.sorted())
    }

    private val eight = arrayOf("A", "B", "C", "D", "E", "F", "G", "H")

    @Test
    fun `a song picked while shuffle is on plays first, and the others are shuffled after it`() {
        val q = queue(*eight)
        var inListOrder = 0
        // The order is random: the picked song is first every time, and what follows it is not
        // simply the list from there on.
        repeat(200) {
            q.refill(songs(*eight), startIndex = 3, shuffled = true, startFirst = true)
            assertTrue(q.shuffled)
            assertEquals("D", q.playOrder().first())
            assertEquals("D", q.playing())
            assertEquals(0, q.getQueuePosShuffled())
            assertWhole(q, eight.toList())
            if (q.playOrder() == listOf("D", "E", "F", "G", "H", "A", "B", "C")) inListOrder++
        }
        assertTrue("the rest came in the list's order $inListOrder times of 200", inListOrder < 5)
    }

    @Test
    fun `the picked song first, in a given order`() {
        // A..E with D playing; the shuffle gives A 2, B 0, C 4, D 1, E 3. B, which drew the first
        // place, and D change places.
        val q = queue("A", "B", "C", "D", "E", pos = 3)
        q.shufflePlayOrder(keepCurrent = true, order = listOf(2, 0, 4, 1, 3))

        assertEquals(listOf("D", "B", "A", "E", "C"), q.playOrder())
        assertEquals("D", q.playing())
        // The stored order does not move: only the play order is shuffled.
        assertEquals(listOf("A", "B", "C", "D", "E"), q.queue.map { it.id })
    }

    @Test
    fun `a song picked while shuffle is off plays the list in its order, also where the queue was shuffled before`() {
        // The playlist played shuffled, then one of its songs tapped with shuffle off: the queue of
        // its title is refilled. It used to keep its shuffled flag, so it played in the list's
        // order with shuffle showing on.
        val q = queue(*eight)
        q.shufflePlayOrder(keepCurrent = false)
        assertTrue(q.shuffled)

        q.refill(songs(*eight), startIndex = 4, shuffled = false)

        assertFalse(q.shuffled)
        assertEquals(eight.toList(), q.playOrder())
        assertEquals("E", q.playing())
        assertEquals(4, q.getQueuePosShuffled())
    }

    @Test
    fun `a list's Shuffle button starts on any song, from its start`() {
        val q = queue(*eight)
        q.lastSongPos = 95_000
        val firsts = HashSet<String>()
        repeat(200) {
            q.refill(songs(*eight), startIndex = 0, shuffled = true)
            assertTrue(q.shuffled)
            // Whichever song comes first is the one playing.
            assertEquals(q.playOrder().first(), q.playing())
            assertEquals(C.TIME_UNSET, q.lastSongPos)
            assertWhole(q, eight.toList())
            firsts += q.playing()!!
        }
        assertTrue("only ever began on $firsts", firsts.size > 3)
    }

    @Test
    fun `shuffle pressed while a song plays keeps it playing, first`() {
        // The board's own shuffle, which now runs these same steps.
        val q = queue(*eight, pos = 5)
        q.lastSongPos = 42_000
        q.shufflePlayOrder(keepCurrent = true)

        assertEquals("F", q.playing())
        assertEquals("F", q.playOrder().first())
        assertEquals(42_000L, q.lastSongPos)
        assertWhole(q, eight.toList())
    }

    @Test
    fun `a position past the end is repaired before the shuffle, not read`() {
        val q = queue("A", "B", "C", pos = 7)
        q.shufflePlayOrder(keepCurrent = true, order = listOf(1, 2, 0))

        assertTrue(q.shuffled)
        assertEquals("A", q.playing())
        assertEquals("A", q.playOrder().first())
    }
}
