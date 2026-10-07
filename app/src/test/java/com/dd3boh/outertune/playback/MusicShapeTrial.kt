/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Not a test: a list to read. For every song in the folder LIVING_AUDIO names, where
 * [LevelAnalyser] finds its build-ups, its drops and what the drops drive, by the song's own time,
 * to hold against the song while listening to it. It is what the numbers under
 * [MusicLevels.BASS_THERE] are turned by.
 *
 * Songs go in as raw samples, one channel of 16 bit at 48 kHz, named .pcm:
 *
 *     ffmpeg -i song.mp3 -ac 1 -ar 48000 -f s16le song.pcm
 *
 * Writes _shape.txt into that folder, and beside every song what each frame measured, as
 * .frames.csv: the three ranges against full scale, then tension, drop and drive.
 */
class MusicShapeTrial {

    private val rate = 48_000

    @Test
    fun shapes() {
        val folder = System.getenv("LIVING_AUDIO"); assumeTrue("set LIVING_AUDIO to run", !folder.isNullOrBlank())
        val songs = File(folder!!).listFiles { f -> f.extension == "pcm" }!!.sortedBy { it.name }
        val out = StringBuilder()
        for (song in songs) {
            val frames = mutableListOf<FloatArray>()
            val measured = StringBuilder("bass,mid,high,tension,drop,drive\n")
            val bytes = ByteBuffer.wrap(song.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            lateinit var analyser: LevelAnalyser
            analyser = LevelAnalyser(rate) { _, levels ->
                frames += levels.copyOf()
                measured.append("${analyser.raw.joinToString(",")},${levels[MusicLevels.TENSION]},${levels[MusicLevels.DROP]},${levels[MusicLevels.DRIVE]}\n")
            }
            analyser.pcm16(bytes, channels = 1)
            File(folder, "${song.nameWithoutExtension}.frames.csv").writeText(measured.toString())
            out.append("${song.nameWithoutExtension} (${clock(frames.size)})\n")
            var tenseFrom = -1
            var tensest = 0f
            var drivenFrom = -1
            for (i in frames.indices) {
                val tension = frames[i][MusicLevels.TENSION]
                val drop = frames[i][MusicLevels.DROP]
                val drive = frames[i][MusicLevels.DRIVE]
                if (tension > 0f && tenseFrom < 0) {
                    tenseFrom = i
                    tensest = 0f
                }
                if (tension > tensest) tensest = tension
                if (tenseFrom >= 0 && (tension == 0f || i == frames.lastIndex)) {
                    // a build-up of a breath is the bass missing a bar, not worth a line
                    if (tensest >= 0.1f) out.append("    ${clock(tenseFrom)} to ${clock(i)}  tension, at most ${"%.2f".format(tensest)}\n")
                    tenseFrom = -1
                }
                if (drop > 0f && (i == 0 || frames[i - 1][MusicLevels.DROP] < drop)) out.append("    ${clock(i)}  DROP ${"%.2f".format(drop)}\n")
                if (drive > 0f && drivenFrom < 0) drivenFrom = i
                if (drivenFrom >= 0 && (drive == 0f || i == frames.lastIndex)) {
                    out.append("    ${clock(drivenFrom)} to ${clock(i)}  driven\n")
                    drivenFrom = -1
                }
            }
            out.append("\n")
        }
        File(folder, "_shape.txt").writeText(out.toString())
        print(out)
    }

    /** Where frame [frame] is in the song, as minutes, seconds and tenths. */
    private fun clock(frame: Int): String {
        val tenths = Math.round(frame * MusicLevels.FRAME_SECONDS * 10).toInt()
        return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
    }
}
