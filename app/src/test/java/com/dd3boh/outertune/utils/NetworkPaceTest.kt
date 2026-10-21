/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** How long a network is taken for slow, with the clock given here. */
class NetworkPaceTest {
    private val minute = 60_000_000_000L

    @Before
    fun fresh() {
        RouteTimes.reset()
        NetworkPace.forget()
    }

    @After
    fun leaveNothingBehind() = NetworkPace.forget()

    @Test
    fun `a network that keeps answering slowly stays slow past its five minutes`() {
        // /player taking 6 to 9 s every time: found slow, and every answer since as slow. It used
        // to lapse five minutes after it was found, and the next walk cut its first clients at 5 s
        // again, about every other song.
        NetworkPace.foundSlow(now = 0)
        for (m in 1..9) NetworkPace.answered(7_000, now = m * minute)
        assertTrue(NetworkPace.isSlow(now = 10 * minute))
        assertFalse("five minutes after the last slow answer", NetworkPace.isSlow(now = 14 * minute + 1))
    }

    @Test
    fun `an answer past 3 s takes the network for slow, a quick one ends it, and one in between changes nothing`() {
        NetworkPace.answered(4_000, now = 0)
        assertTrue(NetworkPace.isSlow(now = minute))
        NetworkPace.answered(200, now = 2 * minute)
        assertFalse(NetworkPace.isSlow(now = 2 * minute + 1))
        NetworkPace.foundSlow(now = 3 * minute)
        NetworkPace.answered(2_000, now = 4 * minute)
        assertTrue(NetworkPace.isSlow(now = 7 * minute))
        assertFalse(NetworkPace.isSlow(now = 8 * minute + 1))
    }
}
