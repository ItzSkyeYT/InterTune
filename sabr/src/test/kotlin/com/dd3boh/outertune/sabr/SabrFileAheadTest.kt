/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sabr

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A song over SABR read ahead of the player (SabrFile.Ahead): once a read of it opens, the rest of
 * it is fetched on a stream of its own, faster than the player plays, held, and handed to every
 * read of the song, so that a connection lost mid-song does not stop the music while what is held
 * lasts. The fake song of SabrFileTest stands in for the server: an init chunk, then forty
 * segments of two seconds, served from whichever segment holds the time asked for.
 */
class SabrFileAheadTest {

    private val init = ByteArray(700) { (it % 251).toByte() }
    private val segmentMs = 2_000L
    private val segments = List(40) { i -> ByteArray(3_000 + (i * 37) % 900) { ((i * 7 + it) % 253).toByte() } }
    private val file = init + segments.fold(ByteArray(0)) { acc, s -> acc + s }
    private val durationMs = segmentMs * segments.size

    /** Where each segment's bytes start, and the end of the file last. */
    private val at = segments.runningFold(init.size.toLong()) { start, s -> start + s.size }

    private fun chunksFrom(startMs: Long): List<SabrChunk> {
        val out = mutableListOf(SabrChunk(init, 0, true, null, null, null))
        segments.forEachIndexed { i, data ->
            val start = i * segmentMs
            if (start + segmentMs > startMs) out += SabrChunk(data, at[i], false, i, start, segmentMs)
        }
        return out
    }

    /**
     * YouTube's server as a test sees it: the streams it was asked for and from when, and every
     * segment it sent with the number of the stream that sent it. [step] runs before each segment
     * goes out, with those two numbers: a test holds a stream there, or fails it.
     */
    private inner class Server(private val step: suspend (stream: Int, segment: Int) -> Unit = { _, _ -> }) {
        val opened = CopyOnWriteArrayList<Long>()
        val sent = CopyOnWriteArrayList<Pair<Int, Int>>()

        @Volatile
        var offline = false

        val stream: (Long) -> Flow<SabrChunk> = { startMs ->
            val number = opened.size
            opened += startMs
            flow {
                if (offline) throw UnknownHostException("no network")
                for (chunk in chunksFrom(startMs)) {
                    chunk.sequenceNumber?.let { step(number, it); sent += number to it }
                    emit(chunk)
                }
            }
        }

        val segmentsSent: List<Int> get() = sent.map { it.second }
    }

    /** Permits for a stream's segments, one each, which a test hands out as it needs. */
    private class Gate {
        private val permits = Channel<Unit>(Channel.UNLIMITED)
        suspend fun pass() = permits.receive()
        fun let(n: Int) = repeat(n) { permits.trySend(Unit) }
    }

    private val readers = CopyOnWriteArrayList<SabrFile.Reader>()
    private val reading = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @AfterTest
    fun closeEverything() {
        readers.forEach { it.close() }
        reading.shutdownNow()
    }

    private fun song(
        own: Server,
        ahead: Server?,
        allowed: () -> Boolean = { true },
        maxBytes: Long = SabrFile.MAX_AHEAD_BYTES,
        onRestHeld: () -> Unit = {},
    ) = SabrFile(
        own.stream, contentLength = file.size.toLong(), durationMs = durationMs,
        ahead = ahead?.let { SabrFile.Ahead(it.stream, allowed = allowed, maxBytes = maxBytes, onRestHeld = onRestHeld) },
    )

    private fun SabrFile.reader(position: Long, length: Long = -1) = open(position, length).also { readers += it }

    /** [count] bytes of [this], fewer only at the end of the file. */
    private fun SabrFile.Reader.take(count: Int): ByteArray {
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = read(out, got, count - got)
            if (n < 0) return out.copyOf(got)
            got += n
        }
        return out
    }

    private fun SabrFile.Reader.rest(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1_234)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun SabrFile.readAllFrom(position: Long): ByteArray {
        val reader = reader(position)
        try {
            return reader.rest()
        } finally {
            reader.close()
        }
    }

    private fun from(position: Long) = file.copyOfRange(position.toInt(), file.size)

    private fun eventually(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > end) fail("not after $timeoutMs ms: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `read ahead from the first read, the song is held whole from one stream of its own, and the read gets it byte for byte`() {
        val own = Server()
        val ahead = Server()
        val sabr = song(own, ahead)
        assertContentEquals(file, sabr.readAllFrom(0))
        eventually("held to its end") { sabr.restHeld }
        assertEquals(file.size.toLong(), sabr.heldSize)
        assertEquals(listOf(0L), ahead.opened.toList())
        assertTrue(own.opened.isEmpty(), "no stream at the player's pace: ${own.opened}")
        assertEquals((0 until 40).toList(), ahead.segmentsSent, "every segment once")
    }

    @Test
    fun `held, the song reads again from any byte with no stream at all, as with the network gone`() {
        val own = Server()
        val ahead = Server()
        val sabr = song(own, ahead)
        sabr.readAllFrom(0)
        eventually("held to its end") { sabr.restHeld }
        own.offline = true
        ahead.offline = true
        val streams = own.opened.size + ahead.opened.size
        for (position in listOf(0L, 1L, 699L, 700L, at[7] + 13, at[25], file.size - 1L)) {
            assertContentEquals(from(position), sabr.readAllFrom(position), "from $position")
        }
        assertEquals(streams, own.opened.size + ahead.opened.size, "no stream asked for")
    }

    @Test
    fun `a read that catches up with the read ahead waits for its bytes, and no segment is asked for twice`() {
        val own = Server()
        // Slower than the reader, which then waits at its edge again and again.
        val ahead = Server { _, _ -> delay(3) }
        val sabr = song(own, ahead)
        assertContentEquals(file, sabr.readAllFrom(0))
        assertTrue(own.opened.isEmpty(), "the read never went on at its own pace: ${own.opened}")
        assertEquals((0 until 40).toList(), ahead.segmentsSent)
    }

    @Test
    fun `a read far past the read ahead moves it there, what it held stays held, and the gap is filled up to it`() {
        val gate = Gate()
        val own = Server()
        val ahead = Server { _, _ -> gate.pass() }
        val sabr = song(own, ahead)
        val first = sabr.reader(0)
        gate.let(5)
        assertContentEquals(file.copyOf(at[5].toInt()), first.take(at[5].toInt()))
        first.close()
        // A seek a minute in.
        val second = sabr.reader(at[30])
        eventually("a stream for the seek") { ahead.opened.size == 2 }
        assertTrue(ahead.opened[1] >= 40_000, "started near the byte, not from the start: ${ahead.opened}")
        gate.let(1_000)
        assertContentEquals(from(at[30]), second.rest())
        second.close()
        assertTrue(sabr.holds(at[2]), "the bytes held before the seek are still held")
        assertFalse(sabr.holds(at[12]), "the gap was never fetched")
        // A seek back into the gap: it is filled from there, and its stream stops where the bytes held begin.
        assertContentEquals(from(at[10]), sabr.readAllFrom(at[10]))
        // From a guess made early on purpose, segment 9 never having been seen, and only as far as
        // the bytes held after the gap.
        val gap = ahead.sent.filter { it.first == 2 }.map { it.second }
        assertTrue(gap.first() <= 10, "$gap")
        assertTrue(gap.last() <= 30, "the gap's stream stopped at what is held: $gap")
        assertTrue(own.opened.isEmpty(), "${own.opened}")
    }

    @Test
    fun `a refused read ahead is quiet, the read goes on at the player's pace from the first byte not held and gets the song whole`() {
        val own = Server()
        val ahead = Server { _, segment ->
            if (segment == 10) throw SabrProtocolException(
                "SABR stream protection requires attestation (status 3, retry=0/unspecified)",
                SabrFailureKind.ATTESTATION_REQUIRED,
            )
        }
        val sabr = song(own, ahead)
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(listOf(10 * segmentMs + SabrFile.INSIDE_MS), own.opened.toList())
        // And for good: read again, what is held comes first, then a stream of its own.
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(1, ahead.opened.size, "never read ahead again: ${ahead.opened}")
        assertEquals(2, own.opened.size)
        assertFalse(sabr.restHeld)
        assertFalse(sabr.readAhead(0))
    }

    @Test
    fun `the network going under the read ahead fails the read that waits on it, as its own stream would, and the next read takes it on from there`() {
        val gate = Gate()
        val own = Server()
        val ahead = Server { stream, segment ->
            if (stream == 0) {
                gate.pass()
                if (segment == 10) throw UnknownHostException("no network")
            }
        }
        val sabr = song(own, ahead)
        val first = sabr.reader(0)
        gate.let(10)
        assertContentEquals(file.copyOf(at[10].toInt()), first.take(at[10].toInt()))
        val waiting = reading.submit(Callable<Throwable?> {
            try {
                first.take(100)
                null
            } catch (e: IOException) {
                e
            }
        })
        Thread.sleep(150)
        assertFalse(waiting.isDone, "it waits at the edge of what is held")
        gate.let(1)
        val failure = waiting.get(3, TimeUnit.SECONDS)
        assertTrue(failure != null && generateSequence(failure) { it.cause }.any { it is UnknownHostException }, "$failure")
        first.close()
        // media3 opens the song again at the same byte.
        assertContentEquals(from(at[10]), sabr.readAllFrom(at[10]))
        assertEquals(listOf(0L, 10 * segmentMs + SabrFile.INSIDE_MS), ahead.opened.toList())
        assertTrue(own.opened.isEmpty(), "${own.opened}")
    }

    @Test
    fun `let go, the read ahead stops and holds nothing, and a read already open goes on at the player's pace`() {
        val gate = Gate()
        val stopped = CountDownLatch(1)
        val own = Server()
        val ahead = Server { _, _ ->
            try {
                gate.pass()
            } catch (e: CancellationException) {
                stopped.countDown()
                throw e
            }
        }
        val sabr = song(own, ahead)
        val first = sabr.reader(0)
        gate.let(5)
        first.take(at[5].toInt())
        sabr.dropAhead()
        assertTrue(stopped.await(2, TimeUnit.SECONDS), "the read ahead's stream was stopped")
        assertEquals(0L, sabr.heldSize)
        assertContentEquals(from(at[5]), first.rest())
        assertEquals(listOf(5 * segmentMs + SabrFile.INSIDE_MS), own.opened.toList())
        first.close()
        // A read opened after it reads ahead again.
        gate.let(1_000)
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(2, ahead.opened.size)
    }

    @Test
    fun `not allowed, as with Data Saver on, the song is read as it always was`() {
        val own = Server()
        val ahead = Server()
        val sabr = song(own, ahead, allowed = { false })
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(listOf(0L), own.opened.toList())
        assertTrue(ahead.opened.isEmpty())
        assertEquals(0L, sabr.heldSize)
        assertFalse(sabr.readAhead(0))
    }

    @Test
    fun `held up to its most, it is taken on again as the read goes, and every segment comes once`() {
        val own = Server()
        val ahead = Server()
        val most = 20_000L
        val sabr = song(own, ahead, maxBytes = most)
        val reader = sabr.reader(0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1_000)
        var largest = 0L
        while (true) {
            val n = reader.read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
            largest = maxOf(largest, sabr.heldSize)
        }
        reader.close()
        assertContentEquals(file, out.toByteArray())
        assertTrue(largest <= most + segments.maxOf { it.size }, "held $largest bytes at most")
        assertTrue(ahead.opened.size > 1, "taken on again: ${ahead.opened}")
        assertEquals((0 until 40).toList(), ahead.segmentsSent, "every segment once")
        assertTrue(own.opened.isEmpty(), "${own.opened}")
    }

    @Test
    fun `the read ahead says once that it has reached the end of the song`() {
        var told = 0
        val sabr = song(Server(), Server(), onRestHeld = { told++ })
        sabr.readAllFrom(0)
        eventually("held to its end") { sabr.restHeld }
        Thread.sleep(50)
        assertEquals(1, told)
    }

    @Test
    fun `a song read ahead before anyone reads it, as the next in the queue, is held whole and plays with no network`() {
        val own = Server()
        val ahead = Server()
        val sabr = song(own, ahead)
        assertTrue(sabr.readAhead(0))
        eventually("held to its end") { sabr.restHeld }
        assertEquals(file.size.toLong(), sabr.heldSize)
        own.offline = true
        ahead.offline = true
        assertContentEquals(file, sabr.readAllFrom(0))
        assertEquals(1, ahead.opened.size)
        assertTrue(own.opened.isEmpty())
    }

    @Test
    fun `a read of the part between two cached ones reads ahead that part only`() {
        val own = Server()
        val ahead = Server()
        val sabr = song(own, ahead)
        val length = at[15] - at[5]
        val reader = sabr.reader(at[5], length)
        assertContentEquals(file.copyOfRange(at[5].toInt(), at[15].toInt()), reader.take(length.toInt()))
        reader.close()
        Thread.sleep(100)
        assertEquals(length, sabr.heldSize)
        assertFalse(sabr.restHeld)
        assertTrue(ahead.segmentsSent.max() <= 15, "${ahead.segmentsSent}")
    }
}
