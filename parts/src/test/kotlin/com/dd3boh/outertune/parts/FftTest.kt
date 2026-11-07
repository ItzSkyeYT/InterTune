/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    private fun dft(re: DoubleArray, im: DoubleArray, sign: Double): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            for (j in 0 until n) {
                val a = sign * 2 * PI * ((j.toLong() * k) % n) / n
                outRe[k] += re[j] * cos(a) - im[j] * sin(a)
                outIm[k] += re[j] * sin(a) + im[j] * cos(a)
            }
        }
        return outRe to outIm
    }

    @Test
    fun `the forward and the inverse are the sums they say, at every size up to the model's`() {
        val random = Random(4096)
        for (n in listOf(2, 4, 8, 64, 512, 4096)) {
            val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
            val im = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
            for (sign in listOf(-1.0, 1.0)) {
                val (wantRe, wantIm) = dft(re, im, sign)
                val gotRe = re.copyOf()
                val gotIm = im.copyOf()
                if (sign < 0) Fft(n).forward(gotRe, gotIm) else Fft(n).inverse(gotRe, gotIm)
                for (k in 0 until n) {
                    assertEquals("n=$n sign=$sign re[$k]", wantRe[k], gotRe[k], 1e-9 * n)
                    assertEquals("n=$n sign=$sign im[$k]", wantIm[k], gotIm[k], 1e-9 * n)
                }
            }
        }
    }

    @Test
    fun `an inverse after a forward gives the input back n times`() {
        val random = Random(7)
        val n = 4096
        val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val r = re.copyOf()
        val i = im.copyOf()
        val fft = Fft(n)
        fft.forward(r, i)
        fft.inverse(r, i)
        for (k in 0 until n) {
            assertEquals(re[k], r[k] / n, 1e-13)
            assertEquals(im[k], i[k] / n, 1e-13)
        }
    }
}
