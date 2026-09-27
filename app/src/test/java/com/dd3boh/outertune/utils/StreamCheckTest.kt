/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCheckTest {
    @Test
    fun `a url that answered 2xx plays, wherever it is in the chain`() {
        for (isLast in listOf(false, true)) {
            assertTrue(StreamCheck.accept(200, isLast))
            assertTrue(StreamCheck.accept(206, isLast))
        }
    }

    @Test
    fun `a refused url is dropped, the last client's included`() {
        // The last client used to be taken unchecked, which is how a refused VISIONOS became
        // IOS's 403 on every song (issue #17).
        for (isLast in listOf(false, true)) {
            assertFalse(StreamCheck.accept(403, isLast))
            assertFalse(StreamCheck.accept(404, isLast))
            assertFalse(StreamCheck.accept(500, isLast))
        }
    }

    @Test
    fun `a check that could not be made moves on, unless nothing is left to try`() {
        assertFalse(StreamCheck.accept(null, isLast = false))
        assertTrue(StreamCheck.accept(null, isLast = true))
    }

    @Test
    fun `the refusal a client gave is the message, the status only without one`() {
        val bot = "Sign in to confirm you’re not a bot"
        assertEquals(bot, StreamCheck.refusalMessage(bot, 403))
        assertEquals("YouTube refused the stream (HTTP 403)", StreamCheck.refusalMessage(null, 403))
        assertEquals("YouTube refused the stream (HTTP 403)", StreamCheck.refusalMessage("  ", 403))
    }

    @Test
    fun `the message keeps what Throttle and the error screen look for`() {
        val message = StreamCheck.refusalMessage("Sign in to confirm you’re not a bot", 403)
        assertTrue(Throttle.looksLikeBlock(message))
    }

    @Test
    fun `trail steps say what each client answered and what its url got`() {
        assertEquals("ANDROID_VR LOGIN_REQUIRED", StreamCheck.trailStep("ANDROID_VR", "LOGIN_REQUIRED", null, checked = false))
        assertEquals("VISIONOS OK, HEAD 200", StreamCheck.trailStep("VISIONOS", "OK", 200, checked = true))
        assertEquals("IOS OK, HEAD 403", StreamCheck.trailStep("IOS", "OK", 403, checked = true))
        assertEquals("IOS OK, HEAD failed", StreamCheck.trailStep("IOS", "OK", null, checked = true))
        assertEquals("ANDROID (account) no answer", StreamCheck.trailStep("ANDROID (account)", null, null, checked = false))
    }
}
