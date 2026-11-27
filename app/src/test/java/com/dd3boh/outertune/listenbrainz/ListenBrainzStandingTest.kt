/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import com.dd3boh.listenbrainz.Gate
import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.listenbrainz.ListenBrainzException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.UnknownHostException

/**
 * What the ListenBrainz card ends up saying after ListenBrainz's answers, against a ListenBrainz on
 * no network that answers as the real one does. His report of 27 Nov 2026: "ListenBrainz no longer
 * accepts your token" with a token just pasted from his settings page. ListenBrainz answers listens
 * with 401 for a token it does not know and for an account with no verified email alike, while
 * validate-token, which the connect dialog asks, looks only at the token: the app took every 401
 * for a dead token, and a fresh one met the same 401 at the next song. Only a token ListenBrainz
 * really refuses says that now; a 429 or no connection says nothing, and the plays wait.
 *
 * The real client runs through the real gate (a second between requests), so a refusal, which
 * asks validate-token once more, costs this test a second each.
 */
class ListenBrainzStandingTest {

    private val app = ListenBrainz.App("InterTune", "0.13.1", "https://github.com/ItzSkyeYT/InterTune")

    /** Made up, shaped like a real one. */
    private val token = "b5d5a3e2-7f41-4c2e-9a1d-3c6f0e8d4a71"

    private val listenBrainz = FakeListenBrainz()

    private val client = ListenBrainz(
        app = app,
        client = HttpClient(MockEngine { request -> listenBrainz.answer(this, request) }),
        gate = Gate(),
    )

    private fun play(n: Int) = client.listen("Artist $n", "Song $n", startedAtSeconds = 1795000000L + 300L * n, youTubeId = "dQw4w9WgXcQ")!!

    private fun waiting(count: Int) = (1..count).map { WaitingListen(user = "mel", json = ListenBrainz.encode(play(it)), keptAt = it.toLong()) }

    @Test
    fun `a token that works, on an account in order - taken, and nothing to say`() = runBlocking<Unit> {
        listenBrainz.knows(token, "mel", verifiedEmail = true)
        assertTrue(client.validateToken(token).getOrThrow().valid)
        assertEquals(ListenBrainzStanding.TAKEN, ListenBrainzStanding.after(client.single(token, play(1))))
        assertEquals(listOf("validate-token", "submit-listens"), listenBrainz.asked)
    }

    @Test
    fun `a token reset on the settings page is the one way to no longer accepts your token`() = runBlocking<Unit> {
        listenBrainz.knows(token, "mel", verifiedEmail = true)
        assertTrue(client.validateToken(token).getOrThrow().valid)
        // Reset on listenbrainz.org/settings: ListenBrainz no longer knows it.
        listenBrainz.forget(token)
        val result = client.single(token, play(1))
        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is ListenBrainzException.TokenRefused)
        assertEquals(ListenBrainzStanding.TOKEN_REFUSED, ListenBrainzStanding.after(result))
        assertEquals(listOf("validate-token", "submit-listens", "validate-token"), listenBrainz.asked)
    }

    @Test
    fun `his report - a fresh token on an account with no verified email is not a token refused`() = runBlocking<Unit> {
        listenBrainz.knows(token, "mel", verifiedEmail = false)
        // The connect dialog's question: the token is good, so it is kept and the dialog closes.
        assertTrue(client.validateToken(token).getOrThrow().valid)
        // The next song, and the outbox's run that connecting starts: the same 401 each time.
        val single = client.single(token, play(1))
        assertEquals(ListenBrainzStanding.NEEDS_EMAIL, ListenBrainzStanding.after(single))
        val run = OutboxRun.run(waiting(3)) { client.import(token, it) }
        assertEquals(OutboxRun.Next.LATER, run.next)
        assertTrue(run.sent.isEmpty() && run.dropped.isEmpty())
        assertEquals(ListenBrainzStanding.NEEDS_EMAIL, ListenBrainzStanding.after(Result.failure<Unit>(run.stoppedBy!!)))

        // The email verified on the MetaBrainz profile: the next play goes, and the card goes back to connected.
        listenBrainz.verify("mel")
        assertEquals(ListenBrainzStanding.TAKEN, ListenBrainzStanding.after(client.single(token, play(2))))
    }

    @Test
    fun `asked to wait - nothing to say, the play waits`() = runBlocking<Unit> {
        listenBrainz.knows(token, "mel", verifiedEmail = true)
        listenBrainz.tooMany = true
        val result = client.single(token, play(1))
        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is ListenBrainzException.RateLimited)
        assertNull(ListenBrainzStanding.after(result))
        assertEquals(OutboxRun.Next.LATER, OutboxRun.run(waiting(1)) { Result.failure(result.exceptionOrNull()!!) }.next)
        assertEquals(listOf("submit-listens"), listenBrainz.asked)
    }

    @Test
    fun `no connection - nothing to say, the play waits`() = runBlocking<Unit> {
        listenBrainz.knows(token, "mel", verifiedEmail = true)
        listenBrainz.offline = true
        val result = client.single(token, play(1))
        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is ListenBrainzException.Unreachable)
        assertNull(ListenBrainzStanding.after(result))
        assertNull(ListenBrainzStanding.after(client.playingNow(token, play(2))))
    }

    @Test
    fun `the card says no longer accepts your token only from a refusal of the token`() {
        val sources = File("src/main/java/com/dd3boh/outertune")
        val main = sources.walkTopDown().filter { it.extension == "kt" }.map { code(it.readText()) }.joinToString("\n")
        // Shown behind refusedAt alone (ListenBrainzRowsTest has where), and refusedAt written in one place.
        assertEquals(1, Regex("""R\.string\.listenbrainz_refused\)""").findAll(main).count())
        assertEquals(1, Regex("""it\[ListenBrainzRefusedAtKey] =""").findAll(main).count())
        val sender = code(File(sources, "listenbrainz/ListenBrainzSender.kt").readText())
        assertTrue("it[ListenBrainzRefusedAtKey] = System.currentTimeMillis()" in sender.substringAfter("private suspend fun refused("))
        // refused() is called from one branch: a token ListenBrainz does not know.
        assertEquals(2, Regex("""\brefused\(""").findAll(sender).count())
        assertTrue("ListenBrainzStanding.TOKEN_REFUSED -> refused(" in sender)
        // Every answer to listens goes through note(): playing now, a finished play, and the outbox's run.
        for (head in listOf("suspend fun nowPlaying(", "suspend fun scrobble(", "suspend fun sendWaiting(")) {
            assertTrue(head, "note(" in sender.substringAfter(head).substringBefore("\n    }\n"))
        }
    }

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }
}

/**
 * ListenBrainz on no network, answering as the real one does. Asked 27 Nov 2026 with a made-up token
 * (experiments/agents-1127/lb-token/api/): validate-token's HTTP 200 with valid false, and
 * submit-listens' 401 "Invalid authorization token.", with the rate headers. The rest word for word
 * from its server code read the same day (experiments/agents-1127/lb-token/lb-source/):
 * validate-token looks the token up and nothing else, submit-listens refuses an account with no
 * verified email with a 401 of its own, and brainzutils' 429.
 */
private class FakeListenBrainz {
    private val users = mutableMapOf<String, String>()
    private val verified = mutableSetOf<String>()
    var tooMany = false
    var offline = false

    /** The last part of each address asked, in order. */
    val asked = mutableListOf<String>()

    fun knows(token: String, user: String, verifiedEmail: Boolean) {
        users[token] = user
        if (verifiedEmail) verified += user else verified -= user
    }

    fun forget(token: String) {
        users -= token
    }

    fun verify(user: String) {
        verified += user
    }

    fun answer(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
        // What OkHttp throws with no connection at all.
        if (offline) throw UnknownHostException("Unable to resolve host \"api.listenbrainz.org\": No address associated with hostname")
        val path = request.url.encodedPath
        asked += path.substringAfterLast('/')
        if (tooMany) return scope.reply(HttpStatusCode.TooManyRequests, TOO_MANY, remaining = 0)
        // validate_auth_header: header.split(" ")[1].
        val user = request.headers[HttpHeaders.Authorization]?.split(" ")?.getOrNull(1)?.let { users[it] }
        return when (path) {
            "/1/validate-token" ->
                if (user == null) scope.reply(HttpStatusCode.OK, """{"code":200,"message":"Token invalid.","valid":false}""")
                else scope.reply(HttpStatusCode.OK, """{"code":200,"message":"Token valid.","valid":true,"user_name":"$user"}""")
            "/1/submit-listens" -> when {
                user == null -> scope.reply(HttpStatusCode.Unauthorized, """{"code":401,"error":"Invalid authorization token."}""")
                user !in verified -> scope.reply(HttpStatusCode.Unauthorized, NO_EMAIL)
                else -> scope.reply(HttpStatusCode.OK, """{"status":"ok"}""")
            }
            else -> scope.reply(HttpStatusCode.NotFound, """{"code":404,"error":"Not Found"}""")
        }
    }

    private fun MockRequestHandleScope.reply(status: HttpStatusCode, body: String, remaining: Int = 29) = respond(
        body, status,
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            "X-RateLimit-Limit" to listOf("30"),
            "X-RateLimit-Remaining" to listOf("$remaining"),
            "X-RateLimit-Reset-In" to listOf("9"),
        ),
    )

    private companion object {
        /** REJECT_LISTENS_WITHOUT_EMAIL_ERROR, as listenbrainz/webserver/utils.py builds it. */
        const val NO_EMAIL = """{"code":401,"error":"The listens were rejected because your MetaBrainz account does not have a verified email address. Please check your inbox for a verification email, or go to your MetaBrainz profile page to verify your email: https://metabrainz.org/profile. Read the blog post at https://blog.metabrainz.org/?p=8915 to understand why we need your email."}"""

        /** brainzutils' on_over_limit. */
        const val TOO_MANY = """{"code":429,"error":"You have exceeded your rate limit. See the X-RateLimit-* response headers for more information on your current rate limit."}"""
    }
}
