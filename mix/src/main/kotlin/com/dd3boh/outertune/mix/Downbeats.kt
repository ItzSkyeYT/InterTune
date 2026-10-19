/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which beat of four starts the bar.
 *
 * Bars are taken to be four beats, which covers nearly everything a mix is made of; a waltz then
 * shows no favourite phase, and the margin says so. Two cues, after Goto (2001) and Davies and
 * Plumbley (2006): the low onsets on each phase, since kicks and bass notes land hardest on the
 * one, and how much the spectrum changes across each phase, since chords change on bar lines. Each
 * is divided by its own mean over all beats, so the two weigh alike whatever the mix's balance.
 *
 * [phase] is a grid index modulo 4: grid beat k is a downbeat when (k - phase) is a multiple of 4.
 * [margin] is how clearly the best phase won, as a paired t over the bars against the runner-up.
 */
class Downbeats(val phase: Int, val margin: Double, val scores: DoubleArray) {

    /** Whether grid beat [k] starts a bar. */
    fun isDownbeat(k: Int): Boolean = Math.floorMod(k - phase, BEATS_PER_BAR) == 0

    /** The first downbeat at or after grid beat [k]. */
    fun firstAtOrAfter(k: Int): Int = k + Math.floorMod(phase - k, BEATS_PER_BAR)

    /** The last downbeat at or before grid beat [k]. */
    fun lastAtOrBefore(k: Int): Int = k - Math.floorMod(k - phase, BEATS_PER_BAR)

    companion object {
        const val BEATS_PER_BAR = 4

        /** The weight of the spectral change against the low onsets. */
        private const val CHANGE_WEIGHT = 0.5

        /** Null when the grid holds fewer than two bars to compare. */
        fun find(spectrum: Spectrum, grid: BeatGrid): Downbeats? {
            val first = grid.firstBeat
            val last = grid.lastBeat
            if (last - first + 1 < 2 * BEATS_PER_BAR) return null
            val count = last - first + 1
            val low = DoubleArray(count)
            val change = DoubleArray(count) { Double.NaN }
            var lowSum = 0.0
            var changeSum = 0.0
            var changeCount = 0
            for (i in 0 until count) {
                val k = first + i
                low[i] = Peaks.nearest(spectrum.lowOnset, grid.frameOf(k), 2)?.value?.toDouble() ?: 0.0
                lowSum += low[i]
                changeAcross(spectrum, grid, k)?.let {
                    change[i] = it
                    changeSum += it
                    changeCount++
                }
            }
            if (changeCount == 0) return null
            val lowMean = lowSum / count
            val changeMean = changeSum / changeCount
            // Each beat's evidence that a bar starts on it, with both cues on the scale of their
            // own mean, so a mix with a quiet kick and loud chords weighs them alike.
            val evidence = DoubleArray(count) { i ->
                if (change[i].isNaN()) Double.NaN else {
                    val l = if (lowMean > 0) low[i] / lowMean else 0.0
                    val c = if (changeMean > 0) change[i] / changeMean else 0.0
                    l + CHANGE_WEIGHT * c
                }
            }
            val sums = DoubleArray(BEATS_PER_BAR)
            val counts = IntArray(BEATS_PER_BAR)
            for (i in 0 until count) {
                if (evidence[i].isNaN()) continue
                val p = Math.floorMod(first + i, BEATS_PER_BAR)
                sums[p] += evidence[i]
                counts[p]++
            }
            if (counts.any { it == 0 }) return null
            val scores = DoubleArray(BEATS_PER_BAR) { sums[it] / counts[it] }
            val order = scores.indices.sortedByDescending { scores[it] }
            val best = order[0]
            val second = order[1]

            // How sure: a paired t over the bars, the best phase against the runner-up bar by bar.
            // A ratio of the two means would be diluted by any cue that is the same on every beat,
            // a kick on every beat above all, and would put a clear bar line in four-on-the-floor
            // music at the edge of chance; the bars agreeing with each other is what says the
            // phase is real, and a waltz read in fours never manages it.
            val differences = ArrayList<Double>()
            var barStart = first - Math.floorMod(first, BEATS_PER_BAR)
            while (barStart <= last) {
                val a = barStart + best
                val b = barStart + second
                if (a in first..last && b in first..last) {
                    val ea = evidence[a - first]
                    val eb = evidence[b - first]
                    if (!ea.isNaN() && !eb.isNaN()) differences += ea - eb
                }
                barStart += BEATS_PER_BAR
            }
            val margin = pairedT(differences)
            return Downbeats(best, margin, scores)
        }

        /** The t statistic of [differences] against zero; a run that never disagrees counts as very sure. */
        private fun pairedT(differences: List<Double>): Double {
            val n = differences.size
            if (n < 2) return 0.0
            val mean = differences.average()
            var squares = 0.0
            for (d in differences) squares += (d - mean) * (d - mean)
            val deviation = sqrt(squares / (n - 1))
            if (deviation < 1e-9) return if (mean > 0) 99.0 else 0.0
            return mean / (deviation / sqrt(n.toDouble()))
        }

        /**
         * How much the spectrum changes across grid beat [k]: the mean log-mel spectrum of the two
         * beats after it against that of the two beats before, band by band, in dB.
         */
        private fun changeAcross(spectrum: Spectrum, grid: BeatGrid, k: Int): Double? {
            val before0 = grid.frameOf(k - 2).toInt()
            val at = grid.frameOf(k).toInt()
            val after1 = grid.frameOf(k + 2).toInt()
            if (before0 < 0 || after1 > spectrum.frames || at - before0 < 2 || after1 - at < 2) return null
            var sum = 0.0
            for (b in 0 until Spectrum.BANDS) {
                var x = 0.0
                for (t in before0 until at) x += spectrum.mel(t, b)
                var y = 0.0
                for (t in at until min(after1, spectrum.frames)) y += spectrum.mel(t, b)
                sum += abs(y / max(1, min(after1, spectrum.frames) - at) - x / (at - before0))
            }
            return sum / Spectrum.BANDS
        }
    }
}
