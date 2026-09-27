/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole server, on a loopback port, against a fake source, fetched with OkHttp the way a
 * receiver fetches: a HEAD, then ranges.
 *
 * The fake songs are made of bytes computed from their position, so a song of any size can be
 * checked byte for byte without being held anywhere, and each is read through [ChunkedReader] in
 * short sources, the way the app's resolver hands a remote song out 512 KB at a time.
 */
class CastServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val servers = mutableListOf<CastServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun byteAt(position: Long): Byte = ((position * 31 + position / 7) and 0xFF).toByte()

    private fun expected(from: Long, count: Int) = ByteArray(count) { byteAt(from + it) }

    /** A song of [length] bytes that reads [chunk] bytes per source, and records what it was asked. */
    private inner class FakeSong(
        override val length: Long,
        override val mimeType: String = "audio/webm",
        private val chunk: Long = 1000,
        private val gate: CountDownLatch? = null,
        private val realLength: Long = length,
    ) : CastSong {
        val largestAsk = AtomicInteger()
        val chunksOpened = AtomicInteger()

        override fun openAt(position: Long): CastReader =
            ChunkedReader(position, if (length >= 0) length else -1) { from ->
                chunksOpened.incrementAndGet()
                object : CastReader {
                    var at = from
                    val stop = minOf(from + chunk, realLength)

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        gate?.await(10, TimeUnit.SECONDS)
                        largestAsk.accumulateAndGet(length) { a, b -> maxOf(a, b) }
                        if (at >= stop) return -1
                        val count = minOf(length.toLong(), stop - at).toInt()
                        for (i in 0 until count) buffer[offset + i] = byteAt(at + i)
                        at += count
                        return count
                    }

                    override fun close() = Unit
                }
            }
    }

    private inner class FakeSource(vararg songs: Pair<String, CastSong>) : CastSource {
        val songs = ConcurrentHashMap(songs.toMap())
        val opens = AtomicInteger()
        var unavailable: String? = null

        override fun open(mediaId: String): CastSong {
            opens.incrementAndGet()
            unavailable?.let { throw CastUnavailableException(it) }
            return songs[mediaId] ?: throw CastUnavailableException("No such song")
        }
    }

    private fun serve(
        source: CastSource,
        servable: (String) -> Boolean = { true },
        maxConnections: Int = CastServer.MAX_CONNECTIONS,
        port: Int = 0,
    ): CastServer = CastServer(source, loopback, servable, port = port, maxConnections = maxConnections)
        .also { servers += it; it.start() }

    private fun CastServer.url(id: String, ext: String = "webm") =
        "http://127.0.0.1:$boundPort/c/$token/s/$id.$ext"

    private fun get(url: String, range: String? = null): Response =
        client.newCall(Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute()

    private fun head(url: String): Response = client.newCall(Request.Builder().url(url).head().build()).execute()

    @Test
    fun `HEAD describes the song without sending it`() {
        val song = FakeSong(5000)
        val server = serve(FakeSource("a" to song))
        head(server.url("a")).use {
            assertEquals(200, it.code)
            assertEquals("5000", it.header("Content-Length"))
            assertEquals("audio/webm", it.header("Content-Type"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals(0, it.body!!.bytes().size)
        }
        assertEquals(0, song.chunksOpened.get())
    }

    @Test
    fun `GET sends the whole song across its sources`() {
        val server = serve(FakeSource("a" to FakeSong(5500, chunk = 1000)))
        get(server.url("a")).use {
            assertEquals(200, it.code)
            assertArrayEquals(expected(0, 5500), it.body!!.bytes())
        }
    }

    @Test
    fun `each form of range gets the right bytes`() {
        val server = serve(FakeSource("a" to FakeSong(5500, chunk = 1000)))
        get(server.url("a"), "bytes=950-2100").use {
            assertEquals(206, it.code)
            assertEquals("bytes 950-2100/5500", it.header("Content-Range"))
            assertArrayEquals(expected(950, 1151), it.body!!.bytes())
        }
        get(server.url("a"), "bytes=4000-").use {
            assertEquals(206, it.code)
            assertEquals("bytes 4000-5499/5500", it.header("Content-Range"))
            assertArrayEquals(expected(4000, 1500), it.body!!.bytes())
        }
        get(server.url("a"), "bytes=-10").use {
            assertEquals(206, it.code)
            assertEquals("bytes 5490-5499/5500", it.header("Content-Range"))
            assertArrayEquals(expected(5490, 10), it.body!!.bytes())
        }
        get(server.url("a"), "bytes=0-0").use {
            assertEquals(206, it.code)
            assertArrayEquals(expected(0, 1), it.body!!.bytes())
        }
    }

    @Test
    fun `a range past the end is 416`() {
        val server = serve(FakeSource("a" to FakeSong(5500)))
        get(server.url("a"), "bytes=5500-").use {
            assertEquals(416, it.code)
            assertEquals("bytes */5500", it.header("Content-Range"))
            assertEquals(0, it.body!!.bytes().size)
        }
    }

    @Test
    fun `a url without the session's token names nothing`() {
        val source = FakeSource("a" to FakeSong(100))
        val server = serve(source)
        val base = "http://127.0.0.1:${server.boundPort}"
        for (url in listOf(
            "$base/c/${CastHttp.newToken()}/s/a.webm",
            "$base/c/${server.token.dropLast(1)}/s/a.webm",
            "$base/c/${server.token.uppercase()}x/s/a.webm",
            "$base/s/a.webm",
            "$base/a.webm",
            "$base/",
        )) {
            get(url).use { assertEquals(url, 404, it.code) }
            head(url).use { assertEquals(url, 404, it.code) }
        }
        assertEquals(0, source.opens.get())
    }

    @Test
    fun `a song outside the session is refused before anything is resolved`() {
        val source = FakeSource("a" to FakeSong(100), "b" to FakeSong(100))
        val server = serve(source, servable = { it == "a" })
        get(server.url("b")).use { assertEquals(404, it.code) }
        assertEquals(0, source.opens.get())
        get(server.url("a")).use { assertEquals(200, it.code) }
    }

    @Test
    fun `leaving the session takes effect on the next request`() {
        var inQueue = true
        val server = serve(FakeSource("a" to FakeSong(100)), servable = { inQueue })
        get(server.url("a")).use { assertEquals(200, it.code) }
        inQueue = false
        get(server.url("a")).use { assertEquals(404, it.code) }
    }

    @Test
    fun `a song that cannot be had is 503 with the reason`() {
        val source = FakeSource("a" to FakeSong(100)).apply { unavailable = "No internet" }
        val server = serve(source)
        get(server.url("a")).use {
            assertEquals(503, it.code)
            assertEquals("No internet\n", it.body!!.string())
        }
        // Tried again on the next request, not remembered as a failure.
        source.unavailable = null
        get(server.url("a")).use { assertEquals(200, it.code) }
        assertEquals(2, source.opens.get())
    }

    @Test
    fun `a song is resolved once for its HEAD, its GET and every seek`() {
        val source = FakeSource("a" to FakeSong(5000))
        val server = serve(source)
        head(server.url("a")).close()
        get(server.url("a")).use { it.body!!.bytes() }
        get(server.url("a"), "bytes=2000-").use { it.body!!.bytes() }
        get(server.url("a"), "bytes=-100").use { it.body!!.bytes() }
        assertEquals(1, source.opens.get())
    }

    @Test
    fun `forget resolves a song again`() {
        val source = FakeSource("a" to FakeSong(5000))
        val server = serve(source)
        head(server.url("a")).close()
        server.forget("a")
        head(server.url("a")).close()
        assertEquals(2, source.opens.get())
    }

    @Test
    fun `methods other than GET, HEAD and OPTIONS are refused`() {
        val server = serve(FakeSource("a" to FakeSong(100)))
        val post = Request.Builder().url(server.url("a")).post("x".repeat(20_000).toRequestBody()).build()
        client.newCall(post).execute().use {
            assertEquals(405, it.code)
            assertEquals("GET, HEAD, OPTIONS", it.header("Allow"))
        }
    }

    @Test
    fun `a CORS preflight is answered`() {
        val server = serve(FakeSource("a" to FakeSong(100)))
        client.newCall(Request.Builder().url(server.url("a")).method("OPTIONS", null).build()).execute().use {
            assertEquals(204, it.code)
            assertEquals("*", it.header("Access-Control-Allow-Origin"))
            assertEquals("Range", it.header("Access-Control-Allow-Headers"))
        }
    }

    @Test
    fun `a big song is streamed in pieces and arrives whole`() {
        val length = 24L * 1024 * 1024
        val song = FakeSong(length, chunk = 512 * 1024)
        val server = serve(FakeSource("a" to song))
        get(server.url("a")).use { response ->
            assertEquals(length.toString(), response.header("Content-Length"))
            val stream = response.body!!.byteStream()
            val buffer = ByteArray(100_000)
            var at = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    if (buffer[i] != byteAt(at + i)) fail("Byte ${at + i} differs")
                }
                at += count
            }
            assertEquals(length, at)
        }
        assertTrue("asked for ${song.largestAsk.get()}", song.largestAsk.get() <= 64 * 1024)
        assertEquals(48, song.chunksOpened.get())
    }

    @Test
    fun `a song whose length is unknown is sent whole and ended by closing`() {
        val server = serve(FakeSource("a" to FakeSong(-1, chunk = 10_000, realLength = 3000)))
        get(server.url("a"), "bytes=100-").use {
            assertEquals(200, it.code)
            assertNull(it.header("Content-Length"))
            assertEquals("none", it.header("Accept-Ranges"))
            assertArrayEquals(expected(0, 3000), it.body!!.bytes())
        }
    }

    @Test
    fun `a song that ends early cuts the answer short rather than padding it`() {
        // Said to be 5000 bytes, has 3000.
        val server = serve(FakeSource("a" to FakeSong(5000, chunk = 1000, realLength = 3000)))
        get(server.url("a")).use {
            assertEquals("5000", it.header("Content-Length"))
            try {
                it.body!!.bytes()
                fail("A short body was read as complete")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun `prepare says where the song is and what it is`() {
        val server = serve(FakeSource("a.b" to FakeSong(4321, mimeType = "audio/mp4")))
        val offer = server.prepare("a.b")
        assertEquals("audio/mp4", offer.mimeType)
        assertEquals(4321L, offer.length)
        assertEquals("http://127.0.0.1:${server.boundPort}/c/${server.token}/s/a%2Eb.m4a", offer.url)
        get(offer.url).use { assertArrayEquals(expected(0, 4321), it.body!!.bytes()) }
    }

    @Test
    fun `a request head that never ends is refused`() {
        val server = serve(FakeSource("a" to FakeSong(100)))
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(
                ("GET /c/${server.token}/s/a.webm HTTP/1.1\r\nX-Pad: " + "a".repeat(10_000) + "\r\n\r\n").toByteArray()
            )
            val answer = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 431 Request Header Fields Too Large", answer)
        }
    }

    @Test
    fun `a request head trickled in a byte at a time is cut off at its deadline`() {
        val server = CastServer(FakeSource("a" to FakeSong(100)), loopback, { true }, headTimeoutMs = 600)
            .also { servers += it; it.start() }
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            output.write("GET /c/${server.token}/s/a.webm HTTP/1.1\r\n".toByteArray())
            val started = System.nanoTime()
            // Each byte well inside any per-read timeout, never finishing the head.
            val trickle = Thread {
                runCatching {
                    repeat(50) {
                        output.write('X'.code)
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            }.apply { start() }
            val answer = try {
                socket.getInputStream().read()
            } catch (e: IOException) {
                -1
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            trickle.interrupt()
            assertEquals(-1, answer)
            assertTrue("closed after $elapsedMs ms", elapsedMs in 400..2500)
        }
    }

    @Test
    fun `a request that is not HTTP is refused`() {
        val server = serve(FakeSource("a" to FakeSong(100)))
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("HELLO\r\n\r\n".toByteArray())
            assertEquals("HTTP/1.1 400 Bad Request", socket.getInputStream().bufferedReader().readLine())
        }
    }

    @Test
    fun `connections past the cap are told the server is busy`() {
        val gate = CountDownLatch(1)
        val server = serve(FakeSource("a" to FakeSong(100_000, gate = gate)), maxConnections = 2)
        // Each of these has its head and then waits for the gate, holding its worker.
        val held = List(2) { get(server.url("a")) }
        try {
            get(server.url("a")).use {
                assertEquals(503, it.code)
                assertEquals("Busy\n", it.body!!.string())
            }
        } finally {
            gate.countDown()
            held.forEach { it.use { response -> assertEquals(100_000, response.body!!.bytes().size) } }
        }
    }

    @Test
    fun `stop ends a song being sent and refuses new connections`() {
        val server = serve(FakeSource("a" to FakeSong(64L * 1024 * 1024, chunk = 512 * 1024)))
        val port = server.boundPort
        get(server.url("a")).use { response ->
            val stream = response.body!!.byteStream()
            stream.read(ByteArray(1000))
            server.stop()
            assertFalse(server.isRunning)
            assertEquals(-1, server.boundPort)
            try {
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) >= 0) Unit
                fail("The song carried on after stop")
            } catch (expected: IOException) {
            }
        }
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000) }
            fail("Still listening after stop")
        } catch (expected: IOException) {
        }
        server.stop()
    }

    @Test
    fun `a server is started once`() {
        val server = serve(FakeSource())
        try {
            server.start()
            fail("Started twice")
        } catch (expected: IllegalStateException) {
        }
        server.stop()
        try {
            server.start()
            fail("Started again after stop")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `it listens on its own address and no other`() {
        val server = serve(FakeSource("a" to FakeSong(100)))
        // On Linux all of 127/8 is loopback, so this reaches the same machine on another address.
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.2", server.boundPort), 2000) }
            fail("Reachable on an address it was not bound to")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun `a port already taken gives way to one the system picks`() {
        val first = serve(FakeSource("a" to FakeSong(100)))
        val second = serve(FakeSource("a" to FakeSong(200)), port = first.boundPort)
        assertTrue(second.isRunning)
        assertTrue(second.boundPort != first.boundPort)
        get(second.url("a")).use { assertEquals("200", it.header("Content-Length")) }
    }

    @Test
    fun `the preferred port is used when it is free`() {
        val probe = java.net.ServerSocket(0, 1, loopback)
        val free = probe.localPort
        probe.close()
        val server = serve(FakeSource(), port = free)
        assertEquals(free, server.boundPort)
    }
}
