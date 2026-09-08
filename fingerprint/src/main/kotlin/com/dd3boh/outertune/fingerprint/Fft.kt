/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.dd3boh.outertune.fingerprint

/**
 * A fixed-size radix-2 FFT, single precision.
 *
 * Single precision because the reference is. SongRec and Audile both run rustfft's f32 planner and
 * then square the bins into f32, and a signature is a set of comparisons between neighbouring bins;
 * computing the transform in double and rounding at the end would drift from the implementations
 * that are known to be accepted by Shazam's endpoint. Being faithful matters more here than being
 * marginally more accurate.
 *
 * Real input, so the transform runs over a complex buffer whose imaginary half starts at zero and
 * only the first [size] / 2 + 1 bins are read back. That wastes about half the work compared with
 * packing two real samples per complex bin, which at 1500 transforms per recognition is a few tens
 * of milliseconds and not worth the extra failure surface.
 */
internal class Fft(private val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)

    /** Twiddles for the whole transform, computed once in double and stored single. */
    private val cosTable = FloatArray(size / 2) { Math.cos(2.0 * Math.PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { Math.sin(2.0 * Math.PI * it / size).toFloat() }

    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /**
     * Transforms [input], which must be [size] real samples, and writes the squared magnitude of
     * bins 0..[size] / 2 into [magnitudes].
     *
     * Squared rather than absolute because that is all the caller wants and it saves 1025 square
     * roots per transform.
     */
    fun realForwardSquaredMagnitude(input: FloatArray, magnitudes: FloatArray) {
        require(input.size == size) { "expected $size samples, got ${input.size}" }
        require(magnitudes.size == size / 2 + 1) { "expected ${size / 2 + 1} bins" }

        // Bit-reversed load, which is what lets the butterflies run in place afterwards.
        for (i in 0 until size) {
            re[Integer.reverse(i) ushr (32 - levels)] = input[i]
        }
        java.util.Arrays.fill(im, 0f)

        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val partner = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[partner] * c + im[partner] * s
                    val tim = -re[partner] * s + im[partner] * c
                    re[partner] = re[j] - tre
                    im[partner] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }

        for (bin in magnitudes.indices) {
            magnitudes[bin] = re[bin] * re[bin] + im[bin] * im[bin]
        }
    }
}
