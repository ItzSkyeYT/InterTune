/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.Spectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a song sings, guessed from the log-mel frames the analysis already has, for Auto's rule
 * that two voices never sing at once. The phone has no model to split a voice out yet, so this is
 * the cheap stand-in: the 40 bands put in six groups, over a second around each step, through one
 * logistic fit made on the voice parts htdemucs split from 42 ends of 38 songs on the PC
 * (experiments/features/mix-better/tools/voice_fit.py, which computes the same features).
 *
 * The features, per step: each group's mean level against the window's own (the spectrum's shape:
 * a voice lifts 300 Hz to 2.4 kHz), how much each group moves from frame to frame and how widely it
 * ranges (syllables move the mids, a held pad does not), the window's level against the end's
 * median, and how much the 1.2 to 5 kHz groups move at syllable rates (3 to 8 Hz).
 */
object VoiceGuess {

    /** Where the six groups part, in Hz: under 150, 150 to 300, ..., over 2.4 kHz. */
    val EDGES = doubleArrayOf(150.0, 300.0, 600.0, 1200.0, 2400.0)

    /** The frames either side of a step a guess reads, in seconds. */
    const val HALF_S = 0.5

    const val GROUPS = 6
    const val FEATURES = 3 * GROUPS + 2

    /**
     * Fitted by voice_fit.py on the raw features of the 42 ends (each weight already divided by its
     * feature's spread): groups' shape, frame-to-frame movement and range, the level, the syllable share.
     */
    private val WEIGHTS = doubleArrayOf(
        -0.0278895, -0.0177775, 0.015785, 0.087404, -0.0344721,
        0.0213475, 0.0073355, -0.0898268, -0.0822014, -0.0006835,
        -0.0374215, -0.8842221, 0.0571235, -0.0908591, -0.1763072,
        0.4478071, -0.1192017, 0.1208902, 0.2245124, 2.3614545,
    )
    private const val BIAS = 1.639335

    /**
     * How likely the song sings at each of [steps] steps of [stepMs] from the start of the copy
     * [spectrum] was taken of, 0 to 1. [guideFrom] is the decimated frame the spectrum's copy
     * starts at in the region (RegionAnalysis.guideFrom).
     */
    fun of(spectrum: Spectrum, sampleRate: Int, steps: Int, stepMs: Double, guideFrom: Int = 0): FloatArray {
        val f = features(spectrum, sampleRate, steps, stepMs, guideFrom)
        return FloatArray(steps) { i ->
            var z = BIAS
            val x = f[i]
            for (j in 0 until FEATURES) z += WEIGHTS[j] * x[j]
            (1.0 / (1.0 + exp(-z))).toFloat()
        }
    }

    /** The features of each step, as voice_fit.py computes them; a step with under four frames around it has none (zeros). */
    fun features(spectrum: Spectrum, sampleRate: Int, steps: Int, stepMs: Double, guideFrom: Int = 0): Array<DoubleArray> {
        val frames = spectrum.frames
        val bands = Spectrum.BANDS
        val group = IntArray(bands) { b -> EDGES.count { spectrum.bandCentres[b] >= it } }
        val count = IntArray(GROUPS).also { c -> for (b in 0 until bands) c[group[b]]++ }
        // Each group's level per frame, the mean of its bands' dB, and the frame's mean over all of them.
        val g = Array(GROUPS) { DoubleArray(frames) }
        val total = DoubleArray(frames)
        for (t in 0 until frames) {
            var all = 0.0
            for (b in 0 until bands) {
                val v = spectrum.mel(t, b).toDouble()
                g[group[b]][t] += v
                all += v
            }
            for (k in 0 until GROUPS) if (count[k] > 0) g[k][t] /= count[k]
            total[t] = all / bands
        }
        val median = median(total)
        val factor = spectrum.factor
        val msPerFrame = Spectrum.HOP * factor * 1000.0 / sampleRate
        val centre0 = (guideFrom + Spectrum.WINDOW / 2.0) * factor * 1000.0 / sampleRate
        val half = (HALF_S * 1000.0 / msPerFrame).roundToInt()
        val nWin = 2 * half + 1
        val frameRate = 1000.0 / msPerFrame
        // The syllable-rate share: a DFT of the window's 1.2-5 kHz level at the bins it needs only.
        val hann = DoubleArray(nWin) { 0.5 - 0.5 * cos(2.0 * PI * it / (nWin - 1)) }
        val bins = (0..nWin / 2).filter { val hz = it * frameRate / nWin; hz >= 0.5 && hz <= 20.0 }
        val syllable = bins.map { val hz = it * frameRate / nWin; hz >= 3.0 && hz <= 8.0 }
        val cosT = Array(bins.size) { j -> DoubleArray(nWin) { n -> cos(2.0 * PI * bins[j] * n / nWin) } }
        val sinT = Array(bins.size) { j -> DoubleArray(nWin) { n -> sin(2.0 * PI * bins[j] * n / nWin) } }
        val high = DoubleArray(frames) { (g[4][it] + g[5][it]) / 2 }
        val seg = DoubleArray(nWin)
        return Array(steps) { i ->
            val x = DoubleArray(FEATURES)
            val c = (((i + 0.5) * stepMs - centre0) / msPerFrame).roundToInt()
            val lo = max(0, c - half)
            val hi = min(frames, c + half + 1)
            val n = hi - lo
            if (n < 4) return@Array x
            var meanAll = 0.0
            for (t in lo until hi) meanAll += total[t]
            meanAll /= n
            for (k in 0 until GROUPS) {
                var sum = 0.0
                var squares = 0.0
                var moved = 0.0
                for (t in lo until hi) {
                    val v = g[k][t]
                    sum += v
                    squares += v * v
                    moved += if (t == 0) 0.0 else abs(v - g[k][t - 1])
                }
                val mean = sum / n
                x[k] = mean - meanAll
                x[GROUPS + k] = moved / n
                x[2 * GROUPS + k] = sqrt(max(0.0, squares / n - mean * mean))
            }
            x[3 * GROUPS] = meanAll - median
            if (n == nWin) {
                var m = 0.0
                for (t in lo until hi) m += high[t]
                m /= n
                for (t in 0 until nWin) seg[t] = (high[lo + t] - m) * hann[t]
                var syl = 0.0
                var band = 0.0
                for (j in bins.indices) {
                    var re = 0.0
                    var im = 0.0
                    val ct = cosT[j]
                    val st = sinT[j]
                    for (t in 0 until nWin) {
                        re += seg[t] * ct[t]
                        im -= seg[t] * st[t]
                    }
                    val mag = sqrt(re * re + im * im)
                    band += mag
                    if (syllable[j]) syl += mag
                }
                x[3 * GROUPS + 1] = syl / max(band, 1e-9)
            }
            x
        }
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
