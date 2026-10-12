/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.YouTubeClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `a request for a web client's address gets those headers, over whatever it had set, and keeps the rest`() {
        val asked = Request.Builder().url(address("WEB_REMIX")).header("Range", "bytes=2097152-2097167").header("User-Agent", "okhttp/5.1.0").build()
        val sent = WebStreamHeaders.on(asked)
        assertEquals(YouTubeClient.USER_AGENT_WEB, sent.header("User-Agent"))
        assertEquals("one User-Agent, the page's, and not the library's beside it", 1, sent.headers("User-Agent").size)
        assertEquals("https://music.youtube.com", sent.header("Origin"))
        assertEquals("https://music.youtube.com/", sent.header("Referer"))
        assertEquals("the range it asked for is still asked for", "bytes=2097152-2097167", sent.header("Range"))
        assertEquals(asked.url, sent.url)
        assertEquals("GET", sent.method)
    }

    @Test
    fun `a check keeps being a check, and any other request is handed back as it came`() {
        val check = WebStreamHeaders.on(Request.Builder().url(address("WEB_REMIX")).head().build())
        assertEquals("HEAD", check.method)
        assertEquals("https://music.youtube.com", check.header("Origin"))

        val app = Request.Builder().url(address("VISIONOS")).head().build()
        assertSame(app, WebStreamHeaders.on(app))
        assertNull(app.header("Origin"))
        assertNull(app.header("User-Agent"))
    }

    // ---- nothing but the address decides ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a flag is not taken for a check. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `no flag and no kind of build stands between a web client's address and its headers`() {
        // They were put on only where the developer options' "Ask YouTube Music's web client
        // first" exists, which no release has. A flag is a constant of the build and a test
        // cannot turn it, so this reads the source: neither file so much as names one, in its
        // comments either.
        for (path in listOf("utils/WebStreamHeaders.kt", "utils/StreamFamily.kt")) {
            val text = File(sources, path).readText()
            assertFalse("$path names the flags", "Unreleased" in text)
            assertFalse("$path names the kind of build", "BuildConfig" in text)
        }
    }

    @Test
    fun `the developer's switch decides who is asked first and nothing about how a stream is fetched`() {
        // One read in each place its doc comment names: the row, the stored switch, and the walk
        // of the chain. The check's client and the player's each had a second one.
        val reads = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .associate { it.relativeTo(sources).path to Regex("""Unreleased\.WEB_CLIENT_FIRST\b""").findAll(code(it.readText())).count() }
            .filterValues { it > 0 }
        assertEquals(
            mapOf(
                "playback/MusicService.kt" to 1,
                "ui/screens/settings/fragments/DeveloperFrag.kt" to 1,
                "utils/YTPlayerUtils.kt" to 1,
            ),
            reads,
        )
        val walk = code(File(sources, "utils/YTPlayerUtils.kt").readText())
        assertTrue(Regex("""val trialOn = Unreleased\.WEB_CLIENT_FIRST && askWebClientFirst""").containsMatchIn(walk))
        // And none of the three places that fetch a stream puts the headers on for itself, where
        // one could again be left out: StreamFamily.Calls does it for all of them.
        for (path in listOf("utils/YTPlayerUtils.kt", "playback/MusicService.kt", "playback/DownloadUtil.kt")) {
            assertFalse("$path has headers of its own for a stream", "WebStreamHeaders" in code(File(sources, path).readText()))
        }
    }
}
