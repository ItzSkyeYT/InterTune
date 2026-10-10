/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.YouTubeClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stream addresses are fetched with a web page's headers. Every address here is made up. */
class WebStreamHeadersTest {
    private fun address(client: String?, host: String = "rr3---sn-test.googlevideo.com") =
        "https://$host/videoplayback?expire=1791548840&ip=198.51.100.7&itag=251&mime=audio%2Fwebm" +
            (client?.let { "&c=$it" } ?: "") + "&n=abc&sig=def&pot=TOKEN&cpn=0123456789abcdef"

    @Test
    fun `the web music client's address is fetched as its page fetches it`() {
        assertEquals(
            mapOf(
                "User-Agent" to YouTubeClient.USER_AGENT_WEB,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/",
            ),
            WebStreamHeaders.of(address("WEB_REMIX").toHttpUrl()),
        )
    }

    @Test
    fun `the embedded player's address names YouTube's own site`() {
        val headers = WebStreamHeaders.of(address("WEB_EMBEDDED_PLAYER").toHttpUrl())
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertEquals("https://www.youtube.com/", headers["Referer"])
    }

    @Test
    fun `an app client's address, one that names no client and one on another host go out as they always have`() {
        for (plain in listOf(address("VISIONOS"), address("IOS"), address("ANDROID_VR"), address(null), address("WEB_REMIX", host = "example.com"),
            address("WEB_REMIX", host = "googlevideo.com.example.org"), address("WEB_REMIX").replace("https://", "http://"))) {
            assertTrue(plain, WebStreamHeaders.of(plain.toHttpUrl()).isEmpty())
        }
    }

    /** A chain that answers 200 to whatever it is handed and keeps it. */
    private class Kept(private val request: Request) : Interceptor.Chain by throwingChain() {
        var sent: Request? = null
        override fun request() = request
        override fun proceed(request: Request): Response {
            sent = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }
    }

    @Test
    fun `the check and the player send those headers, over whatever they had set, and leave other requests alone`() {
        val web = Kept(Request.Builder().url(address("WEB_REMIX")).header("Range", "bytes=2097152-2097167").header("User-Agent", "okhttp/5.1.0").build())
        WebStreamHeaders.interceptor.intercept(web)
        assertEquals(YouTubeClient.USER_AGENT_WEB, web.sent?.header("User-Agent"))
        assertEquals("https://music.youtube.com", web.sent?.header("Origin"))
        assertEquals("the range it asked for is still asked for", "bytes=2097152-2097167", web.sent?.header("Range"))

        val app = Kept(Request.Builder().url(address("VISIONOS")).head().build())
        WebStreamHeaders.interceptor.intercept(app)
        assertNull(app.sent?.header("Origin"))
        assertNull(app.sent?.header("User-Agent"))
        assertEquals("HEAD", app.sent?.method)
    }
}

/** Every other method of a chain is unused by the interceptor, and says so if that changes. */
private fun throwingChain(): Interceptor.Chain = java.lang.reflect.Proxy.newProxyInstance(
    Interceptor.Chain::class.java.classLoader,
    arrayOf(Interceptor.Chain::class.java),
) { _, method, _ -> error("${method.name} was not expected to be called") } as Interceptor.Chain
