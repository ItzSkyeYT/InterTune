/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End to end: a 128 BPM song into a 124 BPM one, analysed, planned and rendered as the phone would,
 * has the kicks of both within 10 ms of each other across the overlap. Beats within 10 ms sound
 * tight, beyond 30 ms they are a flam.
 */
class BeatLockTest {

    private val rate = 44_100

    @Test
    fun theKicksOfBothSongsFallTogether() {
        for ((bpmA, bpmB) in listOf(128.0 to 124.0, 124.0 to 128.0, 140.0 to 136.5)) {
            val a = Synth.song(SynthSpec(bpm = bpmA, bars = 48, seed = 11, chords = true))
            val b = Synth.song(SynthSpec(bpm = bpmB, bars = 24, seed = 12))
            val tail = a.slice(a.seconds - 40)
            val head = b.slice(0.0, 30.0)
            val tailAnalysis = RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL)
            val headAnalysis = RegionAnalysis.analyse(head, rate, 2, RegionKind.HEAD)
            val plan = Planner.plan(PlanInput(tailAnalysis, headAnalysis, readMark = 6L * rate, aDurationMs = 200_000, bDurationMs = 200_000))
            println(plan.describe())
            assertEquals(Style.BEAT, plan.style)

            // Both songs as they are heard through the overlap, without the curves and filters, so
            // that each one's kicks can be found: the outgoing one through the time map as
            // rendered, the incoming one from its first frame at bStart.
            val plain = MixPlan(
                Style.BEAT, "plain", rate, 2, plan.tailFrames, plan.bStart, plan.aMap, plan.aFirstProcessed, plan.aGone,
                plan.overlapStart, plan.overlapEnd, Curve.constant(1.0), Curve.constant(1.0), emptyList(), emptyList(),
                Headroom.NONE, DoubleArray(0), 1.0, 1.0, plan.shapedUntil, 0, 0, plan.speed, plan.bars,
            )
            val rendered = MixRender.renderOutgoing(plain, tail, tailAnalysis.guide, tailAnalysis.decimation, plan.anchors)
            val from = plan.overlapStart.toLong()
            val to = plan.overlapEnd.toLong()
            val n = (to - from).toInt()
            val aHeard = ShortArray(n * 2) { rendered.at(from + it / 2, it % 2) }
            val bHeard = ShortArray(n * 2) { i -> head[((from - plan.bStart) * 2 + i).toInt()] }
            val aKicks = kicks(aHeard)
            val bKicks = kicks(bHeard)
            // At every one of the incoming song's true beats through the overlap, the kick each song
            // is heard to play there.
            val overlapInB = (from - plan.bStart).toDouble() / rate
            val gaps = ArrayList<Double>()
            for (beat in b.beats) {
                val t = beat - overlapInB
                if (t < 0.05 || t > n.toDouble() / rate - 0.05) continue
                val kb = bKicks.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) < 0.03 } ?: continue
                val ka = aKicks.minByOrNull { abs(it - kb) }!!
                gaps += abs(ka - kb) * 1000
            }
            println("%.1f into %.1f: %d kicks, %.2f ms apart on average, %.2f ms at most".format(bpmA, bpmB, gaps.size, gaps.average(), gaps.max()))
            assertTrue(gaps.size >= plan.bars * 4 - 2)
            assertTrue(gaps.max() <= 10.0)
        }
    }

    /** The kicks in [samples], in seconds: the peaks of the low onset curve. */
    private fun kicks(samples: ShortArray): List<Double> {
        val spectrum = Spectrum.of(FrontEnd.of(samples, rate, 2))
        val peaks = Peaks.of(spectrum.lowOnset, 1.5f, 8)
        return peaks.map { spectrum.frameToSample(it.frame) / rate }
    }
}
