/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ListenReportingTest {

    @Test
    fun `paused listen history sends no now playing`() {
        assertFalse(ListenReporting.sendsNowPlaying(listenHistoryPaused = true))
        assertTrue(ListenReporting.sendsNowPlaying(listenHistoryPaused = false))
    }

    @Test
    fun `the YouTube history ping needs someone signed in`() {
        assertTrue(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = false, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = false, remoteHistoryPaused = false, throttled = false))
    }

    @Test
    fun `the YouTube history ping keeps its other conditions`() {
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = true, loggedIn = true, remoteHistoryPaused = false, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = true, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = false, throttled = true))
    }

    // What YouTube answered to a play reported to its history, in the log and nowhere else.

    /** The shape of the address a play is reported to, with made-up values. */
    private val address = "https://s.youtube.com/api/stats/playback?cl=993313800&docid=dQw4w9WgXcQ&ei=AbCdEfGh" +
        "&fexp=1,2,3&ns=yt&plid=AAZZ11&el=detailpage&len=97&of=Qq11&vm=CAEQ"

    private val identifiers = listOf("993313800", "dQw4w9WgXcQ", "AbCdEfGh", "AAZZ11", "Qq11", "CAEQ", "docid", "plid", "http", "?", "&", "=")

    @Test
    fun `an answer is logged as its status, and a refusal says so`() {
        assertEquals("Remote history: YouTube answered 204", ListenReporting.historyAnswerLine(204))
        assertEquals("Remote history: YouTube answered 200", ListenReporting.historyAnswerLine(200))
        assertEquals("Remote history: YouTube answered 403, the play was refused", ListenReporting.historyAnswerLine(403))
        assertEquals("Remote history: YouTube answered 429, the play was refused", ListenReporting.historyAnswerLine(429))
        assertEquals("Remote history: YouTube answered 500, the play was refused", ListenReporting.historyAnswerLine(500))
        assertTrue(ListenReporting.historyReportTaken(204))
        assertFalse(ListenReporting.historyReportTaken(302))
        assertFalse(ListenReporting.historyReportTaken(403))
    }

    @Test
    fun `a report with no answer is logged by its kind, never by its own words`() {
        // What a timeout from the HTTP client says: the whole address, parameters and all.
        val timeout = IOException("Request timeout has expired [url=$address&ver=2&c=WEB_REMIX&cpn=Zz99Yy88Xx77Ww66, request_timeout=30000 ms]")
        val line = ListenReporting.historyFailureLine(timeout)
        assertEquals("Remote history: the report got no answer (network error, IOException)", line)
        (identifiers + "Zz99Yy88Xx77Ww66" + "cpn").forEach { assertFalse("the line carries $it", it in line) }

        assertEquals(
            "Remote history: the report got no answer (no connection, UnknownHostException)",
            ListenReporting.historyFailureLine(UnknownHostException("Unable to resolve host \"music.youtube.com\"")),
        )
        assertEquals(
            "Remote history: the report got no answer (no connection, ConnectException)",
            ListenReporting.historyFailureLine(ConnectException("Failed to connect to $address")),
        )
        assertEquals(
            "Remote history: the report got no answer (timed out, SocketTimeoutException)",
            ListenReporting.historyFailureLine(SocketTimeoutException("timeout")),
        )
        assertEquals(
            "Remote history: the report got no answer (failed, IllegalStateException)",
            ListenReporting.historyFailureLine(IllegalStateException("Bad response for $address")),
        )
    }

    @Test
    fun `the address a play is reported to is logged as its host and a count`() {
        val line = ListenReporting.trackingAddressForLog(address)
        assertEquals("s.youtube.com, parameters: 10", line)
        identifiers.forEach { assertFalse("the line carries $it", it in line) }
        assertEquals("none", ListenReporting.trackingAddressForLog(null))
        assertEquals("music.youtube.com, parameters: 0", ListenReporting.trackingAddressForLog("https://music.youtube.com/api/stats/playback"))
        assertEquals("s.youtube.com, parameters: 1", ListenReporting.trackingAddressForLog("https://user:secret@s.youtube.com:443/p?docid=abc#frag&x=1"))
        // Not an address at all: nothing of it is repeated.
        assertEquals("no host, parameters: 0", ListenReporting.trackingAddressForLog("docid-dQw4w9WgXcQ"))
        assertEquals("no host, parameters: 1", ListenReporting.trackingAddressForLog("?docid=dQw4w9WgXcQ"))
    }

    @Test
    fun `the service logs the report through these and never the address or the failure itself`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val start = service.indexOf("Trying to register remote history")
        assertTrue("the history report was not found", start >= 0)
        val block = service.substring(start, service.indexOf("override fun onRepeatModeChanged", start))
        assertTrue("the report itself was not found", "YouTube.registerPlayback(" in block)
        assertTrue("historyAnswerLine(" in block)
        assertTrue("historyFailureLine(" in block)
        // Each of these put the address in the log: the url itself, and a stack trace whose message quotes it.
        val logged = block.lines().filter { "Log." in it || "reportException" in it || "printStackTrace" in it }
        logged.forEach { line ->
            assertFalse("the failure itself is logged: $line", "reportException" in line || "printStackTrace" in line)
            assertFalse("the failure itself is logged: $line", Regex("""Log\.\w\([^)]*,\s*it\s*\)""").containsMatchIn(line))
            if ("playbackUrl" in line) assertTrue("the address is logged as it is: $line", "trackingAddressForLog(playbackUrl)" in line)
        }
    }
}
