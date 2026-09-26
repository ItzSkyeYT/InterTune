/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A position from some other list, such as the one Android Auto handed the player, must never be
 * stored in a queue it does not fit: stored, it threw the shuffle away later, or crashed a shuffle.
 */
class MultiQueuePositionTest {

    private fun song(i: Int) = MediaMetadata(id = "s$i", title = "Song $i", artists = emptyList(), duration = 180, genre = null)

    private fun queue(size: Int, pos: Int, shuffled: Boolean = false): MultiQueueObject {
        val songs = MutableList(size) { song(it).also { s -> s.shuffleIndex = it } }
        if (shuffled) {
            // Play order is the stored order reversed.
            songs.forEachIndexed { i, s -> s.shuffleIndex = size - 1 - i }
        }
        return MultiQueueObject(id = 1, title = "Q", queue = songs, shuffled = shuffled, queuePos = pos, index = 0)
    }

    @Test
    fun `a position past the end is left out`() {
        val q = queue(size = 5, pos = 2)
        q.setCurrentQueuePos(40)
        assertEquals(2, q.queuePos)
        q.setCurrentQueuePos(-1)
        assertEquals(2, q.queuePos)
    }

    @Test
    fun `a shuffled queue keeps its shuffle and its place when handed a position it does not have`() {
        val q = queue(size = 5, pos = 1, shuffled = true)
        q.setCurrentQueuePos(12)
        assertTrue(q.shuffled)
        assertEquals(1, q.queuePos)
        // Nothing thrown away: the play order is still the reversed one.
        assertEquals(listOf("s4", "s3", "s2", "s1", "s0"), q.getCurrentQueueShuffled().map { it.id })
    }

    @Test
    fun `a position that fits still moves the queue, through the play order when shuffled`() {
        val plain = queue(size = 5, pos = 0)
        plain.setCurrentQueuePos(3)
        assertEquals(3, plain.queuePos)

        val shuffled = queue(size = 5, pos = 0, shuffled = true)
        // Play position 1 is the second song heard, stored at index 3.
        shuffled.setCurrentQueuePos(1)
        assertEquals(3, shuffled.queuePos)
        assertEquals(1, shuffled.getQueuePosShuffled())
    }
}
