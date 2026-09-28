/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.compose.runtime.mutableStateListOf
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * saveAllQueues writes the list of queues on IO while the player's thread goes on changing it.
 * bubbleUp moves a queue to the top, and a shuffle followed by loading the queue does that twice
 * in a row, so the save's walk over the live list threw ConcurrentModificationException and closed
 * the app. The save is handed a copy of the list, taken on the player's thread.
 *
 * Exercises queuesToSave, the copy saveAllQueues hands over. QueueBoard itself needs a live
 * MusicService to construct and cannot be unit tested directly. The walk and the change are
 * interleaved on one thread here, which makes the collision certain rather than a matter of timing.
 */
class QueueBoardSaveAllQueuesTest {

    private fun queue(id: Long) =
        MultiQueueObject(id = id, title = "Q$id", queue = mutableListOf(), queuePos = 0, index = (id - 1).toInt())

    @Test
    fun `moving a queue to the top while the save walks the list leaves the save's list alone`() {
        val a = queue(1)
        val b = queue(2)
        val c = queue(3)
        val masterQueues = mutableStateListOf(a, b, c)

        val saved = queuesToSave(masterQueues)
        // The save has started walking its list on IO...
        val walk = saved.iterator()
        assertSame(a, walk.next())

        // ...when bubbleUp moves A to the top on the player's thread.
        masterQueues.remove(a)
        masterQueues.add(a)

        // Handed the live list, this is where the walk threw.
        assertSame(b, walk.next())
        assertSame(c, walk.next())
        assertFalse(walk.hasNext())
        assertEquals(listOf(b, c, a), masterQueues.toList())
    }

    @Test
    fun `the save keeps the queues themselves, so their positions are read when it runs`() {
        val a = queue(1)
        val saved = queuesToSave(mutableStateListOf(a))

        // A song change after the save was asked for.
        a.queuePos = 7
        a.lastSongPos = 42_000L

        assertSame(a, saved.single())
        assertEquals(7, saved.single().queuePos)
        assertEquals(42_000L, saved.single().lastSongPos)
    }
}
