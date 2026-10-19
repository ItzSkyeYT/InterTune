/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: the rhythm of every song in AUTOMIX_TRIAL_DIR, head and tail and the middle, to read
 * while tuning. Writes rhythm.txt into the work folder.
 */
class RhythmProbe {

    @Test
    fun probe() {
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val out = StringBuilder()
        for (song in TrialSongs.load(File(dir!!))) {
            val rate = song.sampleRate
            out.append("${song.title} (%.0f s)\n".format(song.seconds))
            fun line(label: String, from: Int, to: Int) {
                val samples = song.read(from, to)
                val spectrum = Spectrum.of(FrontEnd.of(samples, rate, 2))
                val r = Rhythm.analyse(spectrum)
                val g = r.gates
                out.append("  %-6s bpm %7.3f (raw %6.1f) z %5.1f rival %.2f spread %.4f hits %.2f jitter %5.1f beats %3d margin %.2f %s%s%s %s\n".format(
                    label, r.bpm ?: 0.0, r.tempo?.rawBpm ?: 0.0, g.tempoZ, g.rivalRatio, g.steadiness, g.hitShare, g.jitterMs, g.activeBeats,
                    g.downbeatMargin, if (g.tempo) "T" else "-", if (g.beats) "B" else "-", if (g.downbeats) "D" else "-",
                    g.failures().joinToString("; "),
                ))
            }
            line("head", 0, 30 * rate)
            line("middle", song.frames / 2 - 20 * rate, song.frames / 2 + 20 * rate)
            line("tail", song.frames - 40 * rate, song.frames)
        }
        File(TrialSongs.work, "rhythm.txt").writeText(out.toString())
        print(out)
    }
}
