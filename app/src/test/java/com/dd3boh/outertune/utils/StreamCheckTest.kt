/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
    fun `a missing or broken visitorData is replaced by the one a player answer carried`() {
        // Synthetic, in the shape YouTube issues them: visitor id TESTVISITOR, region FR.
        val offered = "CgtURVNUVklTSVRPUiiA98TVBjIECgJGUg%3D%3D"
        // Issue #17: sw.js_data failed at every launch, so there was none at all.
        assertEquals(offered, StreamCheck.visitorDataToAdopt(null, offered))
        // And what a failed sign-in capture or an old bug leaves behind.
        for (broken in listOf("", "   ", "null", "undefined")) {
            assertEquals(broken, offered, StreamCheck.visitorDataToAdopt(broken, offered))
        }
    }

    @Test
    fun `a visitorData the app already has is kept`() {
        val offered = "CgtURVNUVklTSVRPUiiA98TVBjIECgJGUg%3D%3D"
        assertNull(StreamCheck.visitorDataToAdopt("CgtLRVBUVklTSVRPUiiA98TVBjIECgJGUg%3D%3D", offered))
        assertNull(StreamCheck.visitorDataToAdopt("CgswS0VQVFZJU0lUUiiA98TVBjIECgJGUg%3D%3D", offered))
    }

    @Test
    fun `nothing is adopted that does not look like a visitorData`() {
        for (offered in listOf(null, "", "undefined", "null", "hello")) {
            assertNull(offered, StreamCheck.visitorDataToAdopt(null, offered))
        }
    }

    @Test
    fun `a refused VISIONOS earns one try with a new visitorData`() {
        assertTrue(StreamCheck.mayRetryWithNewVisitor(visionosRefused = true, msSinceFailedSwap = null))
        // Refused for some other reason (a song that is not available, the network down): no.
        assertFalse(StreamCheck.mayRetryWithNewVisitor(visionosRefused = false, msSinceFailedSwap = null))
    }

    @Test
    fun `after a failed try, not again until the wait is over`() {
        val wait = StreamCheck.NEW_VISITOR_RETRY_MS
        assertFalse(StreamCheck.mayRetryWithNewVisitor(true, 0))
        assertFalse(StreamCheck.mayRetryWithNewVisitor(true, wait - 1))
        assertTrue(StreamCheck.mayRetryWithNewVisitor(true, wait))
        assertFalse(StreamCheck.mayRetryWithNewVisitor(false, wait * 3))
    }

    @Test
    fun `trail steps say what each client answered and what its url got`() {
        assertEquals("ANDROID_VR LOGIN_REQUIRED", StreamCheck.trailStep("ANDROID_VR", "LOGIN_REQUIRED", null, checked = false))
        assertEquals("VISIONOS OK, HEAD 200", StreamCheck.trailStep("VISIONOS", "OK", 200, checked = true))
        assertEquals("IOS OK, HEAD 403", StreamCheck.trailStep("IOS", "OK", 403, checked = true))
        assertEquals("IOS OK, HEAD failed", StreamCheck.trailStep("IOS", "OK", null, checked = true))
        assertEquals("ANDROID (account) no answer", StreamCheck.trailStep("ANDROID (account)", null, null, checked = false))
    }

    @Test
    fun `a fallback client's explanation wins over the last client's dropped connection`() {
        // Five Hours (4f963e5f4): VISIONOS and IOS both answered UNPLAYABLE, then ANDROID's /player
        // call failed.
        val explained = PlayerResponse.PlayabilityStatus(status = "UNPLAYABLE", reason = "This video is not available")
        val dropped = IOException("Software caused connection abort")
        val failure = StreamCheck.resolveOnceFailure(explained, dropped)
        assertEquals(StreamCheck.ChainFailure.Explained("This video is not available"), failure)
    }

    @Test
    fun `a dropped connection with nothing explained still reaches MusicService as itself`() {
        val dropped = IOException("Software caused connection abort")
        val failure = StreamCheck.resolveOnceFailure(null, dropped)
        assertTrue(failure is StreamCheck.ChainFailure.LastFailure)
        assertSame(dropped, (failure as StreamCheck.ChainFailure.LastFailure).cause)
    }

    @Test
    fun `nothing explained and nothing failed is the generic unknown response`() {
        assertEquals(StreamCheck.ChainFailure.Unknown, StreamCheck.resolveOnceFailure(null, null))
    }

    @Test
    fun `a status without a reason is still explained by its bare status`() {
        val explained = PlayerResponse.PlayabilityStatus(status = "ERROR", reason = null)
        val failure = StreamCheck.resolveOnceFailure(explained, IOException("dropped"))
        assertEquals(StreamCheck.ChainFailure.Explained("ERROR"), failure)
    }
}
