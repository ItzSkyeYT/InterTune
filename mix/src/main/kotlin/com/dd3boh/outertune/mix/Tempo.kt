/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The tempo of a region, before any beat is placed.
 *
 * [bpm] is folded into [Tempo.FOLD_LOW, Tempo.FOLD_HIGH): a grid at double or half the tempo still
 * holds every beat of the other, so the octave does not matter for mixing and is not guessed at.
 * What does matter is a 3:2 mistake, a shuffle read as straight or the other way round, and
 * [rivalRatio] is how close the best 3:2 rival came to explaining the strong onsets as well as
 * this tempo does, as a fraction of how well this tempo does it. [z] says how far above chance
 * the pulse at this tempo stands, against pulses at random tempos and phases, so a busy song
 * without a beat does not pass as rhythmic.
 */
class TempoEstimate(
    val bpm: Double,
    /** The beat period at [bpm], in spectral frames. */
    val periodFrames: Double,
    val z: Double,
    val rivalRatio: Double,
    /** The tempo the autocorrelation picked before the rivals were heard, unfolded. */
    val rawBpm: Double,
)

object Tempo {
    const val MIN_BPM = 60.0
    const val MAX_BPM = 200.0
    const val FOLD_LOW = 85.0
    const val FOLD_HIGH = 170.0

    /** The centre and width of the prior, in BPM and octaves. */
    private const val PRIOR_BPM = 120.0
    private const val PRIOR_OCTAVES = 1.0

    /** How many random tempos the pulse is held against. */
    private const val NULL_TEMPOS = 200

    /** [bpm] doubled or halved into [FOLD_LOW, FOLD_HIGH). */
    fun fold(bpm: Double): Double {
        if (!(bpm > 0) || !bpm.isFinite()) return bpm
        var b = bpm
        while (b < FOLD_LOW) b *= 2
        while (b >= FOLD_HIGH) b /= 2
        return b
    }

    /** The beat period in spectral frames for [bpm]. */
    fun periodFor(bpm: Double, frameRate: Double): Double = 60.0 * frameRate / bpm

    /**
     * The tempo of [onset] between frames [from] and [to], or null when there is not enough of it
     * to hear a tempo in: under eight seconds, or nothing but silence.
     */
    fun estimate(onset: FloatArray, frameRate: Double, from: Int = 0, to: Int = onset.size): TempoEstimate? {
        val lo = from.coerceIn(0, onset.size)
        val hi = to.coerceIn(lo, onset.size)
        val n = hi - lo
        if (n < 8 * frameRate) return null
        if ((lo until hi).none { onset[it] > 0f }) return null

        val raw = autocorrelationTempo(onset, frameRate, lo, hi) ?: return null

        // The autocorrelation cannot tell a beat from one 3:2 of it, a shuffle read straight or the
        // other way round, so each candidate is laid down as a straight pulse train, at its best
        // period near where it should be and its best phase, and judged by how well it explains
        // the strong onsets: the F-measure of its pulses against the top 30% of onset peaks. A
        // plain mean along the train cannot do this in dense music, where every train on a
        // multiple of the eighth note lands on something and the train that also crosses the weak
        // beats is the one that loses.
        val strong = strongPeaks(onset, lo, hi)
        val radius = HIT_MS / 1000.0 * frameRate
        var best = refine(onset, frameRate, lo, hi, fold(raw))
        var bestF = gridF(best, lo, hi, strong, radius)
        // One switch at most, to a rival that explains the strong onsets clearly better; then the
        // rivals of whatever won are heard again, the tempo it replaced among them.
        val rivals = RIVALS.map { refine(onset, frameRate, lo, hi, best.bpm * it) }
        val rivalFs = rivals.map { gridF(it, lo, hi, strong, radius) }
        val top = rivalFs.indices.maxBy { rivalFs[it] }
        var rivalF = rivalFs[top]
        if (rivalFs[top] * RIVAL_SWITCH > bestF) {
            best = rivals[top]
            bestF = rivalFs[top]
            rivalF = RIVALS.maxOf { gridF(refine(onset, frameRate, lo, hi, best.bpm * it), lo, hi, strong, radius) }
        }

        val folded = fold(best.bpm)
        val z = zScore(onset, frameRate, lo, hi, best.score)
        return TempoEstimate(
            bpm = folded,
            periodFrames = periodFor(folded, frameRate),
            z = z,
            rivalRatio = if (bestF > 0) rivalF / bestF else 1.0,
            rawBpm = raw,
        )
    }

    /** The tempos 3:2 away from a beat, at the octaves either side of it. */
    private val RIVALS = doubleArrayOf(2.0 / 3.0, 3.0 / 4.0, 4.0 / 3.0, 3.0 / 2.0)

    /** How close to a strong onset a pulse must fall to explain it, as for the beats. */
    private const val HIT_MS = 35.0

    /** The onset peaks of the region in its top 30%, sorted by frame. */
    private fun strongPeaks(onset: FloatArray, from: Int, to: Int): DoubleArray {
        val peaks = Peaks.of(onset, 0f, 2, from, to)
        if (peaks.isEmpty()) return DoubleArray(0)
        val sorted = peaks.map { it.value }.sorted()
        val threshold = sorted[(sorted.size * 0.7).toInt().coerceAtMost(sorted.size - 1)]
        return peaks.filter { it.value >= threshold }.map { it.frame }.toDoubleArray()
    }

    /**
     * The F-measure of [fit]'s pulse train against [strong]: the share of pulses that land within
     * [radius] of a strong onset, against the share of strong onsets that a pulse lands on.
     */
    internal fun gridF(fit: Fit, from: Int, to: Int, strong: DoubleArray, radius: Double): Double {
        if (strong.isEmpty()) return 0.0
        var pulses = 0
        var hits = 0
        val used = BooleanArray(strong.size)
        var p = from + fit.phase
        var cursor = 0
        while (p < to - 1) {
            pulses++
            while (cursor < strong.size && strong[cursor] < p - radius) cursor++
            var best = -1
            var bestDistance = radius
            var i = cursor
            while (i < strong.size && strong[i] <= p + radius) {
                val d = abs(strong[i] - p)
                if (!used[i] && d <= bestDistance) {
                    best = i
                    bestDistance = d
                }
                i++
            }
            if (best >= 0) {
                used[best] = true
                hits++
            }
            p += fit.period
        }
        if (hits == 0) return 0.0
        val precision = hits.toDouble() / pulses
        val recall = hits.toDouble() / strong.size
        return 2 * precision * recall / (precision + recall)
    }

    /** A rival must explain the strong onsets this much better than the chosen tempo to replace it. */
    private const val RIVAL_SWITCH = 0.87

    /** The tempo the enhanced, weighted autocorrelation peaks at, between 60 and 200 BPM. */
    internal fun autocorrelationTempo(onset: FloatArray, frameRate: Double, from: Int, to: Int): Double? {
        val n = to - from
        var mean = 0.0
        for (t in from until to) mean += onset[t]
        mean /= n
        val x = DoubleArray(n) { onset[from + it] - mean }
        val minLag = floor(periodFor(MAX_BPM, frameRate)).toInt()
        val maxLag = ceil(periodFor(MIN_BPM, frameRate)).toInt()
        val reach = min(n - 1, 3 * maxLag + 3)
        val a = DoubleArray(reach + 1)
        for (lag in 0..reach) {
            var sum = 0.0
            for (t in 0 until n - lag) sum += x[t] * x[t + lag]
            a[lag] = sum / (n - lag)
        }
        if (a[0] <= 0) return null
        val prior120 = periodFor(PRIOR_BPM, frameRate)
        val weighted = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            // Percival and Tzanetakis's enhancement: a true beat period also correlates at twice
            // and three times itself, a lucky lag usually does not.
            var s = a[lag]
            if (2 * lag <= reach) s += a[2 * lag] / 2
            if (3 * lag <= reach) s += a[3 * lag] / 3
            val octaves = ln(lag / prior120) / ln(2.0) / PRIOR_OCTAVES
            weighted[lag] = s * exp(-0.5 * octaves * octaves)
        }
        var bestLag = minLag
        for (lag in minLag..maxLag) if (weighted[lag] > weighted[bestLag]) bestLag = lag
        if (weighted[bestLag] <= 0) return null
        var lag = bestLag.toDouble()
        if (bestLag > minLag && bestLag < maxLag) {
            val l = weighted[bestLag - 1]
            val c = weighted[bestLag]
            val r = weighted[bestLag + 1]
            val d = l - 2 * c + r
            if (d < 0) lag += (0.5 * (l - r) / d).coerceIn(-0.5, 0.5)
        }
        return 60.0 * frameRate / lag
    }

    /** A tempo and how well its best pulse train lands, see [refine]. */
    internal class Fit(val bpm: Double, val period: Double, val phase: Double, val score: Double)

    /**
     * The pulse train near [bpm] that lands on the strongest onsets: periods within 2% of it in
     * steps of 0.05%, every phase in half frames. The period is searched because one lag of the
     * autocorrelation is 2% of a beat, and a train that far off would slide off the beat within
     * a few bars.
     */
    internal fun refine(onset: FloatArray, frameRate: Double, from: Int, to: Int, bpm: Double): Fit {
        val nominal = periodFor(bpm, frameRate)
        var best = Fit(bpm, nominal, 0.0, -1.0)
        var step = -40
        while (step <= 40) {
            val period = nominal * (1.0 + step * 0.0005)
            val (phase, score) = bestPhase(onset, from, to, period, 0.5)
            if (score > best.score) best = Fit(60.0 * frameRate / period, period, phase, score)
            step++
        }
        return best
    }

    /** The phase, from [from], of the pulse train of [period] with the highest [pulse] score. */
    fun bestPhase(onset: FloatArray, from: Int, to: Int, period: Double, step: Double = 0.5): Pair<Double, Double> {
        var bestPhase = 0.0
        var bestScore = -1.0
        var phase = 0.0
        while (phase < period) {
            val score = pulse(onset, from, to, period, phase)
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
            phase += step
        }
        return bestPhase to bestScore
    }

    /**
     * The mean onset strength along a pulse train of [period] frames starting [phase] frames after
     * [from], each pulse taking the strongest frame within one of it, so a train a fraction of a
     * frame off still counts the onset it was meant to land on.
     */
    fun pulse(onset: FloatArray, from: Int, to: Int, period: Double, phase: Double): Double {
        var sum = 0.0
        var count = 0
        var p = from + phase
        while (p < to - 1) {
            val c = p.roundToInt()
            var v = onset[c]
            if (c > from && onset[c - 1] > v) v = onset[c - 1]
            if (c + 1 < to && onset[c + 1] > v) v = onset[c + 1]
            sum += v
            count++
            p += period
        }
        return if (count == 0) 0.0 else sum / count
    }

    /**
     * How many deviations [score] stands above the best-phase pulse of random tempos.
     *
     * Each random tempo also gets its best phase, so a busy song is held against the same search
     * that found its own tempo, and noise does not pass because there are many phases to try. The
     * tempos are drawn from a fixed seed, so the answer for a region never changes.
     */
    private fun zScore(onset: FloatArray, frameRate: Double, from: Int, to: Int, score: Double): Double {
        val random = java.util.Random(0x6D6978L)
        val scores = DoubleArray(NULL_TEMPOS)
        val logLo = ln(MIN_BPM)
        val logHi = ln(MAX_BPM)
        for (i in 0 until NULL_TEMPOS) {
            val bpm = exp(logLo + (logHi - logLo) * random.nextDouble())
            scores[i] = bestPhase(onset, from, to, periodFor(bpm, frameRate), 1.0).second
        }
        val mean = scores.average()
        var variance = 0.0
        for (s in scores) variance += (s - mean) * (s - mean)
        val deviation = sqrt(variance / NULL_TEMPOS)
        if (deviation < 1e-12) return 0.0
        return (score - mean) / deviation
    }

    /** How far apart two tempos are once folded, as a fraction: 0.03 is 3%. */
    fun distance(a: Double, b: Double): Double {
        val fa = fold(a)
        val fb = fold(b)
        // Folding can put two close tempos on either side of the fold, 168 and 86 for 168 and 172.
        val r = fb / fa
        val candidates = doubleArrayOf(r, r * 2, r / 2)
        return candidates.minOf { abs(it - 1.0) }
    }
}
