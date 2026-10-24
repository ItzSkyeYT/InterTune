/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sabr

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.ProtoReader
import com.metrolist.innertubex.sabr.ProtoWriter
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.UmpPartType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An AAC song (itag 140) read again from a byte part way in, through InnerTubeX itself, as on a
 * phone on 21 Oct 2026: "SABR first media segment does not cover the requested start".
 *
 * AAC at 44.1 kHz comes in segments of 441,344 ticks, 10,007.8 ms, so most of them start inside a
 * millisecond. InnerTubeX cuts a segment's start and its length to whole milliseconds each, so a
 * time read off them can sit just before where a segment truly starts. A fake server stands in for
 * YouTube's: it serves from the segment that holds the time asked for, by its ticks, as the real
 * one does. Opus (itag 251) counts in whole milliseconds, which is why its songs never showed it.
 */
@OptIn(ExperimentalSabrApi::class)
class SabrFileAacTest {

    private val timescale = 44_100L
    private val segmentTicks = 441_344L
    private val count = 12
    private val init = ByteArray(64) { (it * 3).toByte() }
    private val media = List(count) { i -> ByteArray(180 + (i * 53) % 90) { ((i * 11 + it) % 251).toByte() } }
    private val file = init + media.reduce { a, b -> a + b }

    /** Where each segment's bytes start, and the end of the file last. */
    private val at = media.runningFold(init.size.toLong()) { start, s -> start + s.size }
    private val durationMs = count * segmentTicks * 1000 / timescale

    /** The times streams were asked from, as the server heard them. */
    private val asked = mutableListOf<Long>()

    /** The segment that holds [timeMs] by its ticks, as YouTube's server finds it. */
    private fun holding(timeMs: Long): Int =
        (0 until count).firstOrNull { i -> timeMs * timescale < (i + 1) * segmentTicks * 1000 } ?: (count - 1)

    /** A server that serves, for a time asked, from the segment [from] picks given the one that holds it. */
    private fun server(from: (Int) -> Int = { it }) = HttpClient(MockEngine { request ->
        val time = playerTimeOf((request.body as OutgoingContent.ByteArrayContent).bytes())
        asked += time
        respond(response(from(holding(time))), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
    })

    private val bootstrap = SabrBootstrap(
        videoId = "aac-song",
        serverAbrStreamingUrl = "https://example.googlevideo.com/videoplayback?sabr=1",
        videoPlaybackUstreamerConfig = byteArrayOf(1, 2, 3),
        clientName = 3,
        clientVersion = "21.02.35",
        audioFormat = SabrFormatId(140, 100, "x"),
        discardVideoFormat = SabrFormatId(160, 200),
        discardVideoHeight = 144,
        durationMs = durationMs,
        contentLengthBytes = file.size.toLong(),
        mimeType = "audio/mp4",
    )

    /** A song as the app holds one: InnerTubeX's stream under SabrFile, both as they ship. */
    private fun song(http: HttpClient) = SabrFile(
        stream = { startMs -> SabrAudioStream(http, bootstrap, initialPlayerTimeMs = startMs).chunks() },
        contentLength = file.size.toLong(),
        durationMs = durationMs,
    )

    private fun SabrFile.readAllFrom(position: Long): ByteArray {
        val reader = open(position)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(97)
        try {
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } finally {
            reader.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `the fake song has segments that start inside a millisecond, as AAC's do`() {
        // Otherwise the tests below would prove nothing about AAC.
        val starts = (0 until count).map { i -> i * segmentTicks * 1000.0 / timescale }
        assertTrue(starts.count { it % 1.0 != 0.0 } >= count / 2, "$starts")
    }

    @Test
    fun `read again from where a segment ends, it is the file from that byte`() {
        val sabr = song(server())
        assertContentEquals(file, sabr.readAllFrom(0))
        for (i in 1 until count) {
            asked.clear()
            val position = at[i]
            assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position), "from segment $i's first byte")
            assertEquals(1, asked.size, "one stream for segment $i, no second try: $asked")
        }
    }

    @Test
    fun `read again from inside a segment seen before, it is the file from that byte`() {
        val sabr = song(server())
        sabr.readAllFrom(0)
        for (i in 0 until count) {
            val position = at[i] + 17
            assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position), "from inside segment $i")
        }
    }

    @Test
    fun `a start the library refuses is tried again earlier, and from the beginning at worst`() {
        // A server that skips the segment holding the time asked for, so that InnerTubeX's first
        // segment never covers the start, except from the very beginning of the song.
        val sabr = song(server { holdingIt -> if (holdingIt == 0) 0 else minOf(holdingIt + 1, count - 1) })
        val position = at[7] + 40
        assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position))
        assertTrue(asked.size in 2..4, "tried again a few times at most: $asked")
        assertTrue(asked.zipWithNext().all { (a, b) -> b < a }, "each new start is earlier: $asked")
        assertEquals(0L, asked.last(), "the last start is the song's beginning, which every stream covers")
    }

    @Test
    fun `read ahead through InnerTubeX, the song is held whole, and read again from any byte with no request`() {
        var paced = 0
        val sabr = SabrFile(
            stream = { _ -> paced++; kotlinx.coroutines.flow.flow { throw IllegalStateException("a stream at the player's pace") } },
            contentLength = file.size.toLong(),
            durationMs = durationMs,
            ahead = SabrFile.Ahead(stream = { startMs -> SabrAudioStream(server(), bootstrap, initialPlayerTimeMs = startMs).chunks() }),
        )
        assertContentEquals(file, sabr.readAllFrom(0))
        val deadline = System.currentTimeMillis() + 5_000
        while (!sabr.restHeld && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(sabr.restHeld, "held to its end")
        val requests = asked.size
        for (i in listOf(1, 5, 11)) {
            val position = at[i] + 17
            assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position), "from inside segment $i")
        }
        assertEquals(requests, asked.size, "no request once the song is held")
        assertEquals(0, paced)
    }

    @Test
    fun `a read ahead YouTube refuses leaves the song to the player's own stream, without a word to the reader`() {
        val refusing = HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.Forbidden) })
        val sabr = SabrFile(
            stream = { startMs -> SabrAudioStream(server(), bootstrap, initialPlayerTimeMs = startMs).chunks() },
            contentLength = file.size.toLong(),
            durationMs = durationMs,
            ahead = SabrFile.Ahead(stream = { startMs -> SabrAudioStream(refusing, bootstrap, initialPlayerTimeMs = startMs).chunks() }),
        )
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(listOf(0L), asked, "the player's own stream, from the start")
        assertEquals(0L, sabr.heldSize)
    }

    // ---- the server's side ----

    /** One answer: the format, the init segment, the segments from [first] to the end, the end of track. */
    private fun response(first: Int): ByteArray {
        var out = part(UmpPartType.FORMAT_INITIALIZATION_METADATA, ProtoWriter().apply {
            message(2) { int32(1, 140); int64(2, 100); string(3, "x") }
            int64(3, durationMs)
            int32(4, count - 1)
            int64(9, count * segmentTicks)
            int64(10, timescale)
        }.toByteArray())
        out += segment(headerId = 1, data = init, startRange = 0, sequence = 0, ticks = null)
        for (i in first until count) {
            out += segment(headerId = 2 + i - first, data = media[i], startRange = at[i], sequence = i, ticks = i * segmentTicks)
        }
        return out + part(UmpPartType.END_OF_TRACK, ByteArray(0))
    }

    /** A segment's header, its bytes and its end. Its times only as ticks, as InnerTubeX reads them. */
    private fun segment(headerId: Int, data: ByteArray, startRange: Long, sequence: Int, ticks: Long?): ByteArray {
        val header = ProtoWriter().apply {
            int32(1, headerId)
            int32(3, 140)
            int64(4, 100)
            string(5, "x")
            if (startRange > 0) int64(6, startRange)
            if (ticks == null) bool(8, true)
            int32(9, sequence)
            int64(14, data.size.toLong())
            if (ticks != null) message(15) {
                int64(1, ticks)
                int64(2, segmentTicks)
                int64(3, timescale)
            }
        }.toByteArray()
        return part(UmpPartType.MEDIA_HEADER, header) +
            part(UmpPartType.MEDIA, byteArrayOf(headerId.toByte()) + data) +
            part(UmpPartType.MEDIA_END, byteArrayOf(headerId.toByte()))
    }

    private fun part(type: Int, data: ByteArray) = varint(type) + varint(data.size) + data

    /** UMP's own varint: the count of leading ones in the first byte says how many bytes follow. */
    private fun varint(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf(value.toByte())
        value < 0x4000 -> byteArrayOf(((value and 0x3f) or 0x80).toByte(), (value ushr 6).toByte())
        else -> byteArrayOf(((value and 0x1f) or 0xc0).toByte(), ((value ushr 5) and 0xff).toByte(), ((value ushr 13) and 0xff).toByte())
    }

    /** The player time a request asks from: field 28 of its client state, field 1. */
    private fun playerTimeOf(request: ByteArray): Long {
        val outer = ProtoReader(request)
        while (outer.hasRemaining) {
            val tag = outer.tag()
            if (tag.field != 1) { outer.skip(tag); continue }
            val state = ProtoReader(outer.bytes())
            while (state.hasRemaining) {
                val inner = state.tag()
                if (inner.field == 28) return state.varint()
                state.skip(inner)
            }
        }
        return 0
    }
}
