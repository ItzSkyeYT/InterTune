/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android refusing to make the download service a foreground service is a line in the log, not a
 * crash.
 *
 * Issue #71: a queued download started while the app was in the background, media3 called
 * startForeground from the download manager's listener, Android refused with a
 * ForegroundServiceStartNotAllowedException, and nothing on the main looper caught it.
 */
class ForegroundPromotionTest {

    private val lines = mutableListOf<String>()
    private val promotion = ForegroundPromotion { lines += it }

    /** What startForeground throws for an app in the background (the subclass is an IllegalStateException). */
    private fun refused(): Nothing =
        throw IllegalStateException("Service.startForeground() not allowed due to mAllowStartForeground false: service dev.skye.intertune/com.dd3boh.outertune.playback.ExoDownloadService")

    @Test
    fun `the exception Android throws is the one that is caught`() {
        val android = Class.forName("android.app.ForegroundServiceStartNotAllowedException")
        assertTrue(IllegalStateException::class.java.isAssignableFrom(android))
    }

    @Test
    fun `a promotion that goes through is made once and logs nothing`() {
        var made = 0
        assertTrue(promotion.attempt { made++ })
        assertEquals(1, made)
        assertTrue(promotion.promoted)
        assertFalse(promotion.owed)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a refusal is one line in the log and a false`() {
        assertFalse(promotion.attempt { refused() })
        assertFalse("the service is not a foreground service", promotion.promoted)
        assertEquals(1, lines.size)
        assertTrue(lines[0], "background" in lines[0] && "IllegalStateException" in lines[0])
        assertTrue("one line", '\n' !in lines[0])
    }

    @Test
    fun `a run of refusals says it once`() {
        repeat(5) { promotion.attempt { refused() } }
        assertEquals("every download that changes asks again, the log does not fill up", 1, lines.size)
        assertTrue(promotion.owed)
    }

    @Test
    fun `only the refusal is caught`() {
        try {
            promotion.attempt { throw IllegalArgumentException("a bug") }
            fail("a bug in startForeground must still show")
        } catch (expected: IllegalArgumentException) {
            assertEquals("a bug", expected.message)
        }
        try {
            promotion.attempt { throw SecurityException("the permission is missing") }
            fail("a missing permission must still show")
        } catch (expected: SecurityException) {
            // thrown on
        }
        assertFalse("a bug is not a refusal", promotion.owed)
        assertFalse(promotion.promoted)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a refused promotion is owed until one goes through`() {
        assertFalse("nothing is owed at the start", promotion.owed)

        promotion.attempt { refused() }
        assertTrue(promotion.owed)

        // The app comes to the front, or a download changes: asked again.
        var made = 0
        assertTrue(promotion.attempt { made++ })
        assertEquals(1, made)
        assertTrue(promotion.promoted)
        assertFalse("made, so nothing is owed", promotion.owed)
    }

    @Test
    fun `the promotion that makes up for a refusal says so once`() {
        promotion.attempt { refused() }
        assertEquals("the refusal", 1, lines.size)
        promotion.attempt { }
        assertEquals("and the promotion that made up for it", 2, lines.size)
        promotion.attempt { }
        assertEquals("then it is as quiet as ever", 2, lines.size)
    }

    @Test
    fun `once the service is a foreground service nothing is asked again`() {
        var made = 0
        promotion.attempt { made++ }
        repeat(10) { assertTrue(promotion.attempt { made++ }) }
        assertEquals("startForeground is called once, the notification after that is notify", 1, made)
    }

    @Test
    fun `a service that was never refused owes nothing and is quiet`() {
        promotion.attempt { }
        assertFalse(promotion.owed)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `media3 puts the service in the foreground before Android 12 and the app from Android 12`() {
        // Android 12 is API 31, where ForegroundServiceStartNotAllowedException appeared.
        assertFalse(ForegroundPromotion.selfPromotes(24))
        assertFalse(ForegroundPromotion.selfPromotes(30))
        assertTrue(ForegroundPromotion.selfPromotes(31))
        assertTrue(ForegroundPromotion.selfPromotes(36))
        assertTrue(ForegroundPromotion.selfPromotes(37))
    }
}
