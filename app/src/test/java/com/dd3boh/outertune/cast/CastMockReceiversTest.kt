/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The whole path without hardware: the real cast server serving real audio files, the real links
 * driving Python mock receivers, and each mock fetching the songs from the server as a receiver
 * would, with its log read back to see what it got.
 *
 * The mocks (mock_fcast.py, mock_dlna.py, mock_cast.py and the receiver_common.py they share) are
 * not part of the app. With python3 and ffmpeg on the machine, this runs when CAST_MOCKS_DIR names
 * the folder that holds them, and is skipped otherwise (CAST_MOCKS_KEEP=1 as well keeps their
 * logs). The Chromecast cases also need openssl, with which mock_cast.py makes its certificate:
 *
 *     CAST_MOCKS_DIR=<the mocks' folder> \
 *         ./gradlew :app:testCoreDebugUnitTest --tests '*CastMockReceiversTest*' --rerun
 */
class CastMockReceiversTest {
    private val mocksDir = System.getenv("CAST_MOCKS_DIR")?.let(::File)
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableListOf<Process>()
    private val links = mutableListOf<ReceiverLink>()
    private lateinit var work: File
    private lateinit var server: CastServer
    private lateinit var webm: File
    private lateinit var m4a: File

    @Before
    fun setUp() {
        assumeTrue("CAST_MOCKS_DIR is not set", mocksDir != null && File(mocksDir, "mock_fcast.py").isFile)
        assumeTrue("ffmpeg is not installed", runs("ffmpeg", "-version"))
        work = Files.createTempDirectory("cast-mocks").toFile()
        // What YouTube serves: Opus in WebM (itag 251) and AAC in fragmented MP4 (itag 140).
        webm = File(work, "tone.webm")
        m4a = File(work, "tone.m4a")
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:a", "libopus", "-b:a", "96k", webm.path)
        ffmpeg(
            "-f", "lavfi", "-i", "sine=frequency=660:duration=8", "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", m4a.path,
        )
        val songs = mapOf("opus1" to FileCastSong(webm, "audio/webm"), "aac1" to FileCastSong(m4a, "audio/mp4"))
        val source = object : CastSource {
            override fun open(mediaId: String): CastSong = songs[mediaId] ?: throw CastUnavailableException("No such song")

            // The Opus song fetched again as AAC, as the app does from YouTube for such a receiver.
            override fun openAac(mediaId: String): CastSong =
                if (mediaId == "opus1") FileCastSong(m4a, "audio/mp4") else throw CastUnavailableException("No AAC stream")
        }
        server = CastServer(source, loopback, { it in songs })
        server.start()
    }

    @After
    fun tearDown() {
        links.forEach { it.close() }
        scope.cancel()
        processes.forEach { it.destroy(); it.waitFor(3, TimeUnit.SECONDS); it.destroyForcibly() }
        if (::server.isInitialized) server.stop()
        if (::work.isInitialized) {
            // CAST_MOCKS_KEEP keeps the mocks' logs and the songs they fetched, to read afterwards.
            if (System.getenv("CAST_MOCKS_KEEP") != null) println("Mock receiver logs kept in $work") else work.deleteRecursively()
        }
    }

    /** Runs a command to its end, its output read and dropped; whether it ran and exited 0. */
    private fun run(command: List<String>, timeoutSeconds: Long = 60): Boolean = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun runs(vararg command: String): Boolean = run(command.toList(), 20)

    private fun ffmpeg(vararg args: String) {
        assertTrue("ffmpeg ${args.toList()}", run(listOf("ffmpeg", "-y", "-loglevel", "error") + args))
    }

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    /** Starts a mock and waits until it listens; its events go to the returned JSON-lines file. */
    private fun mock(script: String, vararg args: String): File {
        val log = File(work, "$script.jsonl")
        val out = File(work, "$script.out")
        val process = ProcessBuilder(listOf("python3", "-u", File(mocksDir, script).path, "--json-log", log.path) + args)
            .directory(mocksDir).redirectErrorStream(true).redirectOutput(out).start()
        processes += process
        val deadline = System.nanoTime() + 10_000_000_000L
        while (events(log, "listening").isEmpty()) {
            assertTrue("$script did not start: ${out.readTextOrEmpty()}", process.isAlive && System.nanoTime() < deadline)
            Thread.sleep(50)
        }
        return log
    }

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""

    private fun events(log: File, kind: String): List<JsonObject> =
        if (!log.exists()) emptyList() else log.readLines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { (it["event"] as? JsonPrimitive)?.content == kind }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content

    private fun waitFor(log: File, kind: String, timeoutMs: Long = 10_000, predicate: (JsonObject) -> Boolean = { true }): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            events(log, kind).firstOrNull(predicate)?.let { return it }
            assertTrue("No $kind in ${log.readTextOrEmpty()}", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun ReceiverLink.await(timeoutMs: Long = 20_000, predicate: (ReceiverStatus) -> Boolean): ReceiverStatus =
        runBlocking { withTimeout(timeoutMs) { status.first(predicate) } }

    private fun item(id: String, title: String, aac: Boolean = false): CastItem {
        val offer = server.prepare(id, aac)
        return CastItem(id, offer.url, offer.mimeType, offer.length, 8_000, title = title, artist = "Test Tones")
    }

    // FCast

    @Test
    fun `an FCast receiver fetches whole songs, follows the controls and reports each end`() {
        val port = freePort()
        val log = mock("mock_fcast.py", "--host", "127.0.0.1", "--port", "$port", "--speed", "4", "--ffprobe")
        val link = FCastLink(CastDevice(CastProtocol.FCAST, "mock", "Mock", "127.0.0.1", port), scope, FCastLink.Sender("Test", "InterTune", "test"))
            .also { links += it }
        runBlocking { link.connect() }
        assertEquals(3L, link.version)

        val opus = item("opus1", "Opus tone")
        runBlocking { link.load(opus, 0, play = true) }
        val song = waitFor(log, "song") { it.text("url")!!.contains("/s/opus1.webm") }
        assertEquals("true", song.text("ok"))
        assertEquals(webm.length().toString(), song.text("bytes"))
        assertEquals("webm/matroska", song.text("container"))
        assertEquals("opus", song.text("codec"))
        assertEquals("audio/webm", song.text("content_type"))
        link.await { it.state == ReceiverState.PLAYING }

        // The controls, while it plays.
        runBlocking { link.pause() }
        link.await { it.state == ReceiverState.PAUSED }
        runBlocking {
            link.seek(4_000)
            link.play()
            link.setVolume(0.3f)
        }
        waitFor(log, "get") { it.text("range")!!.startsWith("bytes=") && it.text("range") != "bytes=0-" }
        waitFor(log, "received") { it.text("opcode") == "SetVolume" }
        val ended = link.await { it.state == ReceiverState.ENDED }
        assertEquals(1, ended.load)

        // The next song, as the player would send it on the end.
        runBlocking { link.load(item("aac1", "AAC tone"), 0, play = true) }
        val aac = waitFor(log, "song") { it.text("url")!!.contains("/s/aac1.m4a") }
        assertEquals("true", aac.text("ok"))
        assertEquals("aac", aac.text("codec"))
        assertEquals(2, link.await { it.state == ReceiverState.ENDED && it.load == 2 }.load)
        assertEquals(1, events(log, "connected").size)
    }

    @Test
    fun `a receiver that refuses Opus fails the song, and the retry as AAC plays`() {
        val port = freePort()
        val log = mock("mock_fcast.py", "--host", "127.0.0.1", "--port", "$port", "--speed", "6", "--no-opus")
        val link = FCastLink(CastDevice(CastProtocol.FCAST, "mock", "Mock", "127.0.0.1", port), scope, FCastLink.Sender("Test", "InterTune", "test"))
            .also { links += it }
        runBlocking { link.connect() }
        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        val failed = link.await { it.state == ReceiverState.FAILED }
        assertTrue(failed.error!!, failed.error!!.contains("does not play audio/webm"))
        assertTrue(CastFormats.retryAsAac(link.caps.formats, FormatChoice.AsIs, "audio/webm", canFetchAac = true))
        runBlocking { link.load(item("opus1", "Opus tone", aac = true), 0, play = true) }
        link.await { it.state == ReceiverState.ENDED && it.load == 2 }
        val retried = waitFor(log, "song") { it.text("url")!!.contains("/aac/opus1.m4a") }
        assertEquals("true", retried.text("ok"))
        assertEquals("mp4", retried.text("container"))
    }

    @Test
    fun `a v2 receiver is followed to the end without events`() {
        val port = freePort()
        mock("mock_fcast.py", "--host", "127.0.0.1", "--port", "$port", "--speed", "6", "--version", "2")
        val link = FCastLink(CastDevice(CastProtocol.FCAST, "mock", "Mock", "127.0.0.1", port), scope, FCastLink.Sender("Test", "InterTune", "test"))
            .also { links += it }
        runBlocking { link.connect() }
        assertEquals(2L, link.version)
        runBlocking { link.load(item("aac1", "AAC tone"), 0, play = true) }
        link.await { it.state == ReceiverState.ENDED }
    }

    @Test
    fun `a receiver that hangs up is a lost link`() {
        val port = freePort()
        mock("mock_fcast.py", "--host", "127.0.0.1", "--port", "$port", "--drop-after", "1.5")
        val link = FCastLink(CastDevice(CastProtocol.FCAST, "mock", "Mock", "127.0.0.1", port), scope, FCastLink.Sender("Test", "InterTune", "test"))
            .also { links += it }
        runBlocking { link.connect() }
        link.await(5_000) { it.link == LinkState.LOST }
    }

    // Chromecast

    /** mock_cast.py started, over its own TLS, and a link connected to it. */
    private fun chromecast(vararg args: String): Pair<File, ChromecastLink> {
        assumeTrue("mock_cast.py is not there", File(mocksDir, "mock_cast.py").isFile)
        assumeTrue("openssl is not installed", runs("openssl", "version"))
        val port = freePort()
        val log = mock("mock_cast.py", "--host", "127.0.0.1", "--port", "$port", *args)
        val link = ChromecastLink(CastDevice(CastProtocol.CHROMECAST, "mock", "Mock", "127.0.0.1", port), scope).also { links += it }
        runBlocking { link.connect() }
        return log to link
    }

    @Test
    fun `a Chromecast over TLS opens its player, fetches whole songs, follows the controls and reports each end`() {
        val (log, link) = chromecast("--speed", "4", "--ffprobe")
        assertTrue(waitFor(log, "connected").text("tls")!!.startsWith("TLS"))
        waitFor(log, "launched")

        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        val song = waitFor(log, "song") { it.text("url")!!.contains("/s/opus1.webm") }
        assertEquals("true", song.text("ok"))
        assertEquals(webm.length().toString(), song.text("bytes"))
        assertEquals("opus", song.text("codec"))
        assertEquals("audio/webm", song.text("content_type"))
        link.await { it.state == ReceiverState.PLAYING }

        runBlocking { link.pause() }
        link.await { it.state == ReceiverState.PAUSED }
        runBlocking {
            link.seek(4_000)
            link.play()
            link.setVolume(0.3f)
        }
        waitFor(log, "get") { it.text("range")!!.startsWith("bytes=") && it.text("range") != "bytes=0-" }
        waitFor(log, "volume") { it.text("level") == "0.3" }
        assertEquals(0.3f, link.await { it.volume == 0.3f }.volume)
        assertEquals(1, link.await { it.state == ReceiverState.ENDED }.load)

        runBlocking { link.load(item("aac1", "AAC tone"), 0, play = true) }
        val aac = waitFor(log, "song") { it.text("url")!!.contains("/s/aac1.m4a") }
        assertEquals("true", aac.text("ok"))
        assertEquals("aac", aac.text("codec"))
        assertEquals(2, link.await { it.state == ReceiverState.ENDED && it.load == 2 }.load)
        assertEquals(1, events(log, "connected").size)

        // The player this link launched is stopped when it closes.
        link.close()
        waitFor(log, "app_stopped")
    }

    @Test
    fun `a Chromecast that refuses Opus fails the song, and the retry as AAC plays`() {
        val (log, link) = chromecast("--speed", "6", "--no-opus")
        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        link.await { it.state == ReceiverState.FAILED }
        assertTrue(CastFormats.retryAsAac(link.caps.formats, FormatChoice.AsIs, "audio/webm", canFetchAac = true))
        runBlocking { link.load(item("opus1", "Opus tone", aac = true), 0, play = true) }
        link.await { it.state == ReceiverState.ENDED && it.load == 2 }
        val retried = waitFor(log, "song") { it.text("url")!!.contains("/aac/opus1.m4a") }
        assertEquals("true", retried.text("ok"))
        assertEquals("mp4", retried.text("container"))
    }

    @Test
    fun `another app taking the Chromecast over leaves the song idle, and the player is not stopped`() {
        val (log, link) = chromecast("--takeover-after", "2")
        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        link.await { it.state == ReceiverState.PLAYING }
        val taken = link.await(10_000) { it.state == ReceiverState.IDLE }
        assertEquals("Something else is playing on Mock", taken.error)
        waitFor(log, "takeover")
        link.close()
        Thread.sleep(1_500)
        assertTrue(events(log, "received").none { it.text("namespace") == Chromecast.NS_RECEIVER && it.text("type") == "STOP" })
    }

    @Test
    fun `a Chromecast that hangs up is a lost link`() {
        val (_, link) = chromecast("--drop-after", "1.5")
        link.await(5_000) { it.link == LinkState.LOST }
    }

    // DLNA

    @Test
    fun `a DLNA renderer is found by SSDP, told AAC since it lists no WebM, and played to the end`() {
        val port = freePort()
        val ssdpPort = freePort()
        val log = mock(
            "mock_dlna.py", "--host", "127.0.0.1", "--port", "$port", "--ssdp-port", "$ssdpPort", "--speed", "4", "--ffprobe",
            "--require-dlna-headers", "--require-extension", "--strict-metadata", "--locked-while-playing",
        )
        val found = runBlocking { DlnaDiscovery.search(timeoutMs = 1_500, destination = InetSocketAddress(loopback, ssdpPort)) }
        assertEquals(1, found.size)
        val device = found.single()
        assertEquals("Mock DLNA renderer", device.name)
        assertEquals("http://127.0.0.1:$port/desc.xml", device.location)

        val link = DlnaLink(device, scope, timing = DlnaLink.Timing(pollActiveMs = 250)).also { links += it }
        runBlocking { link.connect() }
        val formats = link.caps.formats as FormatSupport.Listed
        assertTrue("audio/mp4" in formats.mimeTypes)
        assertEquals(FormatChoice.Aac, CastFormats.choose(formats, "audio/webm", canFetchAac = true))

        runBlocking { link.load(item("opus1", "Opus tone & more", aac = true), 0, play = true) }
        val didl = waitFor(log, "didl")
        assertEquals("Opus tone & more", didl.text("title"))
        assertEquals("Test Tones", didl.text("artist"))
        assertTrue(didl.text("protocol_info")!!.startsWith("http-get:*:audio/mp4:DLNA.ORG_OP=01"))
        val song = waitFor(log, "song") { it.text("url")!!.contains("/aac/opus1.m4a") }
        assertEquals("true", song.text("ok"))
        assertEquals("true", song.text("dlna_headers"))
        assertEquals(m4a.length().toString(), song.text("bytes"))
        link.await { it.state == ReceiverState.PLAYING }

        runBlocking {
            link.seek(3_000)
            link.setVolume(0.25f)
        }
        waitFor(log, "action") { it.text("name") == "Seek" }
        waitFor(log, "get") { it.text("range") != "bytes=0-" }
        waitFor(log, "volume") { it.text("level") == "25" }
        assertEquals(1, link.await { it.state == ReceiverState.ENDED }.load)

        // Sent while it plays, to a renderer that is locked then: stopped first, and played.
        runBlocking { link.load(item("aac1", "Again"), 0, play = true) }
        link.await { it.state == ReceiverState.PLAYING && it.load == 2 }
        runBlocking { link.load(item("aac1", "Once more"), 0, play = true) }
        link.await { it.state == ReceiverState.PLAYING && it.load == 3 }
        assertTrue(events(log, "action").any { it.text("name") == "Stop" })
    }

    @Test
    fun `a renderer sent a type it did not list fails the song`() {
        val port = freePort()
        mock("mock_dlna.py", "--host", "127.0.0.1", "--port", "$port", "--speed", "4")
        val device = CastAddress.parse("dlna http://127.0.0.1:$port/desc.xml")!!
        val link = DlnaLink(device, scope, timing = DlnaLink.Timing(pollActiveMs = 250)).also { links += it }
        runBlocking { link.connect() }
        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        val failed = link.await { it.state == ReceiverState.FAILED }
        assertEquals("Mock DLNA renderer could not play it", failed.error)
    }

    @Test
    fun `a description with a URLBase, an embedded renderer and XML a parser refuses still works`() {
        val port = freePort()
        // Real time: with no positions reported, the link can only carry the position on by its own clock.
        mock("mock_dlna.py", "--host", "127.0.0.1", "--port", "$port", "--ffprobe", "--url-base", "--embedded", "--sloppy", "--accept-all", "--no-position")
        val link = DlnaLink(CastAddress.parse("http://127.0.0.1:$port/desc.xml")!!, scope, timing = DlnaLink.Timing(pollActiveMs = 250))
            .also { links += it }
        runBlocking { link.connect() }
        assertTrue(link.device.name, link.device.name.startsWith("Mock DLNA renderer"))
        assertEquals(FormatSupport.Unknown, link.caps.formats)
        runBlocking { link.load(item("opus1", "Opus tone"), 0, play = true) }
        // With no positions reported, the end is still seen: stopped after playing.
        link.await(30_000) { it.state == ReceiverState.ENDED }
    }
}
