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
 * Which beat of the bar is the one, and how long the bar is in beats of the grid.
 *
 * Bars are four beats, which covers nearly everything a mix is made of; a waltz then shows no
 * favourite phase, and the margin says so. Two cues, after Goto (2001) and Davies and Plumbley
 * (2006): the low onsets on each phase, since kicks and bass notes land hardest on the one, and
 * how much the spectrum changes across each phase, since chords change on bar lines; and a snare
 * two beats later, where the half-time feel of trap and phonk puts it. Each is divided by its own
 * mean over all beats, so they weigh alike whatever the mix's balance.
 *
 * But a grid can run at twice the song's beat: a song at 76 gets a grid at 152, folded into the
 * range tempos are compared in, or picked there by the tempo stage, which cannot tell the octave.
 * Then four beats of the grid are half a bar, and the song's beat 1 and beat 3 fall in one phase of
 * four. So on a grid fast enough to be twice a song's beat, 120 BPM and up (no song is analysed
 * below 60), the winning phase is held against the phase four beats on, over bars of eight: where
 * the two differ clearly, the bar is eight beats of the grid and the stronger is the one. Where they
 * do not, they are alike in every cue there is, kicks, snares and chord changes, and either one
 * lined up with another song's one lines up like with like.
 *
 * [phase] is a grid index modulo [beatsPerBar]: grid beat k is a downbeat when (k - phase) is a
 * multiple of it. [margin] is how clearly the best phase of four won, as a paired t over the bars
 * against the runner-up; [halfMargin] the same of that phase against the one four beats on, over
 * bars of eight, positive where the phase itself is the stronger.
 */
class Downbeats(
    val phase: Int,
    val margin: Double,
    val scores: DoubleArray,
    val beatsPerBar: Int = BEATS_PER_BAR,
    val halfMargin: Double = 0.0,
) {

    /** Whether grid beat [k] starts a bar. */
    fun isDownbeat(k: Int): Boolean = Math.floorMod(k - phase, beatsPerBar) == 0

    /** The first downbeat at or after grid beat [k]. */
    fun firstAtOrAfter(k: Int): Int = k + Math.floorMod(phase - k, beatsPerBar)

    /** The last downbeat at or before grid beat [k]. */
    fun lastAtOrBefore(k: Int): Int = k - Math.floorMod(k - phase, beatsPerBar)

    companion object {
        const val BEATS_PER_BAR = 4

        /** Bars of eight beats of the grid: a grid at twice the song's beat. */
        const val LONG_BAR = 8

        /**
         * How clearly the two halves of a bar of eight must differ for the bar to be eight beats.
         * Measured on the synthetic songs at 64 to 174 BPM: every bar of eight claimed this way had
         * all its downbeats right, at the song's own tempo or at twice it; the halves of a real bar
         * of four often differ as much, a chord cycle of two bars or a phrase that turns every
         * eight, and claiming eight there only keeps every other bar line, all of them real.
         */
        const val MIN_HALF_MARGIN = 1.5

        /** Fewer bars of eight than this, and the halves are not compared. */
        private const val MIN_LONG_BARS = 4

        /** A grid under this cannot be at twice a song's beat, since no song is analysed under 60 BPM. */
        const val DOUBLED_FROM_BPM = 2 * Tempo.MIN_BPM

        /**
         * The weights of the spectral change across a beat, and of a snare two beats after it
         * (the half-time feel's mark of the one), against the low onsets on it. Chosen on the
         * owner's songs by how often two windows of one song, each analysed on its own, agree on
         * where the bars are: with these, 157 windows of 490 claim their bars and 99.7% of pairs
         * of them agree, where low onsets with half the change gave 146 at 99.2%; a snare at full
         * weight fell to 91%, so it only ever leans.
         */
        private const val CHANGE_WEIGHT = 1.0
        private const val SNARE_WEIGHT = 0.5

        /** Null when the grid holds fewer than two bars to compare. */
        fun find(spectrum: Spectrum, grid: BeatGrid): Downbeats? {
            val first = grid.firstBeat
            val last = grid.lastBeat
            if (last - first + 1 < 2 * BEATS_PER_BAR) return null
            val count = last - first + 1
            val low = DoubleArray(count)
            val mid = DoubleArray(count + 2)
            val change = DoubleArray(count) { Double.NaN }
            var lowSum = 0.0
            var midSum = 0.0
            var changeSum = 0.0
            var changeCount = 0
            for (i in 0 until count) {
                val k = first + i
                low[i] = Peaks.nearest(spectrum.lowOnset, grid.frameOf(k), 2)?.value?.toDouble() ?: 0.0
                lowSum += low[i]
                mid[i] = Peaks.nearest(spectrum.midOnset, grid.frameOf(k), 2)?.value?.toDouble() ?: 0.0
                midSum += mid[i]
                changeAcross(spectrum, grid, k)?.let {
                    change[i] = it
                    changeSum += it
                    changeCount++
                }
            }
            if (changeCount == 0) return null
            for (j in 0..1) mid[count + j] = Peaks.nearest(spectrum.midOnset, grid.frameOf(last + 1 + j), 2)?.value?.toDouble() ?: 0.0
            val lowMean = lowSum / count
            val midMean = midSum / count
            val changeMean = changeSum / changeCount
            // Each beat's evidence that a bar starts on it, with both cues on the scale of their
            // own mean, so a mix with a quiet kick and loud chords weighs them alike.
            val evidence = DoubleArray(count) { i ->
                if (change[i].isNaN()) Double.NaN else {
                    val l = if (lowMean > 0) low[i] / lowMean else 0.0
                    val c = if (changeMean > 0) change[i] / changeMean else 0.0
                    val snare = if (midMean > 0) (mid[i + 2] - mid[i]) / midMean else 0.0
                    l + CHANGE_WEIGHT * c + SNARE_WEIGHT * snare
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

            // The halves of a bar of eight: the best phase against the one four beats on, bar by
            // bar, on bars of eight counted from grid index 0 as the phases are.
            val halves = ArrayList<Double>()
            var longStart = first - Math.floorMod(first, LONG_BAR)
            while (longStart <= last) {
                val a = longStart + best
                val b = a + BEATS_PER_BAR
                if (a in first..last && b in first..last) {
                    val ea = evidence[a - first]
                    val eb = evidence[b - first]
                    if (!ea.isNaN() && !eb.isNaN()) halves += ea - eb
                }
                longStart += LONG_BAR
            }
            val half = if (halves.size >= MIN_LONG_BARS) pairedT(halves) else 0.0
            if (grid.bpm < DOUBLED_FROM_BPM) return Downbeats(best, margin, scores, BEATS_PER_BAR, half)
            return when {
                half >= MIN_HALF_MARGIN -> Downbeats(best, margin, scores, LONG_BAR, half)
                half <= -MIN_HALF_MARGIN -> Downbeats(best + BEATS_PER_BAR, margin, scores, LONG_BAR, half)
                else -> Downbeats(best, margin, scores, BEATS_PER_BAR, half)
            }
        }

        /** The t statistic of [differences] against zero; a run that never disagrees counts as very sure, either way. */
        private fun pairedT(differences: List<Double>): Double {
            val n = differences.size
            if (n < 2) return 0.0
            val mean = differences.average()
            var squares = 0.0
            for (d in differences) squares += (d - mean) * (d - mean)
            val deviation = sqrt(squares / (n - 1))
            if (deviation < 1e-9) return if (mean > 0) 99.0 else if (mean < 0) -99.0 else 0.0
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
