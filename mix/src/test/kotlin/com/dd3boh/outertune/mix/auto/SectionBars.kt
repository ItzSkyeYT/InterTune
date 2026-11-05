/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.Spectrum
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tried and not used: a second, independent witness for where the bar starts, where the song's
 * sections change. Kept beside BarsProbe, which measured it on 21 Oct: of 42 windows whose
 * downbeats were leaning but not trusted, it confirmed 6 (all of them agreeing with the rest of
 * their song), and in many windows the downbeat finder trusts, the strongest changes did not fall
 * on its one, so it cannot yet tell a right phase from a wrong one. The text below says what it
 * was meant to do.
 *
 * A second, independent witness for where the bar starts: where the song's sections change.
 *
 * The downbeat finder (Downbeats.kt) reads the one from what happens on each beat, kicks, snares
 * and chord changes, and is only trusted when it wins clearly over the bars; in trap, phonk and
 * slowed edits the 808 lands anywhere and the snare sits on 3, so it often wins only narrowly.
 * But a drop, a break, the drums coming in or going out, a new section of any kind starts on a
 * bar line, and on the one of it, in nearly everything people dance or drive to. So the beats
 * where the music changes most are found, each by how much the spectrum, the level and the level
 * under 150 Hz change across it, eight beats a side; and where the strongest of them agree with
 * the downbeat finder's own phase, the bars are trusted though its margin was narrow.
 *
 * Only ever an upgrade: a phase the sections disagree with stays untrusted, and a region with too
 * few clear changes says nothing.
 */
object SectionBars {

    /** The downbeat finder must still lean this way at least: a t of the right sign, if small. */
    const val MIN_MARGIN = 0.5

    /** Beats either side a change is judged over. */
    private const val SIDE = 8

    /** How many deviations above the median change a beat must stand to count as a section change. */
    private const val STRONG = 3.0

    /** Of the strong changes' weight, the share that must fall on the downbeat finder's phase. */
    const val AGREE = 0.75

    class Verdict(val confirmed: Boolean, val changes: Int, val agreeing: Int, val share: Double) {
        fun describe(): String = "%d of %d section changes on the one (%.0f%%)".format(agreeing, changes, share * 100)
    }

    fun check(spectrum: Spectrum, region: RegionAnalysis): Verdict? {
        val grid = region.grid ?: return null
        val bars = region.downbeats ?: return null
        if (!region.beatsConfident || bars.margin < MIN_MARGIN) return null
        val first = grid.firstBeat
        val last = grid.lastBeat
        val count = last - first + 1
        if (count < 4 * SIDE) return null
        // Each beat's mean spectrum, its level and its level under 150 Hz.
        val lowBands = spectrum.bandCentres.count { it < Spectrum.LOW_ONSET_HZ }.coerceAtLeast(1)
        val vectors = Array(count) { DoubleArray(Spectrum.BANDS) }
        val level = DoubleArray(count)
        val low = DoubleArray(count)
        for (i in 0 until count) {
            val from = grid.frameOf(first + i).toInt().coerceIn(0, spectrum.frames - 1)
            val to = grid.frameOf(first + i + 1).toInt().coerceIn(from + 1, spectrum.frames)
            var all = 0.0
            var lows = 0.0
            for (t in from until to) {
                for (b in 0 until Spectrum.BANDS) {
                    val db = spectrum.mel(t, b).toDouble()
                    vectors[i][b] += db / (to - from)
                    val p = Math.pow(10.0, db / 10)
                    all += p
                    if (b < lowBands) lows += p
                }
            }
            level[i] = 10 * kotlin.math.log10(max(all / ((to - from) * Spectrum.BANDS), 1e-12))
            low[i] = 10 * kotlin.math.log10(max(lows / ((to - from) * lowBands), 1e-12))
        }
        val centre = DoubleArray(Spectrum.BANDS) { b -> vectors.sumOf { it[b] } / count }
        for (v in vectors) for (b in v.indices) v[b] -= centre[b]
        val norms = DoubleArray(count) { sqrt(vectors[it].sumOf { x -> x * x }).coerceAtLeast(1e-9) }
        fun similarity(i: Int, j: Int): Double {
            var dot = 0.0
            for (b in 0 until Spectrum.BANDS) dot += vectors[i][b] * vectors[j][b]
            return dot / (norms[i] * norms[j])
        }
        fun mean(values: DoubleArray, from: Int, to: Int): Double {
            var s = 0.0
            for (i in from until to) s += values[i]
            return s / (to - from)
        }
        // How much changes across the line before each beat, eight beats a side.
        val change = DoubleArray(count)
        for (i in SIDE until count - SIDE + 1) {
            var sum = 0.0
            var weight = 0.0
            for (a in -SIDE until SIDE) for (b in -SIDE until SIDE) {
                val p = i + a
                val q = i + b
                if (p !in 0 until count || q !in 0 until count) continue
                val sign = if ((a < 0) == (b < 0)) 1.0 else -1.0
                val taper = exp(-((a + 0.5) * (a + 0.5) + (b + 0.5) * (b + 0.5)) / (SIDE * SIDE / 2.0))
                sum += sign * taper * similarity(p, q)
                weight += taper
            }
            val novelty = max(0.0, sum / weight)
            val levelStep = abs(mean(level, i, i + SIDE / 2) - mean(level, i - SIDE / 2, i)) / 6.0
            val lowStep = abs(mean(low, i, i + SIDE / 2) - mean(low, i - SIDE / 2, i)) / 6.0
            change[i] = novelty + min(1.0, levelStep) + min(1.0, lowStep)
        }
        val judged = (SIDE until count - SIDE + 1).map { change[it] }.sorted()
        if (judged.size < 8) return null
        val median = judged[judged.size / 2]
        val deviation = judged.map { abs(it - median) }.sorted()[judged.size / 2].coerceAtLeast(1e-6)
        val threshold = median + STRONG * deviation
        val weights = DoubleArray(bars.beatsPerBar)
        var changes = 0
        val agreeing = IntArray(bars.beatsPerBar)
        for (i in SIDE until count - SIDE + 1) {
            val v = change[i]
            if (v < threshold) continue
            // A change is the strongest beat within two of it.
            if ((max(SIDE, i - 2)..min(count - SIDE, i + 2)).any { it != i && change[it] > v }) continue
            val phase = Math.floorMod(first + i, bars.beatsPerBar)
            weights[phase] += v - median
            agreeing[phase]++
            changes++
        }
        if (changes < 2) return Verdict(false, changes, 0, 0.0)
        val total = weights.sum()
        val own = Math.floorMod(bars.phase, bars.beatsPerBar)
        val share = if (total > 0) weights[own] / total else 0.0
        return Verdict(share >= AGREE && agreeing[own] >= 2, changes, agreeing[own], share)
    }
}
