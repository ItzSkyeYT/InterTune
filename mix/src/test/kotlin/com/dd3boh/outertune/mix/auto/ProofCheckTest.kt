/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The validator on proofs made to order: a clean one passes, and each fault is caught for what it is. */
class ProofCheckTest {

    private val rate = 44_100

    /** A kick (a 55 Hz thump falling fast) on every beat at [bpm], starting [offsetMs] in, mono. */
    private fun kicks(seconds: Double, bpm: Double, offsetMs: Double = 0.0): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val beat = 60.0 / bpm
        var t = offsetMs / 1000.0
        while (t < seconds) {
            val start = (t * rate).toInt()
            for (i in 0 until (0.25 * rate).toInt()) {
                if (start + i >= n) break
                val x = i.toDouble() / rate
                out[start + i] += (0.6 * exp(-x / 0.05) * sin(2 * PI * (55 + 80 * exp(-x / 0.01)) * x)).toFloat()
            }
            t += beat
        }
        return out
    }

    /** A steady stereo bed at about -16 dBFS, [seconds] long. */
    private fun bed(seconds: Double, gain: Double = 0.2): FloatArray {
        val n = (seconds * rate).toInt()
        val random = java.util.Random(1)
        return FloatArray(2 * n) { (random.nextGaussian() * gain).toFloat().coerceIn(-0.9f, 0.9f) }
    }

    private fun proof(mix: FloatArray, lockA: FloatArray? = null, lockB: FloatArray? = null, bpm: Double = 120.0): Proof {
        val frames = mix.size / 2
        val beats = if (lockA != null) {
            val beat = 60.0 / bpm * rate
            DoubleArray((lockA.size / beat).toInt()) { it * beat }
        } else DoubleArray(0)
        return Proof(rate, mix, frames / 3, 2 * frames / 3, lockA, lockB, frames / 3, beats)
    }

    @Test
    fun aCleanChangePasses() {
        val v = ProofCheck.check(proof(bed(30.0), kicks(8.0, 120.0), kicks(8.0, 120.0)))
        println(v.why)
        assertTrue(v.passes)
        assertEquals(0, v.clipped)
        assertEquals(0.0, v.driftMeanMs!!, 1.0)
    }

    @Test
    fun aClippedSampleIsRefused() {
        val mix = bed(30.0)
        mix[mix.size / 2] = 1.2f
        val v = ProofCheck.check(proof(mix))
        assertFalse(v.passes)
        assertEquals(1, v.clipped)
    }

    @Test
    fun kicksThatDriftAreRefusedAndTightOnesPass() {
        val loose = ProofCheck.check(proof(bed(30.0), kicks(8.0, 120.0, offsetMs = 16.0), kicks(8.0, 120.0)))
        println("loose: ${loose.why}, ${loose.driftMeanMs}")
        assertFalse(loose.passes)
        assertTrue(loose.driftMeanMs!! > 10)
        val tight = ProofCheck.check(proof(bed(30.0), kicks(8.0, 120.0, offsetMs = 3.0), kicks(8.0, 120.0)))
        println("tight: ${tight.why}, ${tight.driftMeanMs}")
        assertTrue(tight.passes)
    }

    @Test
    fun aGapInsideTheChangeIsRefused() {
        val mix = bed(30.0)
        val frames = mix.size / 2
        for (f in frames / 2 until frames / 2 + rate / 2) {
            mix[2 * f] = 0f
            mix[2 * f + 1] = 0f
        }
        val v = ProofCheck.check(proof(mix))
        println(v.why)
        assertFalse(v.passes)
        assertTrue(v.longestSilenceMs >= 450)
    }

    @Test
    fun aHoleInTheLevelIsRefusedAndASlopeIsNot() {
        val mix = bed(30.0)
        val frames = mix.size / 2
        // 3 s at a quarter of the level in the middle of the change: 12 dB down.
        for (f in frames / 2 - 3 * rate / 2 until frames / 2 + 3 * rate / 2) {
            mix[2 * f] *= 0.25f
            mix[2 * f + 1] *= 0.25f
        }
        val hole = ProofCheck.check(proof(mix))
        println("hole: ${hole.why}")
        assertFalse(hole.passes)
        assertTrue(hole.dipDb < -6)
        // A song 6 dB louder than the one before it: the level moves, but along the line.
        val slope = bed(30.0, gain = 0.08)
        for (f in frames / 3 until frames) {
            val x = ((f - frames / 3).toDouble() / (frames / 3)).coerceAtMost(1.0)
            val g = Math.pow(10.0, 6 * x / 20).toFloat()
            slope[2 * f] *= g
            slope[2 * f + 1] *= g
        }
        val v = ProofCheck.check(proof(slope))
        println("slope: ${v.why}, dip ${v.dipDb}")
        assertTrue(v.passes)
    }

    @Test
    fun withoutOverlappingBeatsThereIsNoDrift() {
        val v = ProofCheck.check(proof(bed(30.0)))
        assertNull(v.driftMeanMs)
        assertTrue(v.passes)
    }
}
