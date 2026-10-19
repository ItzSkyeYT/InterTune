/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.fingerprint.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The spectral view of a region: 40 log-mel bands per frame, and two onset curves made from them.
 *
 * Frames are [WINDOW] decimated samples under a Hann window, [HOP] apart: 46 ms frames 11.6 ms
 * apart at 44.1 kHz, 43 and 10.7 ms at 48 kHz. Fine enough to place a beat to a few milliseconds
 * once many beats are fitted together, coarse enough that a minute of music is 5,000 small FFTs.
 *
 * [onset] is the spectral flux of the whole range, [lowOnset] that of the bands under 150 Hz,
 * where kicks and bass notes live. Both are compared against the louder of each band's neighbours
 * in the frame before (the trick from SuperFlux, Böck and Widmer 2013), so a held note with
 * vibrato does not read as a stream of onsets. Both are then put on one scale: their slow
 * average is taken off, what is left is rectified, and it is divided by its own deviation. So 1
 * means "one deviation above the local floor", for a quiet song as for a loud one.
 */
class Spectrum private constructor(
    val decimatedRate: Double,
    val factor: Int,
    val frames: Int,
    val bandCentres: DoubleArray,
    val logMel: FloatArray,
    val onset: FloatArray,
    val lowOnset: FloatArray,
) {
    /** Spectral frames per second, 86 at 44.1 kHz and 94 at 48 kHz. */
    val frameRate: Double get() = decimatedRate / HOP

    /** The log-mel level of band [band] in frame [frame], in dB. */
    fun mel(frame: Int, band: Int): Float = logMel[frame * BANDS + band]

    /**
     * The full-rate sample at which an onset found at frame [frame] is heard.
     *
     * A sound shows up in the flux in the first frames that take it in, while it still sits near
     * the end of the window: on a log scale, the first sliver of a new kick is already a big rise
     * over what was left of the last one. So a frame is placed at a fixed lag into its window, 78%
     * of the way in, as measured on the synthetic kicks of the tests. It is the same for every
     * song, so two songs analysed alike line up with each other whatever its exact value.
     */
    fun frameToSample(frame: Double): Double = (frame * HOP + ONSET_LAG) * factor

    /** The inverse of [frameToSample]. */
    fun sampleToFrame(sample: Double): Double = (sample / factor - ONSET_LAG) / HOP

    companion object {
        const val HOP = 128
        const val WINDOW = 512
        const val BANDS = 40
        const val LOW_HZ = 30.0
        const val HIGH_HZ = 5000.0
        const val LOW_ONSET_HZ = 150.0

        /** Where in its window a frame's onset is heard, in decimated samples. See [frameToSample]. */
        const val ONSET_LAG = 400.0

        /** How far under the frame's loudest band a band may sit, so silence does not read as huge flux. */
        private const val FLOOR_DB = 80f

        private val window = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / WINDOW)).toFloat() }

        fun of(front: FrontEndResult): Spectrum = of(front.mono, front.decimatedRate, front.factor)

        fun of(mono: FloatArray, decimatedRate: Double, factor: Int): Spectrum {
            val frames = if (mono.size < WINDOW) 0 else (mono.size - WINDOW) / HOP + 1
            val bank = MelBank.forRate(decimatedRate)
            val logMel = FloatArray(frames * BANDS)
            val fft = Fft(WINDOW)
            val buffer = FloatArray(WINDOW)
            val power = FloatArray(WINDOW / 2 + 1)
            for (t in 0 until frames) {
                val start = t * HOP
                for (i in 0 until WINDOW) buffer[i] = mono[start + i] * window[i]
                fft.realForwardSquaredMagnitude(buffer, power)
                var loudest = -Float.MAX_VALUE
                for (b in 0 until BANDS) {
                    val weights = bank.weights[b]
                    val first = bank.firstBin[b]
                    var e = 0.0
                    for (k in weights.indices) e += weights[k] * power[first + k]
                    val db = (10.0 * log10(max(e, 1e-10))).toFloat()
                    logMel[t * BANDS + b] = db
                    if (db > loudest) loudest = db
                }
                val floor = loudest - FLOOR_DB
                for (b in 0 until BANDS) {
                    if (logMel[t * BANDS + b] < floor) logMel[t * BANDS + b] = floor
                }
            }
            val rawOnset = FloatArray(frames)
            val rawLow = FloatArray(frames)
            for (t in 1 until frames) {
                var all = 0f
                var low = 0f
                for (b in 0 until BANDS) {
                    var before = logMel[(t - 1) * BANDS + b]
                    if (b > 0) before = max(before, logMel[(t - 1) * BANDS + b - 1])
                    if (b < BANDS - 1) before = max(before, logMel[(t - 1) * BANDS + b + 1])
                    val rise = logMel[t * BANDS + b] - before
                    if (rise > 0f) {
                        all += rise
                        if (b < bank.lowBands) low += rise
                    }
                }
                rawOnset[t] = all
                rawLow[t] = low
            }
            val frameRate = decimatedRate / HOP
            return Spectrum(
                decimatedRate = decimatedRate,
                factor = factor,
                frames = frames,
                bandCentres = bank.centres,
                logMel = logMel,
                onset = normalise(rawOnset, frameRate),
                lowOnset = normalise(rawLow, frameRate),
            )
        }

        /**
         * Takes off the centred one-second average, rectifies, and divides by the deviation of what
         * is left. A curve with no deviation at all (silence, or a drone) comes back as zeros.
         */
        internal fun normalise(raw: FloatArray, frameRate: Double): FloatArray {
            val n = raw.size
            if (n == 0) return raw
            val half = max(1, (frameRate / 2).roundToInt())
            val prefix = DoubleArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + raw[i]
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = max(0, i - half)
                val hi = min(n, i + half + 1)
                val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
                val v = raw[i] - mean
                out[i] = if (v > 0) v.toFloat() else 0f
            }
            var sum = 0.0
            var squares = 0.0
            for (v in out) {
                sum += v
                squares += v.toDouble() * v
            }
            val mean = sum / n
            val deviation = sqrt(max(0.0, squares / n - mean * mean))
            if (deviation < 1e-9) return FloatArray(n)
            val scale = (1.0 / deviation).toFloat()
            for (i in 0 until n) out[i] *= scale
            return out
        }
    }
}

/**
 * Triangular mel filters from [Spectrum.LOW_HZ] to 5 kHz over the bins of a [Spectrum.WINDOW]
 * point FFT, kept sparse: each band stores only the bins it touches.
 */
internal class MelBank private constructor(
    val firstBin: IntArray,
    val weights: Array<FloatArray>,
    val centres: DoubleArray,
    val lowBands: Int,
) {
    companion object {
        private val cache = HashMap<Double, MelBank>()

        fun forRate(rate: Double): MelBank = synchronized(cache) { cache.getOrPut(rate) { build(rate) } }

        private fun mel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        private fun hz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        private fun build(rate: Double): MelBank {
            val bands = Spectrum.BANDS
            val bins = Spectrum.WINDOW / 2 + 1
            val binHz = rate / Spectrum.WINDOW
            // Kept under the decimator's passband, so the top band never reads folded energy only.
            val top = min(Spectrum.HIGH_HZ, 0.45 * rate)
            val lo = mel(Spectrum.LOW_HZ)
            val hi = mel(top)
            val edges = DoubleArray(bands + 2) { hz(lo + (hi - lo) * it / (bands + 1)) }
            val first = IntArray(bands)
            val weights = arrayOfNulls<FloatArray>(bands)
            val centres = DoubleArray(bands)
            for (b in 0 until bands) {
                val left = edges[b]
                val centre = edges[b + 1]
                val right = edges[b + 2]
                centres[b] = centre
                val w = FloatArray(bins)
                var any = false
                for (k in 0 until bins) {
                    val f = k * binHz
                    val v = when {
                        f > left && f < centre -> (f - left) / (centre - left)
                        f >= centre && f < right -> (right - f) / (right - centre)
                        else -> 0.0
                    }
                    if (v > 0) {
                        w[k] = v.toFloat()
                        any = true
                    }
                }
                // A band narrower than a bin would otherwise hear nothing at all.
                if (!any) w[(centre / binHz).roundToInt().coerceIn(0, bins - 1)] = 1f
                val from = w.indexOfFirst { it > 0f }
                val to = w.indexOfLast { it > 0f }
                first[b] = from
                weights[b] = w.copyOfRange(from, to + 1)
            }
            val low = centres.count { it < Spectrum.LOW_ONSET_HZ }.coerceAtLeast(1)
            @Suppress("UNCHECKED_CAST")
            return MelBank(first, weights as Array<FloatArray>, centres, low)
        }
    }
}

/** Peaks of an onset curve, shared by every stage that places something on an onset. */
object Peaks {

    /**
     * The local maxima of [curve] at or above [threshold], each the highest point within [spread]
     * frames either side, as fractional frames refined by a parabola through the three points
     * around them.
     */
    fun of(curve: FloatArray, threshold: Float = 0f, spread: Int = 2, from: Int = 0, to: Int = curve.size): List<Peak> {
        val out = ArrayList<Peak>()
        val lo = max(1, from)
        val hi = min(curve.size - 1, to)
        var t = lo
        while (t < hi) {
            val v = curve[t]
            if (v >= threshold && v > 0f && v >= curve[t - 1] && v > curve[t + 1]) {
                var highest = true
                for (d in -spread..spread) {
                    val j = t + d
                    if (d != 0 && j in curve.indices && curve[j] > v) {
                        highest = false
                        break
                    }
                }
                if (highest) out += Peak(refine(curve, t), v)
            }
            t++
        }
        return out
    }

    /** The highest point of [curve] within [radius] frames of [around], refined, or null if it is flat there. */
    fun nearest(curve: FloatArray, around: Double, radius: Int): Peak? {
        val centre = around.roundToInt()
        var best = -1
        var bestValue = 0f
        for (t in centre - radius..centre + radius) {
            if (t !in curve.indices) continue
            if (curve[t] > bestValue) {
                bestValue = curve[t]
                best = t
            }
        }
        return if (best < 0) null else Peak(refine(curve, best), bestValue)
    }

    /** [t] moved to the top of the parabola through it and its two neighbours. */
    fun refine(curve: FloatArray, t: Int): Double {
        if (t <= 0 || t >= curve.size - 1) return t.toDouble()
        val a = curve[t - 1].toDouble()
        val b = curve[t].toDouble()
        val c = curve[t + 1].toDouble()
        val denominator = a - 2 * b + c
        if (denominator >= 0.0) return t.toDouble()
        return t + (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
    }

    /** [curve] read at a fractional frame, straight between its neighbours. */
    fun at(curve: FloatArray, frame: Double): Float {
        if (frame <= 0.0) return if (curve.isEmpty()) 0f else curve[0]
        val i = frame.toInt()
        if (i >= curve.size - 1) return if (curve.isEmpty()) 0f else curve[curve.size - 1]
        val f = (frame - i).toFloat()
        return curve[i] * (1f - f) + curve[i + 1] * f
    }
}

/** One peak of an onset curve, at a fractional frame. */
data class Peak(val frame: Double, val value: Float)
