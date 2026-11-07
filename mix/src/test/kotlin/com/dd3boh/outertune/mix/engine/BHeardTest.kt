/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The next song drawn where it is heard: on the change's bars while it keeps them, and with the
 * next song adjusted, through its way back to its own tempo after the song before is gone and at
 * its own tempo after that, as the plan plays it. Drawn on the change's bars through the glide, it
 * was off by up to the tempo difference.
 */
class BHeardTest {

    private val rate = 48_000

    /** A at 128, B at 124 with its bar 1 at 4 s, 8 bars, A gone on bar 8. */
    private fun spec(side: Side, loop: IntroLoop? = null) = TransitionSpec(
        a = Anchor("a", 60_000.0, 128.0), b = Anchor("b", 4_000.0, 124.0), lanes = LaneSet.BANDS, bars = 8, beatMatch = true,
        preset = Preset.CUSTOM,
        moves = mapOf(
            LaneId(Side.A, Lane.LOWS) to Move(4.0, 0.0, How.CUT),
            LaneId(Side.A, Lane.MIDS) to Move(8.0, 0.0, How.CUT),
            LaneId(Side.A, Lane.HIGHS) to Move(8.0, 0.0, How.CUT),
            LaneId(Side.B, Lane.LOWS) to Move(4.0, 0.0, How.CUT),
            LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
            LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
        ),
        introLoop = loop, matchSide = side,
    )

    private fun plan(spec: TransitionSpec): LanePlan {
        val facts = ChangeFacts(rate, 2, 60.0 * rate, 120L * rate, spec.b.atMs * rate / 1000, 60L * rate)
        return (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
    }

    @Test
    fun `where B keeps the change's bars, it is drawn on them`() {
        for (loop in listOf(null, IntroLoop(1, 4_000.0, 2))) {
            val s = spec(Side.A, loop)
            val h = BHeard(s)
            for (u in listOf(-2.0, 0.0, 0.5, 1.5, 3.0, 8.0, 10.0)) assertEquals(s.bMsAt(u), h.msAt(u), 0.0)
            // B's own bar 1 after its loop, on the bar the loop's length on.
            assertEquals(s.loopBars, h.uAt(s.b.atMs), 1e-9)
        }
    }

    @Test
    fun `with B adjusted it is drawn where the plan plays it, its way back to its own tempo included`() {
        for (loop in listOf(null, IntroLoop(1, 4_000.0, 2), IntroLoop(4, 4_000.0, 3))) {
            val s = spec(Side.B, loop)
            val p = plan(s)
            val st = p.bStretch!!
            val h = BHeard(s)
            var worstBefore = 0.0
            // Between bar lines, where a loop's pass or B's own bar 1 does not start on the very frame.
            var u = -0.875
            while (u <= 12.0) {
                // Where the plan plays B at this bar: its map's frame, in its loop the loop's own bars.
                val v = st.map.tailAt(p.out(u))
                val l = st.loop
                val frame = if (l != null && v >= l.outFrom && v < l.outTo) l.srcFrom + (v - l.outFrom) % l.frames else v
                if (frame >= 0) {
                    assertEquals("bar $u", frame * 1000.0 / rate, h.msAt(u), 0.1)
                    worstBefore = maxOf(worstBefore, abs(s.bMsAt(u) - frame * 1000.0 / rate))
                }
                u += 0.25
            }
            println("loop $loop: drawn on the change's bars, B was up to %.0f ms off where it plays; the glide from bar %.2f to %.2f".format(worstBefore, h.glideFromU, h.glideToU))
            assertTrue(worstBefore > 50.0)
            // B's own bar lines after the glide, where they are heard.
            val bar = s.b.barMs
            for (k in 1..4) {
                val ms = st.switchFrame * 1000.0 / rate + k * bar
                assertEquals(st.map.outAt(ms * rate / 1000.0), p.out(h.uAt(ms)), 1.0)
            }
            // Before the glide, on the change's bars.
            assertEquals(s.bMsAt(h.glideFromU - 0.5), h.msAt(h.glideFromU - 0.5), 0.0)
        }
    }
}
