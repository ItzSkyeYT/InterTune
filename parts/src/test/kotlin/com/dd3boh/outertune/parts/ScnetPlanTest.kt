/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.SEGMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The segments stemlib cuts and how they add back, with stand-ins for the network. */
class ScnetPlanTest {

    /** A network that hands its input back as one stem and silence as the others. */
    private class AsStem(private val stem: Int) : SpecModel {
        private var last = FloatArray(0)
        override fun run(spec: FloatArray) {
            last = spec.copyOf()
        }

        override fun readStem(stem: Int, into: FloatArray) {
            if (stem == this.stem) last.copyInto(into) else into.fill(0f)
        }
    }

    @Test
    fun `at overlap 1_33 the segments are stemlib's`() {
        // stemlib: step = int(485100 / 1.33), border = chunk - step, padded when longer than 2 borders.
        val plan = ScnetPlan(19 * 44_100, 1.33)
        assertEquals(364_736, plan.step)
        assertEquals(120_364, plan.border)
        assertEquals(48_510, plan.fade)
        assertTrue(plan.padded)
        assertEquals(3, plan.count)
        assertTrue(plan.isLast(2))
        assertFalse(plan.isLast(1))
        // The three windows of parts/reference: the last segment padded by reflection, with zeros, and a stretch too short to pad.
        assertEquals(349_156, plan.length(2))
        val seven = ScnetPlan(324_008, 1.33)
        assertEquals(2, seven.count)
        assertEquals(200_000, seven.length(1))
        val five = ScnetPlan(5 * 44_100, 1.33)
        assertFalse(five.padded)
        assertEquals(1, five.count)
        assertEquals(220_500, five.length(0))
    }

    @Test
    fun `the bench asks for a count of segments and finds where the second one starts`() {
        val frames = ScnetPlan.framesFor(10, 1.33)
        assertEquals(10 * 364_736 - 2 * 120_364, frames)
        assertEquals(10, ScnetPlan(frames, 1.33).count)
        assertEquals(11, ScnetPlan(frames + 1, 1.33).count)
        // A plain slice of the stretch, no padding in it: the PC reference's segment can be put there.
        assertEquals(244_372, ScnetPlan(frames, 1.33).startInStretch(1))
        for (n in 1..12) assertEquals(n, ScnetPlan(ScnetPlan.framesFor(n, 1.33), 1.33).count)
        // At overlap 4 no stretch takes 7 to 12: the border padding starts at 6 and makes it 13.
        assertEquals(6, ScnetPlan(ScnetPlan.framesFor(9, 4.0), 4.0).count)
        assertEquals(13, ScnetPlan(ScnetPlan.framesFor(13, 4.0), 4.0).count)
    }

    @Test
    fun `a stretch comes back whole through a network that changes nothing`() {
        val random = Random(11)
        var turn = 0
        for (overlap in listOf(1.33, 2.0, 4.0)) {
            for (frames in listOf(30_000, 220_500, 324_008, 600_000)) {
                val left = FloatArray(frames) { (0.4 * sin(2 * PI * 220 * it / 44100.0) + random.nextDouble(-0.1, 0.1)).toFloat() }
                val right = FloatArray(frames) { random.nextDouble(-0.5, 0.5).toFloat() }
                // Each stem in turn: the others must stay silent.
                for (stem in listOf(turn++ % 4)) {
                    val parts = ScnetSegment.separate(left, right, overlap, AsStem(stem))
                    var worst = 0f
                    for (p in 0 until frames) {
                        worst = maxOf(worst, abs(parts[2 * stem][p] - left[p]), abs(parts[2 * stem + 1][p] - right[p]))
                        for (other in 0 until 4) if (other != stem) {
                            assertEquals(0f, parts[2 * other][p])
                            assertEquals(0f, parts[2 * other + 1][p])
                        }
                    }
                    assertTrue("overlap $overlap, $frames frames, stem $stem: off by $worst", worst < 1e-6f)
                }
            }
        }
    }

    @Test
    fun `the weights rise and fall over a tenth of a segment, flat at the stretch's two ends`() {
        val plan = ScnetPlan(19 * 44_100, 1.33)
        assertEquals(1f, plan.weight(0, 0))
        assertEquals(0f, plan.weight(1, 0))
        assertEquals(1f, plan.weight(1, plan.fade - 1))
        assertEquals(0f, plan.weight(1, SEGMENT - 1))
        assertEquals(1f, plan.weight(2, SEGMENT - 1))
        assertEquals(0.5f, plan.weight(1, (plan.fade - 1) / 2), 1e-4f)
    }
}
