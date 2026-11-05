/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Real songs for the host trial, decoded once with ffmpeg into raw 16-bit stereo and kept.
 *
 * The songs are read from AUTOMIX_TRIAL_DIR, in name order, and never written to; their decoded
 * copies go to AUTOMIX_WORK (by default the automix experiments' work/decoded folder), outside the
 * repository, and nothing decoded or rendered from them ever leaves this PC.
 */
class TrialSong(val file: File, val title: String, val sampleRate: Int, val decoded: File, val album: String?) {

    val frames: Int get() = (decoded.length() / 4).toInt()
    val seconds: Double get() = frames.toDouble() / sampleRate

    /** Frames [from] to [to] of the song, interleaved stereo. */
    fun read(from: Int = 0, to: Int = frames): ShortArray {
        val lo = from.coerceIn(0, frames)
        val hi = to.coerceIn(lo, frames)
        val bytes = ByteArray((hi - lo) * 4)
        java.io.RandomAccessFile(decoded, "r").use {
            it.seek(lo * 4L)
            it.readFully(bytes)
        }
        val out = ShortArray((hi - lo) * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }
}

object TrialSongs {

    val dir: String? get() = System.getenv("AUTOMIX_TRIAL_DIR")?.takeIf { it.isNotBlank() }

    val work: File
        get() = File(System.getenv("AUTOMIX_WORK")?.takeIf { it.isNotBlank() }
            ?: "/home/melb/projects/intertune/experiments/features/automix/work")

    val renders: File
        get() = File(System.getenv("AUTOMIX_RENDERS")?.takeIf { it.isNotBlank() }
            ?: "/home/melb/projects/intertune/experiments/features/automix/renders")

    private val audio = setOf("mp3", "m4a", "aac", "opus", "ogg", "webm", "flac", "wav")

    /** Every song in [folder], by name, decoded if it was not already. */
    fun load(folder: File): List<TrialSong> {
        val songs = folder.listFiles { f -> f.isFile && f.extension.lowercase() in audio }!!.sortedBy { it.name }
        val decodedDir = File(work, "decoded").apply { mkdirs() }
        return songs.map { file ->
            val rate = probeRate(file)
            val out = File(decodedDir, file.nameWithoutExtension + ".s16")
            if (!out.exists() || out.length() == 0L) decode(file, rate, out)
            TrialSong(file, file.nameWithoutExtension, rate, out, probeAlbum(file))
        }
    }

    private fun probeRate(file: File): Int {
        val text = run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate", "-of", "csv=p=0", file.path)
        return text.trim().lines().first().trim().toInt()
    }

    private fun probeAlbum(file: File): String? {
        val text = run("ffprobe", "-v", "error", "-show_entries", "format_tags=album", "-of", "default=noprint_wrappers=1:nokey=1", file.path)
        return text.trim().lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decode(file: File, rate: Int, out: File) {
        val partial = File(out.path + ".part")
        run("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", file.path, "-f", "s16le", "-ac", "2", "-ar", rate.toString(), partial.path)
        check(partial.renameTo(out)) { "could not move ${partial.name} into place" }
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(300, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command[0]} failed: $text" }
        return text
    }
}
