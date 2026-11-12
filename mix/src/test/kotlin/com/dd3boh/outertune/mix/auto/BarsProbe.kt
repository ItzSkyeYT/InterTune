/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.TrialSongs
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Not a test: whether bars confirmed by where the sections change ([SectionBars]) are as right as
 * bars the downbeat finder trusts on its own, on real songs, which have no labels.
 *
 * As ConsistencyProbe does: every song in AUTOMIX_TRIAL_DIR is cut into 60 s windows every 20 s,
 * each analysed on its own, and every two windows of a song that both claim their bars are held
 * against each other: the same tempo, and their downbeats a whole number of bars apart. A song
 * made in a sequencer keeps one grid from its first bar to its last, so disagreement proves one of
 * the two wrong. Writes bars-probe.txt beside the trial's summary.
 */
class BarsProbe {

    private class Window(val start: Double, val bpm: Double, val period: Double, val downbeat: Double, val trusted: Boolean, val confirmed: Boolean, val margin: Double)

    @Test
    fun probe() {
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val out = StringBuilder()
        var windows = 0
        var trusted = 0
        var confirmed = 0
        var leaning = 0
        val pairs = IntArray(3)
        val agree = IntArray(3)
        for (song in TrialSongs.load(File(dir!!))) {
            val rate = song.sampleRate
            val list = ArrayList<Window>()
            var start = 0.0
            while (start + 60.0 <= song.seconds) {
                val from = (start * rate).toInt()
                val samples = song.read(from, from + 60 * rate)
                val region = RegionAnalysis.analyse(samples, rate, 2, RegionKind.TAIL)
                windows++
                val grid = region.grid
                val bars = region.downbeats
                if (grid != null && bars != null && region.beatsConfident) {
                    val spectrum = com.dd3boh.outertune.mix.Spectrum.of(FrontEnd.of(samples, rate, 2))
                    val verdict = SectionBars.check(spectrum, region)
                    val isTrusted = region.barsConfident
                    val isConfirmed = !isTrusted && verdict?.confirmed == true
                    if (isTrusted) trusted++
                    if (isConfirmed) confirmed++
                    if (!isTrusted && bars.margin >= SectionBars.MIN_MARGIN) leaning++
                    val down = start + region.beatFrame(bars.firstAtOrAfter(grid.firstBeat)) / rate
                    val period = region.periodFrames!! / rate
                    list += Window(start, grid.bpm, period * bars.beatsPerBar / 4, down, isTrusted, isConfirmed, bars.margin)
                    out.append("%-44s %5.0f s %7.2f BPM margin %5.2f %s %s\n".format(
                        song.title.take(44), start, grid.bpm, bars.margin,
                        if (isTrusted) "trusted" else if (isConfirmed) "CONFIRMED" else "-",
                        verdict?.describe() ?: "",
                    ))
                }
                start += 20.0
            }
            for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (!(a.trusted || a.confirmed) || !(b.trusted || b.confirmed)) continue
                // 0: both trusted by the finder; 1: one of them only confirmed; 2: both only confirmed.
                val kind = (if (a.confirmed) 1 else 0) + (if (b.confirmed) 1 else 0)
                pairs[kind]++
                if (abs(a.bpm / b.bpm - 1) < 0.003) {
                    val bar = 2 * (a.period + b.period)
                    val n = (b.downbeat - a.downbeat) / bar
                    val tolerance = 0.07 + 0.001 * abs(b.downbeat - a.downbeat)
                    if (abs(n - Math.round(n)) * bar < tolerance) agree[kind]++
                }
            }
        }
        out.append("\nwindows $windows, bars trusted by the finder $trusted, leaning but not trusted $leaning, of those confirmed by sections $confirmed\n")
        val names = listOf("both trusted by the finder", "one confirmed by sections", "both confirmed by sections")
        for (k in 0..2) out.append("%s: pairs %d, agreeing %d (%.1f%%)\n".format(names[k], pairs[k], agree[k], 100.0 * agree[k] / pairs[k].coerceAtLeast(1)))
        val file = File(System.getenv("AUTOMIX_AUTO_OUT")?.takeIf { it.isNotBlank() } ?: "/home/melb/projects/intertune/experiments/features/automix/auto-2026-10-21", "bars-probe.txt")
        file.writeText(out.toString())
        print(out)
    }
}
