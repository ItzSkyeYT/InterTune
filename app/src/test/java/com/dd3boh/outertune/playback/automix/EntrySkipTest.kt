/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The next song's frames before its entry, dropped in front of the sink: exactly that many frames,
 * across buffers of any size; the sink handed an empty buffer for each one dropped, at the entry's
 * time, so the held end drains on; the entry buffer from its entry, at the entry's time, retried as
 * it is; nothing dropped for a song that comes in from its first frame, or after a flush.
 */
class EntrySkipTest {

    private val rate = 48_000
    private val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(2).setSampleRate(rate).build()

    private class Stage(var entry: Long) : EntrySkip.Stage {
        var asked = 0
        var done = false
        var tookFrames = -1L
        override fun entryToSkip(): Long = entry.also { asked++ }
        override fun entrySkipped(done: Boolean) {
            this.done = done
        }
        override fun entrySkipTook(frames: Long, millis: Long) {
            tookFrames = frames
        }
    }

    /** The sink: what it was handed, frame by frame, and when; it takes nothing until [accepting]. */
    private class Sink {
        val frames = ArrayList<Short>()
        val emptyTimes = ArrayList<Long>()
        val times = ArrayList<Long>()
        var accepting = true
        fun handle(b: ByteBuffer, t: Long): Boolean {
            if (!b.hasRemaining()) {
                emptyTimes += t
                return true
            }
            if (!accepting) return false
            times += t
            while (b.remaining() >= 2) frames += b.short
            return true
        }
    }

    /** The song's frames from [from], [n] of them, channel 0 holding the frame's number (mod 32768). */
    private fun buffer(from: Int, n: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            b.putShort(((from + i) % 32768).toShort())
            b.putShort(0)
        }
        b.flip()
        return b
    }

    private fun us(frames: Long) = frames * 1_000_000L / rate

    @Test
    fun `the frames before the entry are dropped, to the frame, across buffers`() {
        val entry = 10_000L
        val stage = Stage(entry)
        val sink = Sink()
        val skip = EntrySkip(stage)
        skip.configured(format)
        val start = 5_000_000L
        var frame = 0
        val sizes = intArrayOf(1024, 4096, 333, 2048, 3000, 777, 4096, 4096)
        for (n in sizes) {
            assertTrue(skip.handle(buffer(frame, n), start + us(frame.toLong())) { b, t -> sink.handle(b, t) })
            frame += n
        }
        assertEquals(1, stage.asked)
        assertTrue(stage.done)
        assertEquals(entry, stage.tookFrames)
        // The first frame the sink got is the entry, and every one after it in order.
        assertEquals(entry.toInt() % 32768, sink.frames[0].toInt())
        assertEquals((frame - entry.toInt()) * 2, sink.frames.size)
        for (i in 0 until frame - entry.toInt()) assertEquals((entry.toInt() + i) % 32768, sink.frames[i * 2].toInt())
        // An empty buffer for every one dropped, at the entry's time; the entry buffer at it too.
        assertTrue(sink.emptyTimes.isNotEmpty())
        assertTrue(sink.emptyTimes.all { it == start + us(entry) })
        assertEquals(start + us(entry), sink.times[0])
    }

    @Test
    fun `the entry buffer waits for the drain, at the entry's time each time`() {
        val stage = Stage(1500)
        val sink = Sink()
        val skip = EntrySkip(stage)
        skip.configured(format)
        val b = buffer(0, 4096)
        sink.accepting = false
        assertFalse(skip.handle(b, 0) { x, t -> sink.handle(x, t) })
        assertFalse(skip.handle(b, 0) { x, t -> sink.handle(x, t) })
        sink.accepting = true
        assertTrue(skip.handle(b, 0) { x, t -> sink.handle(x, t) })
        assertEquals(listOf(us(1500)), sink.times)
        assertEquals(1500, sink.frames[0].toInt())
        assertEquals((4096 - 1500) * 2, sink.frames.size)
        // The next buffers go in as they are.
        assertTrue(skip.handle(buffer(4096, 100), us(4096)) { x, t -> sink.handle(x, t) })
        assertEquals(us(4096), sink.times.last())
    }

    @Test
    fun `nothing is dropped from a song coming in from its first frame, or after a flush`() {
        val stage = Stage(0)
        val sink = Sink()
        val skip = EntrySkip(stage)
        skip.configured(format)
        assertTrue(skip.handle(buffer(0, 512), 0) { b, t -> sink.handle(b, t) })
        assertEquals(0, sink.frames[0].toInt())
        assertTrue(sink.emptyTimes.isEmpty())
        // Asked once a stream: a later answer changes nothing for the stream that started.
        stage.entry = 4000
        assertTrue(skip.handle(buffer(512, 512), us(512)) { b, t -> sink.handle(b, t) })
        assertEquals(1, stage.asked)
        assertEquals(1024 * 2, sink.frames.size)
        // A flush in the middle of dropping: what comes after goes in as it is.
        skip.configured(format)
        assertTrue(skip.handle(buffer(0, 1000), 0) { b, t -> sink.handle(b, t) })
        skip.reset()
        val before = sink.frames.size
        assertTrue(skip.handle(buffer(1000, 1000), us(1000)) { b, t -> sink.handle(b, t) })
        assertEquals(before + 2000, sink.frames.size)
    }
}
