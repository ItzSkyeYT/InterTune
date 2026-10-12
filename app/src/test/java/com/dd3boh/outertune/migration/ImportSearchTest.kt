/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.body.SearchBody
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the import's searches go out.
 *
 * An import is hundreds of searches that nobody typed. They are not the account's searches, so
 * they carry no sign-in whatever "Use login for browsing content" says. No request is made here:
 * this reads what a search would send.
 */
class ImportSearchTest {

    /** A client signed in, with browsing as the account on, as App sets it up by default. */
    private fun signedIn() = InnerTube().apply {
        locale = YouTubeLocale(gl = "FR", hl = "fr")
        visitorData = "visitor-1"
        dataSyncId = "channel-1"
        cookie = "SID=one; SAPISID=two; __Secure-3PAPISID=three"
        useLoginForBrowse = true
    }

    private fun search(client: InnerTube, anonymous: Boolean) = HttpRequestBuilder().apply {
        with(client) { searchRequest(YouTubeClient.WEB_REMIX, query = "Kids MGMT", params = "songs", continuation = null, anonymous = anonymous) }
    }

    /** The body as the client writes it: its Json leaves out what is null and keeps the defaults. */
    private fun HttpRequestBuilder.sent(): JsonObject = Json {
        explicitNulls = false
        encodeDefaults = true
    }.encodeToJsonElement(SearchBody.serializer(), body as SearchBody).jsonObject

    /** Whose request the body says it is. */
    private fun HttpRequestBuilder.onBehalfOf(): String? =
        sent()["context"]!!.jsonObject["user"]!!.jsonObject["onBehalfOfUser"]?.jsonPrimitive?.content

    @Test
    fun `a search of the app's own goes out as the account, as it did`() {
        val request = search(signedIn(), anonymous = false)
        assertEquals("SID=one; SAPISID=two; __Secure-3PAPISID=three", request.headers["cookie"])
        assertTrue(request.headers["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
        assertEquals("channel-1", request.onBehalfOf())
    }

    @Test
    fun `the import's search carries nothing of the account, whatever the setting says`() {
        val request = search(signedIn(), anonymous = true)
        assertNull("the cookie is the sign-in", request.headers["cookie"])
        assertNull(request.headers["Authorization"])
        assertNull("nobody it is on behalf of", request.onBehalfOf())
    }

    @Test
    fun `it is otherwise the search the app makes`() {
        val own = search(signedIn(), anonymous = false)
        val imports = search(signedIn(), anonymous = true)
        // The visitor's id and the language are not the account, and YouTube answers a search
        // without a visitor less readily.
        val client = imports.sent()["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("visitor-1", client["visitorData"]!!.jsonPrimitive.content)
        assertEquals("fr", client["hl"]!!.jsonPrimitive.content)
        assertEquals(own.sent()["query"], imports.sent()["query"])
        assertEquals(own.sent()["params"], imports.sent()["params"])
        assertEquals(own.headers["User-Agent"], imports.headers["User-Agent"])
        assertEquals(own.headers["X-YouTube-Client-Name"], imports.headers["X-YouTube-Client-Name"])
    }

    @Test
    fun `with browsing as the account turned off nothing changes either way`() {
        val client = signedIn().apply { useLoginForBrowse = false }
        for (anonymous in listOf(false, true)) {
            val request = search(client, anonymous)
            assertNull(request.headers["cookie"])
            assertNull(request.onBehalfOf())
        }
    }

    @Test
    fun `the import asks for its searches that way, and for songs`() {
        val source = File("src/main/java/com/dd3boh/outertune/migration/ImportSearch.kt").readText()
        assertTrue(
            "the import's search is not asked for as a visitor",
            Regex("""YouTube\.search\(\s*query,\s*YouTube\.SearchFilter\.FILTER_SONG,\s*anonymous = true\s*\)""").containsMatchIn(source),
        )
        // And it is the only search the session is given.
        val session = File("src/main/java/com/dd3boh/outertune/migration/LibraryImport.kt").readText()
        assertTrue("ImportSearch::songs" in session)
        assertFalse("the session searches by another way", "YouTube.search(" in session)
    }
}
