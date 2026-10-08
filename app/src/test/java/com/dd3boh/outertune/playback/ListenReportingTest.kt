/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.dd3boh.outertune.playback.ListenReporting.AccountStep
import com.dd3boh.outertune.playback.ListenReporting.AddressFrom
import com.zionhuang.innertube.models.Context
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    // Whose /player request gives the address: the account's first, the visitor's when that gives none.

    private val accountThenVisitor = listOf(AddressFrom.ACCOUNT, AddressFrom.VISITOR)
    private val visitorOnly = listOf(AddressFrom.VISITOR)

    @Test
    fun `the address is asked for as the account first when signed in and playback may use the account`() {
        assertEquals(accountThenVisitor, ListenReporting.addressRequests(loggedIn = true, authMode = PlaybackAuthMode.WHEN_REFUSED))
        assertEquals(accountThenVisitor, ListenReporting.addressRequests(loggedIn = true, authMode = PlaybackAuthMode.ALWAYS))
    }

    @Test
    fun `nothing is asked as the account when signed out or when playback as the account is set to never`() {
        assertEquals(visitorOnly, ListenReporting.addressRequests(loggedIn = true, authMode = PlaybackAuthMode.NEVER))
        PlaybackAuthMode.entries.forEach { mode ->
            assertEquals(visitorOnly, ListenReporting.addressRequests(loggedIn = false, authMode = mode))
        }
    }

    @Test
    fun `the visitor's request is the last one in every combination, so a play is never worse off`() {
        for (loggedIn in listOf(true, false)) PlaybackAuthMode.entries.forEach { mode ->
            val requests = ListenReporting.addressRequests(loggedIn, mode)
            val combination = "signed in $loggedIn, $mode"
            assertEquals(combination, AddressFrom.VISITOR, requests.last())
            assertEquals("$combination: a request is made twice", requests.distinct(), requests)
            assertEquals(combination, loggedIn && mode != PlaybackAuthMode.NEVER, AddressFrom.ACCOUNT in requests)
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
    fun `signed out there is one request, as before`() {
        val asked = mutableListOf<AddressFrom>()
        assertEquals(AddressFrom.VISITOR to address, ListenReporting.firstAddress(visitorOnly) { asked += it; address })
        assertNull(ListenReporting.firstAddress(visitorOnly) { asked += it; null })
        assertEquals(listOf(AddressFrom.VISITOR, AddressFrom.VISITOR), asked)
    }

    // The account's request waits for a script and for a WebView, so it is given so long and no longer.

    @Test
    fun `an answer that comes in time is the answer, and a request that fails is a failure and not a wait`(): Unit = runBlocking {
        assertEquals(Result.success(address), ListenReporting.answerWithin(5_000) { address })
        assertEquals(Result.success(address), ListenReporting.answerWithin(5_000) { delay(50); address })
        val failed = ListenReporting.answerWithin<String>(5_000) { throw IOException("refused") }
        assertTrue("a failure was taken for running out of time", failed?.exceptionOrNull() is IOException)
    }

    @Test
    fun `the account's request that has not answered in time gives way to the visitor's`(): Unit = runBlocking {
        // A thread parked in a call that does not return, which is what a WebView that never calls
        // back looks like from here, and a wait that is merely long.
        val hangs = listOf<suspend () -> String>(
            { Thread.sleep(5_000); "https://late" },
            { delay(5_000); "https://late" },
        )
        for (hang in hangs) {
            val asked = mutableListOf<AddressFrom>()
            val started = System.nanoTime()
            val found = ListenReporting.firstAddress(accountThenVisitor) { from ->
                asked += from
                when (from) {
                    AddressFrom.ACCOUNT -> ListenReporting.answerWithin(150) { hang() }?.getOrNull()
                    AddressFrom.VISITOR -> address
                }
            }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(AddressFrom.VISITOR to address, found)
            assertEquals(accountThenVisitor, asked)
            assertTrue("the play waited $tookMs ms for a request that was given 150", tookMs < 2_500)
        }
    }

    @Test
    fun `a request out of time is no longer waited for, and is not stopped`(): Unit = runBlocking {
        // What it was fetching, the player's script and the WebView that makes tokens, is kept for
        // the next play, which then answers in time.
        val finished = CompletableDeferred<String>()
        assertNull(ListenReporting.answerWithin(100) { delay(600); finished.complete("fetched"); "late" })
        assertFalse(finished.isCompleted)
        assertEquals("fetched", withTimeout(5_000) { finished.await() })
    }

    @Test
    fun `the limit is a few seconds`() {
        assertTrue(ListenReporting.ACCOUNT_ADDRESS_LIMIT_MS in 3_000L..10_000L)
    }

    @Test
    fun `the answer to the report says whose request the address came from`() {
        assertEquals("Remote history: YouTube answered 204 (address from the account's request)", ListenReporting.historyAnswerLine(204, AddressFrom.ACCOUNT))
        assertEquals("Remote history: YouTube answered 204 (address from the visitor's request)", ListenReporting.historyAnswerLine(204, AddressFrom.VISITOR))
        assertEquals(
            "Remote history: YouTube answered 403, the play was refused (address from the account's request)",
            ListenReporting.historyAnswerLine(403, AddressFrom.ACCOUNT),
        )
        // When the account was not asked the line is the one it was.
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
    fun `the log says what the account's request had to be asked without`() {
        // Without the po token this client answers UNPLAYABLE and no address, so the log has to
        // tell that apart from a song YouTube will not play.
        assertEquals(
            "Remote history: no address from the account's request (answered UNPLAYABLE, asked without a po token), the visitor's is used",
            ListenReporting.addressSourceLine(AddressFrom.VISITOR, "UNPLAYABLE", null, without = setOf(AccountStep.PO_TOKEN)),
        )
        assertEquals(
            "Remote history: address from the account's request (answered OK, asked without a signature timestamp)",
            ListenReporting.addressSourceLine(AddressFrom.ACCOUNT, "OK", null, without = setOf(AccountStep.SIGNATURE_TIMESTAMP)),
        )
        assertEquals(
            "Remote history: no address from the account's request (answered UNPLAYABLE, asked without a signature timestamp or a po token)" +
                " nor from the visitor's",
            ListenReporting.addressSourceLine(null, "UNPLAYABLE", null, without = setOf(AccountStep.PO_TOKEN, AccountStep.SIGNATURE_TIMESTAMP)),
        )
        assertEquals(
            "Remote history: no address from the account's request (answered HTTP 400, asked without a po token), the visitor's is used",
            ListenReporting.addressSourceLine(
                AddressFrom.VISITOR, null, IllegalStateException("Bad response for $address"), httpStatus = 400, without = setOf(AccountStep.PO_TOKEN),
            ),
        )
    }

    @Test
    fun `the log says what the account's request was waiting for when its time ran out`() {
        assertEquals(
            "Remote history: no address from the account's request (no answer within 8 s, waiting for the po token), the visitor's is used",
            ListenReporting.addressSourceLine(AddressFrom.VISITOR, null, null, outOfTimeAt = AccountStep.PO_TOKEN, limitMs = 8_000),
        )
        assertEquals(
            "Remote history: no address from the account's request (no answer within 8 s, waiting for the signature timestamp), the visitor's is used",
            ListenReporting.addressSourceLine(AddressFrom.VISITOR, null, null, outOfTimeAt = AccountStep.SIGNATURE_TIMESTAMP, limitMs = 8_000),
        )
        assertEquals(
            "Remote history: no address from the account's request (no answer within 2.5 s, waiting for YouTube's answer) nor from the visitor's",
            ListenReporting.addressSourceLine(null, null, null, outOfTimeAt = AccountStep.ANSWER, limitMs = 2_500),
        )
    }

    // "Check the history address" in developer options: both requests, and what each came to.

    @Test
    fun `the check says of each request what it answered, whether an address came and how long it took`() {
        assertEquals(
            "The account's request (WEB_REMIX): answered OK, address present, 1.4 s (the signature timestamp 0.6 s, the po token 0.7 s, YouTube's answer 0.1 s)",
            ListenReporting.addressCheckLine(
                AddressFrom.ACCOUNT, "WEB_REMIX", ListenReporting.requestAnswer("OK", null), hasAddress = true, tookMs = 1_420,
                steps = linkedMapOf(AccountStep.SIGNATURE_TIMESTAMP to 610L, AccountStep.PO_TOKEN to 700L, AccountStep.ANSWER to 110L),
            ),
        )
        assertEquals(
            "The visitor's request (VISIONOS): answered OK, address present, 0.3 s",
            ListenReporting.addressCheckLine(AddressFrom.VISITOR, "VISIONOS", ListenReporting.requestAnswer("OK", null), hasAddress = true, tookMs = 312),
        )
        assertEquals(
            "The account's request (WEB_REMIX): answered UNPLAYABLE, asked without a po token, no address, 2.1 s, YouTube says: The page needs to be reloaded.",
            ListenReporting.addressCheckLine(
                AddressFrom.ACCOUNT, "WEB_REMIX", ListenReporting.requestAnswer("UNPLAYABLE", null, without = setOf(AccountStep.PO_TOKEN)),
                hasAddress = false, tookMs = 2_149, reason = "The page needs to be reloaded.",
            ),
        )
        assertEquals(
            "The account's request (WEB_REMIX): no answer within 8 s, waiting for the po token, no address, 8.0 s (the signature timestamp 1.2 s)",
            ListenReporting.addressCheckLine(
                AddressFrom.ACCOUNT, "WEB_REMIX",
                ListenReporting.requestAnswer(null, null, outOfTimeAt = AccountStep.PO_TOKEN, limitMs = 8_000),
                hasAddress = false, tookMs = 8_004, steps = mapOf(AccountStep.SIGNATURE_TIMESTAMP to 1_200L),
            ),
        )
    }

    @Test
    fun `the check repeats YouTube's reason as plain words only, and no failure's words at all`() {
        // A reason is a sentence for a person. Should one ever quote an address, it is not passed on.
        val line = ListenReporting.addressCheckLine(
            AddressFrom.VISITOR, "VISIONOS", ListenReporting.requestAnswer("ERROR", null), hasAddress = false, tookMs = 90,
            reason = "Bad request for $address\n<b>cookie</b>: SAPISID=Zz99Yy88; " + "x".repeat(300),
        )
        assertTrue(line.startsWith("The visitor's request (VISIONOS): answered ERROR, no address, 0.1 s, YouTube says: Bad request for "))
        listOf("?", "&", "=", "://", "<", ">", "\n", ";").forEach { assertFalse("the line carries $it", it in line) }
        assertTrue("the reason is repeated at its whole length", line.length < 260)

        val timeout = SocketTimeoutException("Request timeout has expired [url=$address, cookie=SAPISID=Zz99Yy88]")
        val failed = ListenReporting.addressCheckLine(AddressFrom.VISITOR, "VISIONOS", ListenReporting.requestAnswer(null, timeout), hasAddress = false, tookMs = 30_000)
        assertEquals("The visitor's request (VISIONOS): no answer, timed out, SocketTimeoutException, no address, 30.0 s", failed)
        (identifiers + "SAPISID" + "Zz99Yy88" + "cookie").forEach { assertFalse("the line carries $it", it in failed) }
    }

    @Test
    fun `the check says why the account's request is not made, as a play would not make it either`() {
        assertEquals(
            "The account's request: not made, nobody is signed in",
            ListenReporting.accountNotAskedLine(loggedIn = false, authMode = PlaybackAuthMode.WHEN_REFUSED),
        )
        assertEquals(
            "The account's request: not made, nobody is signed in",
            ListenReporting.accountNotAskedLine(loggedIn = false, authMode = PlaybackAuthMode.NEVER),
        )
        assertEquals(
            "The account's request: not made, playback as the account is set to never",
            ListenReporting.accountNotAskedLine(loggedIn = true, authMode = PlaybackAuthMode.NEVER),
        )
        // It says nothing exactly when a play asks as the account.
        for (loggedIn in listOf(true, false)) PlaybackAuthMode.entries.forEach { mode ->
            assertEquals(
                AddressFrom.ACCOUNT !in ListenReporting.addressRequests(loggedIn, mode),
                ListenReporting.accountNotAskedLine(loggedIn, mode) != null,
            )
        }
    }

    @Test
    fun `the check ends by saying what a play would do, and never says reported when the service would not report`() {
        fun verdict(found: AddressFrom?, loggedIn: Boolean = true, historyPaused: Boolean = false, remotePaused: Boolean = false, throttled: Boolean = false) =
            ListenReporting.checkVerdictLine(found, loggedIn, historyPaused, remotePaused, throttled)

        assertEquals("A play is reported to the address from the account's request. Nothing was reported now.", verdict(AddressFrom.ACCOUNT))
        assertEquals("A play is reported to the address from the visitor's request. Nothing was reported now.", verdict(AddressFrom.VISITOR))
        assertEquals("A play would not be reported: neither request gave an address.", verdict(null))
        assertEquals(
            "A play is not reported: sharing listen history with YouTube Music is paused. It would go to the address from the account's request." +
                " Nothing was reported.",
            verdict(AddressFrom.ACCOUNT, remotePaused = true),
        )
        // Pause listen history keeps a play from being counted at all, so it is never reported either.
        assertEquals(
            "A play is not reported: listen history is paused. It would go to the address from the account's request. Nothing was reported.",
            verdict(AddressFrom.ACCOUNT, historyPaused = true, remotePaused = true),
        )
        assertEquals(
            "A play is not reported: listen history is paused. It would go to the address from the visitor's request. Nothing was reported.",
            verdict(AddressFrom.VISITOR, historyPaused = true),
        )
        assertEquals(
            "A play is not reported: YouTube is refusing this network for now. It would go to the address from the visitor's request." +
                " Nothing was reported.",
            verdict(AddressFrom.VISITOR, throttled = true),
        )
        // Signed out there is no account for a play to go to, whatever the visitor's request gave.
        assertEquals("A play is not reported: nobody is signed in. Nothing was reported.", verdict(AddressFrom.VISITOR, loggedIn = false))
        assertEquals("A play is not reported: nobody is signed in. Nothing was reported.", verdict(null, loggedIn = false, remotePaused = true))

        for (loggedIn in listOf(true, false)) for (history in listOf(true, false)) for (remote in listOf(true, false)) for (throttled in listOf(true, false)) {
            // What MusicService.onPlaybackStatsReady asks before it reports a counted play.
            val reports = !history &&
                ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = loggedIn, remoteHistoryPaused = remote, throttled = throttled)
            val line = verdict(AddressFrom.ACCOUNT, loggedIn, history, remote, throttled)
            assertEquals("signed in $loggedIn, history paused $history, sharing paused $remote, throttled $throttled: $line", !reports, line.startsWith("A play is not reported: "))
        }
    }

    @Test
    fun `the client that asks as the account is the one known to be answered, and it names the channel`() {
        val locale = YouTubeLocale(gl = "US", hl = "en")
        fun YouTubeClient.onBehalfOf(): String? {
            val context = toContext(locale, visitorData = "visitor", dataSyncId = "channel-1", hlOverride = "en")
            return Json { explicitNulls = false }.encodeToJsonElement(Context.serializer(), context)
                .jsonObject["user"]?.jsonObject?.get("onBehalfOfUser")?.toString()
        }

        val client = ListenReporting.ACCOUNT_ADDRESS_CLIENT
        // The one AsterTune asks, and before it Metrolist: nobody reports a play with another.
        assertEquals("WEB_REMIX", client.clientName)
        // InnerTube.ytClient sends the cookie only for a client that says it takes one.
        assertTrue(client.loginSupported)
        assertEquals("\"channel-1\"", client.onBehalfOf())
        // InnerTube.player passes the signature timestamp and the po token on only for a client
        // that says it uses them, and without the token this one answers UNPLAYABLE and no address.
        assertTrue(client.useSignatureTimestamp)
        assertTrue(client.useWebPoTokens)
        assertFalse(client.isEmbedded)

        assertFalse(YouTubeClient.VISIONOS.loginSupported)
        assertNull(YouTubeClient.VISIONOS.onBehalfOf())
    }

    /** [text] without its comments, so that a word in a sentence is not taken for a call. */
    private fun code(text: String): String = text.lines()
        .filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
        .joinToString("\n")

    @Test
    fun `the account's request goes with the timestamp and the token, within the limit, and tells the throttle nothing`() {
        val utils = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        val start = utils.indexOf("suspend fun playerResponseAsAccount(")
        assertTrue("the account's request was not found", start >= 0)
        val body = code(utils.substring(start, utils.indexOf("\n    /**", start)))
        assertTrue("ListenReporting.answerWithin(limitMs)" in body)
        assertTrue("limitMs: Long = ListenReporting.ACCOUNT_ADDRESS_LIMIT_MS" in body)
        assertTrue("getSignatureTimestampOrNull(" in body)
        assertTrue("getWebClientPoTokenOrNull(" in body)
        assertTrue("client = ListenReporting.ACCOUNT_ADDRESS_CLIENT" in body)
        assertTrue("signatureTimestamp = " in body)
        assertTrue("webPlayerPot = " in body)
        // Over the address family the stream chain found to work, and nothing of it remembered.
        assertTrue("playerOverBestFamily(remember = false)" in body)
        // One client's refusal is not YouTube's: the visitor's request tells the throttle what it always has.
        assertFalse("noteThrottle" in body)
        assertFalse("Throttle." in body)
    }

    @Test
    fun `the service takes the address through the choice for everybody, with no switch left to read`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val start = service.indexOf("Trying to register remote history")
        val block = service.substring(start, service.indexOf("override fun onRepeatModeChanged", start))
        assertTrue("ListenReporting.addressRequests(" in block)
        assertTrue("ListenReporting.firstAddress(" in block)
        assertTrue("YTPlayerUtils.playerResponseAsAccount(" in block)
        assertTrue("YTPlayerUtils.playerResponseForMetadata(" in block)
        assertTrue("addressSourceLine(" in block)

        // It was a trial behind Unreleased.HISTORY_AS_ACCOUNT. Nothing reads that any more.
        val readers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "HISTORY_AS_ACCOUNT" in it.readText() }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), readers)
    }

    @Test
    fun `the check in developer options makes both requests and reports nothing`() {
        val file = File("src/main/java/com/dd3boh/outertune/playback/HistoryAddressCheck.kt")
        assertTrue("the check was not found", file.isFile)
        val check = code(file.readText())
        assertTrue("YTPlayerUtils.playerResponseAsAccount(" in check)
        assertTrue("YTPlayerUtils.playerResponseForMetadata(" in check)
        assertTrue("ListenReporting.addressCheckLine(" in check)
        // It asks for the address and stops there: no play is reported by looking.
        assertFalse("registerPlayback" in check)
        assertFalse("playbackTracking" in check)

        // One row, in debug builds, and it reports nothing either.
        val rows = code(File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/DeveloperFrag.kt").readText())
        assertTrue("HistoryAddressCheck.run(" in rows)
        assertTrue("if (BuildConfig.DEBUG)" in rows)
        assertFalse("registerPlayback" in rows)
    }
}
