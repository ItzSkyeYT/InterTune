/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.dd3boh.outertune.playback.ListenReporting.AddressFrom
import com.zionhuang.innertube.models.Context
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // Whose /player request gives the address, while Unreleased.HISTORY_AS_ACCOUNT is tried.

    private val accountThenVisitor = listOf(AddressFrom.ACCOUNT, AddressFrom.VISITOR)
    private val visitorOnly = listOf(AddressFrom.VISITOR)

    @Test
    fun `the address is asked for as the account first only when signed in and playback may use the account`() {
        assertEquals(accountThenVisitor, ListenReporting.addressRequests(asAccountTrial = true, loggedIn = true, authMode = PlaybackAuthMode.WHEN_REFUSED))
        assertEquals(accountThenVisitor, ListenReporting.addressRequests(asAccountTrial = true, loggedIn = true, authMode = PlaybackAuthMode.ALWAYS))
        assertEquals(visitorOnly, ListenReporting.addressRequests(asAccountTrial = true, loggedIn = true, authMode = PlaybackAuthMode.NEVER))
        PlaybackAuthMode.entries.forEach { mode ->
            assertEquals(visitorOnly, ListenReporting.addressRequests(asAccountTrial = true, loggedIn = false, authMode = mode))
        }
    }

    @Test
    fun `without the trial the address is the visitor's whatever else is set`() {
        for (loggedIn in listOf(true, false)) PlaybackAuthMode.entries.forEach { mode ->
            assertEquals(visitorOnly, ListenReporting.addressRequests(asAccountTrial = false, loggedIn = loggedIn, authMode = mode))
        }
    }

    @Test
    fun `the visitor is asked only when the account's request gave no address`() {
        val asked = mutableListOf<AddressFrom>()
        fun answers(account: String?, visitor: String?) = { from: AddressFrom ->
            asked += from
            if (from == AddressFrom.ACCOUNT) account else visitor
        }

        assertEquals(AddressFrom.ACCOUNT to address, ListenReporting.firstAddress(accountThenVisitor, answers(address, "https://visitor")))
        assertEquals("the visitor was asked although the account gave an address", listOf(AddressFrom.ACCOUNT), asked)

        // A failed request, an answer without an address, and an empty one all count as none.
        for (nothing in listOf(null, "", "  ")) {
            asked.clear()
            assertEquals(AddressFrom.VISITOR to address, ListenReporting.firstAddress(accountThenVisitor, answers(nothing, address)))
            assertEquals(accountThenVisitor, asked)
        }

        asked.clear()
        assertNull(ListenReporting.firstAddress(accountThenVisitor, answers(null, null)))
        assertEquals(accountThenVisitor, asked)
    }

    @Test
    fun `without the trial there is one request, as before`() {
        val asked = mutableListOf<AddressFrom>()
        assertEquals(AddressFrom.VISITOR to address, ListenReporting.firstAddress(visitorOnly) { asked += it; address })
        assertNull(ListenReporting.firstAddress(visitorOnly) { asked += it; null })
        assertEquals(listOf(AddressFrom.VISITOR, AddressFrom.VISITOR), asked)
    }

    @Test
    fun `the answer to the report says whose request the address came from`() {
        assertEquals("Remote history: YouTube answered 204 (address from the account's request)", ListenReporting.historyAnswerLine(204, AddressFrom.ACCOUNT))
        assertEquals("Remote history: YouTube answered 204 (address from the visitor's request)", ListenReporting.historyAnswerLine(204, AddressFrom.VISITOR))
        assertEquals(
            "Remote history: YouTube answered 403, the play was refused (address from the account's request)",
            ListenReporting.historyAnswerLine(403, AddressFrom.ACCOUNT),
        )
        // Without the trial the line is the one it was.
        assertEquals(ListenReporting.historyAnswerLine(204), ListenReporting.historyAnswerLine(204, null))
        assertEquals(ListenReporting.historyAnswerLine(403), ListenReporting.historyAnswerLine(403, null))
    }

    @Test
    fun `what the account's request answered is logged as its status or its kind of failure, never its words`() {
        assertEquals(
            "Remote history: address from the account's request (answered OK)",
            ListenReporting.addressSourceLine(AddressFrom.ACCOUNT, "OK", null),
        )
        assertEquals(
            "Remote history: no address from the account's request (answered LOGIN_REQUIRED), the visitor's is used",
            ListenReporting.addressSourceLine(AddressFrom.VISITOR, "LOGIN_REQUIRED", null),
        )
        assertEquals(
            "Remote history: no address from the account's request (answered UNPLAYABLE) nor from the visitor's",
            ListenReporting.addressSourceLine(null, "UNPLAYABLE", null),
        )
        assertEquals(
            "Remote history: no address from the account's request (answered without a status), the visitor's is used",
            ListenReporting.addressSourceLine(AddressFrom.VISITOR, null, null),
        )

        // A failure's message quotes the address, and a status is YouTube's to write: neither is repeated.
        val timeout = SocketTimeoutException("Request timeout has expired [url=$address, cookie=SAPISID=Zz99Yy88]")
        val failed = ListenReporting.addressSourceLine(AddressFrom.VISITOR, null, timeout)
        assertEquals("Remote history: no address from the account's request (no answer, timed out, SocketTimeoutException), the visitor's is used", failed)
        val odd = ListenReporting.addressSourceLine(AddressFrom.VISITOR, address, null)
        assertEquals("Remote history: no address from the account's request (answered with a status not known here), the visitor's is used", odd)
        // Turned away by its HTTP status: that is an answer, and it is the status that is logged.
        val refused = ListenReporting.addressSourceLine(AddressFrom.VISITOR, null, IllegalStateException("Bad response for $address"), httpStatus = 401)
        assertEquals("Remote history: no address from the account's request (answered HTTP 401), the visitor's is used", refused)
        listOf(failed, odd, refused).forEach { line ->
            (identifiers + "SAPISID" + "Zz99Yy88" + "cookie").forEach { assertFalse("the line carries $it", it in line) }
        }
    }

    @Test
    fun `the client that asks as the account puts the channel in the request and the visitor's cannot`() {
        val locale = YouTubeLocale(gl = "US", hl = "en")
        fun YouTubeClient.onBehalfOf(): String? {
            val context = toContext(locale, visitorData = "visitor", dataSyncId = "channel-1", hlOverride = "en")
            return Json { explicitNulls = false }.encodeToJsonElement(Context.serializer(), context)
                .jsonObject["user"]?.jsonObject?.get("onBehalfOfUser")?.toString()
        }

        val client = ListenReporting.ACCOUNT_ADDRESS_CLIENT
        // InnerTube.ytClient sends the cookie only for a client that says it takes one.
        assertTrue(client.loginSupported)
        assertEquals("\"channel-1\"", client.onBehalfOf())
        // Answers without a po token, and is not the embedded player that walks past an age gate.
        assertFalse(client.useWebPoTokens)
        assertFalse(client.isEmbedded)
        // YouTube is known to ignore the cookie on the Android client.
        assertFalse(client.clientName == YouTubeClient.ANDROID.clientName)

        assertFalse(YouTubeClient.VISIONOS.loginSupported)
        assertNull(YouTubeClient.VISIONOS.onBehalfOf())
    }

    @Test
    fun `the service takes the address through the choice, and the trial is read there and nowhere else`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val start = service.indexOf("Trying to register remote history")
        val block = service.substring(start, service.indexOf("override fun onRepeatModeChanged", start))
        assertTrue("ListenReporting.addressRequests(" in block)
        assertTrue("asAccountTrial = Unreleased.HISTORY_AS_ACCOUNT" in block)
        assertTrue("ListenReporting.firstAddress(" in block)
        assertTrue("addressSourceLine(" in block)

        // Read in code, that is: the comments that point at it do not count.
        val readers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { file ->
                file.readLines().map { it.trim() }
                    .any { "Unreleased.HISTORY_AS_ACCOUNT" in it && !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") }
            }
            .map { it.name }.toList()
        assertEquals(listOf("MusicService.kt"), readers)

        // Never in a release: off, or debug builds only like the other things held back.
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        assertTrue(Regex("""val HISTORY_AS_ACCOUNT = (false|BuildConfig\.DEBUG)\b""").containsMatchIn(flags))
    }
}
