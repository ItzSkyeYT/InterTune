/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.body.NextBody
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A radio started by somebody signed out names no account (Val, 4 Nov 2026).
 *
 * YouTube Music's page gives a signed-out visitor a DATASYNC_ID ("V2e6a5248||"), and the sign-in
 * page kept it from any page of YouTube Music, signed in or not. Every radio start then went out
 * on behalf of that id with no cookie to prove it, and YouTube answered each one 401, "missing
 * required authentication credential", on Wi-Fi and on mobile data alike. The same request naming
 * no account gets its 50 songs (experiments/bugs/val-1104/probe_next.py).
 */
class RadioAsVisitorTest {

    private fun client(dataSyncId: String?, cookie: String?) = InnerTube().apply {
        visitorData = "visitor-1"
        this.dataSyncId = dataSyncId
        this.cookie = cookie
    }

    /** A radio's first request, as MusicService asks for one. */
    private fun radio(client: InnerTube) = HttpRequestBuilder().apply {
        with(client) {
            nextRequest(YouTubeClient.WEB_REMIX, videoId = "dUx9LSK0p6Y", playlistId = "RDAMVMdUx9LSK0p6Y", playlistSetVideoId = null, index = null, params = "wAEB")
        }
    }

    /** Whose request the body says it is, as the client writes it. */
    private fun HttpRequestBuilder.onBehalfOf(): String? = Json {
        explicitNulls = false
        encodeDefaults = true
    }.encodeToJsonElement(NextBody.serializer(), body as NextBody)
        .jsonObject["context"]!!.jsonObject["user"]!!.jsonObject["onBehalfOfUser"]?.jsonPrimitive?.content

    @Test
    fun `signed out, a radio names no account, whatever id the sign-in page left behind`() {
        val request = radio(client(dataSyncId = "V2e6a5248", cookie = null))
        assertNull(request.onBehalfOf())
        assertNull(request.headers["cookie"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `a visitor's cookie does not make the id an account's`() {
        assertNull(radio(client(dataSyncId = "V2e6a5248", cookie = "YSC=abc; VISITOR_INFO1_LIVE=def")).onBehalfOf())
    }

    @Test
    fun `signed in, a radio is the account's, as it was`() {
        val request = radio(client(dataSyncId = "channel-1", cookie = "SID=one; SAPISID=two"))
        assertEquals("channel-1", request.onBehalfOf())
        assertTrue(request.headers["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
    }

    @Test
    fun `every request reads the id a sign-in proves, never the stored one`() {
        val source = File("../innertube/src/main/java/com/zionhuang/innertube/InnerTube.kt").readText()
        assertFalse(Regex("""toContext\([^)]*\bdataSyncId\b""").containsMatchIn(source))
        assertFalse("dataSyncId else null" in source)
        assertTrue("""get() = dataSyncId?.takeIf { it.isNotBlank() && "SAPISID" in cookieMap }""" in source)
    }

    @Test
    fun `the sign-in page keeps an id only from a page that is signed in`() {
        val login = File("src/main/java/com/dd3boh/outertune/ui/screens/LoginScreen.kt").readText()
        val keep = login.indexOf("keep { it[DataSyncIdKey] = newDataSyncId")
        assertTrue(keep >= 0)
        assertTrue(
            "the id is kept behind a check of the page's sign-in",
            "if (isYouTubeMusicPage(page) && WhyStopped.signedIn(cookie)) {" in login.substring(maxOf(0, keep - 400), keep),
        )
    }
}
