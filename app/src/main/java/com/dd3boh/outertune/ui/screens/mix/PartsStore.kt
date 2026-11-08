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
 * window. Made from the editor's own windows by SCNet Standard at overlap 1.33: on the PC
 * (experiments/features/automix/parts/tools/split_windows.py) and copied in, or in the debug app
 * on the phone as the editor opens ([PartsMaker]). Mapped from their files, off the app's heap: a
 * window's four parts weigh four windows. They go with their window ([EditorCache.prune]).
 */
internal class PartsStore(mixDir: File) {
    val dir = File(mixDir, "parts")

    /** [id]'s [end] window's four files, in [NAMES]' order, there or not. */
    fun files(id: String, end: String): List<File> = filesOf("${EditorCache.digest(id)}-$end")

    private fun filesOf(stem: String): List<File> = NAMES.map { File(dir, "$stem.$it.pcm") }

    /** Whether all four of [id]'s [end] window's parts are there, at [samples] samples each. */
    fun has(id: String, end: String, samples: Long): Boolean = files(id, end).all { it.isFile && it.length() == samples * 2 }

    /** Drops [id]'s [end] window's parts, as its window is read again. */
    fun delete(id: String, end: String) = files(id, end).forEach { it.delete() }

    /** Drops the parts of the window kept as `<stem>.pcm`, as it leaves the editor's cache. */
    fun deleteOf(stem: String) = filesOf(stem).forEach { it.delete() }

    /** Drops what a split cut short left behind (its process ended), untouched for [STALE_MS]. */
    fun deleteLeftovers(now: Long) {
        dir.listFiles()?.filter { it.name.endsWith(".part") && now - it.lastModified() > STALE_MS }?.forEach { it.delete() }
    }

    /** [id]'s [end] window's four parts, mapped, when all four are there at [samples] samples each; null otherwise. */
    fun map(id: String, end: String, samples: Long): List<ShortBuffer>? {
        val files = files(id, end)
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

        /** A split writes its files as it goes, so one an hour old was cut short. */
        const val STALE_MS = 60 * 60 * 1000L

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
