/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneSet
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max

/**
 * An editor window's parts, where they have been made: vocals, drums, bass and melody (in
 * [LaneSet.PARTS]'s order), each `<key>-<end>.<part>.pcm` in the cache's `mix/parts`, 16-bit
 * interleaved at the window's rate and channels and of its length, the four adding up to the
 * window. Made on the PC for now from the editor's own windows (experiments/features/automix/parts/
 * tools/split_windows.py, SCNet Standard at overlap 1.33) and copied in; the phone's own splitting
 * comes later. Mapped from their files, off the app's heap: a window's four parts weigh four
 * windows.
 */
internal class PartsStore(mixDir: File) {
    val dir = File(mixDir, "parts")

    /** [id]'s [end] window's four parts, mapped, when all four are there at [samples] samples each; null otherwise. */
    fun map(id: String, end: String, samples: Long): List<ShortBuffer>? {
        val key = EditorCache.digest(id)
        val files = NAMES.map { File(dir, "$key-$end.$it.pcm") }
        if (files.any { !it.isFile || it.length() != samples * 2 }) return null
        return try {
            files.map { f ->
                RandomAccessFile(f, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        /** The file names of the parts, in [LaneSet.PARTS]'s order. */
        val NAMES = listOf("vocals", "drums", "bass", "melody")

        init {
            check(LaneSet.PARTS.lanes == listOf(Lane.VOCALS, Lane.DRUMS, Lane.BASS, Lane.MELODY))
        }

        /**
         * Each part's peak every [hopMs] ms, as the band envelopes are measured: what the canvas draws
         * a part's lane from.
         */
        fun envelopes(parts: List<ShortBuffer>, channels: Int, sampleRate: Int, hopMs: Double): Map<Lane, FloatArray> {
            val hopFrames = max(1, (sampleRate * hopMs / 1000).toInt())
            return LaneSet.PARTS.lanes.zip(parts).associate { (lane, buf) ->
                val frames = buf.limit() / channels
                val hops = (frames + hopFrames - 1) / hopFrames
                val out = FloatArray(hops)
                for (h in 0 until hops) {
                    var peak = 0
                    val end = minOf(frames, (h + 1) * hopFrames) * channels
                    var i = h * hopFrames * channels
                    while (i < end) {
                        val s = abs(buf.get(i).toInt())
                        if (s > peak) peak = s
                        i++
                    }
                    out[h] = peak / 32768f
                }
                lane to out
            }
        }
    }
}
