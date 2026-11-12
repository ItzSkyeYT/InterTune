/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The total under the queue's position. On the Pixel 5 on 5 Nov 2026 a radio the phone picked
 * itself said 18:08 for 15 songs: some had no length, each counted as minus one second.
 */
class QueueLengthTextTest {
    @Test
    fun `every length known gives the plain total`() {
        assertEquals("5:30", queueLengthText(listOf(150, 180)))
        assertEquals("1:00:00", queueLengthText(listOf(1800, 1800)))
    }

    @Test
    fun `a song without a length is left out and the total says there is more`() {
        assertEquals("5:30+", queueLengthText(listOf(150, -1, 180, 0)))
    }

    @Test
    fun `no length known at all says so rather than a zero`() {
        assertEquals("–:––", queueLengthText(listOf(-1, -1)))
    }

    @Test
    fun `an empty queue is still zero`() {
        assertEquals("0:00", queueLengthText(emptyList()))
    }
}
