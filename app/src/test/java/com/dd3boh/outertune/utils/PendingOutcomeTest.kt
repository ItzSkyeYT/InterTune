/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.response.EditPlaylistResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a send that failed comes to: kept and tried later, forgotten, or counted. Keeping the
 * person's change is the side to err on, so only an answer that says the request itself is wrong
 * forgets it. The failures are the exceptions the app's client throws (expectSuccess, as in
 * InnerTube.kt), made by ktor's mock engine, as WhyStoppedTest makes them.
 */
class PendingOutcomeTest {

    private fun answered(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine {
            respond("""{"error":{"code":${status.value}}}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        return runCatching {
            runBlocking { client.post("https://music.youtube.com/youtubei/v1/browse/edit_playlist") }
        }.exceptionOrNull() ?: error("no failure for $status")
    }

    @Test
    fun `no connection, a lapsed sign-in, throttling and YouTube's own errors keep the change for later`() {
        for (failure in listOf(UnknownHostException("music.youtube.com"), SocketTimeoutException(), IOException("reset"), IOException("wrapped", SocketTimeoutException()))) {
            assertEquals(failure.toString(), Sent.NotNow, PendingOutcome.of(failure))
        }
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.RequestTimeout, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError, HttpStatusCode.ServiceUnavailable)) {
            assertEquals(status.toString(), Sent.NotNow, PendingOutcome.of(answered(status)))
        }
    }

    @Test
    fun `read only and signed out keep it too, for when the account may be written to again`() {
        assertEquals(Sent.NotNow, PendingOutcome.of(YouTube.AccountWriteRefused()))
    }

    @Test
    fun `an answer that the request is wrong forgets it`() {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict, HttpStatusCode.Gone)) {
            assertEquals(status.toString(), Sent.Refused, PendingOutcome.of(answered(status)))
        }
        assertEquals(Sent.Refused, PendingOutcome.of(YouTube.PlaylistEditRefused("STATUS_FAILED")))
    }

    @Test
    fun `anything else is counted, so a change YouTube never takes is given up after a few tries`() {
        assertEquals(Sent.Failed, PendingOutcome.of(answered(HttpStatusCode.Forbidden)))
        assertEquals(Sent.Failed, PendingOutcome.of(IllegalStateException("odd")))
    }

    @Test
    fun `a cancelled send is not a failure, it goes up and the change stays`() {
        assertThrows(CancellationException::class.java) { PendingOutcome.of(CancellationException("left")) }
    }

    // ---- the answer to an edit ----

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `an add's answer names the new entry, as ytmusicapi reads it`() {
        val answer = json.decodeFromString(
            EditPlaylistResponse.serializer(),
            """{"status":"STATUS_SUCCEEDED","playlistEditResults":[{"playlistEditVideoAddedResultData":{"videoId":"dQw4w9WgXcQ","setVideoId":"56B44F6D10557CC6"}}],"actions":[]}""",
        )
        assertEquals(false, answer.refused)
        assertEquals("56B44F6D10557CC6", answer.setVideoIdOf("dQw4w9WgXcQ"))
        assertNull(answer.setVideoIdOf("anotherSong"))
    }

    @Test
    fun `an answer that says no, or says nothing, is read as such`() {
        val refused = json.decodeFromString(EditPlaylistResponse.serializer(), """{"status":"STATUS_FAILED"}""")
        assertTrue(refused.refused)
        val bare = json.decodeFromString(EditPlaylistResponse.serializer(), """{"responseContext":{}}""")
        assertEquals(false, bare.refused)
        assertNull(bare.setVideoIdOf("x"))
    }
}
