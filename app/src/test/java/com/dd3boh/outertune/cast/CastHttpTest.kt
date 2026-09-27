/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class CastHttpTest {

    // Range

    private fun range(header: String?, total: Long = 1000) = CastHttp.parseRange(header, total)

    @Test
    fun `no range or a blank one is the whole song`() {
        assertEquals(RangeAnswer.Whole, range(null))
        assertEquals(RangeAnswer.Whole, range(""))
        assertEquals(RangeAnswer.Whole, range("   "))
    }

    @Test
    fun `a closed range is served as asked`() {
        assertEquals(RangeAnswer.Part(0, 99), range("bytes=0-99"))
        assertEquals(RangeAnswer.Part(500, 500), range("bytes=500-500"))
        assertEquals(100L, (range("bytes=0-99") as RangeAnswer.Part).length)
    }

    @Test
    fun `an open range runs to the end`() {
        assertEquals(RangeAnswer.Part(0, 999), range("bytes=0-"))
        assertEquals(RangeAnswer.Part(999, 999), range("bytes=999-"))
    }

    @Test
    fun `a suffix range is the last n bytes`() {
        assertEquals(RangeAnswer.Part(900, 999), range("bytes=-100"))
        assertEquals(RangeAnswer.Part(999, 999), range("bytes=-1"))
    }

    @Test
    fun `a suffix longer than the song is the whole song as a part`() {
        assertEquals(RangeAnswer.Part(0, 999), range("bytes=-5000"))
        assertEquals(RangeAnswer.Part(0, 999), range("bytes=-99999999999999999999999"))
    }

    @Test
    fun `an end past the song is cut to it`() {
        assertEquals(RangeAnswer.Part(900, 999), range("bytes=900-5000"))
        assertEquals(RangeAnswer.Part(0, 999), range("bytes=0-99999999999999999999999"))
    }

    @Test
    fun `a start at or past the end cannot be satisfied`() {
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=1000-"))
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=1000-2000"))
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=99999999999999999999999-"))
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=-0"))
    }

    @Test
    fun `an empty song satisfies no range`() {
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=0-", total = 0))
        assertEquals(RangeAnswer.Unsatisfiable, range("bytes=-10", total = 0))
        assertEquals(RangeAnswer.Whole, range(null, total = 0))
    }

    @Test
    fun `the unit is read without regard to case and space`() {
        assertEquals(RangeAnswer.Part(0, 99), range("Bytes=0-99"))
        assertEquals(RangeAnswer.Part(0, 99), range("  BYTES = 0-99 "))
    }

    @Test
    fun `several ranges get the whole song`() {
        assertEquals(RangeAnswer.Whole, range("bytes=0-99,200-299"))
        assertEquals(RangeAnswer.Whole, range("bytes=0-99, -100"))
    }

    @Test
    fun `an empty list element beside one range still reads as that range`() {
        assertEquals(RangeAnswer.Part(0, 99), range("bytes=0-99,"))
    }

    @Test
    fun `a header that cannot be read is ignored, not refused`() {
        for (header in listOf(
            "bytes", "bytes=", "bytes=abc", "bytes=10", "bytes=--5", "bytes=5-3",
            "bytes=+5-", "bytes=5-+9", "bytes=1 0-", "items=0-99", "0-99", "bytes=-",
        )) {
            assertEquals(header, RangeAnswer.Whole, range(header))
        }
    }

    @Test
    fun `with the length unknown every range gets the whole song`() {
        assertEquals(RangeAnswer.Whole, range("bytes=0-99", total = -1))
        assertEquals(RangeAnswer.Whole, range("bytes=500-", total = -1))
    }

    // The token

    @Test
    fun `a token is 128 bits of hex and new each time`() {
        val tokens = (1..200).map { CastHttp.newToken() }
        for (token in tokens) {
            assertEquals(32, token.length)
            assertTrue(token, token.all { it in '0'..'9' || it in 'a'..'f' })
        }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun `only the exact token matches`() {
        val token = CastHttp.newToken()
        assertTrue(CastHttp.tokenMatches(token, token))
        assertTrue(CastHttp.tokenMatches(token, String(token.toCharArray())))
        assertFalse(CastHttp.tokenMatches(token, null))
        assertFalse(CastHttp.tokenMatches(token, ""))
        assertFalse(CastHttp.tokenMatches(token, token.dropLast(1)))
        assertFalse(CastHttp.tokenMatches(token, token + "0"))
        assertFalse(CastHttp.tokenMatches(token, token.uppercase().takeIf { it != token } ?: "x"))
        val flipped = token.substring(0, 31) + (if (token.last() == '0') '1' else '0')
        assertFalse(CastHttp.tokenMatches(token, flipped))
    }

    // Urls

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a song url names the host, port, token, id and extension`() {
        val url = CastHttp.songUrl(InetAddress.getByName("192.168.1.20"), 47800, token, "dQw4w9WgXcQ", "webm")
        assertEquals("http://192.168.1.20:47800/c/$token/s/dQw4w9WgXcQ.webm", url)
    }

    @Test
    fun `an IPv6 host goes in brackets with its zone escaped`() {
        assertEquals("[fd00:0:0:0:0:0:0:1]", CastHttp.hostForUrl(InetAddress.getByName("fd00::1")))
        val scoped = java.net.Inet6Address.getByAddress(
            null, InetAddress.getByName("fe80::1").address, 3,
        )
        assertEquals("[fe80:0:0:0:0:0:0:1%253]", CastHttp.hostForUrl(scoped))
    }

    @Test
    fun `an id with dots, slashes and other letters survives the url`() {
        for (id in listOf("LSabc.def", "a/b", "a b+c", "é-ü_~", "..", "%41", "a?b#c")) {
            val url = CastHttp.songUrl(InetAddress.getByName("10.0.0.2"), 1, token, id, "m4a")
            val path = url.substringAfter("10.0.0.2:1")
            assertEquals(id, CastPath(token, id), CastHttp.parsePath(path))
        }
    }

    @Test
    fun `the encoding leaves the plain letters of a YouTube id alone`() {
        assertEquals("aZ09-_~", CastHttp.encodeSegment("aZ09-_~"))
        assertEquals("a%2Eb", CastHttp.encodeSegment("a.b"))
        assertEquals("%C3%A9", CastHttp.encodeSegment("é"))
    }

    @Test
    fun `a malformed escape is refused`() {
        assertNull(CastHttp.decodeSegment("abc%"))
        assertNull(CastHttp.decodeSegment("abc%4"))
        assertNull(CastHttp.decodeSegment("abc%zz"))
        assertNull(CastHttp.decodeSegment("abc%+1"))
        assertEquals("a+b", CastHttp.decodeSegment("a+b"))
    }

    @Test
    fun `a path is taken apart into token and id`() {
        assertEquals(CastPath(token, "abc"), CastHttp.parsePath("/c/$token/s/abc.webm"))
        assertEquals(CastPath(token, "abc"), CastHttp.parsePath("/c/$token/s/abc"))
        assertEquals(CastPath(token, "abc"), CastHttp.parsePath("/c/$token/s/abc.webm?t=1#x"))
        assertEquals(CastPath(token, "abc"), CastHttp.parsePath("http://10.0.0.2:47800/c/$token/s/abc.webm"))
    }

    @Test
    fun `the AAC copy of a song has a url of its own`() {
        val url = CastHttp.songUrl(InetAddress.getByName("192.168.1.20"), 47800, token, "dQw4w9WgXcQ", "m4a", aac = true)
        assertEquals("http://192.168.1.20:47800/c/$token/aac/dQw4w9WgXcQ.m4a", url)
        assertEquals(CastPath(token, "dQw4w9WgXcQ", aac = true), CastHttp.parsePath(url))
        assertEquals(CastPath(token, "abc", aac = false), CastHttp.parsePath("/c/$token/s/abc.m4a?load=3"))
    }

    @Test
    fun `anything that is not a song url is refused`() {
        for (path in listOf(
            "/", "", "/c", "/c/$token", "/c/$token/s", "/c/$token/s/", "/c/$token/s/.webm",
            "/c//s/abc.webm", "/x/$token/s/abc.webm", "/c/$token/a/abc.jpg", "/c/$token/s/abc/def",
            "c/$token/s/abc.webm", "http://host", "/c/$token/s/%zz.webm", "/c/$token/AAC/abc.m4a", "/c/$token/aac/",
        )) {
            assertNull(path, CastHttp.parsePath(path))
        }
    }

    // Requests

    private fun read(text: String, max: Int = CastHttp.MAX_HEAD_BYTES) =
        CastHttp.readHead(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), max)

    @Test
    fun `a head is read up to its blank line and no further`() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\nBODY".toByteArray())
        assertEquals(HeadRead.Head("GET / HTTP/1.1\r\nHost: x\r\n\r\n"), CastHttp.readHead(input))
        assertEquals('B'.code, input.read())
    }

    @Test
    fun `bare line feeds end a head too`() {
        assertEquals(HeadRead.Head("GET / HTTP/1.0\nHost: x\n\n"), read("GET / HTTP/1.0\nHost: x\n\n"))
    }

    @Test
    fun `a head cut off before its blank line is closed`() {
        assertEquals(HeadRead.Closed, read("GET / HTTP/1.1\r\nHost: x\r\n"))
        assertEquals(HeadRead.Closed, read(""))
    }

    @Test
    fun `a head past the cap is too large`() {
        val long = "GET / HTTP/1.1\r\nX-Pad: " + "a".repeat(9000) + "\r\n\r\n"
        assertEquals(HeadRead.TooLarge, read(long))
        assertTrue(read("GET / HTTP/1.1\r\n\r\n", max = 18) is HeadRead.Head)
        assertEquals(HeadRead.TooLarge, read("GET / HTTP/1.1\r\n\r\n", max = 17))
    }

    @Test
    fun `a request line and its headers are parsed`() {
        val request = CastHttp.parseRequest(
            "GET /c/t/s/a.webm HTTP/1.1\r\nHost: 10.0.0.2\r\nRange:  bytes=0-99 \r\n" +
                "X-A: 1\r\nx-a: 2\r\nbroken line\r\n\r\n"
        )!!
        assertEquals("GET", request.method)
        assertEquals("/c/t/s/a.webm", request.target)
        assertEquals("bytes=0-99", request.header("Range"))
        assertEquals("bytes=0-99", request.header("RANGE"))
        assertEquals("1, 2", request.header("x-a"))
        assertNull(request.header("broken line"))
    }

    @Test
    fun `a request line that is not HTTP 1 is refused`() {
        assertNull(CastHttp.parseRequest("GET /\r\n\r\n"))
        assertNull(CastHttp.parseRequest("GET / HTTP/2\r\n\r\n"))
        assertNull(CastHttp.parseRequest("\r\n\r\n"))
        assertNull(CastHttp.parseRequest("GET / x HTTP/1.1\r\n\r\n"))
    }

    // Responses

    private fun request(vararg headers: Pair<String, String>) =
        CastRequest("GET", "/", headers.associate { it.first.lowercase() to it.second })

    private fun SongReply.header(name: String) = headers.lastOrNull { it.first.equals(name, true) }?.second

    @Test
    fun `a whole song is 200 with its length`() {
        val reply = CastHttp.songReply(request(), "audio/webm", 1000)
        assertEquals(200, reply.status)
        assertEquals(0L, reply.bodyStart)
        assertEquals(1000L, reply.bodyLength)
        assertEquals("audio/webm", reply.header("Content-Type"))
        assertEquals("1000", reply.header("Content-Length"))
        assertEquals("bytes", reply.header("Accept-Ranges"))
        assertNull(reply.header("Content-Range"))
    }

    @Test
    fun `a range is 206 with its span`() {
        val reply = CastHttp.songReply(request("Range" to "bytes=100-199"), "audio/mp4", 1000)
        assertEquals(206, reply.status)
        assertEquals(100L, reply.bodyStart)
        assertEquals(100L, reply.bodyLength)
        assertEquals("bytes 100-199/1000", reply.header("Content-Range"))
        assertEquals("100", reply.header("Content-Length"))
    }

    @Test
    fun `a range past the end is 416 naming the length`() {
        val reply = CastHttp.songReply(request("Range" to "bytes=5000-"), "audio/mp4", 1000)
        assertEquals(416, reply.status)
        assertEquals(0L, reply.bodyLength)
        assertEquals("bytes */1000", reply.header("Content-Range"))
        assertEquals("0", reply.header("Content-Length"))
    }

    @Test
    fun `an unknown length is served whole with no length and no ranges`() {
        val reply = CastHttp.songReply(request("Range" to "bytes=100-"), "audio/webm", -1)
        assertEquals(200, reply.status)
        assertEquals(-1L, reply.bodyLength)
        assertNull(reply.header("Content-Length"))
        assertNull(reply.header("Content-Range"))
        assertEquals("none", reply.header("Accept-Ranges"))
        assertTrue(reply.header("contentFeatures.dlna.org")!!.startsWith("DLNA.ORG_OP=00"))
    }

    @Test
    fun `every song reply carries the DLNA, CORS and caching headers`() {
        val reply = CastHttp.songReply(request(), "audio/webm", 10)
        assertEquals("Streaming", reply.header("transferMode.dlna.org"))
        assertEquals(
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            reply.header("contentFeatures.dlna.org"),
        )
        assertEquals("*", reply.header("Access-Control-Allow-Origin"))
        assertTrue(reply.header("Access-Control-Expose-Headers")!!.contains("Content-Range"))
        assertEquals("no-store", reply.header("Cache-Control"))
        assertEquals("close", reply.header("Connection"))
    }

    @Test
    fun `a head is written with CRLFs and a blank line, and a value cannot add a header`() {
        val bytes = CastHttp.head(206, listOf("A" to "1", "B" to "x\r\nEvil: yes"))
        assertEquals(
            "HTTP/1.1 206 Partial Content\r\nA: 1\r\nB: x  Evil: yes\r\n\r\n",
            String(bytes, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `a text reply says its length`() {
        val (headers, body) = CastHttp.textReply("Not found")
        assertEquals("Not found\n", String(body))
        assertEquals(body.size.toString(), headers.first { it.first == "Content-Length" }.second)
    }

    @Test
    fun `every status the server sends has its reason`() {
        for (status in listOf(200, 204, 206, 400, 404, 405, 416, 431, 500, 503)) {
            assertNotEquals(status.toString(), "Status", CastHttp.reason(status))
        }
    }
}
