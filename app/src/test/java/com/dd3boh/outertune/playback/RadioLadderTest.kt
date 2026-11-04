/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.RadioLadder.Rung
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.NextResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/**
 * What a radio asks, in which order, when YouTube Music's own answer is refused (RadioLadder).
 *
 * The network is a stand-in that writes down what it is asked and answers as the test says; the
 * refusals are the exceptions the app's client throws for YouTube's answers (expectSuccess, as in
 * InnerTube.kt), made by ktor's MockEngine. Nothing is sent anywhere.
 */
class RadioLadderTest {

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), duration = 200, thumbnail = "")

    /** An answer of [ids], as a step gives it. */
    private fun answer(vararg ids: String, related: BrowseEndpoint? = null) =
        NextResult(title = "A Mix", items = ids.map(::song), currentIndex = 0, relatedEndpoint = related, continuation = null, endpoint = WatchEndpoint(videoId = "A"))

    /** What the app's client throws when YouTube answers [status]. */
    private fun refusal(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) }) { expectSuccess = true }
        return runCatching { runBlocking { client.post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false") } }.exceptionOrNull()!!
    }

    private class Wire : RadioLadder.Wire {
        val asked = mutableListOf<String>()
        var music: Result<NextResult> = Result.failure(IllegalStateException("not set"))
        var visitor: Result<NextResult> = Result.failure(IllegalStateException("not set"))
        var web: Result<NextResult> = Result.failure(IllegalStateException("not set"))
        var vr: Result<NextResult> = Result.failure(IllegalStateException("not set"))
        var issued: String? = "fresh-1"
        var slowMs = 0L
        override var mixesAllowed = true
        override var capMs = 1_000L

        override suspend fun music(endpoint: WatchEndpoint, continuation: String?): Result<NextResult> {
            asked += "music" + (continuation?.let { " $it" } ?: "")
            return music
        }

        override suspend fun musicAsVisitor(endpoint: WatchEndpoint, visitorData: String?): Result<NextResult> {
            asked += "visitor with $visitorData"
            if (slowMs > 0) delay(slowMs)
            return visitor
        }

        override suspend fun issuedVisitorData(videoId: String): String? {
            asked += "mint for $videoId"
            return issued
        }

        override suspend fun mix(videoId: String, rung: Rung, visitorData: String?): Result<NextResult> {
            asked += "${rung.name} of $videoId"
            return if (rung == Rung.WEB_MIX) web else vr
        }
    }

    private var now = 1_000_000L
    private fun ladder(wire: Wire) = RadioLadder(wire, clock = { now })
    private val radioOfA = WatchEndpoint(videoId = "A")

    @Test
    fun `a radio YouTube Music gives is asked once, as it always was`() = runBlocking {
        val wire = Wire().apply { music = Result.success(answer("A", "B", "C")) }
        val answer = ladder(wire).radio(radioOfA)
        assertEquals(Rung.MUSIC, answer.from)
        assertEquals(listOf("A", "B", "C"), answer.result!!.items.map { it.id })
        assertEquals(listOf("music"), wire.asked)
        assertNull(answer.musicFailure)
    }

    @Test
    fun `refused, it is asked again as a visitor, with a visitorData issued now, which is kept`() = runBlocking {
        val refused = refusal(HttpStatusCode.Unauthorized)
        val wire = Wire().apply {
            music = Result.failure(refused)
            visitor = Result.success(answer("A", "B"))
        }
        val ladder = ladder(wire)
        val answer = ladder.radio(radioOfA)
        assertEquals(Rung.MUSIC_AS_VISITOR, answer.from)
        assertEquals(listOf("music", "mint for A", "visitor with fresh-1"), wire.asked)
        // The account's refusal is still told, for the sign-in's note.
        assertEquals(refused, answer.musicFailure)

        // The next radio, a top-up a song later: YouTube Music refused a moment ago is passed over,
        // and the visitorData that worked is used again rather than a new one asked for.
        wire.asked.clear()
        now += 60_000
        val next = ladder.radio(WatchEndpoint(videoId = "B"))
        assertEquals(Rung.MUSIC_AS_VISITOR, next.from)
        assertEquals(listOf("visitor with fresh-1"), wire.asked)
    }

    @Test
    fun `refused as a visitor too, YouTube's own mix is asked, from its web client and then its VR client`() = runBlocking {
        val wire = Wire().apply {
            music = Result.failure(refusal(HttpStatusCode.Unauthorized))
            visitor = Result.failure(refusal(HttpStatusCode.Forbidden))
            web = Result.failure(refusal(HttpStatusCode.Forbidden))
            vr = Result.success(answer("A", "X", "Y"))
        }
        val answer = ladder(wire).radio(radioOfA)
        assertEquals(Rung.VR_MIX, answer.from)
        assertEquals(listOf("music", "mint for A", "visitor with fresh-1", "WEB_MIX of A", "VR_MIX of A"), wire.asked)
        assertEquals(listOf("YouTube Music: HTTP 401", "YouTube Music as a visitor: HTTP 403", "YouTube's mix (WEB): HTTP 403", "YouTube's mix (ANDROID_VR): 3 songs"), answer.trail)
    }

    @Test
    fun `when every step is refused, nothing is asked again for a while, then all of it is`() = runBlocking {
        val unauthorized = refusal(HttpStatusCode.Unauthorized)
        val wire = Wire().apply {
            music = Result.failure(unauthorized)
            visitor = Result.failure(refusal(HttpStatusCode.Forbidden))
            web = Result.failure(refusal(HttpStatusCode.Forbidden))
            vr = Result.failure(refusal(HttpStatusCode.TooManyRequests))
        }
        val ladder = ladder(wire)
        val first = ladder.radio(radioOfA)
        assertNull(first.result)
        assertNull(first.from)
        // The first failure is the one to report: YouTube Music's.
        assertEquals(unauthorized, first.failure)
        assertEquals(5, wire.asked.size)

        // A top-up a minute later asks nothing: the radio goes on from the phone.
        wire.asked.clear()
        now += 60_000
        val second = ladder.radio(WatchEndpoint(videoId = "B"))
        assertTrue(wire.asked.toString(), wire.asked.isEmpty())
        assertTrue(second.failure is RadioLadder.Refused)

        // Ten minutes on, it is all asked again.
        now += RadioLadder.REFUSED_MS
        wire.music = Result.success(answer("C", "D"))
        val third = ladder.radio(WatchEndpoint(videoId = "C"))
        assertEquals(Rung.MUSIC, third.from)
        assertEquals(listOf("music"), wire.asked)
    }

    @Test
    fun `an answer with the song alone is no radio, and is given back for its related songs when nothing else is`() = runBlocking {
        val related = BrowseEndpoint(browseId = "MPTRt_A")
        val wire = Wire().apply {
            // Through Tor, YouTube Music's radio was its first song twice: its page and its automix.
            music = Result.success(answer("A", "A", related = related))
            visitor = Result.success(answer("A"))
            web = Result.failure(com.zionhuang.innertube.YouTube.NoMixException("WEB"))
            vr = Result.failure(com.zionhuang.innertube.YouTube.NoMixException("ANDROID_VR"))
        }
        val ladder = ladder(wire)
        val answer = ladder.radio(radioOfA)
        assertNull(answer.from)
        assertNull(answer.failure)
        assertEquals(related, answer.result!!.relatedEndpoint)
        assertEquals(listOf("music", "mint for A", "visitor with fresh-1", "WEB_MIX of A", "VR_MIX of A"), wire.asked)

        // An answer is not a refusal: the next radio asks YouTube Music first again.
        wire.asked.clear()
        wire.music = Result.success(answer("B", "C"))
        assertEquals(Rung.MUSIC, ladder.radio(WatchEndpoint(videoId = "B")).from)
        assertEquals(listOf("music"), wire.asked)
    }

    @Test
    fun `the song alone from YouTube Music, a mix from YouTube`() = runBlocking {
        val wire = Wire().apply {
            music = Result.success(answer("A"))
            visitor = Result.success(answer("A"))
            web = Result.success(answer("A", "B", "C", "D"))
        }
        val answer = ladder(wire).radio(radioOfA)
        assertEquals(Rung.WEB_MIX, answer.from)
        assertEquals(4, answer.result!!.items.size)
    }

    @Test
    fun `no answer at all is the network's, and nothing else is tried`() = runBlocking {
        val offline = UnknownHostException("Unable to resolve host \"music.youtube.com\"")
        val wire = Wire().apply { music = Result.failure(offline) }
        val answer = ladder(wire).radio(radioOfA)
        assertEquals(listOf("music"), wire.asked)
        assertEquals(offline, answer.failure)
    }

    @Test
    fun `a next page of YouTube Music's own is asked of YouTube Music alone`() = runBlocking {
        val wire = Wire().apply { music = Result.failure(refusal(HttpStatusCode.Unauthorized)) }
        ladder(wire).radio(radioOfA, continuation = "token")
        assertEquals(listOf("music token"), wire.asked)
    }

    @Test
    fun `with a content switch on, YouTube's mixes are left out, their songs carry no marks`() = runBlocking {
        val wire = Wire().apply {
            music = Result.failure(refusal(HttpStatusCode.Forbidden))
            visitor = Result.failure(refusal(HttpStatusCode.Forbidden))
            mixesAllowed = false
        }
        ladder(wire).radio(radioOfA)
        assertEquals(listOf("music", "mint for A", "visitor with fresh-1"), wire.asked)
    }

    @Test
    fun `a mix or a list asked for by its own id is asked again as a visitor, and has no mix of a song`() = runBlocking {
        val wire = Wire().apply {
            music = Result.failure(refusal(HttpStatusCode.Unauthorized))
            visitor = Result.success(answer("P1", "P2"))
        }
        val answer = ladder(wire).radio(WatchEndpoint(playlistId = "RDCLAK5uy_mix"))
        assertEquals(Rung.MUSIC_AS_VISITOR, answer.from)
        // No song to mint a visitorData with: a new visitor, whose answer carries one.
        assertEquals(listOf("music", "visitor with null"), wire.asked)
    }

    @Test
    fun `a step that does not answer in its time is given up, and the next one asked`() = runBlocking {
        val wire = Wire().apply {
            music = Result.failure(refusal(HttpStatusCode.Unauthorized))
            visitor = Result.success(answer("A", "B"))
            slowMs = 5_000
            capMs = 50
            web = Result.success(answer("A", "W"))
        }
        val answer = ladder(wire).radio(radioOfA)
        assertEquals(Rung.WEB_MIX, answer.from)
        assertTrue(answer.trail.toString(), answer.trail[1].startsWith("YouTube Music as a visitor: TimedOut"))
    }

    @Test
    fun `a mint that failed is not asked again for a while, and another network starts over`() = runBlocking {
        val wire = Wire().apply {
            music = Result.failure(refusal(HttpStatusCode.Unauthorized))
            visitor = Result.failure(IllegalStateException("parse"))
            web = Result.success(answer("A", "W"))
            issued = null
        }
        val ladder = ladder(wire)
        ladder.radio(radioOfA)
        assertEquals(listOf("music", "mint for A", "visitor with null", "WEB_MIX of A"), wire.asked)

        wire.asked.clear()
        ladder.radio(WatchEndpoint(videoId = "B"))
        // YouTube Music refused a moment ago; the mint failed a moment ago; the visitor's failure was
        // no refusal, so it is asked again, as a new visitor.
        assertEquals(listOf("visitor with null", "WEB_MIX of B"), wire.asked)

        wire.asked.clear()
        ladder.forget()
        wire.issued = "fresh-2"
        ladder.radio(WatchEndpoint(videoId = "C"))
        assertEquals(listOf("music", "mint for C", "visitor with fresh-2", "WEB_MIX of C"), wire.asked)
    }
}
