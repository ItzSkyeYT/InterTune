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
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

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
        val aacSongs = ConcurrentHashMap<String, CastSong>()
        val opens = AtomicInteger()
        val aacOpens = AtomicInteger()
        var unavailable: String? = null

        override fun open(mediaId: String): CastSong {
            opens.incrementAndGet()
            unavailable?.let { throw CastUnavailableException(it) }
            return songs[mediaId] ?: throw CastUnavailableException("No such song")
        }

        override fun openAac(mediaId: String): CastSong {
            aacOpens.incrementAndGet()
            return aacSongs[mediaId] ?: throw CastUnavailableException("No AAC stream")
        }
    }

    private fun serve(
        source: CastSource,
        servable: (String) -> Boolean = { true },
        maxConnections: Int = CastServer.MAX_CONNECTIONS,
        port: Int = 0,
        pcmDecoder: PcmDecoder? = null,
    ): CastServer = CastServer(source, loopback, servable, port = port, maxConnections = maxConnections, pcmDecoder = pcmDecoder)
        .also { servers += it; it.start() }

    private fun pcmAt(frame: Long, channel: Int): Short = ((frame * 3 + channel * 1_000) % 30_000).toShort()

    /** Decodes any song into [frames] stereo frames at [rate], each sample worked out from its frame. */
    private inner class ToneDecoder(val rate: Int, val frames: Long) : PcmDecoder {
        val probes = AtomicInteger()

        override fun probe(song: CastSong): PcmSource {
            probes.incrementAndGet()
            return PcmSource(rate, 2, frames * 1_000_000L / rate)
        }

        override fun open(song: CastSong, fromUs: Long): DecodedAudio = object : DecodedAudio {
            var at = fromUs * rate / 1_000_000L
            val samples = ShortArray(2048)

            override fun next(): DecodedPiece? {
                if (at >= frames) return null
                val count = minOf(1024L, frames - at).toInt()
                for (i in 0 until count) {
                    samples[2 * i] = pcmAt(at + i, 0)
                    samples[2 * i + 1] = pcmAt(at + i, 1)
                }
                val piece = DecodedPiece(at * 1_000_000L / rate, rate, 2, samples, count)
                at += count
                return piece
            }

            override fun close() = Unit
        }
    }

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
    fun `the AAC copy is resolved and served apart from the song as it is`() {
        val source = FakeSource("v" to FakeSong(6000, mimeType = "audio/webm")).apply {
            aacSongs["v"] = FakeSong(7000, mimeType = "audio/mp4")
        }
        val server = serve(source)
        val offer = server.prepare("v", aac = true)
        assertEquals("http://127.0.0.1:${server.boundPort}/c/${server.token}/aac/v.m4a", offer.url)
        assertEquals("audio/mp4", offer.mimeType)
        assertEquals(7000L, offer.length)
        get(offer.url, "bytes=6000-").use {
            assertEquals(206, it.code)
            assertEquals("bytes 6000-6999/7000", it.header("Content-Range"))
        }
        get(server.url("v")).use { assertEquals("6000", it.header("Content-Length")) }
        assertEquals(1, source.aacOpens.get())
        assertEquals(1, source.opens.get())

        // Forgetting a song forgets both.
        server.forget("v")
        head(offer.url).close()
        head(server.url("v")).close()
        assertEquals(2, source.aacOpens.get())
        assertEquals(2, source.opens.get())
    }

    @Test
    fun `raw audio is decoded from the song as it is, described without decoding, and served from any byte`() {
        val decoder = ToneDecoder(48_000, 48_000)
        val source = FakeSource("v" to FakeSong(6000, mimeType = "audio/webm"))
        val server = serve(source, pcmDecoder = decoder)
        val offer = server.preparePcm("v", PcmTarget(listOf(PcmOffer("audio/L16;rate=48000;channels=2", 48_000, 2, "LPCM"))))
        assertEquals("http://127.0.0.1:${server.boundPort}/c/${server.token}/l16-48000-2/v.pcm", offer.url)
        assertEquals("audio/L16;rate=48000;channels=2", offer.mimeType)
        assertEquals(192_000L, offer.length)
        assertEquals(1_000L, offer.durationMs)
        head(offer.url).use {
            assertEquals(200, it.code)
            assertEquals("192000", it.header("Content-Length"))
            assertEquals("audio/L16;rate=48000;channels=2", it.header("Content-Type"))
            assertEquals("Streaming", it.header("transferMode.dlna.org"))
            assertEquals(
                "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                it.header("contentFeatures.dlna.org"),
            )
        }
        // A seek: a range from the middle of a frame, the samples big-endian.
        get(offer.url, "bytes=96002-").use {
            assertEquals(206, it.code)
            assertEquals("bytes 96002-191999/192000", it.header("Content-Range"))
            val body = it.body!!.bytes()
            assertEquals(95_998, body.size)
            assertEquals(pcmAt(24_000, 1), ((body[0].toInt() shl 8) or (body[1].toInt() and 0xFF)).toShort())
            assertEquals(pcmAt(24_001, 0), ((body[2].toInt() shl 8) or (body[3].toInt() and 0xFF)).toShort())
        }
        get(offer.url).use { assertEquals(192_000, it.body!!.bytes().size) }
        assertEquals("the song is looked at once", 1, decoder.probes.get())
        assertEquals(1, source.opens.get())

        // Forgetting the song forgets what it decodes to as well.
        server.forget("v")
        head(offer.url).close()
        assertEquals(2, decoder.probes.get())
    }

    @Test
    fun `a url with DLNA's labels names the type in the renderer's words, a plain one in InterTune's, without DLNA's headers`() {
        val server = serve(FakeSource("v" to FakeSong(6000, mimeType = "audio/mp4")))
        // Before the renderer's list is known, InterTune's words, as always.
        head(server.prepare("v").url).use { assertEquals("audio/mp4", it.header("Content-Type")) }
        server.speaksTo(setOf("audio/mpeg", "audio/x-m4a"))
        head(server.prepare("v").url).use {
            assertEquals("audio/x-m4a", it.header("Content-Type"))
            assertEquals("Streaming", it.header("transferMode.dlna.org"))
            assertTrue(it.header("contentFeatures.dlna.org")!!.startsWith("DLNA.ORG_OP=01"))
        }
        val plain = server.prepare("v", plain = true)
        assertEquals("http://127.0.0.1:${server.boundPort}/c/${server.token}/s/plain/v.m4a", plain.url)
        get(plain.url).use {
            assertEquals(200, it.code)
            assertEquals("audio/mp4", it.header("Content-Type"))
            assertNull(it.header("contentFeatures.dlna.org"))
            assertNull(it.header("transferMode.dlna.org"))
            assertArrayEquals(expected(0, 6000), it.body!!.bytes())
        }
    }

    @Test
    fun `plain raw audio is named as RFC 2586 writes it, whatever words the renderer used`() {
        val server = serve(FakeSource("v" to FakeSong(6000)), pcmDecoder = ToneDecoder(48_000, 48_000))
        val form = PcmForm(PcmContainer.L16, 48_000, 2, "AUDIO/L16;RATE=48000;CHANNELS=2")
        val dlna = server.preparePcm("v", form, plain = false)
        head(dlna.url).use { assertEquals("AUDIO/L16;RATE=48000;CHANNELS=2", it.header("Content-Type")) }
        val plain = server.preparePcm("v", form, plain = true)
        assertEquals("audio/L16;rate=48000;channels=2", plain.mimeType)
        head(plain.url).use {
            assertEquals("audio/L16;rate=48000;channels=2", it.header("Content-Type"))
            assertNull(it.header("contentFeatures.dlna.org"))
        }
    }

    @Test
    fun `the server counts what each url was fetched to, each url apart`() {
        val server = serve(FakeSource("v" to FakeSong(5000)))
        val url = server.url("v")
        assertEquals(FetchStats(), server.fetchStats(url))
        head(url).close()
        get(url, "bytes=1000-").use { assertEquals(4000, it.body!!.bytes().size) }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (server.fetchStats(url).open > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        val stats = server.fetchStats(url)
        assertEquals(2, stats.requests)
        assertEquals(4000L, stats.bodyBytes)
        assertEquals(0, stats.open)
        assertTrue(stats.lastActivityAt > 0)
        assertEquals(FetchStats(), server.fetchStats(server.prepare("v", plain = true).url))
        assertEquals(FetchStats(), server.fetchStats("http://127.0.0.1/nothing"))
    }

    @Test
    fun `a server that cannot decode says so, and sends no raw audio`() {
        val server = serve(FakeSource("v" to FakeSong(6000)))
        try {
            server.preparePcm("v", PcmTarget.LPCM)
            fail("Raw audio was offered without a decoder")
        } catch (e: CastUnavailableException) {
            assertEquals("Raw audio cannot be sent from this build", e.message)
        }
        get("http://127.0.0.1:${server.boundPort}/c/${server.token}/l16-44100-2/v.pcm").use { assertEquals(503, it.code) }
    }

    @Test
    fun `a source with no AAC says so, and a song outside the session has no AAC url either`() {
        val plain = CastSource { FakeSong(100) }
        val server = serve(plain, servable = { it == "in" })
        val url = "http://127.0.0.1:${server.boundPort}/c/${server.token}/aac/in.m4a"
        get(url).use {
            assertEquals(503, it.code)
            assertEquals("No AAC copy of this song can be had\n", it.body!!.string())
        }
        get("http://127.0.0.1:${server.boundPort}/c/${server.token}/aac/out.m4a").use { assertEquals(404, it.code) }
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
    fun `a connection that sends nothing is let go at the first byte's deadline`() {
        val server = CastServer(FakeSource("a" to FakeSong(100)), loopback, { true }, headTimeoutMs = 5_000, firstByteTimeoutMs = 300)
            .also { servers += it; it.start() }
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.soTimeout = 5000
            val started = System.nanoTime()
            val answer = try {
                socket.getInputStream().read()
            } catch (e: IOException) {
                -1
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(-1, answer)
            assertTrue("closed after $elapsedMs ms", elapsedMs in 200..2500)
        }
        // One that does send its request in time is answered as ever.
        get(server.url("a")).use { assertEquals(200, it.code) }
    }

    /** OkHttp connecting from [local], one of the other loopback addresses, as another device would. */
    private fun clientFrom(local: String): OkHttpClient = client.newBuilder().socketFactory(object : SocketFactory() {
        override fun createSocket(): Socket = Socket().apply { bind(InetSocketAddress(local, 0)) }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            Socket(address, port, localAddress, localPort)
    }).build()

    @Test
    fun `one address gets only so many connections, and others are still answered`() {
        val gate = CountDownLatch(1)
        val server = CastServer(FakeSource("a" to FakeSong(100_000, gate = gate)), loopback, { true }, maxPerPeer = 2)
            .also { servers += it; it.start() }
        // Each of these has its head and then waits for the gate, holding its place.
        val held = List(2) { get(server.url("a")) }
        val other = clientFrom("127.0.0.2")
        try {
            get(server.url("a")).use {
                assertEquals(503, it.code)
                assertEquals("Busy\n", it.body!!.string())
            }
            other.newCall(Request.Builder().url(server.url("a")).build()).execute().use { response ->
                assertEquals(200, response.code)
                gate.countDown()
                assertEquals(100_000, response.body!!.bytes().size)
            }
        } finally {
            gate.countDown()
            held.forEach { it.use { response -> assertEquals(100_000, response.body!!.bytes().size) } }
        }
        // Their places are free again once they are done.
        get(server.url("a")).use { assertEquals(200, it.code) }
    }

    @Test
    fun `connections refused in a burst do not hold up the next one`() {
        val gate = CountDownLatch(1)
        val server = CastServer(
            FakeSource("a" to FakeSong(100_000, gate = gate)), loopback, { true }, maxConnections = 1, maxPerPeer = 100,
        ).also { servers += it; it.start() }
        val held = get(server.url("a"))
        // Each is refused and then waited on for a moment, since it never closes; twelve of them one
        // after another took the accept loop three seconds.
        val silent = List(12) { Socket("127.0.0.1", server.boundPort) }
        try {
            Thread.sleep(200)
            gate.countDown()
            held.use { assertEquals(100_000, it.body!!.bytes().size) }
            val started = System.nanoTime()
            var code = 0
            while (code != 200 && System.nanoTime() - started < 5_000_000_000L) {
                code = get(server.url("a")).use { it.code }
                if (code != 200) Thread.sleep(20)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, code)
            assertTrue("answered after $elapsedMs ms", elapsedMs < 1_500)
        } finally {
            silent.forEach { runCatching { it.close() } }
        }
    }

    /** Sends a request for [id] on a raw socket and reads nothing, as a receiver that paused would. */
    private fun stalled(server: CastServer, id: String): Socket = Socket("127.0.0.1", server.boundPort).apply {
        soTimeout = 10_000
        getOutputStream().write("GET /c/${server.token}/s/$id.webm HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
    }

    /** Reads everything [socket] still has, until the end, and says how much. */
    private fun readAll(socket: Socket): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        try {
            while (true) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) break
                total += count
            }
        } catch (e: IOException) {
            // Reset by the server, after what it had sent.
        }
        return total
    }

    @Test
    fun `a connection that stopped reading is let go when its worker is wanted`() {
        val length = 64L * 1024 * 1024
        val server = CastServer(
            FakeSource("a" to FakeSong(length, chunk = 512 * 1024)), loopback, { true }, maxConnections = 1, stalledAfterMs = 300,
        ).also { servers += it; it.start() }
        stalled(server, "a").use { paused ->
            // Long enough to fill every buffer on the way and wait past the limit.
            Thread.sleep(1_500)
            // Told the server is busy once, while the stalled answer's worker lets go of it.
            val started = System.nanoTime()
            var code = 0
            while (code != 206 && System.nanoTime() - started < 10_000_000_000L) {
                code = get(server.url("a"), "bytes=0-99").use { it.code }
                if (code != 206) Thread.sleep(50)
            }
            assertEquals(206, code)
            assertTrue("the stalled answer was cut short", readAll(paused) < length)
        }
    }

    @Test
    fun `a connection that stopped reading is kept while nothing else needs its worker`() {
        val length = 64L * 1024 * 1024
        val server = CastServer(
            FakeSource("a" to FakeSong(length, chunk = 512 * 1024)), loopback, { true }, maxConnections = 2, stalledAfterMs = 300,
        ).also { servers += it; it.start() }
        stalled(server, "a").use { paused ->
            Thread.sleep(1_500)
            // A worker is free for this one, so the paused receiver keeps its own.
            get(server.url("a"), "bytes=0-99").use { assertEquals(206, it.code) }
            Thread.sleep(500)
            val all = readAll(paused)
            // Everything, after the head.
            assertTrue("read $all of $length", all > length)
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
        // The port is the system's again once the server stops, and on a busy machine another
        // program can take it at once: a Gradle or Kotlin daemon opening a loopback port failed a
        // full check on 7 Nov 2026. So what is checked is that this process no longer listens
        // there, read from Linux's own table, and where there is none, that nothing answers.
        val listening = listeningHere(port)
        if (listening != null) {
            assertFalse("Still listening after stop", listening)
        } else {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000) }
                fail("Still listening after stop")
            } catch (expected: IOException) {
            }
        }
        server.stop()
    }

    /**
     * Whether this process has a socket listening on [port], from /proc/net/tcp and tcp6 (the
     * listening sockets, by inode) and /proc/self/fd (this process's own); null without /proc.
     */
    private fun listeningHere(port: Int): Boolean? {
        val tables = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6")).filter { it.canRead() }
        val fds = File("/proc/self/fd").listFiles()
        if (tables.isEmpty() || fds == null) return null
        val hex = "%04X".format(port)
        // Each line: sl, local address (ip:port in hex), remote address, state (0A is LISTEN), ..., inode.
        val inodes = tables.flatMap { it.readLines().drop(1) }.mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size > 9 && f[1].substringAfterLast(':') == hex && f[3] == "0A") f[9] else null
        }.toSet()
        if (inodes.isEmpty()) return false
        return fds.any { fd ->
            val target = runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull()
            target != null && target.removePrefix("socket:[").removeSuffix("]") in inodes
        }
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
    fun `asked to, it also answers on loopback at the same port, and its urls still name its own address`() {
        val own = InetAddress.getByName("127.0.0.2")
        val plain = CastServer(FakeSource("a" to FakeSong(100)), own, { true }).also { servers += it; it.start() }
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", plain.boundPort), 2000) }
            fail("Reachable on loopback without being asked")
        } catch (expected: IOException) {
        }

        val server = CastServer(FakeSource("a" to FakeSong(100)), own, { true }, alsoLoopback = true)
            .also { servers += it; it.start() }
        val port = server.boundPort
        val url = server.urlFor("a", "audio/webm")
        assertTrue(url, url.startsWith("http://127.0.0.2:$port/c/"))
        get(url).use { assertEquals(200, it.code) }
        get(url.replace("127.0.0.2", "127.0.0.1"), "bytes=10-19").use { response ->
            assertEquals(206, response.code)
            assertArrayEquals(expected(10, 10), response.body!!.bytes())
        }

        server.stop()
        for (host in listOf("127.0.0.1", "127.0.0.2")) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
                fail("Still listening on $host after stop")
            } catch (expected: IOException) {
            }
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
