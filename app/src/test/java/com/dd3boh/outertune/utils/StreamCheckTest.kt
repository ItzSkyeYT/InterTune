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
import java.io.File
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

    // What the log may say about a stream url. Logcat is pasted whole into issues and chats.

    /** The shape of a stream url, with made-up values. The address is one set aside for examples. */
    private val streamUrl = "https://rr4---sn-25ge7nzr.googlevideo.com/videoplayback?expire=1759876543&ei=AbCdEfGhIjKlMnOp" +
        "&ip=203.0.113.57&id=o-AMadeUpStreamId&itag=251&source=youtube&requiressl=yes&mn=sn-25ge7nzr%2Csn-25glenl6" +
        "&gcr=fr&mime=audio%2Fwebm&clen=3433514&dur=212.061&lmt=1714871031251364&c=IOS&n=MadeUpNParam" +
        "&sparams=expire%2Cei%2Cip%2Cid%2Citag%2Csource&sig=AJfQdSswRQIhAMadeUpSignature" +
        "&lsparams=mn&lsig=AGluJ3MwRQIhAMadeUpLsig&pot=MadeUpPoToken"

    /** Whose address it is, what makes the url play, and anything that reads as a url at all. */
    private val neverLogged = listOf(
        "203.0.113.57", "AbCdEfGhIjKlMnOp", "o-AMadeUpStreamId", "MadeUpNParam", "AJfQdSswRQIhAMadeUpSignature",
        "AGluJ3MwRQIhAMadeUpLsig", "MadeUpPoToken", "sn-25glenl6", "http", "videoplayback", "?", "&", "=", "%",
    )

    @Test
    fun `a stream url is logged as its host, what the stream is and a count`() {
        val line = StreamCheck.urlForLog(streamUrl)
        assertEquals("rr4---sn-25ge7nzr.googlevideo.com, itag 251, mime audio/webm, expire 1759876543, parameters: 20", line)
        neverLogged.forEach { assertFalse("the line carries $it", it in line) }
    }

    @Test
    fun `the address a url was issued to is not logged, whichever family it is`() {
        // An IPv6 address the way the ip parameter writes one. 2001:db8:: is set aside for examples as well.
        val over6 = streamUrl.replace("ip=203.0.113.57", "ip=2001%3Adb8%3A85a3%3A%3A8a2e%3A370%3A7334")
        val line = StreamCheck.urlForLog(over6)
        assertEquals(StreamCheck.urlForLog(streamUrl), line)
        (neverLogged + listOf("db8", "85a3", "8a2e", "7334")).forEach { assertFalse("the line carries $it", it in line) }
    }

    @Test
    fun `a value that is not what its name says is left out`() {
        // Anything can be written after itag=, mime= and expire=. A number, a type and a number are
        // repeated, and nothing else gets into the log under their names.
        val odd = "https://rr4---sn-25ge7nzr.googlevideo.com/videoplayback" +
            "?itag=203.0.113.57&mime=text%2F203.0.113.57&expire=AJfQdSswRQIhAMadeUpSignature"
        assertEquals("rr4---sn-25ge7nzr.googlevideo.com, parameters: 3", StreamCheck.urlForLog(odd))
        // A url that says only some of the three is logged with those.
        assertEquals(
            "rr4---sn-25ge7nzr.googlevideo.com, itag 140, mime audio/mp4, parameters: 3",
            StreamCheck.urlForLog("https://rr4---sn-25ge7nzr.googlevideo.com/videoplayback?itag=140&mime=audio/mp4&sig=AJfQdSswRQIhAMadeUpSignature"),
        )
    }

    @Test
    fun `only the host is taken from the rest of a url, and nothing from what is not one`() {
        val host = "rr4---sn-25ge7nzr.googlevideo.com"
        assertEquals("$host, parameters: 0", StreamCheck.urlForLog("https://$host/videoplayback"))
        // Some stream urls write their parameters into the path. None of it is repeated.
        assertEquals(
            "$host, parameters: 0",
            StreamCheck.urlForLog("https://$host/videoplayback/expire/1759876543/ip/203.0.113.57/sig/AJfQdSswRQIhAMadeUpSignature"),
        )
        assertEquals("$host, itag 251, parameters: 1", StreamCheck.urlForLog("https://user:secret@$host:443/videoplayback?itag=251#ip=203.0.113.57&x=1"))
        // Not a url at all, or one with no name where the host goes: nothing of it is repeated.
        assertEquals("no host, parameters: 0", StreamCheck.urlForLog("ip-203.0.113.57"))
        assertEquals("no host, parameters: 1", StreamCheck.urlForLog("?ip=203.0.113.57"))
        assertEquals("no host, parameters: 0", StreamCheck.urlForLog("https://ip=203.0.113.57&sig=AJfQdSsw/videoplayback"))
    }

    @Test
    fun `the player logs the url it found through this`() {
        val player = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        assertTrue("the stream url line was not found", "stream url: \${StreamCheck.urlForLog(streamUrl)}" in player)
    }
}
