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
 * favourite phase, and the margin says so. The cues, after Goto (2001) and Davies and Plumbley
 * (2006): the kick on each beat, and how much the chords, the bass notes and the timbre change
 * across it, over two and four beats ([evidence]). Each is put on the scale of its own spread, so
 * they weigh alike whatever the mix's balance.
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
    /** The one is an opening's first sound, which lands on a beat: a song starts on its bar 1 ([RegionAnalysis.analyse]). */
    val fromFirstSound: Boolean = false,
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

        /** Null when the grid holds fewer than two bars to compare. */
        fun find(spectrum: Spectrum, grid: BeatGrid): Downbeats? {
            val first = grid.firstBeat
            val last = grid.lastBeat
            if (last - first + 1 < 2 * BEATS_PER_BAR) return null
            val count = last - first + 1
            val evidence = evidence(spectrum, grid, first, last) ?: return null
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
            // A phase that only wins on average, one bar here and another there, is no bar line: a
            // waltz read in fours spreads its chord changes over every phase in turn. The best phase
            // must be the best of its own bar in [MIN_AGREEMENT] of the bars, or the margin is none.
            var agreeing = 0
            var bars = 0
            barStart = first - Math.floorMod(first, BEATS_PER_BAR)
            while (barStart <= last) {
                if (barStart >= first && barStart + BEATS_PER_BAR - 1 <= last) {
                    var top = -1
                    for (q in 0 until BEATS_PER_BAR) {
                        val e = evidence[barStart + q - first]
                        if (e.isNaN()) { top = -2; break }
                        if (top < 0 || e > evidence[barStart + top - first]) top = q
                    }
                    if (top >= 0) {
                        bars++
                        if (top == best) agreeing++
                    }
                }
                barStart += BEATS_PER_BAR
            }
            val margin = if (bars > 0 && agreeing < MIN_AGREEMENT * bars) min(pairedT(differences), 0.0) else pairedT(differences)

            // The halves of a bar of eight: the best phase against the one four beats on, bar by
            // bar, on bars of eight counted from grid index 0 as the phases are. On the kick, the
            // timbre and the snare, as before the pitch classes came in: a chord cycle of two bars
            // makes the halves differ in pitch everywhere, at any tempo, and a bar of eight is
            // only claimed where the drums and the sound say so.
            val halfEvidence = legacyEvidence(spectrum, grid, first, last) ?: evidence
            val halves = ArrayList<Double>()
            var longStart = first - Math.floorMod(first, LONG_BAR)
            while (longStart <= last) {
                val a = longStart + best
                val b = a + BEATS_PER_BAR
                if (a in first..last && b in first..last) {
                    val ea = halfEvidence[a - first]
                    val eb = halfEvidence[b - first]
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
         * Each beat's evidence that a bar starts on it, for grid beats [first] to [last]: five cues,
         * each put on the scale of its own spread over the stretch and added up. The kick on the
         * beat; how far the pitch classes of the two beats after it stand from those of the two
         * before, and of the four after from the four before, since chords change on bar lines; the
         * same of the bass notes over four beats; and the timbre over four beats. Held against the
         * Harmonix annotations on 39 windows of 23 songs (mix-better, 2 Nov 2026), these found the
         * one in 27 where the kick, the timbre over two beats and a snare two beats on had found it
         * in 14: those cannot tell the one from the three when the kick lands on both, and they had
         * been weighed by how often two windows of a song agreed, which a wrong phase does as well.
         */
        internal fun evidence(spectrum: Spectrum, grid: BeatGrid, first: Int, last: Int): DoubleArray? {
            val count = last - first + 1
            val harmony = spectrum.harmony
            // Per beat, from the wide span before the stretch to the wide span after, where the region has them.
            val lo = first - WIDE
            val n = count + 2 * WIDE
            fun startOf(k: Int): Double = grid.frameOf(k) * Spectrum.HOP + Spectrum.ONSET_LAG
            val chroma = Array(n) { DoubleArray(12) }
            val bass = Array(n) { DoubleArray(12) }
            val mel = Array(n) { DoubleArray(Spectrum.BANDS) }
            val have = BooleanArray(n)
            val haveMel = BooleanArray(n)
            for (j in 0 until n) {
                val k = lo + j
                val a = startOf(k)
                val b = startOf(k + 1)
                if (a < 0) continue
                // Harmony frames whose centre falls inside the beat, the beat whole inside the copy.
                if (harmony.frames > 0 && b <= harmony.centreOf(harmony.frames - 1) + Harmony.HOP) {
                    var t = kotlin.math.ceil((a - Harmony.WINDOW / 2.0) / Harmony.HOP).toInt().coerceAtLeast(0)
                    var used = 0
                    while (t < harmony.frames && harmony.centreOf(t) < b) {
                        for (c in 0 until 12) {
                            chroma[j][c] += harmony.chroma[t * 12 + c]
                            bass[j][c] += harmony.bass[t * 12 + c]
                        }
                        used++
                        t++
                    }
                    if (used > 0) {
                        for (c in 0 until 12) {
                            chroma[j][c] /= used
                            bass[j][c] /= used
                        }
                        have[j] = true
                    }
                }
                // Spectral frames whose centre falls inside the beat.
                if (spectrum.frames > 0 && b <= (spectrum.frames - 1).toDouble() * Spectrum.HOP + Spectrum.WINDOW / 2.0 + Spectrum.HOP) {
                    var s = kotlin.math.ceil((a - Spectrum.WINDOW / 2.0) / Spectrum.HOP).toInt().coerceAtLeast(0)
                    var m = 0
                    while (s < spectrum.frames && s.toDouble() * Spectrum.HOP + Spectrum.WINDOW / 2.0 < b) {
                        for (band in 0 until Spectrum.BANDS) mel[j][band] += spectrum.mel(s, band).toDouble()
                        m++
                        s++
                    }
                    if (m > 0) {
                        for (band in 0 until Spectrum.BANDS) mel[j][band] /= m
                        haveMel[j] = true
                    }
                }
            }
            val cues = Array(5) { DoubleArray(count) { Double.NaN } }
            for (i in 0 until count) {
                val k = first + i
                val j = k - lo
                cues[0][i] = Peaks.nearest(spectrum.lowOnset, grid.frameOf(k), 2)?.value?.toDouble() ?: 0.0
                cues[1][i] = cosine(meanOf(chroma, have, j - 2, j), meanOf(chroma, have, j, j + 2))
                cues[2][i] = cosine(meanOf(chroma, have, j - WIDE, j), meanOf(chroma, have, j, j + WIDE))
                cues[3][i] = cosine(meanOf(bass, have, j - WIDE, j), meanOf(bass, have, j, j + WIDE))
                cues[4][i] = meanAbs(meanOf(mel, haveMel, j - WIDE, j), meanOf(mel, haveMel, j, j + WIDE))
            }
            val evidence = DoubleArray(count)
            var any = false
            for (cue in cues) {
                var sum = 0.0
                var squares = 0.0
                var m = 0
                for (v in cue) if (!v.isNaN()) {
                    sum += v
                    squares += v * v
                    m++
                }
                if (m < 2) continue
                val mean = sum / m
                val deviation = sqrt(max(0.0, squares / m - mean * mean))
                if (deviation <= 1e-12) continue
                any = true
                for (i in 0 until count) if (!cue[i].isNaN()) evidence[i] += (cue[i] - mean) / deviation
            }
            if (!any) return null
            // A beat too near the region's edge for the pitch-class windows knows less than its neighbours.
            for (i in 0 until count) if (cues[1][i].isNaN() && cues[2][i].isNaN()) evidence[i] = Double.NaN
            return evidence
        }

        /** The span of the wide cues, in beats either side of the beat. */
        private const val WIDE = 4

        /** The share of bars whose own best phase is the phase found, under which the bars are not claimed. */
        const val MIN_AGREEMENT = 0.4

        /**
         * The evidence the bars were found on before the pitch classes came in (mix-simple): the low
         * onsets on the beat, how much the spectrum changes across it over two beats, and a snare
         * two beats later, each on the scale of its own mean. Kept for the halves of a bar of eight.
         */
        internal fun legacyEvidence(spectrum: Spectrum, grid: BeatGrid, first: Int, last: Int): DoubleArray? {
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
            return DoubleArray(count) { i ->
                if (change[i].isNaN()) Double.NaN else {
                    val l = if (lowMean > 0) low[i] / lowMean else 0.0
                    val c = if (changeMean > 0) change[i] / changeMean else 0.0
                    val snare = if (midMean > 0) (mid[i + 2] - mid[i]) / midMean else 0.0
                    l + c + 0.5 * snare
                }
            }
        }

        /** How much the log-mel spectrum changes across grid beat [k]: two beats after it against two before, band by band, in dB. */
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

        private fun meanOf(rows: Array<DoubleArray>, ok: BooleanArray, from: Int, to: Int): DoubleArray? {
            if (from < 0 || to > rows.size || to <= from) return null
            val out = DoubleArray(rows[0].size)
            for (j in from until to) {
                if (!ok[j]) return null
                for (c in out.indices) out[c] += rows[j][c]
            }
            for (c in out.indices) out[c] /= (to - from)
            return out
        }

        private fun cosine(x: DoubleArray?, y: DoubleArray?): Double {
            if (x == null || y == null) return Double.NaN
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            for (c in x.indices) {
                xy += x[c] * y[c]
                xx += x[c] * x[c]
                yy += y[c] * y[c]
            }
            val d = sqrt(xx * yy)
            return if (d <= 1e-12) Double.NaN else 1.0 - xy / d
        }

        private fun meanAbs(x: DoubleArray?, y: DoubleArray?): Double {
            if (x == null || y == null) return Double.NaN
            var sum = 0.0
            for (c in x.indices) sum += abs(x[c] - y[c])
            return sum / x.size
        }
    }
}
