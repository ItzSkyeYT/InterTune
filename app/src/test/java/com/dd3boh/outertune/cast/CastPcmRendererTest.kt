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
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Raw audio end to end without a phone or a speaker: the real cast server, serving a tone that a
 * fake decoder makes as Opus would decode it (48 kHz stereo), and the real DLNA link driving
 * l16_renderer.py, a renderer that lists only raw audio and checks what it is sent: the type and its
 * rate, the length against the DIDL-Lite, the DLNA headers, that the samples are 16-bit in the right
 * order, and that bytes fetched again after a seek are the bytes fetched before.
 *
 * The renderer is not part of the app (experiments/features/cast-pcm/). This runs when
 * CAST_PCM_RENDERER names it, with python3 on the machine, and is skipped otherwise:
 *
 *     CAST_PCM_RENDERER=<path to l16_renderer.py> \
 *         ./gradlew :app:testCoreDebugUnitTest --tests '*CastPcmRendererTest*' --rerun
 */
class CastPcmRendererTest {
    private val script = System.getenv("CAST_PCM_RENDERER")?.let(::File)
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableListOf<Process>()
    private val links = mutableListOf<ReceiverLink>()
    private lateinit var work: File
    private lateinit var server: CastServer

    /** Twelve seconds of a 440 Hz tone at 48 kHz in stereo, in pieces of 20 ms as Opus gives them. */
    private class ToneDecoder : PcmDecoder {
        override fun probe(song: CastSong) = PcmSource(48_000, 2, 12_000_000)

        override fun open(song: CastSong, fromUs: Long): DecodedAudio = object : DecodedAudio {
            var at = fromUs * 48_000 / 1_000_000 / 960 * 960
            val samples = ShortArray(960 * 2)

            override fun next(): DecodedPiece? {
                if (at >= 576_000) return null
                for (i in 0 until 960) {
                    val value = (12_000 * sin(2 * PI * 440 * (at + i) / 48_000)).roundToInt().toShort()
                    samples[2 * i] = value
                    samples[2 * i + 1] = value
                }
                val piece = DecodedPiece(at * 1_000_000 / 48_000, 48_000, 2, samples, 960)
                at += 960
                return piece
            }

            override fun close() = Unit
        }
    }

    @Before
    fun setUp() {
        assumeTrue("CAST_PCM_RENDERER is not set", script?.isFile == true)
        work = Files.createTempDirectory("cast-pcm").toFile()
        val asIs = object : CastSong {
            override val mimeType = "audio/webm"
            override val length = 100_000L
            override fun openAt(position: Long): CastReader = error("The tone decoder reads nothing")
        }
        server = CastServer(CastSource { asIs }, loopback, { it == "tone1" }, pcmDecoder = ToneDecoder())
        server.start()
    }

    @After
    fun tearDown() {
        links.forEach { it.close() }
        scope.cancel()
        processes.forEach { it.destroy(); it.waitFor(3, TimeUnit.SECONDS); it.destroyForcibly() }
        if (::server.isInitialized) server.stop()
        if (::work.isInitialized) {
            if (System.getenv("CAST_MOCKS_KEEP") != null) println("Renderer logs kept in $work") else work.deleteRecursively()
        }
    }

    private fun events(log: File, kind: String): List<JsonObject> =
        if (!log.exists()) emptyList() else log.readLines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { (it["event"] as? JsonPrimitive)?.content == kind }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content

    private fun waitFor(log: File, kind: String, timeoutMs: Long = 15_000, predicate: (JsonObject) -> Boolean = { true }): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            events(log, kind).firstOrNull(predicate)?.let { return it }
            if (System.nanoTime() > deadline) fail("No $kind in ${if (log.exists()) log.readText() else "a log not yet written"}")
            Thread.sleep(50)
        }
    }

    /** The renderer started on a port of its own, playing four times faster than real time; its log. */
    private fun renderer(vararg args: String): Pair<File, Int> {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val log = File(work, "renderer-$port.jsonl")
        val out = File(work, "renderer-$port.out")
        val command = listOf("python3", "-I", "-u", script!!.path, "--host", "127.0.0.1", "--port", "$port", "--json-log", log.path, "--speed", "4", "--ahead", "3")
        processes += ProcessBuilder(command + args).redirectErrorStream(true).redirectOutput(out).start()
        waitFor(log, "listening", 10_000)
        return log to port
    }

    /** Connects to the renderer, sends the tone the way the remote player would, plays it, and seeks ahead, then back. */
    private fun castTone(port: Int, log: File): CastOffer {
        val link = DlnaLink(CastAddress.parse("http://127.0.0.1:$port/desc.xml")!!, scope, timing = DlnaLink.Timing(pollActiveMs = 250))
            .also { links += it }
        runBlocking { link.connect() }
        val choice = CastFormats.choose(link.caps.formats, "audio/webm; codecs=\"opus\"", canFetchAac = true, canDecode = true)
        assertTrue("$choice", choice is FormatChoice.Pcm)
        val offer = server.preparePcm("tone1", (choice as FormatChoice.Pcm).target, 12_000)
        val item = CastItem("tone1", offer.url, offer.mimeType, offer.length, offer.durationMs, title = "Tone", converted = true)
        runBlocking { link.load(item, 0, play = true) }
        runBlocking { withTimeout(10_000) { link.status.first { it.state == ReceiverState.PLAYING } } }
        waitFor(log, "get")
        Thread.sleep(800)
        runBlocking { link.seek(9_000) }
        waitFor(log, "seek") { it.text("to") == "0:00:09" }
        Thread.sleep(500)
        runBlocking { link.seek(2_000) }
        waitFor(log, "overlap", 15_000)
        runBlocking { link.stop() }
        return offer
    }

    @Test
    fun `a renderer that lists only L16 at 44 1 kHz gets the tone resampled, whole, big-endian, and the same after a seek`() {
        val (log, port) = renderer("--rates", "44100", "--with-l8")
        val offer = castTone(port, log)
        assertEquals("audio/L16;rate=44100;channels=2", offer.mimeType)
        assertEquals(529_200L * 4, offer.length)
        val head = waitFor(log, "head")
        assertEquals("audio/L16;rate=44100;channels=2", head.text("content_type"))
        assertEquals("${529_200L * 4}", head.text("content_length"))
        val overlap = waitFor(log, "overlap")
        assertEquals("1.0", overlap.text("identical"))
        assertEquals(emptyList<JsonObject>(), events(log, "PROBLEM"))
    }

    @Test
    fun `a renderer that lists WAV and no L16 gets the tone in a WAV file`() {
        val (log, port) = renderer("--wav")
        val offer = castTone(port, log)
        assertEquals("audio/wav", offer.mimeType)
        assertEquals(44L + 576_000 * 4, offer.length)
        assertEquals("audio/wav", waitFor(log, "head").text("content_type"))
        assertEquals("1.0", waitFor(log, "overlap").text("identical"))
        assertEquals(emptyList<JsonObject>(), events(log, "PROBLEM"))
    }
}
