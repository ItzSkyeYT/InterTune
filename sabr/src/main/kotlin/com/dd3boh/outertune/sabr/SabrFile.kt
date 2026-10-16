/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sabr

import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentSkipListMap

/**
 * One song's audio file, read over SABR from any byte: what a DataSource hands to the player.
 *
 * SABR sends a song as an initialization chunk and then media segments, each carrying its byte
 * offset in the format's file, so laid end to end they are the very file a direct address serves.
 * That is what lets everything after the DataSource stay as it is: the extractors, the cache under
 * the song's id, downloads, the cast server.
 *
 * A read from byte 0 is a stream from time 0, passed on as it comes. A read from a byte P further
 * in has to be turned into a time first, since a SABR stream starts at a time, not a byte: the
 * segment that starts at or before P when one has been seen, otherwise a guess from P's share of the
 * file, made early on purpose ([earlyByMs]). The stream's init chunk is dropped, its first segment
 * is cut at P, and the rest follows. When the guess still lands after P, the stream is started once
 * more, earlier, from what the late segment said about the song's bytes per second.
 *
 * Every chunk has to begin where the last one ended, and the file has to reach [contentLength]: a
 * gap or a short stream is an IOException, never bytes from the wrong place. Plain Kotlin, no
 * Android, so the JVM tests reach it.
 *
 * @param stream a new SABR stream of the song from a time, in milliseconds
 * @param contentLength the format's length in bytes, from the player's answer
 * @param durationMs the song's length
 * @param bufferedChunks how many chunks wait between the stream and the reader before the stream
 *   is held. Generous, since holding it stops the library in the middle of a response.
 */
class SabrFile(
    private val stream: (startMs: Long) -> Flow<SabrChunk>,
    val contentLength: Long,
    private val durationMs: Long,
    private val earlyByMs: Long = 10_000,
    private val bufferedChunks: Int = 256,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Where each media segment seen so far starts: byte offset to time. Kept for later reads. */
    private val seen = ConcurrentSkipListMap<Long, Long>()

    /** A reader of the file from [position]; [Reader.read] blocks until bytes come. */
    fun open(position: Long): Reader {
        require(position >= 0) { "negative position $position" }
        return Reader(position)
    }

    inner class Reader internal constructor(start: Long) : Closeable {
        private val chunks = Channel<ByteArray>(bufferedChunks)
        private var current: ByteArray? = null
        private var at = 0
        private val job = scope.launch {
            try {
                produce(start, chunks)
                chunks.close()
            } catch (e: CancellationException) {
                chunks.close()
                throw e
            } catch (e: IOException) {
                chunks.close(e)
            } catch (e: Throwable) {
                chunks.close(IOException("SABR stream failed: ${e.message}", e))
            }
        }

        /** Up to [length] bytes into [buffer], the number read, or -1 at the end of the file. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                val held = current
                if (held != null && at < held.size) {
                    val n = minOf(length, held.size - at)
                    System.arraycopy(held, at, buffer, offset, n)
                    at += n
                    return n
                }
                val next = try {
                    runBlocking { chunks.receiveCatching() }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("SABR read interrupted")
                }
                if (next.isClosed) {
                    val cause = next.exceptionOrNull() ?: return -1
                    throw cause as? IOException ?: IOException(cause)
                }
                current = next.getOrThrow()
                at = 0
            }
        }

        /** Stops the stream: its HTTP request is cancelled with it. */
        override fun close() {
            job.cancel()
            chunks.cancel()
        }
    }

    /** The stream's way out of a start that landed after the byte asked for. */
    private class Late(val chunk: SabrChunk) : Exception(null, null, false, false)

    private suspend fun produce(from: Long, out: SendChannel<ByteArray>) {
        if (from >= contentLength) return
        var startMs = startFor(from)
        var tries = 0
        var next = from
        while (true) {
            var firstMedia = true
            try {
                stream(startMs).collect { chunk ->
                    if (chunk.isInitialization) {
                        // Only wanted when the read begins inside it.
                        if (next < chunk.endRangeExclusive) {
                            out.send(chunk.data.copyOfRange((next - chunk.startRange).toInt(), chunk.data.size))
                            next = chunk.endRangeExclusive
                        }
                        return@collect
                    }
                    chunk.startMs?.let { seen[chunk.startRange] = it }
                    if (firstMedia) {
                        firstMedia = false
                        if (chunk.startRange > next) throw Late(chunk)
                    }
                    when {
                        chunk.endRangeExclusive <= next -> Unit   // before the byte asked for
                        chunk.startRange > next -> throw IOException("SABR left a gap: byte $next missing, the next chunk starts at ${chunk.startRange}")
                        else -> {
                            val skip = (next - chunk.startRange).toInt()
                            out.send(if (skip == 0) chunk.data else chunk.data.copyOfRange(skip, chunk.data.size))
                            next = chunk.endRangeExclusive
                        }
                    }
                }
            } catch (late: Late) {
                tries++
                startMs = if (tries >= MAX_TRIES) 0 else earlierThan(startMs, late.chunk, next)
                continue
            }
            break
        }
        if (next < contentLength) throw IOException("SABR stream ended at byte $next of $contentLength")
    }

    /**
     * The time to start a stream at for byte [position]: the segment seen at or before it, else
     * the position's share of the file, [earlyByMs] early so that it lands before rather than after.
     */
    private fun startFor(position: Long): Long {
        seen.floorEntry(position)?.let { return it.value }
        if (contentLength <= 0 || durationMs <= 0) return 0
        val guess = (position.toDouble() / contentLength * durationMs).toLong() - earlyByMs
        return guess.coerceIn(0, durationMs)
    }

    /**
     * A start before [startMs] for byte [owed], from the [late] segment the stream began with: the
     * bytes it skipped, at the song's average bytes per millisecond up to that segment.
     */
    private fun earlierThan(startMs: Long, late: SabrChunk, owed: Long): Long {
        val lateMs = late.startMs ?: return 0
        if (lateMs <= 0 || late.startRange <= 0) return 0
        val bytesPerMs = late.startRange.toDouble() / lateMs
        val estimate = (lateMs - (late.startRange - owed) / bytesPerMs).toLong() - earlyByMs
        return estimate.coerceIn(0, maxOf(0, minOf(startMs, lateMs) - 1))
    }

    companion object {
        /** Starts tried for one read before going back to time 0, which always covers it. */
        const val MAX_TRIES = 3
    }
}
