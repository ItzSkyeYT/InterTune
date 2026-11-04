/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.utils.AlbumAnswerPretend
import com.dd3boh.outertune.utils.WhyStopped
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Opening an album page deleted the album from the library when its request failed with
 * "NOT_FOUND" anywhere in the failure's words (audit of 2 Nov 2026, 3.22), and the Albums tab of
 * the library and Stats did the same to every album with no songs stored, which is how a saved
 * album arrives from the account until its page is opened.
 *
 * Nothing YouTube answers tells an album it removed from one it holds back for now. Asked on 2 Nov
 * 2026 (experiments/features/audit-bugs/probe/answers-20261102.txt): an album id that does not
 * exist comes back as an empty page with HTTP 200, as albums did in South Korea (#68); a sign-in
 * that no longer works is a 401; a connection that does not reach YouTube is no answer at all; and
 * no id asked was answered with a 404 NOT_FOUND. So no failure removes an album: it stays with its
 * songs, and the page says it did not load, with Retry.
 */
class AlbumKeptTest {
    /** Google's own words for an entity it has nothing under. */
    private val notFound = """{ "error": { "code": 404, "message": "Requested entity was not found.", "errors": [ { "message": "Requested entity was not found.", "domain": "global", "reason": "notFound" } ], "status": "NOT_FOUND" } }"""

    /** What the app's client throws when YouTube answers an album's browse request with [status] and [body]. */
    private fun answered(status: HttpStatusCode, body: String): Throwable {
        val client = HttpClient(MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        return runCatching {
            runBlocking { client.post("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false") }
        }.exceptionOrNull() ?: error("no failure for $status")
    }

    @Test
    fun `YouTube's NOT_FOUND reaches the album page in words that name it, and names no cause the app can see`() {
        val failure = answered(HttpStatusCode.NotFound, notFound)
        // What the old rule looked for, and found.
        assertTrue(failure.message.orEmpty().contains("NOT_FOUND"))
        assertEquals(WhyStopped.Kind.OTHER, WhyStopped.kindOf(failure))
        // The debug builds' pretend answers in the very words of the client.
        assertEquals(failure.message, AlbumAnswerPretend.NOT_FOUND_ANSWER)
    }

    @Test
    fun `no failed album request deletes the album, on its page, in the library's Albums or in Stats`() {
        for (file in listOf("AlbumViewModel.kt", "LibraryViewModels.kt", "StatsViewModel.kt")) {
            val source = File("src/main/java/com/dd3boh/outertune/viewmodels/$file").readText()
            var from = source.indexOf("YouTube.album(")
            assertTrue("$file asks for an album's page", from > 0)
            while (from > 0) {
                val failure = block(source, source.indexOf(".onFailure {", from))
                assertFalse("$file: $failure", Regex("::delete\\b|\\bdelete\\(").containsMatchIn(failure))
                from = source.indexOf("YouTube.album(", from + 1)
            }
            assertFalse("$file still reads NOT_FOUND", source.contains("\"NOT_FOUND\""))
        }
    }

    @Test
    fun `the page says it did not load over the songs it had, once the load is over and only online`() {
        assertTrue(keptAlbumNoteShown(WhyStopped.Kind.OTHER, isLoading = false, online = true))
        assertTrue(keptAlbumNoteShown(WhyStopped.Kind.SIGN_IN_REFUSED, isLoading = false, online = true))
        // Retry asking again: the note goes until the answer.
        assertFalse(keptAlbumNoteShown(WhyStopped.Kind.OTHER, isLoading = true, online = true))
        // A downloaded album opened offline is all there, and says nothing.
        assertFalse(keptAlbumNoteShown(WhyStopped.Kind.NO_ANSWER, isLoading = false, online = false))
        assertFalse(keptAlbumNoteShown(null, isLoading = false, online = true))
    }

    /** The braces' content that starts at the first { from [at]. */
    private fun block(source: String, at: Int): String {
        assertTrue(at > 0)
        val open = source.indexOf('{', at)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("no end to the block at $at")
    }
}
