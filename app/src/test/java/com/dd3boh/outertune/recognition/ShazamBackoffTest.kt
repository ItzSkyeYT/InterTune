/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * How long Keep listening leaves Shazam alone once it stops answering: see [ShazamBackoff].
 *
 * Twelve second windows throughout, the default, and requests that take a second to come back,
 * which is about what a refusal takes. Times are in milliseconds from an arbitrary start.
 */
class ShazamBackoffTest {

    private val window = 12_000L

    @Test
    fun `the pause doubles from twelve seconds and then stays at two minutes`() {
        assertEquals(
            listOf(12, 24, 48, 96, 120, 120, 120),
            (1..7).map { (ShazamBackoff.pauseMs(it, null) / 1000).toInt() },
        )
    }

    @Test
    fun `a Retry-After is what Shazam asked for, however many failures came before it`() {
        assertEquals(30_000L, ShazamBackoff.pauseMs(1, 30_000L))
        assertEquals(5_000L, ShazamBackoff.pauseMs(6, 5_000L))
        // Longer than the longest pause is still honoured, up to ten minutes.
        assertEquals(300_000L, ShazamBackoff.pauseMs(1, 300_000L))
        assertEquals(600_000L, ShazamBackoff.pauseMs(1, 86_400_000L))
    }

    @Test
    fun `nothing is sent until the first window to end once the pause is over`() {
        val backoff = ShazamBackoff()
        // The window that ended at 100 s failed a second later, so nothing before 113 s. The window
        // ending at 112 s is a second too soon; the one ending at 124 s goes.
        backoff.failed(nowMs = 101_000, endMs = 100_000, windowMs = window)
        assertEquals(124_000L, backoff.retryAtMs)
        assertFalse(backoff.allows(112_000, window))
        assertTrue(backoff.allows(124_000, window))
        // Each window is timed from the clock as it is cut, so the one meant can end a little early.
        assertTrue(backoff.allows(123_990, window))
    }

    @Test
    fun `Retry-After is waited out to the window, and no further`() {
        val backoff = ShazamBackoff()
        backoff.failed(nowMs = 101_000, endMs = 100_000, windowMs = window, retryAfterMs = 30_000)
        assertEquals(136_000L, backoff.retryAtMs)
        assertFalse(backoff.allows(124_000, window))
        assertTrue(backoff.allows(136_000, window))

        // Asked to wait no time at all, the next window goes as usual.
        val eager = ShazamBackoff()
        eager.failed(nowMs = 100_500, endMs = 100_000, windowMs = window, retryAfterMs = 0)
        assertTrue(eager.allows(112_000, window))
    }

    @Test
    fun `a window heard while the failing request hung is not sent the moment it gives up`() {
        val backoff = ShazamBackoff()
        // Twenty seconds for the request about the window ending at 100 s: the one ending at 112 s
        // was recorded meanwhile and is waiting when the failure comes back at 120 s.
        backoff.failed(nowMs = 120_000, endMs = 100_000, windowMs = window, retryAfterMs = 1_000)
        assertFalse(backoff.allows(112_000, window))
        assertTrue(backoff.allows(124_000, window))
    }

    @Test
    fun `each failure in a row waits longer, and the first answer ends it`() {
        val backoff = ShazamBackoff()
        val sent = mutableListOf<Long>()
        // Twelve minutes of windows, every request refused a second after its window ended.
        for (k in 1..60) {
            val end = k * window
            if (!backoff.allows(end, window)) continue
            sent += end / 1000
            backoff.failed(nowMs = end + 1_000, endMs = end, windowMs = window)
        }
        // Waits of 23, 35, 59, 107 and then 131 s between a failure and the next request: each
        // pause, rounded up to the end of the window that is being recorded when it runs out.
        assertEquals(listOf(12L, 36L, 72L, 132L, 240L, 372L, 504L, 636L), sent)
        assertEquals(8, backoff.failures)

        backoff.answered()
        assertEquals(0, backoff.failures)
        assertTrue(backoff.allows(732_000, window))
        assertTrue(backoff.allows(744_000, window))
    }

    @Test
    fun `the listener hears about it from the third failure in a row`() {
        val backoff = ShazamBackoff()
        backoff.failed(nowMs = 13_000, endMs = 12_000, windowMs = window)
        assertNull(backoff.notice)
        backoff.failed(nowMs = 37_000, endMs = 36_000, windowMs = window)
        assertNull(backoff.notice)
        backoff.failed(nowMs = 73_000, endMs = 72_000, windowMs = window)
        assertEquals(132_000L, backoff.notice)
        backoff.answered()
        assertNull(backoff.notice)
    }

    @Test
    fun `a clock put back does not stretch the pause into hours`() {
        val backoff = ShazamBackoff()
        backoff.failed(nowMs = 101_000, endMs = 100_000, windowMs = window)
        // An hour back: counted on the new clock, the pause would have an hour still to run.
        assertTrue(backoff.allows(112_000 - 3_600_000, window))
        // A second or two back, which the clock does by itself now and then, changes nothing much.
        assertFalse(backoff.allows(110_000, window))
        assertTrue(backoff.allows(122_000, window))
    }

    @Test
    fun `only a request Shazam did not answer counts`() {
        assertTrue(ShazamBackoff.asked("http 429"))
        assertTrue(ShazamBackoff.asked("http 503"))
        assertTrue(ShazamBackoff.asked("network"))
        // A network that wants a login first answers with its own page, which is not JSON.
        assertTrue(ShazamBackoff.asked("parse"))
        // Nothing was asked.
        assertFalse(ShazamBackoff.asked("silence"))
        assertFalse(ShazamBackoff.asked("fingerprint"))
    }

    @Test
    fun `Retry-After is read as seconds or as a date`() {
        val now = Instant.parse("2015-10-21T07:27:15Z").toEpochMilli()
        assertEquals(120_000L, ShazamBackoff.retryAfterMs("120", now))
        assertEquals(7_000L, ShazamBackoff.retryAfterMs(" 7 ", now))
        assertEquals(0L, ShazamBackoff.retryAfterMs("0", now))
        assertEquals(45_000L, ShazamBackoff.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", now))
        // Already past is no wait at all.
        assertEquals(0L, ShazamBackoff.retryAfterMs("Wed, 21 Oct 2015 07:00:00 GMT", now))
        // Absurdly long is cut down before it can overflow anything.
        assertEquals(600_000L, ShazamBackoff.retryAfterMs("99999999999", now))
        assertNull(ShazamBackoff.retryAfterMs(null, now))
        assertNull(ShazamBackoff.retryAfterMs("", now))
        assertNull(ShazamBackoff.retryAfterMs("-3", now))
        assertNull(ShazamBackoff.retryAfterMs("soon", now))
    }

    @Test
    fun `the countdown rounds up and stops at zero`() {
        assertEquals(40, ShazamBackoff.secondsLeft(140_000, 100_000))
        assertEquals(40, ShazamBackoff.secondsLeft(139_001, 100_000))
        assertEquals(1, ShazamBackoff.secondsLeft(100_001, 100_000))
        assertEquals(0, ShazamBackoff.secondsLeft(100_000, 100_000))
        assertEquals(0, ShazamBackoff.secondsLeft(95_000, 100_000))
    }
}
