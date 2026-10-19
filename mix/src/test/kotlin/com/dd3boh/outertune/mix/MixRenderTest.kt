/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.random.Random

/**
 * The renderer: block sizes that change nothing, curves that keep the level, a sum that stays
 * under full scale, gains that never step, and a rendered part that joins the raw tail exactly.
 */
class MixRenderTest {

    private val rate = 44_100

    /** A planned transition with both songs' audio, ready to render. */
    class Pair(val plan: MixPlan, val tail: ShortArray, val tailAnalysis: RegionAnalysis, val head: ShortArray) {
        val channels get() = plan.channels

        fun renderA(): RenderedOutgoing = MixRender.renderOutgoing(plan, tail, tailAnalysis.guide, tailAnalysis.decimation, if (plan.style == Style.BEAT) tailAnalysis.beatFrames() else DoubleArray(0))

        /** The transition from the incoming song's first frame to the end of its shaping, fed in blocks of the sizes [blocks] gives. */
        fun mixFromB(a: RenderedOutgoing, blocks: () -> Int): ShortArray {
            val total = (plan.shapedUntil - plan.bStart).toInt().coerceAtMost(head.size / channels)
            val shaper = IncomingShaper(plan, a.headroom)
            val mixer = Mixer()
            val out = ShortArray(total * channels)
            val shaped = DoubleArray(4096 * channels)
            var at = 0
            while (at < total) {
                val n = minOf(blocks(), total - at, 4096)
                shaper.shape(head, at, n, at.toLong(), shaped)
                mixer.mix(a, plan.bStart + at, shaped, 0, n, out, at)
                at += n
            }
            clipped = mixer.clipped
            return out
        }

        var clipped = 0L
    }

    private fun pair(tail: ShortArray, head: ShortArray, attA: Double = 1.0, attB: Double = 1.0, readSeconds: Double = 6.0): Pair {
        val tailAnalysis = RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL)
        val headAnalysis = RegionAnalysis.analyse(head, rate, 2, RegionKind.HEAD)
        val plan = Planner.plan(
            PlanInput(
                tail = tailAnalysis, head = headAnalysis, readMark = (readSeconds * rate).toLong(),
                attenuationA = attA, attenuationB = attB, aDurationMs = 200_000, bDurationMs = 200_000,
            ),
        )
        return Pair(plan, tail, tailAnalysis, head)
    }

    private val beatPair by lazy {
        val a = Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1))
        val b = Synth.song(SynthSpec(bpm = 124.0, bars = 24, seed = 2))
        pair(a.slice(a.seconds - 40), b.slice(0.0, 30.0), attA = 0.83, attB = 0.91)
    }

    @Test
    fun blockSizesChangeNothing() {
        val p = beatPair
        assertEquals(Style.BEAT, p.plan.style)
        val a = p.renderA()
        val whole = p.mixFromB(a) { 1 shl 20 }
        val random = Random(17)
        val pieces = p.mixFromB(a) { 1 + random.nextInt(4096) }
        val ones = p.mixFromB(a) { 1 }
        assertArrayEquals(whole, pieces)
        assertArrayEquals(whole, ones)
    }

    @Test
    fun theRenderedPartJoinsTheRawTailExactly() {
        for (style in listOf(Style.BEAT, Style.SMOOTH, Style.BLEND, Style.CUT)) {
            val plan = Planner.planStyle(PlanInput(
                tail = beatPair.tailAnalysis, head = RegionAnalysis.analyse(beatPair.head, rate, 2, RegionKind.HEAD),
                readMark = 6L * rate, attenuationA = 0.83, attenuationB = 0.91, aDurationMs = 200_000, bDurationMs = 200_000,
            ), style)!!
            val q = Pair(plan, beatPair.tail, beatPair.tailAnalysis, beatPair.head)
            val a = q.renderA()
            // Where its rendered part starts, nothing has moved yet: it is the raw tail at its own
            // attenuation, rounded the way the raw path rounds it, so the two meet without a seam.
            for (i in 0 until 1000) for (c in 0 until 2) {
                val o = a.firstFrame + i
                val raw = attenuate(q.tail[(o * 2 + c).toInt()], plan.attenuationA)
                assertEquals("$style frame $o channel $c", raw, a.at(o, c))
            }
        }
    }

    @Test
    fun beforeTheRampTheStretcherCopies() {
        val p = beatPair
        val wsola = Wsola(p.tail, 2, rate, p.tailAnalysis.guide, p.tailAnalysis.decimation)
        val untilRamp = p.plan.aMap.rampStart.toLong() - Wsola.FRAME
        val out = wsola.render(p.plan.aMap, untilRamp - 5000, untilRamp)
        for (i in 0 until 5000) for (c in 0 until 2) {
            assertEquals(p.tail[((untilRamp - 5000 + i) * 2 + c).toInt()].toDouble(), out[i * 2 + c], 0.0)
        }
    }

    @Test
    fun equalPowerKeepsTheLevelFlat() {
        val p = pair(Synth.noise(40.0, seed = 3, rmsDb = -24.0), Synth.noise(30.0, seed = 4, rmsDb = -24.0))
        assertTrue(p.plan.style == Style.BLEND || p.plan.style == Style.SMOOTH)
        val plan = if (p.plan.style == Style.BLEND) p.plan else Planner.planStyle(PlanInput(
            tail = p.tailAnalysis, head = RegionAnalysis.analyse(p.head, rate, 2, RegionKind.HEAD), readMark = 6L * rate,
            lastStyles = listOf(Style.SMOOTH), aDurationMs = 200_000, bDurationMs = 200_000,
        ), Style.BLEND)!!
        val q = Pair(plan, p.tail, p.tailAnalysis, p.head)
        // The curves themselves: power adds to one on every frame.
        var o = plan.overlapStart
        while (o < plan.overlapEnd) {
            val gA = plan.gainA.at(o)
            val gB = plan.gainB.at(o)
            assertEquals(1.0, gA * gA + gB * gB, 1e-9)
            o += 97.0
        }
        // And the noise through them, in half-second windows.
        val out = q.mixFromB(q.renderA()) { 1 shl 20 }
        val start = (plan.overlapStart - plan.bStart).toInt()
        val end = (plan.overlapEnd - plan.bStart).toInt()
        val window = rate / 2
        val levels = ArrayList<Double>()
        var w = start
        while (w + window <= end) {
            var s = 0.0
            for (i in w until w + window) for (c in 0 until 2) s += out[i * 2 + c].toDouble() * out[i * 2 + c]
            levels += 10 * log10(s / (2 * window) / (32768.0 * 32768.0))
            w += window
        }
        println("levels through the blend: " + levels.joinToString(" ") { "%.2f".format(it) })
        assertTrue(levels.max() - levels.min() < 0.4)
        levels.forEach { assertEquals(-24.0, it, 0.2) }
    }

    @Test
    fun twoLoudMastersStayUnderFullScale() {
        // Noise scaled so each song peaks at -0.1 dBFS on its own.
        fun loud(seed: Long, seconds: Double): ShortArray {
            val x = Synth.noise(seconds, seed = seed, rmsDb = -12.0)
            val peak = x.maxOf { abs(it.toInt()) }
            val g = 32767 * 0.9886 / peak
            return ShortArray(x.size) { (x[it] * g).toInt().toShort() }
        }
        for (styles in listOf(emptyList(), listOf(Style.SMOOTH))) {
            val tailAnalysis = RegionAnalysis.analyse(loud(5, 40.0), rate, 2, RegionKind.TAIL)
            val head = loud(6, 30.0)
            val plan = Planner.plan(PlanInput(
                tail = tailAnalysis, head = RegionAnalysis.analyse(head, rate, 2, RegionKind.HEAD), readMark = 6L * rate,
                lastStyles = styles, aDurationMs = 200_000, bDurationMs = 200_000,
            ))
            val q = Pair(plan, loud(5, 40.0), tailAnalysis, head)
            val out = q.mixFromB(q.renderA()) { 1 shl 20 }
            val start = (plan.overlapStart - plan.bStart).toInt()
            val end = (plan.overlapEnd - plan.bStart).toInt()
            var peak = 0
            for (i in start * 2 until end * 2) peak = max(peak, abs(out[i].toInt()))
            println("%s: peak %.4f of full scale in the overlap, headroom down to %.2f dB, %d clipped".format(plan.style, peak / 32768.0, plan.headroom.deepestDb, q.clipped))
            assertTrue(peak <= 0.95 * 32768)
            assertEquals(0L, q.clipped)
        }
    }

    @Test
    fun gainsNeverStep() {
        val plans = listOf(beatPair.plan) + listOf(Style.BLEND, Style.SMOOTH, Style.CUT).mapNotNull { style ->
            Planner.planStyle(PlanInput(
                tail = beatPair.tailAnalysis, head = RegionAnalysis.analyse(beatPair.head, rate, 2, RegionKind.HEAD),
                readMark = 6L * rate, aDurationMs = 200_000, bDurationMs = 200_000,
            ), style)
        }
        assertEquals(4, plans.size)
        for (plan in plans) {
            var worst = 0.0
            var worstRoom = 0.0
            var o = plan.aFirstProcessed
            var lastA = plan.gainA.at(o.toDouble())
            var lastB = plan.gainB.at(o.toDouble())
            val fiveMs = (0.005 * rate).toLong()
            while (o < plan.shapedUntil) {
                o++
                val gA = plan.gainA.at(o.toDouble())
                val gB = plan.gainB.at(o.toDouble())
                worst = max(worst, max(abs(gA - lastA), abs(gB - lastB)))
                lastA = gA
                lastB = gB
                if (o % fiveMs == 0L) {
                    val room = abs(20 * log10(plan.headroom.at(o) / plan.headroom.at(o - fiveMs)))
                    worstRoom = max(worstRoom, room)
                }
            }
            println("%s: largest gain step %.6f a frame, headroom moves at most %.3f dB in 5 ms".format(plan.style, worst, worstRoom))
            assertTrue(worst < 1e-3)
            assertTrue(worstRoom <= 0.1)
        }
    }

    @Test
    fun aGaplessPlanLeavesBothSongsAlone() {
        val p = beatPair
        val gapless = MixPlan.gapless(rate, 2, p.tailAnalysis.frames, 0.83, 0.91, "test")
        val a = MixRender.renderOutgoing(gapless, p.tail, p.tailAnalysis.guide, p.tailAnalysis.decimation)
        assertEquals(0, a.frames)
        val out = DoubleArray(1000 * 2)
        IncomingShaper(gapless, a.headroom).shape(p.head, 0, 1000, 0, out)
        for (i in 0 until 2000) assertEquals(p.head[i] * 0.91, out[i], 1e-9)
    }

    @Test
    fun theLockFindsAnOffsetAndAStrangerDoesNotMatch() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 4, seed = 9))
        val expected = Spectrum.of(FrontEnd.of(song.slice(0.0, 6.0), rate, 2))
        val late = Spectrum.of(FrontEnd.of(ShortArray((0.012 * rate).toInt() * 2) + song.slice(0.0, 6.0), rate, 2))
        val r = Lock.measure(expected.onset, late.onset, expected.frameRate)
        println("lock: %.2f ms, match %.2f".format(r.offsetMs, r.match))
        assertEquals(12.0, r.offsetMs, 3.0)
        assertTrue(r.match > 0.8)
        val other = Spectrum.of(FrontEnd.of(Synth.noise(6.0, seed = 3), rate, 2))
        assertTrue(Lock.measure(expected.onset, other.onset, expected.frameRate).match < 0.5)
    }
}
