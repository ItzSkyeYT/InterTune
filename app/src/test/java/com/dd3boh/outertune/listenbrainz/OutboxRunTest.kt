/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.listenbrainz.ListenBrainzException
import com.dd3boh.listenbrainz.models.Listen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One run over the plays waiting for ListenBrainz (OutboxRun) against a made-up ListenBrainz:
 * what each answer does to the plays in the request, and to the ones after it.
 */
class OutboxRunTest {

    private val client = ListenBrainz(ListenBrainz.App("InterTune", "0.13.1", "https://github.com/ItzSkyeYT/InterTune"))

    private fun play(n: Int) = WaitingListen(
        user = "mel",
        json = ListenBrainz.encode(client.listen("Artist $n", "Song $n", startedAtSeconds = 1795000000L + n)!!),
        keptAt = n.toLong(),
    )

    private val waiting = (1..250).map { play(it) }

    /** The track names in each request made, in order. */
    private val requests = mutableListOf<List<String>>()

    private fun answer(how: (List<Listen>) -> Result<Unit>): suspend (List<Listen>) -> Result<Unit> = { listens ->
        requests += listens.map { it.trackMetadata.trackName }
        how(listens)
    }

    private val ok = Result.success(Unit)

    private fun <T> failure(e: ListenBrainzException) = Result.failure<T>(e)

    @Test
    fun `what ListenBrainz takes comes off, in batches of a hundred, oldest first`() = runBlocking<Unit> {
        val outcome = OutboxRun.run(waiting, send = answer { ok })
        assertEquals(listOf(100, 100, 50), requests.map { it.size })
        assertEquals("Song 1", requests.first().first())
        assertEquals(waiting, outcome.sent)
        assertTrue(outcome.dropped.isEmpty())
        assertEquals(OutboxRun.Next.DONE, outcome.next)
    }

    @Test
    fun `a batch refused goes again one by one, and only the play refused is dropped`() = runBlocking<Unit> {
        val tooHigh = "Value for key listened_at is too high."
        val outcome = OutboxRun.run(waiting, send = answer { listens ->
            if (listens.any { it.trackMetadata.trackName == "Song 42" }) failure(ListenBrainzException.Refused(400, tooHigh)) else ok
        })
        // The first hundred refused, then each of them alone, then the rest in batches.
        assertEquals(listOf(100) + List(100) { 1 } + listOf(100, 50), requests.map { it.size })
        assertEquals(listOf(play(42)), outcome.dropped)
        assertEquals(listOf(tooHigh), outcome.refusals)
        assertEquals(waiting - play(42), outcome.sent)
        assertEquals(OutboxRun.Next.DONE, outcome.next)
    }

    @Test
    fun `a refused token keeps everything not yet taken`() = runBlocking<Unit> {
        var asked = 0
        val outcome = OutboxRun.run(waiting, send = answer {
            if (++asked == 1) ok else failure(ListenBrainzException.TokenRefused(401, "Invalid authorization token."))
        })
        assertEquals(waiting.take(100), outcome.sent)
        assertTrue(outcome.dropped.isEmpty())
        assertEquals(OutboxRun.Next.TOKEN_REFUSED, outcome.next)
        assertEquals(2, requests.size)
    }

    @Test
    fun `an account refused keeps everything not yet taken, for a later run`() = runBlocking<Unit> {
        var asked = 0
        val outcome = OutboxRun.run(waiting, send = answer {
            if (++asked == 1) ok
            else failure(ListenBrainzException.AccountRefused(401, "The listens were rejected because your MetaBrainz account does not have a verified email address.", needsEmail = true))
        })
        assertEquals(waiting.take(100), outcome.sent)
        assertTrue(outcome.dropped.isEmpty())
        // Not TOKEN_REFUSED: the run comes back, and goes through once the email is verified.
        assertEquals(OutboxRun.Next.LATER, outcome.next)
        assertEquals(2, requests.size)
    }

    @Test
    fun `asked to wait, the run stops and everything stays for a later one`() = runBlocking<Unit> {
        val outcome = OutboxRun.run(waiting, send = answer { failure(ListenBrainzException.RateLimited(5_000, "You have exceeded your rate limit.")) })
        assertTrue(outcome.sent.isEmpty() && outcome.dropped.isEmpty())
        assertEquals(OutboxRun.Next.LATER, outcome.next)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a server in trouble or no connection keeps everything for later`() = runBlocking<Unit> {
        for (e in listOf(ListenBrainzException.Unavailable(503, "HTTP 503"), ListenBrainzException.Unreachable("ConnectException: Connection refused"))) {
            val outcome = OutboxRun.run(waiting, send = answer { failure(e) })
            assertTrue(outcome.sent.isEmpty() && outcome.dropped.isEmpty())
            assertEquals(OutboxRun.Next.LATER, outcome.next)
        }
    }

    @Test
    fun `a refused token met one by one stops the run there too`() = runBlocking<Unit> {
        val outcome = OutboxRun.run(waiting, send = answer { listens ->
            when {
                listens.size > 1 -> failure(ListenBrainzException.Refused(400, "Value for key listened_at is too high."))
                listens.single().trackMetadata.trackName == "Song 3" -> failure(ListenBrainzException.TokenRefused(401, "Invalid authorization token."))
                else -> ok
            }
        })
        assertEquals(listOf(play(1), play(2)), outcome.sent)
        assertEquals(OutboxRun.Next.TOKEN_REFUSED, outcome.next)
    }

    @Test
    fun `a kept text that no longer reads as a listen is dropped, never sent`() = runBlocking<Unit> {
        val broken = WaitingListen("mel", "{not json", 0)
        val outcome = OutboxRun.run(listOf(broken, play(1)), send = answer { ok })
        assertEquals(listOf(broken), outcome.dropped)
        assertEquals(listOf(play(1)), outcome.sent)
        assertEquals(listOf(listOf("Song 1")), requests)
    }
}
