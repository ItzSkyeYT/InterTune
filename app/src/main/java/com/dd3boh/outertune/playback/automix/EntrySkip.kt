/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * In front of the sink, for a next song that a change brings in at a bar of its middle: its frames
 * before that bar are dropped as they are decoded, so they never reach the chain and are never
 * heard. For every buffer dropped the sink is handed an empty one, so the end AutoMix holds goes on
 * draining while the decoder races through them and the music never runs dry. The first buffer
 * that goes in starts on the entry, its time moved there: the sink counts the song from the entry,
 * so its position, seekbar and lyrics are true from the first frame heard, and after the change
 * one frame goes out for every frame of it, as before.
 *
 * Frames are counted as decoded, before the sink trims the encoder's delay, which it then trims
 * from the first frames it is given: the entry it hands on is the song's own frame [Stage.entryToSkip]
 * as AutoMix's copy of it counts, trimmed the same way.
 *
 * Asked once, at the first buffer of each new stream; once dropping has begun it goes on to the
 * entry whatever happens to the change. The player's thread only.
 */
internal class EntrySkip(private val stage: Stage, private val nanoTime: () -> Long = System::nanoTime) {

    interface Stage {
        /** How many of the new stream's first frames to drop: 0 to let it in from its first. */
        fun entryToSkip(): Long

        /** Some of them were dropped, all of them when [done]. */
        fun entrySkipped(done: Boolean)

        /** They took [millis] to drop, [frames] of them. */
        fun entrySkipTook(frames: Long, millis: Long)
    }

    private var frameBytes = 0
    private var rate = 0
    private var newStream = false
    private var target = 0L
    private var skipped = 0L
    private var entryUs = C.TIME_UNSET
    private var entryBuffer: ByteBuffer? = null
    private var startedNanos = 0L

    /** Whether frames of the stream starting now are being dropped. */
    val skipping: Boolean get() = newStream && target > 0 && skipped < target

    /** A stream is configured: its first buffer is the one asked about. */
    fun configured(format: Format) {
        val pcm = MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding != Format.NO_VALUE && format.channelCount > 0
        frameBytes = if (pcm) Util.getPcmFrameSize(format.pcmEncoding, format.channelCount) else 0
        rate = format.sampleRate
        newStream = frameBytes > 0 && rate > 0
        target = -1
        skipped = 0
        entryUs = C.TIME_UNSET
        entryBuffer = null
    }

    /**
     * The renderer's buffer at [timeUs], handed on to [sink] (the sink's own handleBuffer) from the
     * entry; true when it is all taken, dropped or played.
     */
    fun handle(buffer: ByteBuffer, timeUs: Long, sink: (ByteBuffer, Long) -> Boolean): Boolean {
        if (!newStream) return sink(buffer, timeUs)
        if (target < 0) {
            target = stage.entryToSkip()
            if (target <= 0) {
                newStream = false
                return sink(buffer, timeUs)
            }
            // The first buffer starts on the song's frame 0 as decoded: the entry is that many frames later.
            entryUs = timeUs + target * 1_000_000L / rate
            startedNanos = nanoTime()
        }
        if (skipped < target) {
            val frames = buffer.remaining() / frameBytes
            val take = min(frames.toLong(), target - skipped).toInt()
            buffer.position(buffer.position() + take * frameBytes)
            skipped += take
            val done = skipped == target
            stage.entrySkipped(done)
            if (done) stage.entrySkipTook(target, (nanoTime() - startedNanos) / 1_000_000L)
            // The held end drains on, at the entry's time should the drain close on this call.
            sink(EMPTY, entryUs)
            if (!buffer.hasRemaining()) return true
            entryBuffer = buffer
        }
        val taken = sink(buffer, if (buffer === entryBuffer) entryUs else timeUs)
        if (taken) {
            newStream = false
            entryBuffer = null
        }
        return taken
    }

    /** A flush or a reset of the sink: nothing is being dropped any more. */
    fun reset() {
        newStream = false
        target = -1
        entryBuffer = null
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
