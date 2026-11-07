/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex FFT of one power-of-two size, in double precision, in place on a real and an imaginary
 * array. Unscaled both ways: [forward] is X[k] = sum of x[j] e^(-2 pi i jk/n), [inverse] the same
 * sum with +i, so the inverse of the forward is n times the input.
 *
 * Double precision because the model's own STFT is torch's float32 one: computing in doubles and
 * rounding once lands closer to the exact transform than torch does, so what is left between the
 * two is torch's rounding, about 137 dB under the signal (parts/reference/floor.json).
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "an FFT size must be a power of two: $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)

    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, 1.0)

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        val n = size
        require(re.size >= n && im.size >= n)
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val m = im[i]; im[i] = im[j]; im[j] = m
            }
        }
        // The first stage needs no multiplication: its only twiddle is 1.
        var a = 0
        while (a < n) {
            val br = re[a + 1]
            val bi = im[a + 1]
            re[a + 1] = re[a] - br; im[a + 1] = im[a] - bi
            re[a] += br; im[a] += bi
            a += 2
        }
        var half = 2
        while (half < n) {
            val span = 2 * half
            val stride = n / span
            for (k in 0 until half) {
                val wr = cosTable[k * stride]
                val wi = sign * sinTable[k * stride]
                var i = k
                while (i < n) {
                    val j = i + half
                    val tr = re[j] * wr - im[j] * wi
                    val ti = re[j] * wi + im[j] * wr
                    re[j] = re[i] - tr; im[j] = im[i] - ti
                    re[i] += tr; im[i] += ti
                    i += span
                }
            }
            half = span
        }
    }
}
