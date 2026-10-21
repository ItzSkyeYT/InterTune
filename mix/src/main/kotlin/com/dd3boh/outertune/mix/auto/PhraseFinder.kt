/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Spectrum
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a song's phrases turn, found from where its music changes rather than only counted.
 *
 * Music is built in phrases of 4, 8 and 16 bars, and a change that starts or ends between two of
 * them is heard by any DJ. The prototype counted phrases back from the last loud bar, which holds
 * when a song ends on a phrase line and fails on every song whose last loud bar is a pickup into
 * a break, a tag or a fade. So here every bar line is scored by how much changes across it, and
 * each place a 4, 8 and 16 bar grid could sit is scored by the bar lines it falls on: sections
 * change on phrase lines, so the grid that lands on the changes is the song's.
 *
 * What changes at a bar line, each on its own scale: Foote's novelty (2000) on the bars' mean
 * spectra with kernels of 4 and 2 bars, how far the level steps across it, and how far the level
 * under 150 Hz steps, which is the kick and the bass coming in or dropping out, the plainest mark
 * of a new section in the music people mix. The last loud bar closing a phrase is kept as a
 * light lean, so the old rule still decides when nothing in the music does.
 *
 * An opening starts its first phrase on its first bar: for the incoming song the grid is counted
 * from its first bar line, and only the lines after are scored.
 */
object PhraseFinder {

    private const val STRONG_DB = 6.0

    /** The lean towards the grid counted back from the last loud bar, against the mean evidence of a bar line. */
    private const val LAST_STRONG_LEAN = 0.35

    /** How clearly the 4-bar grid's place must win over the next best to count as heard and not counted. */
    const val HEARD_MARGIN = 1.5

    fun find(spectrum: Spectrum, region: RegionAnalysis, line: BeatLine): PhraseMap? {
        val grid = region.grid ?: return null
        val bar = line.beatsPerBar
        val first = line.downbeatAtOrAfter(line.firstK)
        val lines = ArrayList<Int>()
        var k = first
        while (k <= line.lastK + bar) {
            if (grid.frameOf(k) > spectrum.frames - 1) break
            lines += k
            k += bar
        }
        if (lines.size < 5) return null
        val bars = lines.size - 1
        val frameOf = { j: Int -> grid.frameOf(lines[j]) }

        // Each bar's mean spectrum, centred on the mean of all of them.
        val vectors = Array(bars) { j ->
            val from = frameOf(j).toInt().coerceIn(0, spectrum.frames - 1)
            val to = frameOf(j + 1).toInt().coerceIn(from + 1, spectrum.frames)
            DoubleArray(Spectrum.BANDS) { b ->
                var sum = 0.0
                for (t in from until to) sum += spectrum.mel(t, b)
                sum / (to - from)
            }
        }
        val centre = DoubleArray(Spectrum.BANDS) { b -> vectors.sumOf { it[b] } / bars }
        for (v in vectors) for (b in v.indices) v[b] -= centre[b]
        val norms = DoubleArray(bars) { j -> sqrt(vectors[j].sumOf { it * it }).coerceAtLeast(1e-9) }
        fun similarity(i: Int, j: Int): Double {
            var dot = 0.0
            for (b in 0 until Spectrum.BANDS) dot += vectors[i][b] * vectors[j][b]
            return dot / (norms[i] * norms[j])
        }
        fun novelty(kernel: Int): DoubleArray {
            val raw = DoubleArray(lines.size)
            for (l in 0..bars) {
                var sum = 0.0
                var weight = 0.0
                for (a in -kernel until kernel) for (b in -kernel until kernel) {
                    val i = l + a
                    val j = l + b
                    if (i !in 0 until bars || j !in 0 until bars) continue
                    val sign = if ((a < 0) == (b < 0)) 1.0 else -1.0
                    val taper = exp(-((a + 0.5) * (a + 0.5) + (b + 0.5) * (b + 0.5)) / (kernel * kernel / 2.0))
                    sum += sign * taper * similarity(i, j)
                    weight += taper
                }
                raw[l] = if (weight > 0) max(0.0, sum / weight) else 0.0
            }
            val top = raw.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            return DoubleArray(raw.size) { raw[it] / top }
        }
        val wide = novelty(4)
        val narrow = novelty(2)

        val env = region.envelope
        val toSample = { f: Double -> spectrum.frameToSample(f).toLong() }
        val barDb = DoubleArray(bars) { j -> env.meanDb(toSample(frameOf(j)), toSample(frameOf(j + 1))) }
        val lowBands = spectrum.bandCentres.count { it < Spectrum.LOW_ONSET_HZ }.coerceAtLeast(1)
        val barLow = DoubleArray(bars) { j ->
            val from = frameOf(j).toInt().coerceIn(0, spectrum.frames - 1)
            val to = frameOf(j + 1).toInt().coerceIn(from + 1, spectrum.frames)
            var p = 0.0
            for (t in from until to) for (b in 0 until lowBands) p += Math.pow(10.0, spectrum.mel(t, b) / 10.0)
            10.0 * kotlin.math.log10(max(p / ((to - from) * lowBands), 1e-12))
        }
        fun step(values: DoubleArray, l: Int): Double {
            val before = (max(0, l - 2) until l).map { values[it] }
            val after = (l until min(values.size, l + 2)).map { values[it] }
            if (before.isEmpty() || after.isEmpty()) return 0.0
            return (abs(after.average() - before.average()) / STRONG_DB).coerceAtMost(1.0)
        }
        val evidence = DoubleArray(lines.size) { l -> wide[l] + 0.5 * narrow[l] + step(barDb, l) + step(barLow, l) }

        // The last loud bar on the trusted grid, whose closing line the old rule took for a phrase end.
        var lastStrong = -1
        for (j in bars - 1 downTo 0) {
            if (lines[j] + bar - 1 <= line.lastK && barDb[j] >= env.medianDb - STRONG_DB) {
                lastStrong = j + 1
                break
            }
        }

        // The first and last bar lines have nothing on one side to change from, so they do not vote.
        val usable = (1 until bars).toList()
        val opening = region.kind == RegionKind.HEAD
        val meanEvidence = usable.map { evidence[it] }.average().takeIf { it > 0 } ?: 1.0

        fun scoreOf(offset: Int, period: Int): Pair<Double, List<Double>> {
            val values = usable.filter { Math.floorMod(it - offset, period) == 0 }.map { evidence[it] }
            if (values.isEmpty()) return Double.NEGATIVE_INFINITY to values
            var s = values.average()
            if (!opening && lastStrong >= 0 && Math.floorMod(lastStrong - offset, period) == 0) s += LAST_STRONG_LEAN * meanEvidence
            return s to values
        }

        val offsets = IntArray(3)
        var margin = 0.0
        if (opening) {
            // The song's first bar opens its first phrase.
            offsets.fill(0)
        } else {
            var choices = (0 until 4).toList()
            for ((i, period) in intArrayOf(4, 8, 16).withIndex()) {
                val scored = choices.map { it to scoreOf(it, period) }.filter { it.second.first.isFinite() }
                if (scored.isEmpty()) {
                    offsets[i] = if (i == 0) Math.floorMod(lastStrong, 4) else offsets[i - 1]
                } else {
                    val ranked = scored.sortedByDescending { it.second.first }
                    offsets[i] = ranked[0].first
                    if (period == 4 && ranked.size > 1) margin = welch(ranked[0].second.second, ranked[1].second.second)
                }
                val chosen = offsets[i]
                choices = listOf(chosen, chosen + period)
            }
        }

        val level = IntArray(lines.size) { l ->
            when {
                Math.floorMod(l - offsets[2], 16) == 0 -> 16
                Math.floorMod(l - offsets[1], 8) == 0 -> 8
                Math.floorMod(l - offsets[0], 4) == 0 -> 4
                Math.floorMod(l - offsets[0], 2) == 0 -> 2
                else -> 1
            }
        }
        val top = evidence.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val heard = opening || margin >= HEARD_MARGIN
        return PhraseMap(lines.toIntArray(), level, DoubleArray(lines.size) { evidence[it] / top * 2.0 }, heard, if (opening) 0.0 else margin)
    }

    /** Welch's t of two samples, the first against the second; 0 where either is too small to say. */
    private fun welch(a: List<Double>, b: List<Double>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val ma = a.average()
        val mb = b.average()
        val va = a.sumOf { (it - ma) * (it - ma) } / (a.size - 1)
        val vb = b.sumOf { (it - mb) * (it - mb) } / (b.size - 1)
        val se = sqrt(va / a.size + vb / b.size)
        if (se < 1e-9) return if (ma > mb) 9.0 else 0.0
        return (ma - mb) / se
    }
}
