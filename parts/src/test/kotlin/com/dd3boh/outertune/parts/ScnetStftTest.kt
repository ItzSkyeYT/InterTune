/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.BINS
import com.dd3boh.outertune.parts.Scnet.FRAMES
import com.dd3boh.outertune.parts.Scnet.HOP
import com.dd3boh.outertune.parts.Scnet.N_FFT
import com.dd3boh.outertune.parts.Scnet.PADDED
import com.dd3boh.outertune.parts.Scnet.SEGMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The transforms on their own, with no model and no reference files: the shapes stemlib gives,
 * single bins against a plain DFT of the frame torch would cut, and a round trip. ScnetReferenceTest
 * holds them to torch itself.
 */
class ScnetStftTest {

    /** A segment of noise with a tone in it, different in each channel. */
    private fun segment(seed: Int): Pair<FloatArray, FloatArray> {
        val random = Random(seed)
        val left = FloatArray(SEGMENT) { (0.3 * sin(2 * PI * 440 * it / 44100.0) + random.nextDouble(-0.2, 0.2)).toFloat() }
        val right = FloatArray(SEGMENT) { (0.25 * sin(2 * PI * 97 * it / 44100.0) + random.nextDouble(-0.3, 0.3)).toFloat() }
        return left to right
    }

    @Test
    fun `the shapes are stemlib's`() {
        // stemlib.py:112-114, as written there.
        var pad = 1024 - SEGMENT % 1024
        if ((SEGMENT + pad) / 1024 % 2 == 0) pad += 1024
        assertEquals(pad, Scnet.TAIL)
        assertEquals(486_400, PADDED)
        assertEquals(476, FRAMES)
        assertEquals(2049, BINS)
        // Frames covering each sample: 3 at the very start, 4 from a hop in.
        assertEquals(3, ScnetStft.covering(N_FFT / 2))
        assertEquals(4, ScnetStft.covering(N_FFT / 2 + HOP))
        assertEquals(4, ScnetStft.covering(N_FFT / 2 + SEGMENT - 1))
    }

    @Test
    fun `single bins are the DFT of the frame torch cuts, scaled by 1 over 64`() {
        val (left, right) = segment(1)
        val spec = FloatArray(Scnet.SPEC_SIZE)
        ScnetStft().forward(left, right, spec)
        // torch.stft(center=True): the segment and its zeros, reflected by half a frame at each end.
        fun padded(x: FloatArray, q: Int): Double {
            var j = q - N_FFT / 2
            if (j < 0) j = -j else if (j >= PADDED) j = 2 * (PADDED - 1) - j
            return if (j < SEGMENT) x[j].toDouble() else 0.0
        }
        val row = BINS * FRAMES
        for (t in listOf(0, 1, 2, 237, 474, 475)) {
            for (f in listOf(0, 1, 40, 1000, 2047, 2048)) {
                for ((channel, x) in listOf(left, right).withIndex()) {
                    var re = 0.0
                    var im = 0.0
                    for (k in 0 until N_FFT) {
                        val v = padded(x, t * HOP + k)
                        val a = -2 * PI * ((f.toLong() * k) % N_FFT) / N_FFT
                        re += v * cos(a)
                        im += v * sin(a)
                    }
                    val cell = f * FRAMES + t
                    val gotRe = spec[2 * channel * row + cell].toDouble()
                    val gotIm = spec[(2 * channel + 1) * row + cell].toDouble()
                    val tolerance = 1e-5 * max(1.0, abs(re / 64) + abs(im / 64))
                    assertEquals("re channel $channel t=$t f=$f", re / 64, gotRe, tolerance)
                    assertEquals("im channel $channel t=$t f=$f", im / 64, gotIm, tolerance)
                }
            }
        }
    }

    @Test
    fun `a segment comes back from its own spectrogram`() {
        val (left, right) = segment(2)
        val stft = ScnetStft()
        val spec = FloatArray(Scnet.SPEC_SIZE)
        stft.forward(left, right, spec)
        val backLeft = FloatArray(SEGMENT)
        val backRight = FloatArray(SEGMENT)
        stft.inverse(spec, 0, backLeft, backRight)
        var worst = 0f
        for (p in 0 until SEGMENT) worst = maxOf(worst, abs(backLeft[p] - left[p]), abs(backRight[p] - right[p]))
        // Only the spectrogram's rounding to float32 is lost.
        assertTrue("worst sample off by $worst", worst < 1e-6f)
    }
}
