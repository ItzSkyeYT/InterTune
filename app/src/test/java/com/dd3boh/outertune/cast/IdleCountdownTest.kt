/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a cast left with nothing playing is ended, and when it is not. */
class IdleCountdownTest {
    /** A timer that only runs what it holds when told to. */
    private class Timer {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = delayMs to action
            pending += entry
            return { pending.remove(entry) }
        }

        fun fire() {
            val (_, action) = pending.removeAt(0)
            action()
        }
    }

    private val timer = Timer()
    private var ended = 0
    private val countdown = IdleCountdown(IdleCountdown.TIMEOUT_MS, timer::schedule) { ended++ }

    @Test
    fun `a pause is given ten minutes, then the cast ends`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        assertEquals(listOf(10L * 60 * 1000), timer.pending.map { it.first })
        timer.fire()
        assertEquals(1, ended)
    }

    @Test
    fun `reports while still paused neither restart the count nor add another`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        countdown.update(playWhenReady = false, playbackState = Player.STATE_BUFFERING)
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        assertEquals(1, timer.pending.size)
    }

    @Test
    fun `playing again before the time is up calls it off`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        countdown.update(playWhenReady = true, playbackState = Player.STATE_READY)
        assertTrue(timer.pending.isEmpty())
        assertEquals(0, ended)
    }

    @Test
    fun `a later pause gets the full ten minutes again`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        countdown.update(playWhenReady = true, playbackState = Player.STATE_READY)
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        assertEquals(listOf(10L * 60 * 1000), timer.pending.map { it.first })
    }

    @Test
    fun `the end of the queue counts as nothing playing, as does a stop after a failure`() {
        assertTrue(IdleCountdown.isIdle(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertTrue(IdleCountdown.isIdle(playWhenReady = true, playbackState = Player.STATE_IDLE))
        assertTrue(IdleCountdown.isIdle(playWhenReady = false, playbackState = Player.STATE_READY))
    }

    @Test
    fun `playing or loading the next song is not idle`() {
        assertFalse(IdleCountdown.isIdle(playWhenReady = true, playbackState = Player.STATE_READY))
        assertFalse(IdleCountdown.isIdle(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun `a cast ended some other way stops the count`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        countdown.stop()
        assertTrue(timer.pending.isEmpty())
        assertEquals(0, ended)
    }

    @Test
    fun `after it has fired, the next pause counts again`() {
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        timer.fire()
        countdown.update(playWhenReady = false, playbackState = Player.STATE_READY)
        assertEquals(1, timer.pending.size)
    }
}
