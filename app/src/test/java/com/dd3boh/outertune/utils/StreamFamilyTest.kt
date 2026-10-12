/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.IpFamily.V4
import com.zionhuang.innertube.IpFamily.V6
import com.zionhuang.innertube.models.YouTubeClient
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * A stream is fetched over the family its address was issued to.
 *
 * The first part is the rule. The second is the other thing the same calls see to: a web client's
 * address is asked for with its page's headers, by all of them. The last reads the source, as
 * StreamAddressesTest does, because both are worth nothing while one of the places that fetch a
 * stream builds a client of its own: the check then passes over one family, or with the headers,
 * and the player is refused over the other, or without them.
 */
class StreamFamilyTest {
    private val host = "https://rr2---sn-25ge7nzr.googlevideo.com/videoplayback"
    private val issuedToV4 = "$host?expire=1791500000&ei=abc&ip=192.0.2.7&id=o-AB&itag=251&mime=audio%2Fwebm"
    private val issuedToV6 = "$host?expire=1791500000&ei=abc&ip=2001%3Adb8%3A12%3A3400%3A%3A7&id=o-AB&itag=251"
    private val namesNone = "$host?expire=1791500000&ei=abc&id=o-AB&itag=251"

    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")

    /** The order a resolver answers in on a network with both families: IPv6 first. */
    private val bothFamilies = Dns { listOf(v6a, v4a, v6b, v4b) }

    @Test
    fun `an address says which family it was issued to`() {
        assertEquals(V4, StreamFamily.of(issuedToV4))
        assertEquals(V6, StreamFamily.of(issuedToV6))
        assertEquals(V6, StreamFamily.of("$host?ip=2001:db8::7&itag=251"))
        assertEquals(V6, StreamFamily.of("$host?itag=251&ip=2001%3adb8%3a%3a7"))
        assertEquals(V4, StreamFamily.of("$host?itag=251&ip=203.0.113.250#t=1"))
    }

    @Test
    fun `an address that names none, or names it unreadably, says nothing`() {
        assertNull(StreamFamily.of(namesNone))
        assertNull(StreamFamily.of("$host?ip=&itag=251"))
        assertNull(StreamFamily.of("$host?ip=somewhere&itag=251"))
        assertNull(StreamFamily.of("$host?ip=1.2.3&itag=251"))
        assertNull(StreamFamily.of("not an address at all"))
        assertNull(StreamFamily.of(""))
    }

    @Test
    fun `only the ip parameter is read`() {
        // ipbits and clip end in "ip" or begin with it, and the path is not the query.
        assertNull(StreamFamily.of("$host?ipbits=0&clip=192.0.2.7&itag=251"))
        assertNull(StreamFamily.of("https://192.0.2.9/videoplayback/ip=192.0.2.7?itag=251"))
        assertEquals(V4, StreamFamily.of("$host?ipbits=0&ip=192.0.2.7&clip=2001:db8::7"))
    }

    @Test
    fun `an address issued to IPv4 is fetched over IPv4, whatever order the system answers in`() {
        val client = StreamFamily.Calls(OkHttpClient(), bothFamilies).clientFor(issuedToV4)
        assertEquals(listOf(v4a, v6a, v4b, v6b), client.dns.lookup("rr2---sn-25ge7nzr.googlevideo.com"))
        // With OkHttp's race on, IPv6 is tried first whatever the order says: see keepToFamily.
        assertFalse("the race between the families is still on", client.fastFallback)
    }

    @Test
    fun `an address issued to IPv6 is fetched over IPv6`() {
        val v4First = Dns { listOf(v4a, v6a, v4b, v6b) }
        val client = StreamFamily.Calls(OkHttpClient(), v4First).clientFor(issuedToV6)
        assertEquals(listOf(v6a, v4a, v6b, v4b), client.dns.lookup("rr2---sn-25ge7nzr.googlevideo.com"))
        assertFalse("the race between the families is still on", client.fastFallback)
    }

    @Test
    fun `the other family stays as the way out when the first does not connect`() {
        val client = StreamFamily.Calls(OkHttpClient(), bothFamilies).clientFor(issuedToV4)
        assertTrue(client.dns.lookup("rr2---sn-25ge7nzr.googlevideo.com").any { it == v6a })
        // A network with one family only has nothing to reorder, and connects as it did.
        val v6Only = StreamFamily.Calls(OkHttpClient(), Dns { listOf(v6a, v6b) }).clientFor(issuedToV4)
        assertEquals(listOf(v6a, v6b), v6Only.dns.lookup("rr2---sn-25ge7nzr.googlevideo.com"))
    }

    @Test
    fun `an address that names no family is fetched as before`() {
        val base = OkHttpClient()
        assertSame(base, StreamFamily.Calls(base, bothFamilies).clientFor(namesNone))
        assertSame(base, StreamFamily.Calls(base, bothFamilies).clientFor("https://example.org/a.mp3"))
    }

    @Test
    fun `through a proxy the family is the proxy's to choose`() {
        val base = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))).build()
        assertSame(base, StreamFamily.Calls(base, bothFamilies).clientFor(issuedToV4))
    }

    @Test
    fun `one client per family is made once and shares the connections of the one it came from`() {
        val base = OkHttpClient()
        val calls = StreamFamily.Calls(base, bothFamilies)
        val first = calls.clientFor(issuedToV4)
        assertSame(first, calls.clientFor("$host?ip=198.51.100.4&itag=140"))
        assertTrue(first !== calls.clientFor(issuedToV6))
        assertSame(base.connectionPool, first.connectionPool)
        assertSame(base.dispatcher, first.dispatcher)
    }

    @Test
    fun `a call goes to the client of its address`() {
        val asked = mutableListOf<String>()
        val recording = Dns { name -> asked += name; listOf(v6a, v4a) }
        val calls = StreamFamily.Calls(OkHttpClient(), recording)
        val call = calls.newCall(okhttp3.Request.Builder().head().url(issuedToV4).build())
        assertEquals(issuedToV4, call.request().url.toString())
        // Nothing is looked up or sent until the call is run, and this one never is.
        assertEquals(emptyList<String>(), asked)
    }

    // ---- what a web client's address is asked for with ----

    /** Answers 200 to every request in place of the network, and keeps each as it was sent. */
    private class Answering : Interceptor {
        val sent = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            sent += chain.request()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }
    }

    /** A dns that fails the test if anything is looked up: no request here may reach the network. */
    private val noNetwork = Dns { name -> throw AssertionError("$name was looked up") }

    private fun webAddress(ip: String?) = "$host?expire=1791500000&ei=abc" + (ip?.let { "&ip=$it" } ?: "") + "&id=o-AB&itag=251&c=WEB_REMIX&n=SOLVED&sig=UNSCRAMBLED"

    @Test
    fun `a web client's address is asked for as its page asks, by every call and over either family`() {
        // The check's client and the player's put the headers on themselves, and only in a build
        // with the developer's switch for the web client; a download's never did. So a release
        // would have checked and played the chain's own web client without them.
        val answering = Answering()
        val calls = StreamFamily.Calls(OkHttpClient.Builder().addInterceptor(answering).build(), noNetwork)
        val addresses = listOf(webAddress("192.0.2.7"), webAddress("2001%3Adb8%3A12%3A3400%3A%3A7"), webAddress(null))

        // As the check asks: once with HEAD, once for a few bytes far in.
        for (address in addresses) {
            calls.newCall(Request.Builder().head().url(address).build()).execute().close()
            calls.newCall(Request.Builder().url(address).header("Range", "bytes=2097152-2097167").build()).execute().close()
        }

        assertEquals(6, answering.sent.size)
        for (request in answering.sent) {
            val what = "${request.method} ${StreamFamily.of(request.url.toString())}"
            assertEquals(what, YouTubeClient.USER_AGENT_WEB, request.header("User-Agent"))
            assertEquals(what, "https://music.youtube.com", request.header("Origin"))
            assertEquals(what, "https://music.youtube.com/", request.header("Referer"))
        }
        assertEquals(listOf("HEAD", "GET", "HEAD", "GET", "HEAD", "GET"), answering.sent.map { it.method })
        assertEquals("bytes=2097152-2097167", answering.sent[1].header("Range"))
    }

    @Test
    fun `so is one fetched through a proxy`() {
        val answering = Answering()
        val proxied = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080)))
            .addInterceptor(answering)
            .build()
        StreamFamily.Calls(proxied, noNetwork).newCall(Request.Builder().head().url(webAddress("192.0.2.7")).build()).execute().close()
        assertEquals("https://music.youtube.com", answering.sent.single().header("Origin"))
    }

    @Test
    fun `an app client's address is asked for as it always was`() {
        val answering = Answering()
        val calls = StreamFamily.Calls(OkHttpClient.Builder().addInterceptor(answering).build(), noNetwork)
        for (address in listOf(issuedToV4, issuedToV6, namesNone, "$issuedToV4&c=VISIONOS", "$issuedToV4&c=IOS")) {
            calls.newCall(Request.Builder().head().url(address).build()).execute().close()
        }
        assertEquals(5, answering.sent.size)
        for (request in answering.sent) {
            assertNull(request.header("Origin"))
            assertNull(request.header("Referer"))
            assertNull("the http library names itself, further down than this", request.header("User-Agent"))
        }
    }

    // ---- the places that fetch a stream ----

    private fun source(path: String) = stripComments(File("src/main/java/com/dd3boh/outertune/$path").readText())

    @Test
    fun `the check of a stream is made over the family its address was issued to`() {
        val utils = source("utils/YTPlayerUtils.kt")
        val check = bodyOf(utils, "private fun streamStatus(")
        assertTrue("the check does not go through StreamFamily: $check", "streamCalls.newCall(" in check)
        assertFalse("the check still asks the plain client: $check", "httpClient.newCall(" in check)
        assertTrue("streamCalls is not a StreamFamily.Calls", Regex("""streamCalls\s*=\s*StreamFamily\.Calls\(""").containsMatchIn(utils))
    }

    @Test
    fun `the player fetches a stream the way the check did`() {
        val player = bodyOf(source("playback/MusicService.kt"), "private fun createCacheDataSource(")
        assertTrue("the player's requests do not go through StreamFamily: $player", "OkHttpDataSource.Factory(StreamFamily.Calls(" in squeeze(player))
    }

    @Test
    fun `so does a download`() {
        val downloads = source("playback/DownloadUtil.kt")
        val at = downloads.indexOf("private val dataSourceFactory = ResolvingDataSource.Factory(")
        assertTrue("DownloadUtil has no dataSourceFactory", at >= 0)
        val upstream = squeeze(downloads.substring(at, downloads.indexOf("{ dataSpec ->", at)))
        assertTrue("a download's requests do not go through StreamFamily: $upstream", "OkHttpDataSource.Factory(StreamFamily.Calls(" in upstream)
    }

    private fun squeeze(text: String) = text.replace(Regex("""\s+"""), "")

    /** The body of the one function [signature] starts, between its braces. */
    private fun bodyOf(text: String, signature: String): String {
        val at = text.indexOf(signature)
        assertTrue("no $signature", at >= 0)
        assertEquals("more than one $signature", -1, text.indexOf(signature, at + 1))
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        throw AssertionError("The block of $signature never closes")
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
