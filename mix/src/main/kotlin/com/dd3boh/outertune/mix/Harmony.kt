/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.fingerprint.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The music's pitch classes over time, for finding bar lines: chords and bass notes change on the
 * one far more often than anywhere else in the bar, which drums alone cannot say when the kick
 * lands on the one and the three alike.
 *
 * Every [HOP] samples of the decimated mono copy, a frame of [WINDOW] samples under a Hann window
 * (0.19 s at 11 kHz, 5.4 Hz a bin): [chroma] folds 100 Hz to 4 kHz into twelve pitch classes, and
 * [bass] does the same with 40 to 160 Hz alone, where the bassline is. Each is log-compressed and
 * made unit length, frame by frame, so a loud bar weighs as much as a quiet one.
 */
class Harmony private constructor(
    val frames: Int,
    /** Frame t's twelve values at 12 t. */
    val chroma: FloatArray,
    val bass: FloatArray,
) {
    /** The decimated sample at the centre of frame [t]. */
    fun centreOf(t: Int): Double = t.toDouble() * HOP + WINDOW / 2.0

    companion object {
        const val WINDOW = 2048
        const val HOP = 512
        private const val CHROMA_LOW_HZ = 100.0
        private const val CHROMA_HIGH_HZ = 4000.0
        private const val BASS_LOW_HZ = 40.0
        private const val BASS_HIGH_HZ = 160.0

        /** How hard magnitudes are compressed before they are added up: log(1 + K m). */
        private const val COMPRESSION = 200.0

        private val window = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / WINDOW)).toFloat() }

        fun of(mono: FloatArray, decimatedRate: Double): Harmony {
            val frames = if (mono.size < WINDOW) 0 else (mono.size - WINDOW) / HOP + 1
            val chroma = FloatArray(frames * 12)
            val bass = FloatArray(frames * 12)
            if (frames == 0) return Harmony(0, chroma, bass)
            val binHz = decimatedRate / WINDOW
            val classOf = IntArray(WINDOW / 2 + 1) { k ->
                if (k == 0) -1 else Math.floorMod((69.0 + 12.0 * ln(k * binHz / 440.0) / ln(2.0)).roundToInt(), 12)
            }
            val c0 = max(1, (CHROMA_LOW_HZ / binHz).roundToInt())
            val c1 = min(WINDOW / 2, (CHROMA_HIGH_HZ / binHz).roundToInt())
            val b0 = max(1, (BASS_LOW_HZ / binHz).roundToInt())
            val b1 = min(WINDOW / 2, (BASS_HIGH_HZ / binHz).roundToInt())
            val fft = Fft(WINDOW)
            val buffer = FloatArray(WINDOW)
            val power = FloatArray(WINDOW / 2 + 1)
            val c = DoubleArray(12)
            val b = DoubleArray(12)
            for (t in 0 until frames) {
                val start = t * HOP
                for (i in 0 until WINDOW) buffer[i] = mono[start + i] * window[i]
                fft.realForwardSquaredMagnitude(buffer, power)
                c.fill(0.0)
                b.fill(0.0)
                for (k in c0..c1) c[classOf[k]] += ln(1.0 + COMPRESSION * sqrt(power[k].toDouble()))
                for (k in b0..b1) b[classOf[k]] += ln(1.0 + COMPRESSION * sqrt(power[k].toDouble()))
                unit(c, chroma, t)
                unit(b, bass, t)
            }
            return Harmony(frames, chroma, bass)
        }

        private fun unit(v: DoubleArray, into: FloatArray, t: Int) {
            var n = 0.0
            for (x in v) n += x * x
            n = sqrt(n)
            if (n <= 1e-12) return
            for (i in 0 until 12) into[t * 12 + i] = (v[i] / n).toFloat()
        }
    }
}
