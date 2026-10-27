/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sabr

import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A song over SABR read as a file, from any byte. A fake song stands in for the server: an init
 * chunk, then segments of a few kilobytes and two seconds each, served from whichever segment holds
 * the time asked for, as the library does.
 */
class SabrFileTest {

    private val init = ByteArray(700) { (it % 251).toByte() }
    private val segmentMs = 2_000L
    private val segments = List(40) { i -> ByteArray(3_000 + (i * 37) % 900) { ((i * 7 + it) % 253).toByte() } }
    private val file = init + segments.fold(ByteArray(0)) { acc, s -> acc + s }
    private val durationMs = segmentMs * segments.size

    /** The chunks a stream from [startMs] gives: the init, then segments from the one holding that time. */
    private fun chunksFrom(startMs: Long): List<SabrChunk> {
        val out = mutableListOf(SabrChunk(init, 0, true, null, null, null))
        var offset = init.size.toLong()
        segments.forEachIndexed { i, data ->
            val start = i * segmentMs
            if (start + segmentMs > startMs) out += SabrChunk(data, offset, false, i, start, segmentMs)
            offset += data.size
        }
        return out
    }

    private val opened = mutableListOf<Long>()

    private fun server(change: (List<SabrChunk>) -> List<SabrChunk> = { it }): (Long) -> Flow<SabrChunk> = { startMs ->
        opened += startMs
        flow { change(chunksFrom(startMs)).forEach { emit(it) } }
    }

    private fun SabrFile.readAllFrom(position: Long): ByteArray {
        val reader = open(position)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1_234)
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
    fun `from the start it is the whole file, init and every segment in order`() {
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs)
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(listOf(0L), opened)
    }

    @Test
    fun `from the middle of a segment it is the file from that byte`() {
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs)
        for (position in listOf(1L, 699L, 700L, 701L, 5_000L, 40_000L, file.size - 1L)) {
            opened.clear()
            assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position), "from $position")
        }
    }

    @Test
    fun `at the very end there is nothing to read`() {
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs)
        assertEquals(0, sabr.readAllFrom(file.size.toLong()).size)
        assertTrue(opened.isEmpty(), "no stream is started for the end of the file")
    }

    @Test
    fun `an estimate that lands late is started once more, earlier`() {
        // The first ten segments are ten times as big as their time says, so a guess from the byte
        // position (bytes spread evenly over the time) lands later than the segment that holds it.
        val heavy = segments.mapIndexed { i, s -> if (i < 10) s + ByteArray(s.size * 9) else s }
        val heavyFile = init + heavy.fold(ByteArray(0)) { acc, s -> acc + s }
        fun heavyFrom(startMs: Long): List<SabrChunk> {
            val out = mutableListOf(SabrChunk(init, 0, true, null, null, null))
            var offset = init.size.toLong()
            heavy.forEachIndexed { i, data ->
                val start = i * segmentMs
                if (start + segmentMs > startMs) out += SabrChunk(data, offset, false, i, start, segmentMs)
                offset += data.size
            }
            return out
        }
        val sabr = SabrFile({ startMs -> opened += startMs; flow { heavyFrom(startMs).forEach { emit(it) } } },
            contentLength = heavyFile.size.toLong(), durationMs = durationMs)
        val position = init.size + heavy.take(30).sumOf { it.size }.toLong() + 10
        assertContentEquals(heavyFile.copyOfRange(position.toInt(), heavyFile.size), sabr.readAllFrom(position))
        assertTrue(opened.size in 2..4, "started again earlier, a few times at most: $opened")
        assertTrue(opened.zipWithNext().all { (a, b) -> b < a }, "each new start is earlier: $opened")
    }

    @Test
    fun `a position seen before is started from its own segment, with no second try`() {
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs)
        sabr.readAllFrom(0)   // every segment is seen once
        opened.clear()
        val position = init.size + segments.take(25).sumOf { it.size }.toLong() + 100
        assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position))
        // A little into it, past the cut of its start (SabrFileAacTest).
        assertEquals(listOf(25 * segmentMs + SabrFile.INSIDE_MS), opened)
    }

    @Test
    fun `a read from where a segment seen ends starts the next one, a little into it`() {
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs)
        sabr.readAllFrom(0)
        opened.clear()
        val position = init.size + segments.take(12).sumOf { it.size }.toLong()
        assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position))
        assertEquals(listOf(12 * segmentMs + SabrFile.INSIDE_MS), opened)
    }

    @Test
    fun `a seek past everything seen is started near the byte, not from the last segment seen`() {
        // A small buffer, so that the stream has seen little more than what was read.
        val sabr = SabrFile(server(), contentLength = file.size.toLong(), durationMs = durationMs, bufferedChunks = 2)
        // the first ten segments seen, as a song played for twenty seconds
        val reader = sabr.open(0)
        val upTo = init.size + segments.take(10).sumOf { it.size }
        val buffer = ByteArray(upTo)
        var got = 0
        while (got < upTo) got += reader.read(buffer, got, upTo - got).also { check(it > 0) }
        reader.close()
        opened.clear()
        val position = init.size + segments.take(32).sumOf { it.size }.toLong() + 50
        assertContentEquals(file.copyOfRange(position.toInt(), file.size), sabr.readAllFrom(position))
        assertTrue(opened.first() > 20 * segmentMs, "started from the byte's own share of the file, not at ${opened.first()} ms")
    }

    @Test
    fun `a gap between two chunks is an error, never bytes from the wrong place`() {
        val sabr = SabrFile(server { chunks -> chunks.filterNot { it.sequenceNumber == 5 } },
            contentLength = file.size.toLong(), durationMs = durationMs)
        assertFailsWith<IOException> { sabr.readAllFrom(0) }
    }

    @Test
    fun `a stream that stops short of the end is an error`() {
        val sabr = SabrFile(server { chunks -> chunks.dropLast(3) }, contentLength = file.size.toLong(), durationMs = durationMs)
        assertFailsWith<IOException> { sabr.readAllFrom(0) }
    }

    @Test
    fun `the library's own failure reaches the reader as an IOException`() {
        val sabr = SabrFile({ _ -> flow { emit(chunksFrom(0).first()); throw IllegalStateException("SABR refused") } },
            contentLength = file.size.toLong(), durationMs = durationMs)
        val e = assertFailsWith<IOException> { sabr.readAllFrom(0) }
        assertTrue(e.cause is IllegalStateException)
    }

    @Test
    fun `a stream ended for a reason a new one gets past is carried on at the byte owed`() {
        // Each stream gives three segments and then stops, as InnerTubeX stops one whose
        // protection stays pending.
        val sabr = SabrFile({ startMs ->
            opened += startMs
            flow {
                var media = 0
                for (chunk in chunksFrom(startMs)) {
                    if (!chunk.isInitialization && media++ == 3) throw IllegalStateException("pending too long")
                    emit(chunk)
                }
            }
        }, contentLength = file.size.toLong(), durationMs = durationMs, restartOn = { it.message == "pending too long" })
        assertContentEquals(file, sabr.readAllFrom(0))
        assertTrue(opened.size in 13..15, "about one new stream per three segments: ${opened.size}")
    }

    @Test
    fun `a stream that stops before any progress is an error, not a loop`() {
        val sabr = SabrFile({ _ -> opened += 0; flow { throw IllegalStateException("pending too long") } },
            contentLength = file.size.toLong(), durationMs = durationMs, restartOn = { true })
        assertFailsWith<IOException> { sabr.readAllFrom(0) }
        assertEquals(1, opened.size)
    }

    @Test
    fun `closing early stops the stream`() {
        var emitted = 0
        val sabr = SabrFile({ startMs -> flow { chunksFrom(startMs).forEach { emitted++; emit(it) } } },
            contentLength = file.size.toLong(), durationMs = durationMs, bufferedChunks = 2)
        val reader = sabr.open(0)
        val buffer = ByteArray(100)
        assertEquals(100, reader.read(buffer, 0, 100))
        reader.close()
        Thread.sleep(200)
        assertTrue(emitted < segments.size, "the stream went on after close: $emitted chunks")
    }
}
