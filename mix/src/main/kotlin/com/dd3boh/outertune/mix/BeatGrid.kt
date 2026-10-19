/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The beats of a region as a straight line: beat k falls at [t0] + k [period], in spectral frames.
 *
 * A straight line because that is what a mix needs. Two songs are lined up by their grids, and a
 * tempo off by 1% drifts 80 ms over 16 beats at 120 BPM, a flam anyone hears; fitted over 30 or
 * more beats, the slope is good to a few hundredths of a percent. The beats a dynamic programme
 * found are kept in [beats], and the line is only trusted between [firstBeat] and [lastBeat],
 * where it keeps landing on onsets.
 */
class BeatGrid(
    val t0: Double,
    val period: Double,
    /** The first and last grid index of the stretch where the line lands on onsets. */
    val firstBeat: Int,
    val lastBeat: Int,
    /** Of the grid's beats in that stretch, the share that land within 35 ms of a real onset. */
    val hitShare: Double,
    /** How far the onsets that were hit sit from the line, root mean square, in milliseconds. */
    val jitterMs: Double,
    /** The spread of the tempo across 8 s windows of that stretch, as a fraction. */
    val steadiness: Double,
    /**
     * How much the tempo changes from one end of the stretch to the other, from a parabola through
     * the beats heard, as a fraction. A song that speeds up by 1% across 30 s is 37 ms off any
     * straight line at its ends, which is a flam, however steady it sounds on its own.
     */
    val drift: Double,
    /** The beats the dynamic programme placed, snapped to their onsets, in frames. */
    val beats: DoubleArray,
    val frameRate: Double,
) {
    val bpm: Double get() = 60.0 * frameRate / period

    /** Where grid beat [k] falls, in frames. */
    fun frameOf(k: Int): Double = t0 + k * period

    /** The grid index nearest [frame]. */
    fun indexNear(frame: Double): Int = ((frame - t0) / period).roundToInt()

    val activeBeats: Int get() = lastBeat - firstBeat + 1

    companion object {
        /** How tightly the dynamic programme holds to the period, Ellis's α. */
        const val TIGHTNESS = 100.0

        /** A beat counts as heard when an onset peak is this close to it. */
        const val HIT_MS = 35.0

        /** A window for the tempo's steadiness, and the step between windows. */
        private const val STEADY_WINDOW_S = 8.0
        private const val STEADY_STEP_S = 2.0

        /**
         * Places the beats of [onset] between [from] and [to] at about [period] frames apart,
         * then fits the line through the ones that land on onsets. Null when not even the
         * programme finds beats to place.
         */
        fun track(onset: FloatArray, frameRate: Double, period: Double, from: Int = 0, to: Int = onset.size): BeatGrid? {
            val lo = from.coerceIn(0, onset.size)
            val hi = to.coerceIn(lo, onset.size)
            if (hi - lo < 4 * period) return null
            val placed = dynamicProgramme(onset, lo, hi, period)
            if (placed.size < 4) return null
            val snapped = DoubleArray(placed.size) { i ->
                Peaks.nearest(onset, placed[i].toDouble(), 2)?.frame ?: placed[i].toDouble()
            }

            val hitRadius = HIT_MS / 1000.0 * frameRate
            val strong = strongPeaks(onset, lo, hi)
            fun hit(frame: Double): Double? = nearestStrong(strong, frame, hitRadius)

            // The stretch of placed beats that mostly land on onsets, then a line through those.
            val placedHits = BooleanArray(snapped.size) { hit(snapped[it]) != null }
            val (i0, i1) = bestStretch(placedHits) ?: return null
            val fit = fitLine(snapped, i0, i1, placedHits, period) ?: return null
            var t0 = fit.first
            var p = fit.second

            // The line, not the programme, is what gets used, so the stretch is judged again on
            // the line's own beats, and the line refitted through the onsets it lands on.
            repeat(2) {
                val first = floor((lo - t0) / p).toInt() + 1
                val last = floor((hi - 1 - t0) / p).toInt()
                if (last - first < 3) return null
                val hits = BooleanArray(last - first + 1)
                val heard = DoubleArray(last - first + 1)
                for (k in first..last) {
                    val h = hit(t0 + k * p)
                    if (h != null) {
                        hits[k - first] = true
                        heard[k - first] = h
                    }
                }
                val (s0, s1) = bestStretch(hits) ?: return null
                val ks = ArrayList<Int>()
                val ts = ArrayList<Double>()
                for (i in s0..s1) if (hits[i]) {
                    ks += first + i
                    ts += heard[i]
                }
                val line = leastSquares(ks, ts) ?: return null
                t0 = line.first
                p = line.second
                if (it == 1) {
                    var squares = 0.0
                    for (j in ks.indices) {
                        val r = ts[j] - (t0 + ks[j] * p)
                        squares += r * r
                    }
                    val jitter = sqrt(squares / ks.size) / frameRate * 1000.0
                    val stretchHits = (s0..s1).count { hits[it] }
                    return BeatGrid(
                        t0 = t0,
                        period = p,
                        firstBeat = first + s0,
                        lastBeat = first + s1,
                        hitShare = stretchHits.toDouble() / (s1 - s0 + 1),
                        jitterMs = jitter,
                        steadiness = steadiness(ks, ts, frameRate),
                        drift = drift(ks, ts),
                        beats = snapped,
                        frameRate = frameRate,
                    )
                }
            }
            return null
        }

        /**
         * Ellis (2007): the best chain of beats, each scoring its onset strength and paying for
         * any gap that is not one period, α ln²(gap / period). Onsets are smoothed by a Gaussian
         * of a 32nd of the period first, as librosa does, so the chain is pulled towards an onset
         * a frame or two away instead of having to hit it exactly.
         */
        internal fun dynamicProgramme(onset: FloatArray, from: Int, to: Int, period: Double): IntArray {
            val n = to - from
            val sigma = period / 32.0
            val radius = max(1, (3 * sigma).roundToInt())
            val kernel = DoubleArray(2 * radius + 1) { exp(-0.5 * ((it - radius) / sigma) * ((it - radius) / sigma)) }
            val local = DoubleArray(n)
            for (t in 0 until n) {
                var s = 0.0
                for (k in kernel.indices) {
                    val j = t + k - radius
                    if (j in 0 until n) s += kernel[k] * onset[from + j]
                }
                local[t] = s
            }
            val shortest = max(1, (period / 2).roundToInt())
            val longest = max(shortest + 1, (2 * period).roundToInt())
            val penalty = DoubleArray(longest + 1) { gap ->
                if (gap < shortest) Double.NEGATIVE_INFINITY else {
                    val l = ln(gap / period)
                    -TIGHTNESS * l * l
                }
            }
            val score = DoubleArray(n)
            val back = IntArray(n) { -1 }
            val localMax = local.maxOrNull() ?: 0.0
            var started = false
            for (t in 0 until n) {
                // Reaching back before the start counts as a fresh chain, worth nothing so far.
                var best = 0.0
                var bestFrom = -1
                if (t < longest) {
                    for (gap in max(shortest, t + 1)..longest) best = max(best, penalty[gap])
                }
                for (gap in shortest..min(longest, t)) {
                    val v = score[t - gap] + penalty[gap]
                    if (v > best || bestFrom < 0 && v >= best) {
                        best = v
                        bestFrom = t - gap
                    }
                }
                score[t] = local[t] + best
                // No chain starts on a frame with nothing in it.
                if (!started && local[t] < 0.01 * localMax) back[t] = -1 else {
                    back[t] = bestFrom
                    started = true
                }
            }
            var end = n - 1
            val tail = max(0, n - period.roundToInt())
            for (t in tail until n) if (score[t] > score[end]) end = t
            val chain = ArrayList<Int>()
            var t = end
            while (t >= 0) {
                chain += from + t
                t = back[t]
            }
            chain.reverse()
            return chain.toIntArray()
        }

        /** The onset peaks of the region that count as real onsets: at least half the median peak. */
        private fun strongPeaks(onset: FloatArray, from: Int, to: Int): DoubleArray {
            val peaks = Peaks.of(onset, 0f, 2, from, to)
            if (peaks.isEmpty()) return DoubleArray(0)
            val sorted = peaks.map { it.value }.sorted()
            val threshold = sorted[sorted.size / 2] * 0.5f
            return peaks.filter { it.value >= threshold }.map { it.frame }.toDoubleArray()
        }

        /** The strong peak nearest [frame] within [radius] frames, or null. */
        private fun nearestStrong(strong: DoubleArray, frame: Double, radius: Double): Double? {
            if (strong.isEmpty()) return null
            var lo = 0
            var hi = strong.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (strong[mid] < frame) lo = mid + 1 else hi = mid
            }
            var best: Double? = null
            var bestDistance = radius
            for (i in max(0, lo - 1)..min(strong.size - 1, lo + 1)) {
                val d = abs(strong[i] - frame)
                if (d <= bestDistance) {
                    bestDistance = d
                    best = strong[i]
                }
            }
            return best
        }

        /**
         * The run of beats where a hit counts one and a miss four, at its best: any run that
         * scores above nothing has more than 80% hits, and this is the longest such run that the
         * best score allows. Null if no beat is heard at all.
         */
        internal fun bestStretch(hits: BooleanArray): Pair<Int, Int>? {
            var best = 0
            var bestFrom = -1
            var bestTo = -1
            var sum = 0
            var start = 0
            for (i in hits.indices) {
                sum += if (hits[i]) 1 else -4
                if (sum <= 0) {
                    sum = 0
                    start = i + 1
                } else if (sum > best) {
                    best = sum
                    bestFrom = start
                    bestTo = i
                }
            }
            return if (bestFrom < 0) null else bestFrom to bestTo
        }

        /**
         * A line through the hit beats between [i0] and [i1], counting beat numbers from the gaps
         * between them, so a beat the programme skipped does not bend the line. Two passes: the
         * second leaves out any beat more than three deviations or two frames off the first line.
         */
        private fun fitLine(beats: DoubleArray, i0: Int, i1: Int, hits: BooleanArray, period: Double): Pair<Double, Double>? {
            val ks = ArrayList<Int>()
            val ts = ArrayList<Double>()
            var k = 0
            for (i in i0..i1) {
                if (i > i0) k += max(1, ((beats[i] - beats[i - 1]) / period).roundToInt())
                if (hits[i]) {
                    ks += k
                    ts += beats[i]
                }
            }
            val first = leastSquares(ks, ts) ?: return null
            var squares = 0.0
            for (j in ks.indices) {
                val r = ts[j] - (first.first + ks[j] * first.second)
                squares += r * r
            }
            val limit = max(2.0, 3 * sqrt(squares / ks.size))
            val keptK = ArrayList<Int>()
            val keptT = ArrayList<Double>()
            for (j in ks.indices) {
                if (abs(ts[j] - (first.first + ks[j] * first.second)) <= limit) {
                    keptK += ks[j]
                    keptT += ts[j]
                }
            }
            val second = leastSquares(keptK, keptT) ?: first
            // Beat numbers were counted from the stretch's first beat; the line is put back in
            // frames so that grid index 0 is the first beat at or after frame 0.
            val t0 = second.first
            val p = second.second
            val shift = floor(t0 / p).toInt()
            return (t0 - shift * p) to p
        }

        /** The least-squares line t = a + b k, or null with fewer than four points. */
        internal fun leastSquares(ks: List<Int>, ts: List<Double>): Pair<Double, Double>? {
            val n = ks.size
            if (n < 4) return null
            var sk = 0.0
            var st = 0.0
            for (i in 0 until n) {
                sk += ks[i]
                st += ts[i]
            }
            val mk = sk / n
            val mt = st / n
            var skk = 0.0
            var skt = 0.0
            for (i in 0 until n) {
                val dk = ks[i] - mk
                skk += dk * dk
                skt += dk * (ts[i] - mt)
            }
            if (skk <= 0) return null
            val b = skt / skk
            return (mt - b * mk) to b
        }

        /**
         * The relative change of the period across the beats, from the parabola t = a + b k + c k²
         * fitted through them: the period is b + 2 c k, so it moves by 2 c (last - first) / b.
         */
        internal fun drift(ks: List<Int>, ts: List<Double>): Double {
            val n = ks.size
            if (n < 8) return Double.POSITIVE_INFINITY
            val mean = ks.average()
            val m = Array(3) { DoubleArray(4) }
            for (i in 0 until n) {
                val x = ks[i] - mean
                val powers = doubleArrayOf(1.0, x, x * x)
                for (r in 0..2) {
                    for (c in 0..2) m[r][c] += powers[r] * powers[c]
                    m[r][3] += powers[r] * ts[i]
                }
            }
            // Gaussian elimination with partial pivoting on the 3 by 3 normal equations.
            for (col in 0..2) {
                var pivot = col
                for (r in col + 1..2) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-12) return Double.POSITIVE_INFINITY
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                for (r in 0..2) {
                    if (r == col) continue
                    val f = m[r][col] / m[col][col]
                    for (c in col..3) m[r][c] -= f * m[col][c]
                }
            }
            val b = m[1][3] / m[1][1]
            val c = m[2][3] / m[2][2]
            if (b <= 0) return Double.POSITIVE_INFINITY
            return abs(2 * c * (ks.last() - ks.first()) / b)
        }

        /**
         * The spread of the tempo across the stretch: a line through each 8 s window of hit beats,
         * every 2 s, and twice the interquartile range of their slopes over the median. For tempos
         * spread evenly, as a ramp spreads them, that is the whole range they cover.
         */
        private fun steadiness(ks: List<Int>, ts: List<Double>, frameRate: Double): Double {
            if (ks.size < 8) return Double.POSITIVE_INFINITY
            val window = STEADY_WINDOW_S * frameRate
            val step = STEADY_STEP_S * frameRate
            val periods = ArrayList<Double>()
            var start = ts.first()
            while (start + window <= ts.last() + 1) {
                val wk = ArrayList<Int>()
                val wt = ArrayList<Double>()
                for (i in ts.indices) if (ts[i] >= start && ts[i] < start + window) {
                    wk += ks[i]
                    wt += ts[i]
                }
                leastSquares(wk, wt)?.let { if (wk.size >= 6) periods += it.second }
                start += step
            }
            if (periods.size < 3) return Double.POSITIVE_INFINITY
            periods.sort()
            val median = periods[periods.size / 2]
            val q1 = periods[periods.size / 4]
            val q3 = periods[(periods.size * 3) / 4]
            return 2 * (q3 - q1) / median
        }
    }
}
