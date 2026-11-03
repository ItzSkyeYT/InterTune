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
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionJson
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * A loop of B's bars in front of it: B's own bar 1 comes exactly its length later, on B's grid;
 * each pass is the same bars to the sample away from its joins; the joins and its two ends do not
 * click; what of it comes before B's own first frame is in A's part, which the held end plays.
 */
class LoopsTest {

    private val rate = 44_100
    private val a = Synth.song(SynthSpec(bpm = 120.0, bars = 32, seed = 41)).samples
    private val b = Synth.song(SynthSpec(bpm = 120.0, bars = 32, seed = 42)).samples

    private fun every(side: Side, move: Move) = LaneSet.BANDS.lanes.associate { LaneId(side, it) to move }

    private fun spec(loop: IntroLoop?) = TransitionSpec(
        a = Anchor("song-a", 40_000.0, 120.0), b = Anchor("song-b", 0.0, 120.0), lanes = LaneSet.BANDS,
        bars = 8, beatMatch = false, preset = Preset.CUSTOM,
        moves = every(Side.A, Move(4.0, 4.0, How.FADE)) + every(Side.B, Move(0.0, 0.0, How.FROM_START)),
        introLoop = loop,
    )

    private fun facts() = ChangeFacts(rate, 2, 40.0 * rate, (a.size / 2).toLong(), 0.0, (b.size / 2).toLong())

    private fun planned(spec: TransitionSpec): LanePlan = when (val r = SpecPlan.plan(spec, facts())) {
        is SpecPlan.Result.Planned -> r.plan
        is SpecPlan.Result.Refused -> throw AssertionError("refused: ${r.why}")
    }

    @Test
    fun `B's own bar 1 comes exactly the loop's length later`() {
        val plain = planned(spec(null))
        val looped = planned(spec(IntroLoop(1, 0.0, 4)))
        val bar = 2.0 * rate
        assertEquals(plain.bStart + 4 * bar, looped.bStart.toDouble(), 1.0)
        val l = checkNotNull(looped.loop)
        assertEquals(plain.bStart, l.outFrom)
        assertEquals(4 * bar, (l.outTo - l.outFrom).toDouble(), 1.0)
        assertEquals(listOf(1, 2, 3).map { l.outFrom + it * l.frames }, l.joins().toList())
    }

    @Test
    fun `each pass is the same bars, and the joins do not click`() {
        val plan = planned(spec(IntroLoop(1, 2_000.0, 4)))
        val l = plan.loop!!
        val src = LoopedSource(b, 2, (b.size / 2).toLong(), plan.bStart, l)
        val x = DoubleArray(2)
        val y = DoubleArray(2)
        // Away from the joins, pass k plays the same frames as the first pass.
        for (m in (l.seam + 10) until (l.frames - l.seam - 10) step 97) {
            src.frame(l.outFrom + m, x)
            for (k in 1 until 4) {
                src.frame(l.outFrom + k * l.frames + m, y)
                for (c in 0 until 2) assertEquals("pass $k, frame $m", x[c], y[c], 1e-9)
            }
        }
        // At every join the sample to sample step is no bigger than inside the bar.
        var inside = 0.0
        var prev = DoubleArray(2)
        src.frame(l.outFrom + l.seam, prev)
        for (m in (l.seam + 1) until (l.frames - l.seam)) {
            src.frame(l.outFrom + m, x)
            for (c in 0 until 2) inside = max(inside, abs(x[c] - prev[c]))
            prev = x.copyOf()
        }
        for (j in l.joins()) {
            var worst = 0.0
            src.frame(j - l.seam - 1, prev)
            for (t in (j - l.seam) until (j + l.seam)) {
                src.frame(t, x)
                for (c in 0 until 2) worst = max(worst, abs(x[c] - prev[c]))
                prev = x.copyOf()
            }
            assertTrue("join at $j: step $worst against $inside inside", worst <= inside * 1.05 + 1.0)
        }
    }

    @Test
    fun `the loop before B's own first frame is in A's part`() {
        val plan = planned(spec(IntroLoop(2, 0.0, 2)))
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        assertTrue(render.outgoing.endFrame >= plan.bStart)
        val l = plan.loop!!
        // B's loop under A from bar 1: the outgoing part there is A and B together, louder than A.
        var both = 0.0
        var aAlone = 0.0
        for (o in (l.outFrom + rate / 2) until (l.outFrom + rate)) {
            both += abs(render.outgoing.at(o, 0).toDouble())
            aAlone += abs(a[(o * 2).toInt()].toDouble())
        }
        assertTrue("both $both, A alone $aAlone", both > aAlone * 1.2)
        // The stage takes it: the held plan's B starts where the incoming part does.
        val held = render.heldPlan()
        assertEquals(plan.bStart, held.bStart)
        assertEquals(render.incoming.firstFrame, held.bStart)
        assertTrue(held.aGone >= held.bStart)
    }

    @Test
    fun `A's loop plays its bars again, then A goes on from the end of them`() {
        val loop = com.dd3boh.outertune.mix.edit.OutroLoop(1, 40_000.0, 2)
        val plan = planned(spec(null).copy(outroLoop = loop))
        val o = checkNotNull(plan.outro)
        val bar = 2.0 * rate
        assertEquals(40.0 * rate, o.srcFrom.toDouble(), 1.0)
        assertEquals(bar, o.frames.toDouble(), 1.0)
        // B's anchor still lands on A's: A's bar 1 is the loop's first bar, before the passes.
        val plain = planned(spec(null))
        assertEquals(plain.bStart, plan.bStart)
        // A as heard, unstretched: the looped bar, twice more, joined as B's loop is, then A from 42 s.
        val heard = o.extend(a, 2, (a.size / 2).toLong())
        val end = o.end.toInt()
        for (k in 0 until 2) for (m in (o.seam + 5) until o.frames.toInt() step 113) {
            assertEquals("pass ${k + 1}, frame $m", a[(o.srcFrom.toInt() + m) * 2], heard[(end + k * o.frames.toInt() + m) * 2])
        }
        for (m in 0 until rate step 97) assertEquals(a[(end + m) * 2], heard[(end + 2 * o.frames.toInt() + m) * 2])
        // The held end is not gone back to past the first join, which it no longer matches.
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        assertTrue(render.heldPlan().alignedUntil <= end)
        assertEquals((a.size / 2).toLong(), render.heldPlan().tailFrames)
    }

    @Test
    fun `a loop is kept and read back`() {
        val s = spec(IntroLoop(4, 1_500.0, 2)).copy(outroLoop = com.dd3boh.outertune.mix.edit.OutroLoop(2, 38_000.0, 3))
        val back = TransitionJson.decode(TransitionJson.encode(s))
        assertEquals(s.introLoop, back?.introLoop)
        assertEquals(s.outroLoop, back?.outroLoop)
        assertTrue(spec(IntroLoop(3, 0.0, 1)).problems().any { it.contains("loop of 3 bars") })
    }
}
