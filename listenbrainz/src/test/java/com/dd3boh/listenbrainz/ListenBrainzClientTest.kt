/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.listenbrainz

import com.dd3boh.listenbrainz.models.Listen
import com.dd3boh.listenbrainz.models.TokenCheck
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * The ListenBrainz client against ListenBrainz's answers, with no network: the JSON each kind of
 * listen goes out as, the headers, what each answer becomes, the pace, and the batch rules.
 *
 * The answers marked "asked 18 Nov" are ListenBrainz's own, word for word, to a made-up token
 * (experiments/features/listenbrainz/probe/answers-20261118.txt). The others are put together
 * from its docs and server code, which say what they hold but were not seen live.
 */
class ListenBrainzClientTest {

    private val app = ListenBrainz.App("InterTune", "0.13.1", "https://github.com/ItzSkyeYT/InterTune")

    /** Made up. Shaped like a real one, so a leak of it would show. */
    private val token = "b5d5a3e2-7f41-4c2e-9a1d-3c6f0e8d4a71"

    private val ok = """{"status":"ok"}"""

    /** Asked 18 Nov, POST /1/submit-listens with a made-up token. */
    private val badToken = """{"code":401,"error":"Invalid authorization token."}"""

    private class Sent(
        val method: HttpMethod,
        val url: String,
        val authorization: String?,
        val userAgent: String?,
        val contentType: String?,
        val body: String,
        val at: Long,
    )

    /**
     * A client whose server is [server] and whose clock moves only when the gate sleeps, so a wait
     * is measured rather than waited.
     */
    private inner class Fixture(private val server: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val sent = mutableListOf<Sent>()
        val lb = ListenBrainz(
            app = app,
            client = HttpClient(MockEngine { request ->
                sent += Sent(
                    method = request.method,
                    url = request.url.toString(),
                    authorization = request.headers[HttpHeaders.Authorization],
                    userAgent = request.headers[HttpHeaders.UserAgent],
                    contentType = request.body.contentType?.toString(),
                    body = (request.body as? TextContent)?.text ?: "",
                    at = now,
                )
                server(this, request)
            }),
            gate = Gate(now = { now }, sleep = { sleeps += it; now += it }),
        )

        fun rick(at: Long? = 1795000000) = lb.listen(
            artist = "Rick Astley",
            track = "Never Gonna Give You Up",
            release = "Whenever You Need Somebody",
            durationMs = 213_000,
            startedAtSeconds = at,
            youTubeId = "dQw4w9WgXcQ",
        )!!
    }

    private fun MockRequestHandleScope.reply(status: HttpStatusCode, body: String, vararg headers: Pair<String, String>) =
        respond(
            body, status,
            headersOf(*(listOf(HttpHeaders.ContentType to "application/json") + headers).map { it.first to listOf(it.second) }.toTypedArray()),
        )

    private fun <T> Result<T>.failure(): ListenBrainzException =
        exceptionOrNull() as? ListenBrainzException ?: error("expected a ListenBrainzException, got $this")

    // The JSON

    @Test
    fun aFinishedListenGoesOutAsTheDocsShowIt() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        assertTrue(f.lb.single(token, f.rick()).isSuccess)
        val sent = f.sent.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("https://api.listenbrainz.org/1/submit-listens", sent.url)
        assertEquals(
            """{"listen_type":"single","payload":[{"listened_at":1795000000,"track_metadata":{"artist_name":"Rick Astley","track_name":"Never Gonna Give You Up","release_name":"Whenever You Need Somebody","additional_info":{"duration_ms":213000,"media_player":"InterTune","media_player_version":"0.13.1","submission_client":"InterTune","submission_client_version":"0.13.1","music_service":"music.youtube.com","origin_url":"https://music.youtube.com/watch?v=dQw4w9WgXcQ"}}}]}""",
            sent.body,
        )
    }

    @Test
    fun playingNowCarriesNoTimeWhateverTheListenHolds() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        // "Timestamp must be omitted from a playing_now submission."
        assertTrue(f.lb.playingNow(token, f.rick(at = 1795000000)).isSuccess)
        assertEquals(
            """{"listen_type":"playing_now","payload":[{"track_metadata":{"artist_name":"Rick Astley","track_name":"Never Gonna Give You Up","release_name":"Whenever You Need Somebody","additional_info":{"duration_ms":213000,"media_player":"InterTune","media_player_version":"0.13.1","submission_client":"InterTune","submission_client_version":"0.13.1","music_service":"music.youtube.com","origin_url":"https://music.youtube.com/watch?v=dQw4w9WgXcQ"}}}]}""",
            f.sent.single().body,
        )
    }

    @Test
    fun aLocalFileHasNoServiceAndNoLink() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val local = f.lb.listen(artist = "Someone", track = "A song on the phone", release = "An album", durationMs = 180_000, startedAtSeconds = 1795000000, trackNumber = 3)!!
        assertTrue(f.lb.single(token, local).isSuccess)
        assertEquals(
            """{"listen_type":"single","payload":[{"listened_at":1795000000,"track_metadata":{"artist_name":"Someone","track_name":"A song on the phone","release_name":"An album","additional_info":{"duration_ms":180000,"tracknumber":"3","media_player":"InterTune","media_player_version":"0.13.1","submission_client":"InterTune","submission_client_version":"0.13.1"}}}]}""",
            f.sent.single().body,
        )
    }

    @Test
    fun anImportSendsTheWholeBatchInOneRequest() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val first = f.lb.listen("A", "One", startedAtSeconds = 1795000000)!!
        val second = f.lb.listen("B", "Two", startedAtSeconds = 1795000300)!!
        assertTrue(f.lb.import(token, listOf(first, second)).isSuccess)
        assertEquals(
            """{"listen_type":"import","payload":[{"listened_at":1795000000,"track_metadata":{"artist_name":"A","track_name":"One","additional_info":{"media_player":"InterTune","media_player_version":"0.13.1","submission_client":"InterTune","submission_client_version":"0.13.1"}}},{"listened_at":1795000300,"track_metadata":{"artist_name":"B","track_name":"Two","additional_info":{"media_player":"InterTune","media_player_version":"0.13.1","submission_client":"InterTune","submission_client_version":"0.13.1"}}}]}""",
            f.sent.single().body,
        )
        // Nothing to send is not a request.
        assertTrue(f.lb.import(token, emptyList()).isSuccess)
        assertEquals(1, f.sent.size)
    }

    @Test
    fun aKeptListenComesBackByteForByte() {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        // What waits in the outbox goes out exactly as it was first made, so a listen sent twice is
        // the same listen to ListenBrainz, which keeps it once.
        val text = ListenBrainz.encode(f.rick())
        assertEquals(f.rick(), ListenBrainz.decode(text))
        assertEquals(text, ListenBrainz.encode(ListenBrainz.decode(text)))
    }

    // The headers

    @Test
    fun everyRequestCarriesTheTokenTheUserAgentAndItsType() = runBlocking<Unit> {
        val f = Fixture { request ->
            if (request.url.encodedPath.endsWith("validate-token")) reply(HttpStatusCode.OK, """{"code":200,"message":"Token valid.","valid":true,"user_name":"someone"}""")
            else reply(HttpStatusCode.OK, ok)
        }
        // Pasted with a space and a line break around it, as happens.
        f.lb.validateToken(" $token\n").getOrThrow()
        f.lb.single(" $token\n", f.rick()).getOrThrow()

        val (check, submit) = f.sent
        assertEquals(HttpMethod.Get, check.method)
        // In the header only, never in the address, where it would end up in logs and errors.
        assertEquals("https://api.listenbrainz.org/1/validate-token", check.url)
        for (sent in f.sent) {
            assertEquals("Token $token", sent.authorization)
            assertEquals("InterTune/0.13.1 ( https://github.com/ItzSkyeYT/InterTune )", sent.userAgent)
        }
        assertEquals("application/json", submit.contentType)
    }

    // validate-token

    @Test
    fun aValidTokenSaysWhoseItIs() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, """{"code":200,"message":"Token valid.","valid":true,"user_name":"someone"}""") }
        assertEquals(TokenCheck(valid = true, userName = "someone"), f.lb.validateToken(token).getOrThrow())
    }

    @Test
    fun anUnknownTokenIsAnAnswerNotAFailure() = runBlocking<Unit> {
        // Asked 18 Nov: HTTP 200 for a made-up token, the verdict in the body.
        val f = Fixture { reply(HttpStatusCode.OK, """{"code":200,"message":"Token invalid.","valid":false}""") }
        assertEquals(TokenCheck(valid = false, userName = null), f.lb.validateToken(token).getOrThrow())
    }

    @Test
    fun validateTokensFourHundredIsARefusal() = runBlocking<Unit> {
        // Asked 18 Nov, with no token at all.
        val f = Fixture { reply(HttpStatusCode.BadRequest, """{"code":400,"error":"You need to provide an Authorization token."}""") }
        val e = f.lb.validateToken(token).failure()
        assertTrue(e is ListenBrainzException.Refused)
        assertEquals(400, (e as ListenBrainzException.Refused).status)
        assertEquals("You need to provide an Authorization token.", e.message)
    }

    @Test
    fun anEmptyTokenIsNeverSent() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        assertEquals(TokenCheck(valid = false, userName = null), f.lb.validateToken("  \n").getOrThrow())
        val e = f.lb.single("", f.rick()).failure()
        assertTrue(e is ListenBrainzException.TokenRefused)
        assertEquals(0, (e as ListenBrainzException.TokenRefused).status)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun anUnreadableAnswerToValidateTokenIsNotAVerdict() = runBlocking<Unit> {
        // A captive portal's page, say: no verdict either way, so the dialog must not say "refused".
        val f = Fixture { respond("<html>Sign in to the Wi-Fi</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val e = f.lb.validateToken(token).failure()
        assertTrue(e is ListenBrainzException.Unavailable)
    }

    // What each answer becomes

    private suspend fun refusalOf(server: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ListenBrainzException {
        val f = Fixture(server)
        return f.lb.single(token, f.rick()).failure()
    }

    @Test
    fun eachAnswerIsSortedByWhatToDoWithTheListen() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        assertTrue(f.lb.single(token, f.rick()).isSuccess)

        // Drop it: the same request gets the same answer.
        val tooHigh = refusalOf { reply(HttpStatusCode.BadRequest, """{"code":400,"error":"Value for key listened_at is too high."}""") }
        assertTrue(tooHigh is ListenBrainzException.Refused)
        assertEquals("Value for key listened_at is too high.", tooHigh.message)
        val tooLarge = refusalOf { respond("<html>413 Request Entity Too Large</html>", HttpStatusCode.PayloadTooLarge) }
        assertTrue(tooLarge is ListenBrainzException.Refused)
        assertEquals("HTTP 413", tooLarge.message)

        // Stop until a new token: what ListenBrainz says goes with it.
        val unknown = refusalOf { reply(HttpStatusCode.Unauthorized, badToken) }
        assertTrue(unknown is ListenBrainzException.TokenRefused)
        assertEquals(401, (unknown as ListenBrainzException.TokenRefused).status)
        assertEquals("Invalid authorization token.", unknown.message)
        val noEmail = refusalOf {
            reply(HttpStatusCode.Unauthorized, """{"code":401,"error":"The listens were rejected because your MetaBrainz account does not have a verified email address."}""")
        }
        assertTrue(noEmail is ListenBrainzException.TokenRefused)
        assertTrue(noEmail.message!!.startsWith("The listens were rejected"))
        val forbidden = refusalOf { reply(HttpStatusCode.Forbidden, """{"code":403,"error":"Forbidden"}""") }
        assertEquals(403, (forbidden as ListenBrainzException.TokenRefused).status)

        // Wait, then send it: as long as ListenBrainz says.
        val tooFast = refusalOf {
            reply(HttpStatusCode.TooManyRequests, """{"code":429,"error":"You have exceeded your rate limit."}""", "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset-In" to "7")
        }
        assertEquals(7_000L, (tooFast as ListenBrainzException.RateLimited).retryAfterMs)

        // Keep it: the server is in trouble, or this address is not ListenBrainz's API.
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable, HttpStatusCode.NotFound)) {
            val e = refusalOf { respond("<html>${status.value}</html>", status, headersOf(HttpHeaders.ContentType, "text/html")) }
            assertTrue("$status", e is ListenBrainzException.Unavailable)
            assertEquals(status.value, (e as ListenBrainzException.Unavailable).status)
        }

        // Keep it: no answer at all.
        for (thrown in listOf(ConnectException("Connection refused"), UnknownHostException("api.listenbrainz.org"), ConnectTimeoutException("Connect timeout has expired"))) {
            val e = refusalOf { throw thrown }
            assertTrue("$thrown", e is ListenBrainzException.Unreachable)
        }
    }

    /**
     * 20 Nov 2026: ListenBrainz took 6 to 40 s to answer anything, the connect dialog gave up at 30 s and
     * told him to check his connection. A connection that opened and then waited too long is
     * ListenBrainz being slow, and says so; one that never opened is the phone's side.
     */
    @Test
    fun aSlowAnswerIsNotAMissingConnection() = runBlocking<Unit> {
        val tooLong = refusalOf { throw HttpRequestTimeoutException("https://api.listenbrainz.org/1/submit-listens", 30_000) }
        assertTrue(tooLong is ListenBrainzException.Unreachable && tooLong.slow)
        val readTimeout = refusalOf { throw java.net.SocketTimeoutException("timeout") }
        assertTrue(readTimeout is ListenBrainzException.Unreachable && readTimeout.slow)
        for (thrown in listOf(ConnectException("Connection refused"), UnknownHostException("api.listenbrainz.org"), ConnectTimeoutException("Connect timeout has expired"), java.net.SocketTimeoutException("failed to connect to api.listenbrainz.org"))) {
            val e = refusalOf { throw thrown }
            assertTrue("$thrown", e is ListenBrainzException.Unreachable && !e.slow)
        }
    }

    @Test
    fun theTokenCheckWaitsUpToAMinute() = runBlocking<Unit> {
        var asked: Long? = null
        val lb = ListenBrainz(
            app = app,
            client = HttpClient(MockEngine { request ->
                asked = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                respond("""{"code":200,"message":"Token invalid.","valid":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } },
            gate = Gate(now = { 0L }, sleep = {}),
        )
        assertFalse(lb.validateToken(token).getOrThrow().valid)
        assertEquals(ListenBrainz.TOKEN_CHECK_MS, asked)
        // a client without the timeout plugin, as in the other tests, is asked nothing and still works
        assertFalse(Fixture { respond("""{"code":200,"message":"Token invalid.","valid":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }.lb.validateToken(token).getOrThrow().valid)
    }

    @Test
    fun theTokenIsInNoExceptionText() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        // An answer that quotes the token, which ListenBrainz's do not today; one that might.
        failures += refusalOf { reply(HttpStatusCode.BadRequest, """{"code":400,"error":"Token $token is not allowed here"}""") }
        failures += refusalOf { reply(HttpStatusCode.Unauthorized, """{"code":401,"error":"No user with token $token"}""") }
        failures += refusalOf { reply(HttpStatusCode.TooManyRequests, """{"code":429,"error":"Slow down, $token"}""", "X-RateLimit-Reset-In" to "3") }
        failures += refusalOf { reply(HttpStatusCode.InternalServerError, """{"code":500,"error":"Crashed on $token"}""") }
        failures += refusalOf { throw IOException("Could not send with $token") }
        failures += refusalOf { throw IllegalStateException("Something odd about $token") }
        // A token no header can carry: the HTTP library's own complaint would quote the header.
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        failures += f.lb.single("$token\nX-Evil: 1", f.rick()).failure()
        assertTrue(f.sent.isEmpty())

        assertEquals(7, failures.size)
        for (e in failures) {
            assertFalse("token in: ${e.javaClass.simpleName}", e.stackTraceToString().contains(token))
        }
        assertTrue(failures[0].message!!.contains("<token>"))
    }

    // The pace

    @Test
    fun oneRequestASecondAtMost() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok, "X-RateLimit-Remaining" to "29", "X-RateLimit-Reset-In" to "8") }
        repeat(3) { f.lb.single(token, f.rick()).getOrThrow() }
        assertEquals(listOf(0L, 1_000L, 2_000L), f.sent.map { it.at })
        assertEquals(listOf(1_000L, 1_000L), f.sleeps)
    }

    @Test
    fun aRequestWithNoAnswerStillCountsForThePace() = runBlocking<Unit> {
        var first = true
        val f = Fixture {
            if (first) { first = false; throw ConnectException("Connection refused") }
            reply(HttpStatusCode.OK, ok)
        }
        assertTrue(f.lb.single(token, f.rick()).failure() is ListenBrainzException.Unreachable)
        f.lb.single(token, f.rick()).getOrThrow()
        assertEquals(listOf(0L, 1_000L), f.sent.map { it.at })
    }

    @Test
    fun aSpentWindowIsWaitedOut() = runBlocking<Unit> {
        var answered = 0
        val f = Fixture {
            answered++
            if (answered == 1) reply(HttpStatusCode.OK, ok, "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset-In" to "7")
            else reply(HttpStatusCode.OK, ok)
        }
        f.lb.single(token, f.rick()).getOrThrow()
        f.lb.single(token, f.rick()).getOrThrow()
        assertEquals(listOf(0L, 7_000L), f.sent.map { it.at })
    }

    @Test
    fun aTooManyRequestsWaitsResetInBeforeTheNextRequest() = runBlocking<Unit> {
        var answered = 0
        val f = Fixture {
            answered++
            if (answered == 1) reply(HttpStatusCode.TooManyRequests, """{"code":429,"error":"You have exceeded your rate limit."}""", "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset-In" to "5")
            else reply(HttpStatusCode.OK, ok)
        }
        val e = f.lb.single(token, f.rick()).failure()
        assertEquals(5_000L, (e as ListenBrainzException.RateLimited).retryAfterMs)
        f.lb.single(token, f.rick()).getOrThrow()
        assertEquals(listOf(0L, 5_000L), f.sent.map { it.at })
    }

    @Test
    fun aTooManyRequestsWithNoHeaderWaitsTheUsualWindow() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.TooManyRequests, """{"code":429,"error":"You have exceeded your rate limit."}""") }
        val e = f.lb.single(token, f.rick()).failure()
        assertEquals(Gate.DEFAULT_WINDOW_MS, (e as ListenBrainzException.RateLimited).retryAfterMs)
    }

    @Test
    fun aLongPauseFailsAtOnceInsteadOfHanging() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.TooManyRequests, """{"code":429,"error":"You have exceeded your rate limit."}""", "X-RateLimit-Reset-In" to "600") }
        assertEquals(600_000L, (f.lb.single(token, f.rick()).failure() as ListenBrainzException.RateLimited).retryAfterMs)
        val again = f.lb.single(token, f.rick()).failure()
        assertTrue(again is ListenBrainzException.RateLimited)
        assertTrue((again as ListenBrainzException.RateLimited).retryAfterMs > Gate.LONGEST_WAIT_MS)
        assertEquals(1, f.sent.size)
        assertTrue(f.sleeps.isEmpty())
    }

    @Test
    fun aNewerPlayingNowReplacesOneStillWaitingItsTurn() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture { request ->
            if ((request.body as TextContent).text.contains("\"single\"")) {
                inside.complete(Unit)
                release.await()
            }
            reply(HttpStatusCode.OK, ok)
        }
        val finished = async { f.lb.single(token, f.rick()) }
        inside.await()
        // Two song changes while the gate is busy: the first of them is stale before its turn.
        val skipped = async(start = CoroutineStart.UNDISPATCHED) { f.lb.playingNow(token, f.lb.listen("A", "Skipped")!!) }
        val playing = async(start = CoroutineStart.UNDISPATCHED) { f.lb.playingNow(token, f.lb.listen("B", "Playing")!!) }
        release.complete(Unit)
        assertTrue(finished.await().isSuccess)
        assertTrue(skipped.await().isSuccess)
        assertTrue(playing.await().isSuccess)
        assertEquals(2, f.sent.size)
        assertTrue(f.sent[1].body.contains("\"Playing\""))
        assertFalse(f.sent.any { it.body.contains("\"Skipped\"") })
    }

    // Batches and sizes

    @Test
    fun batchesSplitAtListenBrainzsMaximum() {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val listens = (0 until 2_500).map { f.lb.listen("Artist $it", "Song $it", startedAtSeconds = 1795000000L + it)!! }
        val batches = ListenBrainz.importBatches(listens)
        assertEquals(listOf(1_000, 1_000, 500), batches.map { it.size })
        assertEquals(listens, batches.flatten())
        // Smaller on request, as the outbox asks, so a refused batch is cheap to go through.
        assertEquals(List(25) { 100 }, ListenBrainz.importBatches(listens, most = 100).map { it.size })
        try {
            ListenBrainz.importBatches(listens, most = ListenBrainz.MAX_LISTENS_PER_REQUEST + 1)
            fail("a batch over ListenBrainz's maximum was allowed")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun moreThanTheMaximumInOneImportIsRefusedWithoutARequest() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val listens = List(ListenBrainz.MAX_LISTENS_PER_REQUEST + 1) { f.lb.listen("A", "B", startedAtSeconds = 1795000000L + it)!! }
        val e = f.lb.import(token, listens).failure()
        assertEquals(0, (e as ListenBrainzException.Refused).status)
        assertTrue(f.sent.isEmpty())
    }

    /** A listen whose JSON is exactly [bytes] long. */
    private fun Fixture.listenOf(bytes: Int, at: Long = 1795000000): Listen? {
        val base = ListenBrainz.encode(lb.listen("A", "x", startedAtSeconds = at)!!).length
        return lb.listen("A", "x".repeat(1 + bytes - base), startedAtSeconds = at)
    }

    @Test
    fun aFullBatchOfTheLargestListensPassesListenBrainzsSizeChecks() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val largest = f.listenOf(ListenBrainz.LISTEN_BUDGET)!!
        assertEquals(ListenBrainz.LISTEN_BUDGET, ListenBrainz.encode(largest).toByteArray().size)
        // One byte more is refused when it is made: it could never be sent.
        assertNull(f.listenOf(ListenBrainz.LISTEN_BUDGET + 1))

        // ListenBrainz's own checks on a request (api.py, submit_listen): the whole body at most
        // MAX_LISTEN_PAYLOAD_SIZE bytes, and at most MAX_LISTEN_SIZE bytes a listen on average.
        f.lb.import(token, List(ListenBrainz.MAX_LISTENS_PER_REQUEST) { largest }).getOrThrow()
        f.lb.single(token, largest).getOrThrow()
        f.lb.playingNow(token, largest).getOrThrow()
        for ((sent, count) in f.sent.zip(listOf(ListenBrainz.MAX_LISTENS_PER_REQUEST, 1, 1))) {
            val bytes = sent.body.toByteArray().size
            assertTrue("$bytes bytes for $count", bytes <= ListenBrainz.MAX_LISTEN_PAYLOAD_SIZE)
            assertTrue("$bytes bytes for $count", bytes <= count * ListenBrainz.MAX_LISTEN_SIZE)
        }
    }

    @Test
    fun aKeptListenListenBrainzWouldRefuseIsRefusedHere() = runBlocking<Unit> {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val fine = f.rick()
        val withNul = fine.copy(trackMetadata = fine.trackMetadata.copy(trackName = "Never\u0000Gonna"))
        val tooEarly = fine.copy(listenedAt = 1_000)
        val noTime = fine.copy(listenedAt = null)
        for (bad in listOf(withNul, tooEarly, noTime)) {
            assertNotNull(ListenBrainz.problemWith(bad))
            val e = f.lb.import(token, listOf(fine, bad)).failure()
            assertEquals(0, (e as ListenBrainzException.Refused).status)
        }
        assertNull(ListenBrainz.problemWith(fine))
        assertTrue(f.sent.isEmpty())
    }

    // Making a listen

    @Test
    fun aListenIsCleanedTheWayListenBrainzWouldOtherwiseRefuse() {
        val f = Fixture { reply(HttpStatusCode.OK, ok) }
        val lb = f.lb
        // Nothing to name it by: nothing to send.
        assertNull(lb.listen(" ", "Song"))
        assertNull(lb.listen("Artist", "\u0000 "))
        // A phone whose clock is far behind.
        assertNull(lb.listen("Artist", "Song", startedAtSeconds = 1_000))
        assertNotNull(lb.listen("Artist", "Song", startedAtSeconds = ListenBrainz.LISTEN_MINIMUM_TS))

        val cleaned = lb.listen(
            artist = " Ar\u0000tist ",
            track = "Song",
            release = "  ",
            durationMs = 0,
            trackNumber = 0,
            youTubeId = "not an id/",
        )!!
        assertEquals("Artist", cleaned.trackMetadata.artistName)
        assertNull(cleaned.trackMetadata.releaseName)
        val info = cleaned.trackMetadata.additionalInfo!!
        assertNull(info.durationMs)
        assertNull(info.trackNumber)
        assertNull(info.musicService)
        assertNull(info.originUrl)
        // Over ListenBrainz's 24 days: a live stream's length, say.
        assertNull(lb.listen("Artist", "Song", durationMs = ListenBrainz.MAX_DURATION_MS + 1)!!.trackMetadata.additionalInfo!!.durationMs)
        assertEquals(ListenBrainz.MAX_DURATION_MS, lb.listen("Artist", "Song", durationMs = ListenBrainz.MAX_DURATION_MS)!!.trackMetadata.additionalInfo!!.durationMs)
    }
}
