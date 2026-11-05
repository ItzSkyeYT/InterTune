/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where playback goes from a blocked artist's song (BlockedSkip), and that it never goes round. */
class BlockedSkipTest {

    /** A queue in its own order, repeat all when [loop], with the blocked places in [blocked]. */
    private fun target(count: Int, from: Int, blocked: Set<Int>, back: Boolean = false, loop: Boolean = false): Int? {
        val forward = { i: Int -> if (i + 1 < count) i + 1 else if (loop) 0 else -1 }
        val backward = { i: Int -> if (i > 0) i - 1 else if (loop) count - 1 else -1 }
        return BlockedSkip.target(from, count, forward, backward, { it in blocked }, back)
    }

    @Test
    fun `it goes on to the next song that is not blocked, past a run of blocked ones`() {
        assertEquals(1, target(count = 5, from = 0, blocked = setOf(0)))
        assertEquals(3, target(count = 5, from = 0, blocked = setOf(0, 1, 2)))
    }

    @Test
    fun `reached by going back, it keeps going back, and goes on only when nothing is behind`() {
        assertEquals(1, target(count = 5, from = 2, blocked = setOf(2), back = true))
        assertEquals(1, target(count = 5, from = 0, blocked = setOf(0), back = true))
        assertEquals(4, target(count = 5, from = 2, blocked = setOf(0, 1, 2, 3), back = true))
    }

    @Test
    fun `with nothing after it that plays, the music stops on it`() {
        assertNull(target(count = 3, from = 1, blocked = setOf(1, 2)))
        assertNull(target(count = 1, from = 0, blocked = setOf(0)))
        // Played forward, a song before it is no answer to the queue running out.
        assertNull(target(count = 3, from = 2, blocked = setOf(2)))
    }

    @Test
    fun `on repeat all it comes round to the start, and a queue all blocked stops instead of going round`() {
        assertEquals(0, target(count = 3, from = 2, blocked = setOf(2), loop = true))
        assertNull(target(count = 3, from = 1, blocked = setOf(0, 1, 2), loop = true))
        assertNull(target(count = 3, from = 1, blocked = setOf(0, 1, 2), back = true, loop = true))
    }

    @Test
    fun `a player whose steps never end is stopped after one look at every song`() {
        var asked = 0
        val result = BlockedSkip.target(0, count = 4, forward = { asked++; (it + 1) % 7 + 1 }, backward = { -1 }, blocked = { true })
        assertNull(result)
        assertEquals(4, asked)
    }
}
