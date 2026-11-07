/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.OutroLoop
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.toPcm16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loops and the intro twice with the next song adjusted. B's loop plays in front of it as with the
 * song before adjusted, its first pass on bar 1 and B's own bar 1 as many bars later, but at the
 * song before's tempo like the rest of B while both are heard; B's own samples take over after it
 * and after B's way back to its own tempo, where the song as it plays goes on. A's loop plays A's
 * bars again at A's own speed, B landing on A as without it.
 */
class LoopsBAdjustedTest {

    private val rate = 48_000
    private val songA by lazy { Synth.song(SynthSpec(bpm = 128.0, bars = 48, seed = 61, sampleRate = rate)).samples }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 62, sampleRate = rate)).samples }

    /** A at 128, B at 124 with its bar 1 at 4 s: adjusted, B plays 3.2% faster till A is gone. */
    private fun spec(side: Side = Side.B, loop: IntroLoop? = null, twice: Int = 0, outro: OutroLoop? = null, bars: Int = 8) = TransitionSpec(
        a = Anchor("a", 60_000.0, 128.0), b = Anchor("b", 4_000.0, 124.0), lanes = LaneSet.BANDS, bars = bars, beatMatch = true,
        preset = Preset.CUSTOM,
        moves = mapOf(
            LaneId(Side.A, Lane.LOWS) to Move(bars / 2.0, 0.0, How.CUT),
            LaneId(Side.A, Lane.MIDS) to Move(bars.toDouble(), 0.0, How.CUT),
            LaneId(Side.A, Lane.HIGHS) to Move(bars.toDouble(), 0.0, How.CUT),
            LaneId(Side.B, Lane.LOWS) to Move(bars / 2.0, 0.0, How.CUT),
            LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
            LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
        ),
        introLoop = loop, introTwice = twice, outroLoop = outro, matchSide = side,
    )

    private fun facts(spec: TransitionSpec) = ChangeFacts(rate, 2, 60.0 * rate, (songA.size / 2).toLong(), spec.b.atMs * rate / 1000, (songB.size / 2).toLong())

    private fun planned(spec: TransitionSpec): LanePlan = when (val r = SpecPlan.plan(spec, facts(spec))) {
        is SpecPlan.Result.Planned -> r.plan
        is SpecPlan.Result.Refused -> throw AssertionError("refused: ${r.why}")
    }

    private val bBar = 4 * 60.0 / 124 * rate

    @Test
    fun `B's loop plays at the song before's tempo, then B's own bar 1, then B's own samples where the song as it plays goes on`() {
        // B's bar 1 twice (as long as the change's first two bars), a 2-bar loop from its bar 3 four
        // times, and a loop longer than the change, which A leaves while it still plays.
        for (loop in listOf(IntroLoop(1, 4_000.0, 2), IntroLoop(2, 4_000.0 + 2 * bBar * 1000 / rate, 4), IntroLoop(4, 4_000.0, 3))) {
            val spec = spec(loop = loop)
            val plan = planned(spec)
            val s = plan.bStretch!!
            val l = s.loop!!
            assertNull(plan.loop)
            println("${plan.describe()}; heard from ${s.heardFrom}, loop of B's ${l.srcFrom}+${l.frames} x${l.times} in place of ${l.outFrom} to ${l.outTo}, own from ${s.switchAt} at ${s.switchFrame}, live from ${plan.bStart}")
            val loopBars = (loop.bars * loop.times).toDouble()
            // A plays as it is.
            assertTrue(plan.aMap.isIdentity)
            // The loop's first pass on bar 1, its passes and then B's own bars on the change's bars
            // while A is heard: B as played, at A's tempo.
            assertEquals(l.outFrom.toDouble(), s.map.tailAt(plan.out(0.0)), 1.0)
            val bar1At = plan.facts.bBar1 - (l.outTo - l.outFrom)
            val gone = 8.0
            for (u in listOf(0.0, 0.5, 1.0, 2.0, 4.0, 7.0, gone)) {
                assertEquals("bar $u", bar1At + u * bBar, s.map.tailAt(plan.out(u)), 1.5)
            }
            // B's own bar 1 the loop's length after bar 1, where A is still heard: each pass is a
            // whole number of frames, so within half a frame a pass, as with A adjusted.
            if (loopBars <= gone) assertEquals(plan.facts.bBar1, s.map.tailAt(plan.out(loopBars)), 1.5 + 0.5 * loop.times)
            // Its own samples take over after the loop's last join, on B's own bar line, at its own speed.
            assertTrue(s.switchFrame >= l.outTo + l.seam)
            val k = (s.switchFrame - plan.facts.bBar1) / bBar
            assertEquals(Math.round(k).toDouble(), k, 1.0 / bBar)
            assertEquals(1.0, s.map.speedAt(s.switchAt.toDouble()), 1e-12)
            assertTrue(s.switchAt > plan.aGone)
            // The song as it plays: from its first frame (a loop has no later entry), one for one,
            // B's own frame where its samples take over.
            assertEquals(0L, plan.bEntry)
            assertEquals(0L, SpecPlan.liveEntryFrames(spec, rate))
            assertEquals(s.switchFrame, s.switchAt - plan.bStart)
            val render = LaneMixer(plan).render(songA, songB, 1.0, 1.0)
            var compared = 0
            for (o in (s.switchAt + s.switchFrames) until plan.mixTo) for (c in 0 until 2) {
                assertEquals("frame $o", songB[((o - plan.bOrigin) * 2 + c).toInt()], render.incoming.at(o, c))
                compared++
            }
            assertTrue(compared > 0)
            val after = render.mix(songA, songB, plan.mixTo, plan.mixTo + 50)
            for (i in 0 until 50) assertEquals(songB[((plan.mixTo + i - plan.bOrigin) * 2).toInt()], after[i * 2])
            // Given up mid-blend, B's part plays on to its own samples, past the loop.
            assertEquals(s.switchAt + s.switchFrames, render.heldPlan().incomingOwnFrom)
            // What the editor and the copy make sure is read of B covers what the change reads.
            val reads = maxOf(s.switchFrame + (plan.mixTo - s.switchAt), l.srcFrom + l.frames + l.seam)
            assertTrue(SpecPlan.bReadToMs(spec)!! * rate / 1000 >= reads)
            assertEquals(reads * 1000.0 / rate, SpecPlan.bReadToMs(spec)!!, 1.5)
            // The end is held from before the change's first frame, and the change runs to where B's own samples have taken over.
            val firstMs = spec.a.atMs + (plan.mixFrom - plan.bar1) * 1000.0 / rate
            assertTrue("${SpecPlan.firstChangeMs(spec)} after $firstMs", SpecPlan.firstChangeMs(spec) <= firstMs + 0.5)
            assertTrue(SpecPlan.firstChangeMs(spec) >= firstMs - Engine.HEADROOM_PAD_S * 1000 - 1.0)
            val endMs = spec.a.atMs + (s.switchAt + 2 * s.switchFrames - plan.bar1) * 1000.0 / rate
            assertEquals(endMs, SpecPlan.bAdjustedEndMs(spec)!!, 1.0)
        }
    }

    @Test
    fun `B as played is its loop's bars over and over in place of the frames before its bar 1, joined as with A adjusted`() {
        val loop = IntroLoop(2, 4_000.0 + 2 * bBar * 1000 / rate, 3)
        val plan = planned(spec(loop = loop))
        val s = plan.bStretch!!
        val l = s.loop!!
        val (music, lo) = LaneMixer(plan).asPlayed(songB, (songB.size / 2).toLong(), s, l)
        fun at(v: Long, c: Int) = music[((v - lo) * 2 + c).toInt()]
        // Before the loop and after its last join, B's own frames; each pass, away from its joins, B's looped bars.
        for (v in maxOf(lo, 0L) until l.outFrom step 7) for (c in 0 until 2) assertEquals(songB[(v * 2 + c).toInt()], at(v, c))
        for (k in 0 until l.times) for (m in l.seam.toLong() until l.frames - l.seam step 13) for (c in 0 until 2) {
            assertEquals("pass $k, frame $m", songB[((l.srcFrom + m) * 2 + c).toInt()], at(l.outFrom + k * l.frames + m, c))
        }
        for (v in (l.outTo + l.seam) until s.switchFrame step 11) for (c in 0 until 2) assertEquals(songB[(v * 2 + c).toInt()], at(v, c))
        // The same joins as the song before adjusted plays.
        val looped = LoopedSource(songB, 2, (songB.size / 2).toLong(), 0L, l, rate)
        val x = DoubleArray(2)
        for (j in l.joins().toList() + l.outTo) for (v in (j - l.seam) until (j + l.seam)) {
            looped.frame(v, x)
            for (c in 0 until 2) assertEquals(toPcm16(x[c]), at(v, c))
        }
    }

    @Test
    fun `each pass of B's loop sounds as the first, at the song before's tempo`() {
        val loop = IntroLoop(1, 4_000.0, 3)
        val plan = planned(spec(loop = loop))
        val source = LaneMixer(plan).incomingSource(songB)
        fun bar(u: Double): DoubleArray {
            val from = Math.round(plan.out(u)) + rate / 50
            val to = Math.round(plan.out(u + 1)) - rate / 50
            val x = DoubleArray(2)
            return DoubleArray((to - from).toInt()) { i -> source.frame(from + i, x); x[0] + x[1] }
        }
        fun similar(p: DoubleArray, q: DoubleArray): Double {
            val n = minOf(p.size, q.size)
            var pq = 0.0
            var pp = 0.0
            var qq = 0.0
            for (i in 0 until n) {
                pq += p[i] * q[i]
                pp += p[i] * p[i]
                qq += q[i] * q[i]
            }
            return pq / sqrt(pp * qq)
        }
        val first = bar(0.0)
        val passes = listOf(1.0, 2.0).map { similar(first, bar(it)) }
        // B's own bar 2 after the loop: another chord and bass, so the passes are not alike by chance.
        val next = similar(bar(3.0), bar(4.0))
        println("passes against the first: $passes; two of B's own bars: $next")
        for (p in passes) assertTrue("$p", p > 0.95)
        assertTrue("$next", next < passes.min() - 0.1)
        // Each pass lasts a bar of the change, A's bar.
        assertEquals(4 * 60.0 / 128 * rate, plan.barFrames, 1e-6)
    }

    @Test
    fun `the intro twice with B adjusted is a loop of B's first bars played once`() {
        val twice = planned(spec(twice = 2))
        val loop = planned(spec(loop = IntroLoop(2, 4_000.0, 1)))
        assertEquals(loop.bStart, twice.bStart)
        assertEquals(loop.mixFrom, twice.mixFrom)
        assertEquals(loop.mixTo, twice.mixTo)
        assertEquals(loop.bStretch!!.switchAt, twice.bStretch!!.switchAt)
        val r1 = LaneMixer(twice).render(songA, songB, 1.0, 1.0)
        val r2 = LaneMixer(loop).render(songA, songB, 1.0, 1.0)
        val a = r1.mix(songA, songB, twice.mixFrom, twice.mixTo)
        val b = r2.mix(songA, songB, loop.mixFrom, loop.mixTo)
        assertTrue(a.contentEquals(b))
        // As the song before adjusted takes it.
        assertNotNull(planned(spec(side = Side.A, twice = 2)).loop)
    }

    @Test
    fun `A's loop with B adjusted plays A's bars again at its own speed, and B lands on A as without it`() {
        val outro = OutroLoop(1, 60_000.0, 2)
        val plain = planned(spec())
        val plan = planned(spec(outro = outro))
        val o = plan.outro!!
        assertTrue(plan.aMap.isIdentity)
        // A as heard: its bar, twice more, then A on from the end of it, the output's own frames.
        val heard = o.extend(songA, 2, (songA.size / 2).toLong(), rate)
        val source = LaneMixer(plan).outgoingSource(songA)
        val x = DoubleArray(2)
        for (f in plan.mixFrom until plan.aGone step 17) {
            source.frame(f, x)
            for (c in 0 until 2) assertEquals("frame $f", heard[(f * 2 + c).toInt()].toDouble(), x[c], 0.0)
        }
        // B as without A's loop: the same bar 1, the same stretch, the same frame its own samples take over on.
        assertEquals(plain.bar1, plan.bar1, 0.0)
        assertEquals(plain.bStretch!!.switchAt, plan.bStretch!!.switchAt)
        assertEquals(plain.bStretch!!.switchFrame, plan.bStretch!!.switchFrame)
        assertEquals(plain.bStart, plan.bStart)
        // The held end is not gone back to past the loop's first join, which it no longer matches.
        val render = LaneMixer(plan).render(songA, songB, 1.0, 1.0)
        assertTrue(render.heldPlan().alignedUntil <= o.end)
        assertTrue(plan.mixFrom <= o.end - o.seam)
        // Before the change, A as it is under the shared headroom.
        for (f in render.outgoing.firstFrame until minOf(plan.bStretch!!.heardFrom, o.end - o.seam)) for (c in 0 until 2) {
            assertEquals("frame $f", toPcm16(songA[(f * 2 + c).toInt()] * render.headroom.at(f)), render.outgoing.at(f, c))
        }
        // Both loops at once.
        val both = planned(spec(outro = outro, loop = IntroLoop(1, 4_000.0, 2)))
        assertNotNull(both.outro)
        assertNotNull(both.bStretch!!.loop)
    }

    @Test
    fun `A's loop before bar 1 is held from before its first join, which is the change's first frame`() {
        // B in on bar 1 from its first frame, A's lows cut on bar 4: nothing moves before A's loop.
        val aBar = 4 * 60_000.0 / 128
        val spec = spec(outro = OutroLoop(1, 60_000.0 - 4 * aBar, 2)).copy(b = Anchor("b", 0.0, 124.0))
        val plan = planned(spec)
        val o = plan.outro!!
        assertEquals((o.end - o.seam - Engine.HEADROOM_PAD_S * rate).toDouble(), plan.mixFrom.toDouble(), 1.0)
        val firstMs = spec.a.atMs + (plan.mixFrom - plan.facts.aBar1) * 1000.0 / rate
        assertTrue("${SpecPlan.firstChangeMs(spec)} after $firstMs", SpecPlan.firstChangeMs(spec) <= firstMs + 0.5)
        // A's bar 1 comes the loop's passes later in what is heard: B lands on it there.
        assertEquals(plan.facts.aBar1 + o.shift, plan.bar1, 1e-9)
    }

    @Test
    fun `a loop with a later entry is refused whichever song is adjusted`() {
        for (side in listOf(Side.A, Side.B)) {
            val s = spec(side = side, loop = IntroLoop(1, 8_000.0, 2)).copy(b = Anchor("b", 8_000.0, 124.0), bStartMs = 8_000.0)
            val r = SpecPlan.plan(s, facts(s))
            assertTrue("$side", r is SpecPlan.Result.Refused && r.why.contains("a loop in front of a next song that comes in after its first frame"))
        }
    }

    @Test
    fun `B adjusted without a loop plays as before`() {
        val plan = planned(spec())
        val s = plan.bStretch!!
        assertNull(s.loop)
        // Heard from B's first frame, its bar 1 on the change's.
        assertEquals(0.0, s.map.t0, 0.0)
        assertEquals(plan.facts.bBar1, s.map.tailAt(plan.bar1), 1.0)
        assertTrue(abs(s.heardFrom - (plan.bar1 - plan.facts.bBar1 * 124.0 / 128.0)) < 1.0)
    }
}
