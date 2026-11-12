/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/** A stretch of a song decoded at its full rate, as it lies in a file: where it starts in the song, and how long the song is. */
data class WindowInfo(
    val sampleRate: Int,
    val channels: Int,
    /** The song's time of the first frame, in ms. */
    val startMs: Double,
    val frames: Long,
    /** The whole song's length, in ms. */
    val durationMs: Double,
) {
    val endMs: Double get() = startMs + frames * 1000.0 / sampleRate

    /** One line per value, so the cache needs no parser. */
    fun toText(): String = "v=$VERSION\nrate=$sampleRate\nchannels=$channels\nstart=$startMs\nframes=$frames\nduration=$durationMs\n"

    companion object {
        const val VERSION = 1

        fun of(text: String): WindowInfo? = runCatching {
            val v = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (v["v"]?.toInt() != VERSION) return null
            WindowInfo(v.getValue("rate").toInt(), v.getValue("channels").toInt(), v.getValue("start").toDouble(), v.getValue("frames").toLong(), v.getValue("duration").toDouble())
        }.getOrNull()
    }
}

/**
 * A decoded window, mapped from its file rather than read into the heap: two songs' worth of
 * full-rate 16-bit audio is 25 MB, which the preview and the canvas read from wherever the page
 * is, and the system pages it in and out as it likes.
 */
class PcmWindow(val info: WindowInfo, private val samples: ShortBuffer) {
    val frames: Int get() = info.frames.toInt()

    /** One sample, 0 outside the window; a mono song gives its one channel for both. */
    fun at(frame: Int, channel: Int): Short {
        if (frame < 0 || frame >= frames) return 0
        val c = if (channel < info.channels) channel else info.channels - 1
        return samples.get(frame * info.channels + c)
    }

    /** [count] frames from [from] into [into], interleaved; silence outside the window. */
    fun read(from: Int, count: Int, into: ShortArray) {
        val n = count * info.channels
        java.util.Arrays.fill(into, 0, n, 0)
        val lo = max(0, from)
        val hi = minOf(frames, from + count)
        if (hi <= lo) return
        val view = samples.duplicate()
        view.position(lo * info.channels)
        view.get(into, (lo - from) * info.channels, (hi - lo) * info.channels)
    }

    companion object {
        fun map(pcm: File, info: WindowInfo): PcmWindow {
            RandomAccessFile(pcm, "r").use { file ->
                val buffer = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN)
                return PcmWindow(info, buffer.asShortBuffer())
            }
        }
    }
}
