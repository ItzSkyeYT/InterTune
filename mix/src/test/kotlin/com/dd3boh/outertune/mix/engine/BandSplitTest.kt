/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Lows, mids and highs: they add back to the music, they are phase-true, and no chunk shows. */
class BandSplitTest {

    private val rate = 44_100

    @Test
    fun `the three bands add back to the music`() {
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 8, seed = 3)).samples
        val split = BandSplit(PcmSource(song, 2), 2, rate, 0)
        val x = DoubleArray(2)
        val l = DoubleArray(2)
        val m = DoubleArray(2)
        val h = DoubleArray(2)
        var worst = 0.0
        for (t in 0 until song.size / 2) {
            split.bands(t.toLong(), x, l, m, h)
            for (c in 0 until 2) {
                assertEquals(song[2 * t + c].toDouble(), x[c], 0.0)
                worst = max(worst, abs(l[c] + m[c] + h[c] - x[c]))
            }
        }
        // In 16-bit samples: far under the last bit, and under -90 dB of full scale by 200 dB.
        assertTrue("worst $worst", worst < 1e-9)
    }

    @Test
    fun `each band is in phase with the music, at a Linkwitz-Riley's level`() {
        // At the edges each band is half the music, at 6 dB down, and in phase with it.
        check(hz = 120.0, lows = 0.5, mids = 0.5, highs = 0.0)
        check(hz = 6_000.0, lows = 0.0, mids = 0.5, highs = 0.5)
        // Well inside a band it is all of the music.
        check(hz = 1_000.0, lows = 0.0, mids = 1.0, highs = 0.0, tolerance = 2e-3)
        check(hz = 30.0, lows = 1.0, mids = 0.0, highs = 0.0, tolerance = 5e-3)
        check(hz = 15_000.0, lows = 0.0, mids = 0.0, highs = 1.0, tolerance = 5e-3)
    }

    private fun check(hz: Double, lows: Double, mids: Double, highs: Double, tolerance: Double = 1e-3) {
        val sine = Synth.sine(hz, 3.0, rate, 0.5)
        val split = BandSplit(PcmSource(sine, 2), 2, rate, 0)
        val x = DoubleArray(2)
        val bands = Array(3) { DoubleArray(2) }
        // Each band projected on the music, and what is left of it after that: a band with no phase
        // shift is the music times a number, with nothing left over.
        val dot = DoubleArray(3)
        var power = 0.0
        val residual = DoubleArray(3)
        val want = doubleArrayOf(lows, mids, highs)
        for (t in rate until 2 * rate) {
            split.bands(t.toLong(), x, bands[0], bands[1], bands[2])
            power += x[0] * x[0]
            for (b in 0 until 3) {
                dot[b] += bands[b][0] * x[0]
                val r = bands[b][0] - want[b] * x[0]
                residual[b] += r * r
            }
        }
        for (b in 0 until 3) {
            assertEquals("$hz Hz, band $b", want[b], dot[b] / power, tolerance)
            assertTrue("$hz Hz, band $b off phase by ${residual[b] / power}", residual[b] / power < tolerance * tolerance * 4)
        }
    }

    @Test
    fun `a frame is the same whenever it is first asked for`() {
        val noise = Synth.noise(4.0, rate, seed = 5, rmsDb = -12.0)
        val all = BandSplit(PcmSource(noise, 2), 2, rate, 0)
        val late = BandSplit(PcmSource(noise, 2), 2, rate, 0)
        val again = BandSplit(PcmSource(noise, 2), 2, rate, 0)
        val a = Array(4) { DoubleArray(2) }
        val b = Array(4) { DoubleArray(2) }
        // Frames before the start read once, to step it back afterwards.
        again.bands(100_000, b[0], b[1], b[2], b[3])
        for (t in 0 until noise.size / 2) {
            all.bands(t.toLong(), a[0], a[1], a[2], a[3])
            if (t >= 10_007) {
                late.bands(t.toLong(), b[0], b[1], b[2], b[3])
                for (k in 0 until 4) for (c in 0 until 2) assertEquals(a[k][c], b[k][c], 0.0)
            }
            again.bands(t.toLong(), b[0], b[1], b[2], b[3])
            for (k in 0 until 4) for (c in 0 until 2) assertEquals(a[k][c], b[k][c], 0.0)
        }
    }
}
