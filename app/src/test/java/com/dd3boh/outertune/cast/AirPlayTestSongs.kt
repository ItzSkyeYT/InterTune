/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The test signal of the AirPlay tests: every frame's samples spell its own number, left the low
 * 15 bits, right 0x4000 and the bits above, so a frame that arrives says which one it is, and
 * silence (zeros) is never taken for one. fake_receiver.py reads it the same way (--expect counter).
 */
object CounterSignal {
    fun left(frame: Long): Short = (frame and 0x7FFF).toShort()

    fun right(frame: Long): Short = (0x4000 or (frame shr 15).toInt()).toShort()

    /** The frame numbers in interleaved stereo [samples], silence left out. */
    fun frames(samples: ShortArray): List<Long> = (0 until samples.size / 2).mapNotNull { index ->
        val l = samples[index * 2].toInt() and 0xFFFF
        val r = samples[index * 2 + 1].toInt() and 0xFFFF
        if (r and 0x4000 == 0) null else ((r and 0x3FFF).toLong() shl 15) or l.toLong()
    }
}

/**
 * Serves songs of the counter signal as the cast server serves raw audio: audio/L16 at 44.1 kHz in
 * stereo, big-endian, with range requests. A url names the song and its length in frames.
 */
class CounterSongServer(address: InetAddress = InetAddress.getByName("127.0.0.1")) : AutoCloseable {
    private val server = ServerSocket(0, 50, address)
    private val host = address.hostAddress
    val requests = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "CounterSongServer") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    fun url(name: String, frames: Long) = "http://$host:${server.localPort}/token/$name/$frames/l16-44100-2.pcm"

    fun item(name: String, frames: Long) = CastItem(
        mediaId = name, url = url(name, frames), mimeType = "audio/L16;rate=44100;channels=2",
        length = frames * 4, durationMs = frames * 1000 / 44_100, converted = true,
    )

    private fun serve(socket: Socket) = socket.use {
        val head = readHead(BufferedInputStream(socket.getInputStream()))
        if (head.isEmpty()) return@use
        val path = head.lines().first().split(' ')[1]
        requests += head.lines().first() + " " + (head.lines().firstOrNull { it.startsWith("Range:") } ?: "")
        val frames = path.split('/')[3].toLong()
        val total = frames * 4
        val from = Regex("""Range: bytes=(\d+)-""").find(head)?.groupValues?.get(1)?.toLong() ?: 0
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/L16;rate=44100;channels=2\r\n" +
                "Content-Length: ${total - from}\r\nContent-Range: bytes $from-${total - 1}/$total\r\n\r\n").toByteArray(),
        )
        val chunk = ByteArray(4 * 1024)
        var position = from
        try {
            while (position < total) {
                var at = 0
                while (at < chunk.size && position < total) {
                    val frame = position / 4
                    val sample = if (position % 4 < 2) CounterSignal.left(frame) else CounterSignal.right(frame)
                    chunk[at++] = if (position % 2 == 0L) (sample.toInt() shr 8).toByte() else sample.toByte()
                    position++
                }
                out.write(chunk, 0, at)
            }
            out.flush()
        } catch (e: IOException) {
            // The link closed the connection: a pause or a seek.
        }
    }

    override fun close() = server.close()

    companion object {
        /** A request or answer head up to its blank line, or "" when the peer closed first. */
        fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            var last = 0
            while (true) {
                val byte = input.read()
                if (byte < 0) return ""
                out.write(byte)
                last = (last shl 8) or byte
                if (last == 0x0D0A0D0A) return out.toString("UTF-8").trimEnd()
            }
        }
    }
}
