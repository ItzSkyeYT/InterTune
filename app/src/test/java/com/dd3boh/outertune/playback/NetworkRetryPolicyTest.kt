/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that decide how long a stalled song waits, and when the waiting stops being true. */
class NetworkRetryPolicyTest {

    @Test
    fun `the wait is bounded, so nothing is held for ever`() {
        assertTrue(NetworkRetryPolicy.shouldRetry(1))
        assertTrue(NetworkRetryPolicy.shouldRetry(NetworkRetryPolicy.MAX_ATTEMPTS))
        assertFalse(NetworkRetryPolicy.shouldRetry(NetworkRetryPolicy.MAX_ATTEMPTS + 1))
        assertFalse(NetworkRetryPolicy.shouldRetry(0))
        // A minute at the outside: long enough for a blip, short enough that a phone genuinely
        // offline is handed back to the connectivity observer while the listener is still there.
        assertTrue(NetworkRetryPolicy.totalWaitMillis() in 10_000..60_000)
    }

    @Test
    fun `the first try is quick and the ladder is capped`() {
        assertEquals(2_000L, NetworkRetryPolicy.delayMillis(1))
        assertEquals(4_000L, NetworkRetryPolicy.delayMillis(2))
        val delays = (1..NetworkRetryPolicy.MAX_ATTEMPTS).map { NetworkRetryPolicy.delayMillis(it) }
        assertEquals(delays.sorted(), delays)
        assertTrue(delays.all { it <= 20_000L })
    }

    @Test
    fun `an error leaves the player idle with playWhenReady still set, and that is what waiting means`() {
        // The state the bug lived in: a fatal error goes to idle without touching playWhenReady,
        // so the old rule of clearing on a pause never fired.
        assertTrue(NetworkRetryPolicy.stillWaiting(true, Player.STATE_IDLE, playWhenReady = true))
        assertTrue(NetworkRetryPolicy.stillWaiting(true, Player.STATE_BUFFERING, playWhenReady = true))
    }

    @Test
    fun `the wait ends when the music comes back, or when the listener pauses`() {
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_READY, playWhenReady = true))
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_ENDED, playWhenReady = true))
        assertFalse(NetworkRetryPolicy.stillWaiting(true, Player.STATE_IDLE, playWhenReady = false))
    }

    @Test
    fun `nothing is waiting when the flag was never set`() {
        assertFalse(NetworkRetryPolicy.stillWaiting(false, Player.STATE_IDLE, playWhenReady = true))
    }
}
