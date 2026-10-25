/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sabr

import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.findSabrProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.TreeMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock

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
 * more, earlier, from what the late segment said about the song's bytes per second. A start that
 * InnerTubeX itself refuses, its first segment not covering the time asked, is tried again earlier
 * too, and from the song's beginning at worst.
 *
 * Every chunk has to begin where the last one ended, and the file has to reach [contentLength]: a
 * gap or a short stream is an IOException, never bytes from the wrong place. Plain Kotlin, no
 * Android, so the JVM tests reach it.
 *
 * Read ahead ([ahead]): from the first read on, the song is also fetched ahead of its reads on a
 * stream of its own, paced faster than the player, and held here. Every read is served from what is
 * held and waits at its edge rather than starting a stream of its own, so no byte comes twice, and
 * a connection lost mid-song stops nothing while what is held lasts. Over SABR the music ahead was
 * YouTube's readahead, about 15 s. One read ahead at a time, from where the newest read is: a seek
 * past it moves it there, and what it held stays held. It goes to the end of the file (or of what
 * the read that started it asked for), goes on past bytes already held without fetching them, and
 * stops at [Ahead.maxBytes], to be taken on again as the reads go on, letting go of what they have
 * passed. Quiet whatever happens to it: refused, by YouTube's word or anything but the network, it
 * is given up for this file, and a read that finds its byte not held goes on at the player's pace,
 * on a stream of its own as every read did before; the network gone, a read waiting on it fails as
 * its own stream would have, and the next read takes it on again.
 *
 * @param stream a new SABR stream of the song from a time, in milliseconds, paced by the player
 * @param contentLength the format's length in bytes, from the player's answer
 * @param durationMs the song's length
 * @param bufferedChunks how many chunks wait between the stream and the reader before the stream
 *   is held. Generous, since holding it stops the library in the middle of a response.
 * @param restartOn which failures of a stream that had made progress are met with a new stream at
 *   the byte still owed, instead of an error: InnerTubeX ends a stream whose protection stays
 *   "pending" after five answers, while YouTube goes on serving it (16 Oct 2026, the emulator).
 * @param ahead how the song is read ahead of its reads, or null to read it as before
 */
class SabrFile(
    private val stream: (startMs: Long) -> Flow<SabrChunk>,
    val contentLength: Long,
    private val durationMs: Long,
    private val earlyByMs: Long = 10_000,
    private val bufferedChunks: Int = 256,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val restartOn: (Throwable) -> Boolean = { false },
    private val ahead: Ahead? = null,
) {
    /**
     * How a song is read ahead of its reads: [stream], a new SABR stream of it from a time, paced
     * faster than the player; [allowed], whether to now, asked each time it would start; at most
     * [maxBytes] held; [log] for its lines; [onRestHeld] each time it has reached the end of the
     * file, called outside every lock.
     */
    class Ahead(
        val stream: (startMs: Long) -> Flow<SabrChunk>,
        val allowed: () -> Boolean = { true },
        val maxBytes: Long = MAX_AHEAD_BYTES,
        val log: (String) -> Unit = {},
        val onRestHeld: () -> Unit = {},
    ) {
        internal fun allowedNow(): Boolean = runCatching { allowed() }.getOrDefault(false)
    }

    /** Where each media segment seen so far starts: byte offset to time. Kept for later reads. */
    private val seen = ConcurrentSkipListMap<Long, Long>()

    /** And where each one ends, byte to time, so that a new stream can begin right after one. */
    private val ends = ConcurrentSkipListMap<Long, Long>()

    // ---- What is read ahead. Every field below is read and written under [lock] only.

    private val lock = ReentrantLock()

    /** Signalled at every byte held, every stop of the read ahead, every read closed. */
    private val changed = lock.newCondition()

    /** The bytes held, in pieces by the first byte of each. Never two pieces over one byte. */
    private val held = TreeMap<Long, ByteArray>()
    private var heldBytes = 0L

    /** The read ahead running, or the last one. */
    private var fill: Fill? = null

    /** Refused once: never read ahead again here. */
    private var refused = false

    /** Counts the times what was held was let go. A read opened before then never starts it again. */
    private var generation = 0

    /** The read opened last: the one that may move the read ahead to where it reads. */
    private var newest: Reader? = null

    @Volatile
    private var restHeldNow = false

    /** Whether the read ahead has reached the end of the file since it last began, nothing let go since. */
    val restHeld: Boolean get() = restHeldNow

    /** How many bytes of the file are held. */
    val heldSize: Long get() = lock.withLock { heldBytes }

    /** Whether the byte at [position] is held. */
    fun holds(position: Long): Boolean = lock.withLock { pieceAt(position) != null }

    /**
     * Reads the song ahead from [from] before anything reads it, as the next one in the queue. False
     * when it may not: no [ahead], not [Ahead.allowed] now, or refused here before.
     */
    fun readAhead(from: Long = 0): Boolean = lock.withLock {
        val a = ahead ?: return false
        if (refused || !a.allowedNow()) return false
        if (fill?.running == true) return true
        if (firstMissing(from) == null) {
            restHeldNow = true
            return true
        }
        startFill(a, from, contentLength, readAt = from, why = ", before it plays")
        true
    }

    /**
     * Stops the read ahead and lets go of every byte held: the queue has moved on from the song, or
     * its start is gone. A read open now goes on at the player's pace; one opened after reads ahead
     * again.
     */
    fun dropAhead(why: String = "") {
        val bytes = lock.withLock {
            generation++
            fill?.let { stop(it) }
            fill = null
            restHeldNow = false
            val had = heldBytes
            held.clear()
            heldBytes = 0
            changed.signalAll()
            had
        }
        if (bytes > 0) ahead?.log?.invoke("SABR read ahead let go of $bytes bytes$why")
    }

    /**
     * A reader of the file from [position], for [length] bytes or -1 for the rest of it, the length
     * only bounding what it reads ahead: [Reader.read] blocks until bytes come.
     */
    fun open(position: Long, length: Long = -1): Reader {
        require(position >= 0) { "negative position $position" }
        val until = if (length >= 0) minOf(contentLength, position + length) else contentLength
        return Reader(position, until)
    }

    inner class Reader internal constructor(start: Long, private val until: Long) : Closeable {
        private var position = start

        /** Its own stream at the player's pace, once it reads on its own: as every read went before. */
        @Volatile
        private var own: Own? = null

        @Volatile
        private var closed = false

        /** The read ahead this read last waited on: when it fails for the network, so does this read. */
        private var waitedOn: Fill? = null

        /** What was held when it opened: let go of since, this read no longer starts the read ahead. */
        private val generation: Int = lock.withLock { this@SabrFile.generation }

        init {
            if (ahead == null) {
                own = Own(start)
            } else {
                // At once, as a stream of its own started at once before: the read ahead from here,
                // unless the byte is held already or on its way.
                val step = lock.withLock {
                    newest = this
                    if (start < contentLength) plan() else null
                }
                if (step === Step.OnItsOwn) own = Own(start)
            }
        }

        /** Up to [length] bytes into [buffer], the number read, or -1 at the end of the file. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            own?.let { return it.read(buffer, offset, length) }
            while (true) {
                if (closed) throw IOException("SABR read closed")
                if (position >= contentLength) return -1
                when (val step = lock.withLock { plan() }) {
                    is Step.Held -> {
                        val from = (position - step.at).toInt()
                        val n = minOf(length, step.bytes.size - from)
                        System.arraycopy(step.bytes, from, buffer, offset, n)
                        position += n
                        return n
                    }
                    is Step.Wait -> await(step.fill)
                    is Step.Fail -> throw step.failure
                    Step.OnItsOwn -> {
                        val mine = Own(position)
                        own = mine
                        if (closed) {
                            mine.close()
                            throw IOException("SABR read closed")
                        }
                        return mine.read(buffer, offset, length)
                    }
                }
            }
        }

        /** Stops its stream, if it has one of its own: the HTTP request is cancelled with it. */
        override fun close() {
            closed = true
            own?.close()
            lock.withLock {
                if (newest === this) newest = null
                changed.signalAll()
            }
        }

        /** Where the read ahead it starts goes to: its own end, or past it the file's. */
        private fun fillsTo(): Long = if (position < until) until else contentLength

        /** What this read does for the byte at [position] now. Under the lock. */
        private fun plan(): Step {
            val a = ahead ?: return Step.OnItsOwn
            pieceAt(position)?.let { piece ->
                // Given bytes since it last waited: a failure after this is not one it waited on.
                waitedOn = null
                takeOnIfShort(a)
                return Step.Held(piece.key, piece.value)
            }
            if (refused || generation != this@SabrFile.generation) return Step.OnItsOwn
            val f = fill
            if (f != null && f.running) {
                // Its next bytes, or a little past them: it gets there sooner than a new stream would.
                if (position >= f.next && position - f.next <= NEAR_BYTES) return Step.Wait(f)
                // Somewhere else, for another read: this one goes on its own rather than take it away.
                if (newest != null && newest !== this) return Step.OnItsOwn
                if (!a.allowedNow()) return Step.OnItsOwn
                return Step.Wait(startFill(a, position, fillsTo(), position, ", where the player reads now"))
            }
            // Stopped by the network while this read waited on it: this read fails as its own
            // stream would have. The next read starts it again.
            val failure = f?.failure
            if (failure != null && waitedOn === f) {
                waitedOn = null
                return Step.Fail(failure)
            }
            if (!a.allowedNow()) return Step.OnItsOwn
            return Step.Wait(startFill(a, position, fillsTo(), position, if (f == null) "" else ", on again"))
        }

        /**
         * A read ahead stopped at its most is taken on again before the newest read reaches the end
         * of what is held: with half of it still ahead, after letting go of what the reads passed.
         */
        private fun takeOnIfShort(a: Ahead) {
            if (newest !== this) return
            val f = fill ?: return
            if (f.running || !f.full || refused || generation != this@SabrFile.generation) return
            val end = firstMissing(position) ?: return
            if (end >= fillsTo() || end - position >= a.maxBytes / 2 || !a.allowedNow()) return
            startFill(a, end, fillsTo(), position, ", on again as the song plays")
        }

        /** Until the byte is held, the read ahead [f] stops or is replaced, or this read is closed. */
        private fun await(f: Fill) {
            lock.withLock {
                waitedOn = f
                while (!closed && fill === f && f.running && pieceAt(position) == null) {
                    try {
                        changed.await(1, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("SABR read interrupted")
                    }
                }
            }
        }
    }

    /** What a read does next: copy what is held, wait for the read ahead, fail with it, or go on its own. */
    private sealed interface Step {
        class Held(val at: Long, val bytes: ByteArray) : Step
        class Wait(val fill: Fill) : Step
        class Fail(val failure: IOException) : Step
        object OnItsOwn : Step
    }

    /** One read ahead: from [from] to [until] at most, its next byte, and why it stopped. */
    private class Fill(val from: Long, val until: Long) {
        @Volatile
        var next = from
        var running = true
        var full = false
        var failure: IOException? = null
        lateinit var job: Job
    }

    /** A read's own stream, paced by the player: how every read went before there was a read ahead. */
    private inner class Own(start: Long) : Closeable {
        private val chunks = Channel<ByteArray>(bufferedChunks)
        private var current: ByteArray? = null
        private var at = 0
        private val job = scope.launch {
            try {
                produce(start, stream) { _, bytes -> chunks.send(bytes) }
                chunks.close()
            } catch (e: CancellationException) {
                // With its cause. Closed with none, a read waiting on the channel took it for the
                // end of the file when this close won the race with the reader's own (the stall
                // watch's cut), and media3 kept the cut point as the song's length.
                chunks.close(e)
                throw e
            } catch (e: IOException) {
                chunks.close(e)
            } catch (e: Throwable) {
                chunks.close(IOException("SABR stream failed: ${e.message}", e))
            }
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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

        override fun close() {
            job.cancel()
            chunks.cancel()
        }
    }

    // ---- the read ahead's own work

    /** The piece that holds [position], if one does. Under the lock. */
    private fun pieceAt(position: Long): Map.Entry<Long, ByteArray>? =
        held.floorEntry(position)?.takeIf { position < it.key + it.value.size }

    /** The first byte at or after [from] that is not held, or null when the rest of the file is. Under the lock. */
    private fun firstMissing(from: Long): Long? {
        var p = from
        while (p < contentLength) {
            val piece = pieceAt(p) ?: return p
            p = piece.key + piece.value.size
        }
        return null
    }

    /** Ends [f], which is then no longer running. Under the lock. */
    private fun stop(f: Fill) {
        if (!f.running) return
        f.running = false
        f.job.cancel()
    }

    /**
     * A new read ahead from [from] to [until], in place of the one running, with room made for it
     * around a read at [readAt]. Under the lock.
     */
    private fun startFill(a: Ahead, from: Long, until: Long, readAt: Long, why: String): Fill {
        fill?.let { stop(it) }
        makeRoom(readAt, a.maxBytes)
        restHeldNow = false
        val f = Fill(from, until)
        fill = f
        a.log("SABR read ahead from byte $from of $contentLength$why")
        f.job = scope.launch { runFill(f, a) }
        return f
    }

    /**
     * Held at its most: what the reads have passed goes first, then, if that is not enough, what is
     * farthest ahead of [readAt], down to half. Under the lock.
     */
    private fun makeRoom(readAt: Long, max: Long) {
        while (heldBytes >= max) {
            val first = held.firstEntry() ?: break
            if (first.key + first.value.size > readAt) break
            held.remove(first.key)
            heldBytes -= first.value.size
        }
        if (heldBytes < max) return
        while (heldBytes >= max / 2) {
            val last = held.lastEntry() ?: break
            if (last.key <= readAt) break
            held.remove(last.key)
            heldBytes -= last.value.size
        }
    }

    /** The read ahead's stream's way out at bytes held already, at [at], or at the end it was given. */
    private class Reached(val at: Long) : Exception(null, null, false, false)

    /** And at as many bytes held as it may. */
    private class Full : Exception(null, null, false, false)

    private suspend fun runFill(f: Fill, a: Ahead) {
        val began = System.nanoTime()
        try {
            var at = f.from
            while (true) {
                val gap = lock.withLock {
                    if (fill !== f) return
                    firstMissing(at)
                }
                if (gap == null || gap >= f.until) break
                try {
                    produce(gap, a.stream) { position, bytes -> keep(f, a, position, bytes) }
                    break
                } catch (reached: Reached) {
                    at = reached.at
                }
            }
            val whole = lock.withLock {
                if (fill !== f) return
                f.running = false
                val end = f.until >= contentLength
                if (end) restHeldNow = true
                changed.signalAll()
                end
            }
            a.log("SABR read ahead held to ${if (whole) "the end" else "byte ${f.until}"}: $heldSize bytes, in ${secondsSince(began)} s")
            if (whole) runCatching { a.onRestHeld() }
        } catch (full: Full) {
            lock.withLock {
                if (fill === f) {
                    f.running = false
                    f.full = true
                    changed.signalAll()
                }
            }
            a.log("SABR read ahead holds $heldSize bytes, as many as it may: on again as the song plays")
        } catch (e: CancellationException) {
            lock.withLock {
                if (fill === f) {
                    f.running = false
                    changed.signalAll()
                }
            }
            throw e
        } catch (e: Throwable) {
            val network = e.isNetwork()
            lock.withLock {
                if (fill === f) {
                    f.running = false
                    if (network) f.failure = e as? IOException ?: IOException(e) else refused = true
                    changed.signalAll()
                }
            }
            a.log(
                if (network) "SABR read ahead stopped, no network: ${e.javaClass.simpleName}"
                else "SABR read ahead refused, read at the player's pace from here: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * The bytes at [position] from the read ahead's stream, held: up to bytes held already, or the
     * end [f] was given, where the stream stops ([Reached]), and [Full] at as many as it may hold.
     */
    private fun keep(f: Fill, a: Ahead, position: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.withLock {
            if (fill !== f) throw CancellationException("the read ahead was replaced")
            if (pieceAt(position) != null) throw Reached(position)
            val end = minOf(position + bytes.size, held.higherKey(position) ?: Long.MAX_VALUE, f.until)
            val piece = if (end == position + bytes.size) bytes else bytes.copyOfRange(0, (end - position).toInt())
            held[position] = piece
            heldBytes += piece.size
            f.next = end
            changed.signalAll()
            if (end < position + bytes.size || end >= f.until) throw Reached(end)
            if (heldBytes >= a.maxBytes) throw Full()
        }
    }

    private fun secondsSince(began: Long) = (System.nanoTime() - began) / 100_000_000 / 10.0

    /** A failure for want of a network, by what is in its causes: as SabrFailures tells one. */
    private fun Throwable.isNetwork(): Boolean = generateSequence(this) { it.cause }.take(8).any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is SocketTimeoutException || it is SocketException || it is SSLException ||
            it.javaClass.simpleName == "HttpRequestTimeoutException"
    }

    /** The stream's way out of a start that landed after the byte asked for. */
    private class Late(val chunk: SabrChunk) : Exception(null, null, false, false)

    /** The file from [from] off [stream], handed on with where each piece begins as it comes. */
    private suspend fun produce(from: Long, stream: (Long) -> Flow<SabrChunk>, emit: suspend (position: Long, bytes: ByteArray) -> Unit) {
        if (from >= contentLength) return
        var startMs = startFor(from)
        var tries = 0
        var restarts = 0
        var next = from
        while (true) {
            var firstMedia = true
            val before = next
            try {
                stream(startMs).collect { chunk ->
                    if (chunk.isInitialization) {
                        // Only wanted when the read begins inside it.
                        if (next < chunk.endRangeExclusive) {
                            emit(next, chunk.data.copyOfRange((next - chunk.startRange).toInt(), chunk.data.size))
                            next = chunk.endRangeExclusive
                        }
                        return@collect
                    }
                    chunk.startMs?.let { start ->
                        seen[chunk.startRange] = start
                        chunk.durationMs?.let { ends[chunk.endRangeExclusive] = start + it }
                    }
                    if (firstMedia) {
                        firstMedia = false
                        if (chunk.startRange > next) throw Late(chunk)
                    }
                    when {
                        chunk.endRangeExclusive <= next -> Unit   // before the byte asked for
                        chunk.startRange > next -> throw IOException("SABR left a gap: byte $next missing, the next chunk starts at ${chunk.startRange}")
                        else -> {
                            val skip = (next - chunk.startRange).toInt()
                            emit(next, if (skip == 0) chunk.data else chunk.data.copyOfRange(skip, chunk.data.size))
                            next = chunk.endRangeExclusive
                        }
                    }
                }
            } catch (late: Late) {
                tries++
                startMs = if (tries >= MAX_TRIES) 0 else earlierThan(startMs, late.chunk, next)
                continue
            } catch (stopped: Throwable) {
                if (stopped is CancellationException || stopped is IOException) throw stopped
                // The read ahead's own ways out, never a failure of the stream.
                if (stopped is Reached || stopped is Full) throw stopped
                // As a late start, with no segment to say how late: earlier, then the beginning,
                // which every stream covers.
                if (firstMedia && startMs > 0 && stopped.isUncoveredStart()) {
                    tries++
                    startMs = if (tries >= MAX_TRIES) 0 else (startMs - earlyByMs).coerceAtLeast(0)
                    continue
                }
                // A stream that made progress and was ended for a reason the caller says a new
                // stream gets past: carried on at the byte still owed, which the reader never sees.
                if (next > before && next < contentLength && restarts < MAX_RESTARTS && restartOn(stopped)) {
                    restarts++
                    tries = 0
                    startMs = startFor(next)
                    continue
                }
                throw stopped
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
        ends[position]?.let { return inside(it) }
        // The segment seen that holds the byte, and only that one: the last one seen before a seek
        // far past it started the stream there, and the read waited on everything in between.
        seen.floorEntry(position)?.let { floor ->
            val end = ends.higherKey(floor.key)
            if (end != null && position < end) return inside(floor.value)
        }
        if (contentLength <= 0 || durationMs <= 0) return 0
        val guess = (position.toDouble() / contentLength * durationMs).toLong() - earlyByMs
        return guess.coerceIn(0, durationMs)
    }

    /**
     * A time [INSIDE_MS] into the segment that starts at [startMs], a time read off the segments
     * seen. Those times are cut to whole milliseconds, a segment's start and its length each
     * (InnerTubeX's ticksToMs), so for AAC at 44.1 kHz, whose segments mostly start inside a
     * millisecond, one can fall just before the segment truly starts. Asked for there, YouTube
     * serves the segment before, which by the cut numbers ends there, and InnerTubeX refuses the
     * start: "SABR first media segment does not cover the requested start", seen on a phone on 21
     * Oct 2026. Asked a little further in, the stream begins with the segment wanted.
     */
    private fun inside(startMs: Long): Long =
        (startMs + INSIDE_MS).let { if (durationMs > 0) it.coerceAtMost(maxOf(startMs, durationMs - 1)) else it }

    /** InnerTubeX's refusal of a stream whose first segment does not cover the time it was started at. */
    private fun Throwable.isUncoveredStart(): Boolean =
        findSabrProtocolException()?.message?.contains(UNCOVERED_START) == true

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

        /** New streams one read may be carried on with ([restartOn]): about a minute each, so an hour. */
        const val MAX_RESTARTS = 60

        /**
         * How far into a segment seen a stream is started: well past the cut of its start, well
         * short of its end (SABR's audio segments run about ten seconds).
         */
        const val INSIDE_MS = 100L

        /** The words of InnerTubeX's refusal (SabrAudioStream's first-segment check). */
        const val UNCOVERED_START = "first media segment does not cover the requested start"

        /**
         * How much of one song the read ahead holds at most: 16 MB, a whole song of 16 minutes at
         * 128 kb/s, or of 8 at 256. A longer one is held a stretch at a time.
         */
        const val MAX_AHEAD_BYTES = 16L * 1024 * 1024

        /**
         * How far past the read ahead's next byte a read waits for it rather than move it: about
         * four seconds of music at 128 kb/s, which it brings sooner than a new stream would.
         */
        const val NEAR_BYTES = 64L * 1024
    }
}
