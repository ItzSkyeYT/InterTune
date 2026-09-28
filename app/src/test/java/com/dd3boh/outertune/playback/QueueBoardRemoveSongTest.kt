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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing a song from a shuffled queue must never turn shuffle off or move the current song,
 * as long as the current song itself was not the one removed. A stale, out-of-range queuePos
 * used to reach validateQueuePos, which reset shuffle to false and renamed the current song.
 *
 * Exercises MultiQueueObject.removeAtPlayIndex directly: the same method QueueBoard.removeSong
 * calls, not a copy of its logic. QueueBoard itself needs a live MusicService to construct and
 * cannot be unit tested directly, the same constraint MultiQueuePositionTest works under.
 */
class QueueBoardRemoveSongTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    @Test
    fun `swiping a different song out keeps the current one current and shuffle on`() {
        // Stored order A,B,C,D,E,X (X appended last by a Play next while B played, and X has
        // since become current), shuffled. Play order is B,X,D,E,A,C: B=0, X=1, D=2, E=3, A=4, C=5.
        val a = song("A").apply { shuffleIndex = 4 }
        val b = song("B").apply { shuffleIndex = 0 }
        val c = song("C").apply { shuffleIndex = 5 }
        val d = song("D").apply { shuffleIndex = 2 }
        val e = song("E").apply { shuffleIndex = 3 }
        val x = song("X").apply { shuffleIndex = 1 }
        val queue = mutableListOf(a, b, c, d, e, x)
        // X is current, at its stored index (5) and its play-order index (1).
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = true, queuePos = 5, index = 0)

        // Swipe C out of the queue sheet (play-order index 5); the player is still at X (play-order 1).
        val removed = item.removeAtPlayIndex(index = 5, currentPlayIndex = 1)

        assertTrue("a song should have been removed", removed)
        assertTrue("shuffle must stay on", item.shuffled)
        assertEquals("X should still be the current song", "X", item.queue[item.queuePos].id)
        assertEquals("at its new stored index", 4, item.queuePos)
        assertEquals(listOf("B", "X", "D", "E", "A"), item.getCurrentQueueShuffled().map { it.id })
    }

    @Test
    fun `removing the current song itself in a shuffled queue picks the next one by play order`() {
        // Stored order A,B,C,D, shuffled. Play order is C,A,D,B: C=0, A=1, D=2, B=3. A is current,
        // with two songs after it, so the song that follows it is not the last one.
        val a = song("A").apply { shuffleIndex = 1 }
        val b = song("B").apply { shuffleIndex = 3 }
        val c = song("C").apply { shuffleIndex = 0 }
        val d = song("D").apply { shuffleIndex = 2 }
        val queue = mutableListOf(a, b, c, d)
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = true, queuePos = 0, index = 0)
        assertEquals("A", item.queue[item.queuePos].id)

        // The player is at A's own play-order index (1) when A is swiped away.
        val removed = item.removeAtPlayIndex(index = 1, currentPlayIndex = 1)

        assertTrue("a song should have been removed", removed)
        assertTrue("shuffle must stay on", item.shuffled)
        assertEquals("D followed A, so D is current", "D", item.queue[item.queuePos].id)
        assertEquals(listOf("C", "D", "B"), item.getCurrentQueueShuffled().map { it.id })
    }

    @Test
    fun `removing the current song when it is stored last keeps shuffle on`() {
        // Stored order A,B,X, shuffled. Play order is B,X,A: B=0, X=1, A=2. X is current and
        // stored last, so its stored index is past the end once the list shrinks.
        val a = song("A").apply { shuffleIndex = 2 }
        val b = song("B").apply { shuffleIndex = 0 }
        val x = song("X").apply { shuffleIndex = 1 }
        val queue = mutableListOf(a, b, x)
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = true, queuePos = 2, index = 0)

        val removed = item.removeAtPlayIndex(index = 1, currentPlayIndex = 1)

        assertTrue("a song should have been removed", removed)
        assertTrue("shuffle must stay on", item.shuffled)
        assertEquals("A followed X, so A is current", "A", item.queue[item.queuePos].id)
        assertEquals(listOf("B", "A"), item.getCurrentQueueShuffled().map { it.id })
    }

    @Test
    fun `an unshuffled removal before the current song shifts its stored index down by one`() {
        val queue = mutableListOf(song("A"), song("B"), song("C"))
        // C is current, at stored (and play-order) index 2.
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = false, queuePos = 2, index = 0)

        val removed = item.removeAtPlayIndex(index = 0, currentPlayIndex = 2)

        assertTrue(removed)
        assertFalse(item.shuffled)
        assertEquals("C", item.queue[item.queuePos].id)
        assertEquals(1, item.queuePos)
    }

    @Test
    fun `an unshuffled removal of the current song picks the next one by play order`() {
        val queue = mutableListOf(song("A"), song("B"), song("C"), song("D"))
        // B is current.
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = false, queuePos = 1, index = 0)

        val removed = item.removeAtPlayIndex(index = 1, currentPlayIndex = 1)

        assertTrue(removed)
        assertEquals("C followed B, so C is current", "C", item.queue[item.queuePos].id)
        assertEquals(1, item.queuePos)
    }

    @Test
    fun `an index that names no song removes nothing`() {
        val queue = mutableListOf(song("A").apply { shuffleIndex = 0 })
        val item = MultiQueueObject(id = 1, title = "Q", queue = queue, shuffled = true, queuePos = 0, index = 0)

        val removed = item.removeAtPlayIndex(index = 5, currentPlayIndex = 0)

        assertFalse(removed)
        assertEquals(1, item.queue.size)
        assertTrue("shuffle must stay on", item.shuffled)
        assertEquals(0, item.queuePos)

        val unshuffled = MultiQueueObject(
            id = 2, title = "Q", queue = mutableListOf(song("A"), song("B")), shuffled = false, queuePos = 0, index = 0,
        )
        assertFalse(unshuffled.removeAtPlayIndex(index = 2, currentPlayIndex = 0))
        assertEquals(2, unshuffled.queue.size)
    }
}
