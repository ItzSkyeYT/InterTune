/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which saved queue stays current when one is deleted. */
class MasterIndexAfterDeleteTest {

    @Test
    fun `deleting a queue below the playing one leaves it current`() {
        // Five queues, the third playing, the fifth deleted.
        assertEquals(2, masterIndexAfterDelete(deleted = 4, current = 2, sizeAfter = 4))
    }

    @Test
    fun `deleting a queue above the playing one follows it up`() {
        assertEquals(1, masterIndexAfterDelete(deleted = 0, current = 2, sizeAfter = 4))
    }

    @Test
    fun `deleting the playing queue lands on the one before it`() {
        assertEquals(1, masterIndexAfterDelete(deleted = 2, current = 2, sizeAfter = 4))
    }

    @Test
    fun `deleting the playing queue when it was first lands on the new first`() {
        assertEquals(0, masterIndexAfterDelete(deleted = 0, current = 0, sizeAfter = 3))
    }

    @Test
    fun `nothing is current once nothing is left`() {
        assertEquals(-1, masterIndexAfterDelete(deleted = 0, current = 0, sizeAfter = 0))
    }

    @Test
    fun `the oldest queue making room for a new one`() {
        // The pool is full and the oldest goes; the playing queue is the newest.
        assertEquals(17, masterIndexAfterDelete(deleted = 0, current = 18, sizeAfter = 18))
    }

    @Test
    fun `no current queue stays none`() {
        assertEquals(-1, masterIndexAfterDelete(deleted = 1, current = -1, sizeAfter = 3))
    }
}
