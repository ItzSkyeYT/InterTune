/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/** The figure that closed the app on a phone (issue #21): never outside 0 to 100, whatever the player says. */
class BufferedPercentTest {
    @Test
    fun `an ordinary song is the share loaded`() {
        assertEquals(0, SessionPlayer.bufferedPercent(0, 200_000))
        assertEquals(50, SessionPlayer.bufferedPercent(100_000, 200_000))
        assertEquals(100, SessionPlayer.bufferedPercent(200_000, 200_000))
    }

    @Test
    fun `a buffered position far before the start, as on the phone that crashed, is nothing loaded`() {
        // -4,249,598,940,685 percent was what media3 tried to make an int of.
        assertEquals(0, SessionPlayer.bufferedPercent(-42_495_989_406_85L / 100 * 3, 3))
        assertEquals(0, SessionPlayer.bufferedPercent(Long.MIN_VALUE + 1, 1))
    }

    @Test
    fun `one past the end, or no length, or unknowns, stay in range`() {
        assertEquals(100, SessionPlayer.bufferedPercent(Long.MAX_VALUE, 200_000))
        assertEquals(100, SessionPlayer.bufferedPercent(5_000, 0))
        assertEquals(100, SessionPlayer.bufferedPercent(5_000, -1))
        assertEquals(0, SessionPlayer.bufferedPercent(C.TIME_UNSET, 200_000))
        assertEquals(0, SessionPlayer.bufferedPercent(5_000, C.TIME_UNSET))
    }
}
