/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Not a test: how far the analysis agrees with itself on real songs, which have no labels.
 *
 * A song made in a sequencer keeps one tempo and one bar grid from its first bar to its last. So
 * every song in AUTOMIX_TRIAL_DIR is cut into 30 s windows every 10 s, each analysed on its own as
 * if it were the only part of the song there was, and every two windows that both claim their
 * beats (or their bars) are held against each other: the same tempo, the beat lines landing on
 * each other where the windows meet, the downbeats a whole number of bars apart. Agreement does
 * not prove a phase right, since two windows can share a mistake, but disagreement proves one of
 * them wrong. Writes consistency.txt into the work folder.
 */
class ConsistencyProbe {

    private class Window(val start: Double, val bpm: Double, val beat0: Double, val period: Double, val beats: Boolean, val bars: Boolean, val downbeat: Double, val margin: Double)

    @Test
    fun probe() {
        val dir = TrialSongs.dir
        assumeTrue("set AUTOMIX_TRIAL_DIR to run", dir != null)
        val out = StringBuilder()
        var beatPairs = 0
        var beatAgree = 0
        var barPairs = 0
        var barAgree = 0
        var windowsAll = 0
        var windowsBeats = 0
        var windowsBars = 0
        val sweep = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0)
        val sweepPairs = IntArray(sweep.size)
        val sweepAgree = IntArray(sweep.size)
        val sweepWindows = IntArray(sweep.size)
        val allWindows = ArrayList<Window>()
        for (song in TrialSongs.load(File(dir!!))) {
            val rate = song.sampleRate
            val windows = ArrayList<Window>()
            var start = 0.0
            while (start + 30.0 <= song.seconds) {
                val from = (start * rate).toInt()
                val spectrum = Spectrum.of(FrontEnd.of(song.read(from, from + 30 * rate), rate, 2))
                val r = Rhythm.analyse(spectrum)
                val grid = r.grid
                windowsAll++
                if (grid != null) {
                    val beat0 = start + spectrum.frameToSample(grid.t0) / rate
                    val period = spectrum.frameToSample(grid.t0 + grid.period) / rate - spectrum.frameToSample(grid.t0) / rate
                    val down = r.downbeats?.let { start + spectrum.frameToSample(grid.frameOf(it.firstAtOrAfter(grid.firstBeat))) / rate } ?: Double.NaN
                    windows += Window(start, grid.bpm, beat0, period, r.gates.beats, r.gates.downbeats, down, r.gates.downbeatMargin)
                    allWindows += windows.last()
                    if (r.gates.beats) windowsBeats++
                    if (r.gates.downbeats) windowsBars++
                }
                start += 10.0
            }
            var songBeatPairs = 0
            var songBeatAgree = 0
            var songBarPairs = 0
            var songBarAgree = 0
            for (i in windows.indices) for (j in i + 1 until windows.size) {
                val a = windows[i]
                val b = windows[j]
                if (!a.beats || !b.beats) continue
                // Beat lines are compared only where the two windows overlap, in the middle of the
                // overlap, so a tempo good to 0.05% is not punished for being extrapolated 200 s.
                // Octaves count as the same tempo, with the finer grid holding the coarser one.
                if (b.start - a.start < 30.0) {
                    songBeatPairs++
                    val ratio = a.bpm / b.bpm
                    val octave = listOf(1.0, 2.0, 0.5).minBy { abs(ratio * it - 1) }
                    val sameTempo = abs(ratio * octave - 1) < 0.003
                    val fine = if (a.period <= b.period) a else b
                    val coarse = if (a.period <= b.period) b else a
                    val meet = (b.start + a.start + 30.0) / 2
                    val beat = coarse.beat0 + Math.round((meet - coarse.beat0) / coarse.period) * coarse.period
                    val phase = (beat - fine.beat0) / fine.period
                    val offset = (phase - Math.round(phase)) * fine.period
                    if (sameTempo && abs(offset) < 0.025) songBeatAgree++
                }
                for ((t, threshold) in sweep.withIndex()) {
                    if (a.margin >= threshold && b.margin >= threshold) {
                        sweepPairs[t]++
                        val bar = 2 * (a.period + b.period)
                        val bars = (b.downbeat - a.downbeat) / bar
                        val tolerance = 0.07 + 0.001 * abs(b.downbeat - a.downbeat)
                        if (abs(a.bpm / b.bpm - 1) < 0.003 && abs(bars - Math.round(bars)) * bar < tolerance) sweepAgree[t]++
                    }
                }
                if (a.bars && b.bars) {
                    songBarPairs++
                    val ratio = a.bpm / b.bpm
                    if (abs(ratio - 1) < 0.003) {
                        val bar = 2 * (a.period + b.period)
                        val bars = (b.downbeat - a.downbeat) / bar
                        val tolerance = 0.07 + 0.001 * abs(b.downbeat - a.downbeat)
                        if (abs(bars - Math.round(bars)) * bar < tolerance) songBarAgree++
                    }
                }
            }
            beatPairs += songBeatPairs
            beatAgree += songBeatAgree
            barPairs += songBarPairs
            barAgree += songBarAgree
            out.append("%-60s windows %2d beats %2d bars %2d | beat pairs %3d agree %3d | bar pairs %3d agree %3d | tempos %s\n".format(
                song.title.take(60), windows.size, windows.count { it.beats }, windows.count { it.bars },
                songBeatPairs, songBeatAgree, songBarPairs, songBarAgree,
                windows.filter { it.beats }.map { "%.2f".format(it.bpm) }.distinct().take(6).joinToString(" "),
            ))
        }
        out.append("\nwindows $windowsAll, beats confident $windowsBeats, bars confident $windowsBars\n")
        out.append("beat pairs $beatPairs, agreeing $beatAgree (%.1f%%)\n".format(100.0 * beatAgree / beatPairs.coerceAtLeast(1)))
        out.append("bar pairs $barPairs, agreeing $barAgree (%.1f%%)\n".format(100.0 * barAgree / barPairs.coerceAtLeast(1)))
        for ((t, threshold) in sweep.withIndex()) {
            sweepWindows[t] = allWindows.count { it.beats && it.margin >= threshold }
            out.append("margin >= %.1f: windows %3d, pairs %4d, agreeing %4d (%.1f%%)\n".format(threshold, sweepWindows[t], sweepPairs[t], sweepAgree[t], 100.0 * sweepAgree[t] / sweepPairs[t].coerceAtLeast(1)))
        }
        File(TrialSongs.work, "consistency.txt").writeText(out.toString())
        print(out)
    }
}
