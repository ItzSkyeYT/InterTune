/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Decoded audio waiting to be sent: a ring of 16-bit frames that one thread fills ([write], which
 * waits while it is full) and the sender empties a packet at a time ([take], which never waits,
 * since the sender runs against the clock).
 */
class PcmPipe(capacityFrames: Int, private val channels: Int = AirPlay.CHANNELS) : Closeable {
    private val buffer = ShortArray(capacityFrames * channels)
    private val capacity = capacityFrames
    private val lock = Object()
    private var start = 0
    private var count = 0
    private var ended = false
    private var closed = false

    /** Why the audio stopped coming, once it has. */
    @Volatile
    var failure: IOException? = null
        private set

    /** Frames waiting. */
    val available: Int get() = synchronized(lock) { count }

    /** Whether every frame there will be has been written ([end]) and taken. */
    val drained: Boolean get() = synchronized(lock) { ended && count == 0 }

    /** Whether no more will come: the end, or a failure. */
    val finished: Boolean get() = synchronized(lock) { ended || failure != null }

    /** Adds [frames] frames of [samples] from index [from], waiting for room. False once closed. */
    fun write(samples: ShortArray, from: Int, frames: Int): Boolean {
        var done = 0
        while (done < frames) {
            synchronized(lock) {
                while (count == capacity && !closed) lock.wait()
                if (closed) return false
                val room = capacity - count
                val chunk = minOf(room, frames - done)
                var at = (start + count) % capacity
                var left = chunk
                var source = from + done * channels
                while (left > 0) {
                    val run = minOf(left, capacity - at)
                    System.arraycopy(samples, source, buffer, at * channels, run * channels)
                    source += run * channels
                    left -= run
                    at = (at + run) % capacity
                }
                count += chunk
                done += chunk
            }
        }
        return true
    }

    /**
     * One packet of [frames] frames into [out], or nothing: all of them when they are there, the
     * rest padded with silence once the song has ended, and 0 when fewer are waiting and more are
     * to come, so that a song is never cut into by a gap of a few frames. Returns the frames of
     * audio given, which is less than [frames] only at the end.
     */
    fun take(out: ShortArray, frames: Int): Int = synchronized(lock) {
        if (count < frames && !ended) return 0
        val given = minOf(frames, count)
        var left = given
        var at = 0
        while (left > 0) {
            val run = minOf(left, capacity - start)
            System.arraycopy(buffer, start * channels, out, at * channels, run * channels)
            at += run
            left -= run
            start = (start + run) % capacity
        }
        count -= given
        if (given < frames) out.fill(0, given * channels, frames * channels)
        if (given > 0) lock.notifyAll()
        given
    }

    /** Every frame there will be has been written. */
    fun end() = synchronized(lock) {
        ended = true
        lock.notifyAll()
    }

    fun fail(cause: IOException) = synchronized(lock) {
        if (failure == null) failure = cause
        lock.notifyAll()
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}

/**
 * Reads a song's raw audio from the cast server into a [PcmPipe], from [startFrame] on: the url
 * the remote player gives every receiver, here as audio/L16 at 44.1 kHz in stereo, big-endian,
 * fetched with a range request from the frame on. A connection that ends before the song does is
 * opened again where it stopped, a few times, before the song is taken as failed; one that the
 * server ends because this one stopped reading (a long pause) is simply opened again.
 */
class AirPlayFetch(
    private val url: String,
    private val startFrame: Long,
    /** Frames in the whole song, or -1 when not known; the end of the answer is then the end. */
    private val totalFrames: Long,
    private val pipe: PcmPipe,
    private val sockets: SocketFactory = SocketFactory.getDefault(),
    private val name: String = "AirPlay-fetch",
) : Closeable {
    @Volatile
    private var closed = false

    @Volatile
    private var socket: Socket? = null

    /** The next frame the server will send. */
    @Volatile
    var nextFrame: Long = startFrame
        private set

    private val thread = Thread({ run() }, name).apply { isDaemon = true }

    fun start(): AirPlayFetch {
        thread.start()
        return this
    }

    private fun run() {
        var failures = 0
        try {
            while (!closed) {
                if (totalFrames in 0..nextFrame) {
                    pipe.end()
                    return
                }
                try {
                    val gave = readFrom(nextFrame)
                    if (closed) return
                    if (totalFrames < 0) {
                        // Nothing says how long the song is: the end of an answer is its end.
                        pipe.end()
                        return
                    }
                    if (gave > 0) failures = 0
                    if (nextFrame < totalFrames && ++failures > MAX_TRIES) {
                        throw IOException("The song stopped at frame $nextFrame of $totalFrames")
                    }
                } catch (e: IOException) {
                    if (closed) return
                    if (++failures > MAX_TRIES) {
                        Log.w(TAG, "$url failed at frame $nextFrame: ${e.message}")
                        pipe.fail(e)
                        return
                    }
                    Log.i(TAG, "Opening $url again at frame $nextFrame: ${e.message}")
                    pause(RETRY_MS * failures)
                }
            }
        } catch (e: Exception) {
            // Nothing a fetch meets may close the app; the song fails instead.
            if (!closed) pipe.fail(e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e))
        }
    }

    private val sleeper = Object()

    private fun pause(ms: Long) = synchronized(sleeper) {
        if (!closed) sleeper.wait(ms)
    }

    /** One request from [frame] on, its audio into the pipe; the frames it gave. */
    private fun readFrom(frame: Long): Long {
        val uri = LanHttp.parseUrl(url)
        val port = if (uri.port > 0) uri.port else 80
        val connection = sockets.createSocket()
        socket = connection
        try {
            if (closed) return 0
            connection.connect(InetSocketAddress(uri.host.trim('[', ']'), port), CONNECT_TIMEOUT_MS)
            connection.tcpNoDelay = true
            connection.soTimeout = READ_TIMEOUT_MS
            val headers = listOf("User-Agent" to USER_AGENT, "Range" to "bytes=${frame * FRAME_BYTES}-")
            connection.getOutputStream().apply {
                write(LanHttp.requestBytes("GET", uri, headers, null))
                flush()
            }
            val input = BufferedInputStream(connection.getInputStream(), 64 * 1024)
            val status = readHead(input)
            if (status != 206 && !(status == 200 && frame == 0L)) throw IOException("The server answered $status for frame $frame")
            // Big-endian 16-bit samples, two to a frame. The bytes of a frame cut off at the end of
            // one read wait at the start of the buffer for the rest.
            val bytes = ByteArray(READ_BYTES + FRAME_BYTES)
            val samples = ShortArray((READ_BYTES + FRAME_BYTES) / 2)
            var kept = 0
            var gave = 0L
            while (!closed) {
                val count = input.read(bytes, kept, READ_BYTES)
                if (count < 0) break
                val total = kept + count
                val frames = total / FRAME_BYTES
                for (index in 0 until frames * AirPlay.CHANNELS) {
                    samples[index] = ((bytes[index * 2].toInt() shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)).toShort()
                }
                kept = total - frames * FRAME_BYTES
                System.arraycopy(bytes, frames * FRAME_BYTES, bytes, 0, kept)
                if (frames > 0) {
                    if (!pipe.write(samples, 0, frames)) return gave
                    nextFrame += frames
                    gave += frames
                }
            }
            return gave
        } finally {
            socket = null
            runCatching { connection.close() }
        }
    }

    /** The status line and headers of the answer; the status. */
    private fun readHead(input: InputStream): Int {
        val line = readLine(input) ?: throw IOException("The server closed without answering")
        val status = line.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP answer: $line")
        while (true) {
            val header = readLine(input) ?: throw IOException("The answer ended in its headers")
            if (header.isEmpty()) return status
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.write(byte)
            if (out.size() > 8192) throw IOException("A header line too long")
        }
        return out.toString("ISO-8859-1")
    }

    override fun close() {
        closed = true
        socket?.let { runCatching { it.close() } }
        synchronized(sleeper) { sleeper.notifyAll() }
    }

    companion object {
        private const val TAG = "AirPlayFetch"
        const val FRAME_BYTES = 4
        const val USER_AGENT = "InterTune AirPlay"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val READ_BYTES = 32 * 1024
        private const val MAX_TRIES = 4
        private const val RETRY_MS = 500L
    }
}
