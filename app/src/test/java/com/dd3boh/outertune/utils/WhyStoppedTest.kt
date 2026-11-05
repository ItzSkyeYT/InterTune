/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.WhyStopped.Cause
import com.dd3boh.outertune.utils.WhyStopped.Kind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Search, radios, mixes and Home stopped loading for people who were never told why (Discord
 * digest of 2 Nov 2026): a sign-in that lapsed for a week, a radio of one song in Colombia, a VPN
 * in Russia that left InterTune out. What the app receives in each case decides what it says, and
 * it says something only when the cause is clear.
 *
 * The 401 below is YouTube's own answer, word for word, to a signed-in request whose cookie no
 * longer proves the account it names (experiments/features/why-stopped/probe/answers-20261102.txt),
 * turned into the exception the app's client throws (expectSuccess, as in InnerTube.kt).
 */
class WhyStoppedTest {
    private val unauthenticated = """{ "error": { "code": 401, "message": "Request is missing required authentication credential. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.", "errors": [ { "message": "Request is missing required authentication credential. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.", "domain": "global", "reason": "unauthorized" } ], "status": "UNAUTHENTICATED" } }"""

    /** What the app's client throws when YouTube answers [status] with [body] to a radio's start. */
    private fun answered(status: HttpStatusCode, body: String = """{"error":{"code":${status.value}}}"""): Throwable {
        val client = HttpClient(MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        return runCatching {
            runBlocking { client.post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false") }
        }.exceptionOrNull() ?: error("no failure for $status")
    }

    private val cookie = "SID=made-up; HSID=made-up; SAPISID=madeUp/madeUp; __Secure-3PAPISID=madeUp/madeUp"

    @Before
    fun clean() = YouTubeTrouble.reset()

    @Test
    fun `YouTube refusing the account a signed-in request names is a sign-in that no longer works`() {
        val failure = answered(HttpStatusCode.Unauthorized, unauthenticated)
        assertEquals(Kind.SIGN_IN_REFUSED, WhyStopped.kindOf(failure))
        assertEquals(Cause.SIGN_IN, WhyStopped.cause(WhyStopped.kindOf(failure), signedIn = true, online = true, regionClosed = false))
        // Its words, wherever the status went: some other code that still says UNAUTHENTICATED.
        assertEquals(Kind.SIGN_IN_REFUSED, WhyStopped.kindOf(answered(HttpStatusCode.Forbidden, unauthenticated)))
    }

    @Test
    fun `signed out, a refusal says nothing about a sign-in, and stays as it is`() {
        val kind = WhyStopped.kindOf(answered(HttpStatusCode.Unauthorized, unauthenticated))
        assertNull(WhyStopped.cause(kind, signedIn = false, online = true, regionClosed = false))
        // Where the catalogue is closed it is that note's to say.
        assertEquals(Cause.REGION, WhyStopped.cause(kind, signedIn = false, online = true, regionClosed = true))
    }

    @Test
    fun `any other answer from YouTube is not a cause the app can name, unless the country is closed`() {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound,
            HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError, HttpStatusCode.ServiceUnavailable)) {
            val kind = WhyStopped.kindOf(answered(status))
            assertEquals("$status", Kind.OTHER, kind)
            assertNull("$status", WhyStopped.cause(kind, signedIn = true, online = true, regionClosed = false))
            assertEquals("$status", Cause.REGION, WhyStopped.cause(kind, signedIn = true, online = true, regionClosed = true))
        }
        assertNull(WhyStopped.cause(null, signedIn = true, online = true, regionClosed = true))
    }

    @Test
    fun `no answer at all, on a phone with a network, is a connection that does not reach YouTube`() {
        val noAnswer = listOf(
            UnknownHostException("Unable to resolve host \"music.youtube.com\": No address associated with hostname"),
            ConnectException("Failed to connect to music.youtube.com/IPv4"),
            ConnectTimeoutException("Connect timeout has expired [url=https://music.youtube.com/youtubei/v1/search, connect_timeout=15000 ms]"),
            SocketTimeoutException("Socket timeout has expired [url=https://music.youtube.com/youtubei/v1/next, socket_timeout=20000] ms"),
            HttpRequestTimeoutException("https://music.youtube.com/youtubei/v1/browse", 30_000L),
            SocketException("Connection reset"),
            // GH #33: a VPN's kill switch with InterTune left out of it.
            SocketException("Binding socket to network 1040 failed: EPERM (Operation not permitted)"),
            NoRouteToHostException("No route to host"),
            SSLHandshakeException("Connection reset by peer"),
        )
        for (failure in noAnswer) {
            assertEquals("$failure", Kind.NO_ANSWER, WhyStopped.kindOf(failure))
            assertEquals("$failure", Cause.UNREACHABLE, WhyStopped.cause(Kind.NO_ANSWER, signedIn = true, online = true, regionClosed = false))
        }
        // However deep the app wrapped it.
        assertEquals(Kind.NO_ANSWER, WhyStopped.kindOf(IllegalStateException("radio", RuntimeException(ConnectException("refused")))))
        // Unreachable outranks a closed country: nothing was asked of YouTube at all.
        assertEquals(Cause.UNREACHABLE, WhyStopped.cause(Kind.NO_ANSWER, signedIn = false, online = true, regionClosed = true))
    }

    @Test
    fun `offline is offline, which the pages already say, so no cause is named`() {
        assertNull(WhyStopped.cause(Kind.NO_ANSWER, signedIn = true, online = false, regionClosed = false))
        assertNull(WhyStopped.cause(Kind.NO_ANSWER, signedIn = true, online = false, regionClosed = true))
    }

    @Test
    fun `the app's own failures say nothing about YouTube`() {
        for (failure in listOf(NullPointerException(), IllegalStateException("parse"), kotlinx.coroutines.CancellationException("left"),
            java.io.IOException("unexpected end of stream"), java.io.EOFException())) {
            assertEquals("$failure", Kind.OTHER, WhyStopped.kindOf(failure))
        }
    }

    @Test
    fun `signed in means a cookie with SAPISID, as the Account page reads it`() {
        assertTrue(WhyStopped.signedIn(cookie))
        assertFalse(WhyStopped.signedIn(null))
        assertFalse(WhyStopped.signedIn(""))
        assertFalse(WhyStopped.signedIn("VISITOR_INFO1_LIVE=abc; YSC=def"))
    }

    @Test
    fun `the app-wide state keeps a clear failure until something says otherwise`() {
        YouTubeTrouble.failed(answered(HttpStatusCode.Unauthorized, unauthenticated))
        assertEquals(Kind.SIGN_IN_REFUSED, YouTubeTrouble.kind.value)
        // A visitor's answer says nothing of the sign-in.
        YouTubeTrouble.answered(asAccount = false)
        assertEquals(Kind.SIGN_IN_REFUSED, YouTubeTrouble.kind.value)
        // Nor does another network.
        YouTubeTrouble.networkChanged()
        assertEquals(Kind.SIGN_IN_REFUSED, YouTubeTrouble.kind.value)
        // An answer to the account does.
        YouTubeTrouble.answered(asAccount = true)
        assertNull(YouTubeTrouble.kind.value)

        YouTubeTrouble.failed(answered(HttpStatusCode.Unauthorized, unauthenticated))
        // Signing in again, or out.
        YouTubeTrouble.signInChanged()
        assertNull(YouTubeTrouble.kind.value)
    }

    @Test
    fun `a connection that did not reach YouTube is forgotten on any answer or another network`() {
        YouTubeTrouble.failed(ConnectTimeoutException("Connect timeout has expired"))
        assertEquals(Kind.NO_ANSWER, YouTubeTrouble.kind.value)
        YouTubeTrouble.signInChanged()
        assertEquals(Kind.NO_ANSWER, YouTubeTrouble.kind.value)
        YouTubeTrouble.answered(asAccount = false)
        assertNull(YouTubeTrouble.kind.value)

        YouTubeTrouble.failed(UnknownHostException("music.youtube.com"))
        YouTubeTrouble.networkChanged()
        assertNull(YouTubeTrouble.kind.value)

        // An answer YouTube gave, even a refusal of another kind, is an answer.
        YouTubeTrouble.failed(SocketException("Connection reset"))
        YouTubeTrouble.failed(answered(HttpStatusCode.InternalServerError))
        assertNull(YouTubeTrouble.kind.value)
    }

    @Test
    fun `a failure with no clear cause leaves the state as it was`() {
        YouTubeTrouble.failed(answered(HttpStatusCode.Unauthorized, unauthenticated))
        YouTubeTrouble.failed(NullPointerException())
        YouTubeTrouble.failed(kotlinx.coroutines.CancellationException("left the page"))
        assertEquals(Kind.SIGN_IN_REFUSED, YouTubeTrouble.kind.value)
    }
}
