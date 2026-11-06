/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.PlanInput
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Auto written as a spec: each of the planner's styles becomes its preset, placed where the
 * planner placed it, to the frame.
 */
class AutoSpecTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 2)) }
    private val tail by lazy { songA.slice(songA.seconds - 40) }
    private val tailAnalysis by lazy { RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL) }
    private val headAnalysis by lazy { RegionAnalysis.analyse(songB.slice(0.0, 30.0), rate, 2, RegionKind.HEAD) }

    private fun input(forced: Style? = null) = PlanInput(
        tail = tailAnalysis, head = headAnalysis, readMark = 6L * rate, aDurationMs = 200_000, bDurationMs = 200_000, forced = forced,
    )

    @Test
    fun `each style becomes its preset, placed where the planner placed it`() {
        val expected = mapOf(Style.BEAT to Preset.FADE, Style.BLEND to Preset.BLEND, Style.SMOOTH to Preset.RISE, Style.CUT to Preset.CUT)
        val b = songB.samples
        for ((style, preset) in expected) {
            val plan = Planner.planStyle(input(), style) ?: continue
            val made = AutoSpec.of(plan, tailAnalysis, headAnalysis, "a", "b", 160_000.0, (b.size / 2).toLong(), 6L * rate)
            assertNotNull(made)
            val spec = made!!.spec
            println("$style: ${AutoSpec.label(spec)}, ${plan.describe()}")
            assertEquals(style.name, emptyList<String>(), spec.problems())
            assertEquals(Preset.AUTO, spec.preset)
            assertEquals(preset, spec.from)
            val lanePlan = when (val r = SpecPlan.plan(spec, made.facts)) {
                is SpecPlan.Result.Planned -> r.plan
                is SpecPlan.Result.Refused -> throw AssertionError("$style refused: ${r.why}")
            }
            // B starts on the planner's frame, nothing changes before the plan can be in, and A's part
            // covers B's first frame, as the held end needs.
            assertEquals(style.name, plan.bStart, lanePlan.bStart)
            assertTrue(style.name, lanePlan.mixFrom >= 6L * rate)
            assertTrue(style.name, lanePlan.mixFrom <= lanePlan.bStart)
            if (style == Style.BEAT) {
                assertEquals(plan.bars, spec.bars)
                assertEquals(plan.speed, lanePlan.speed, 1e-12)
                assertEquals(plan.overlapStart, lanePlan.out(0.0), 1.0)
                assertEquals(plan.overlapEnd, lanePlan.out(spec.lengthBars), 1.0)
                assertEquals(abs(1 - plan.speed), abs(1 - spec.tempoRatio()), 1e-9)
                assertTrue(spec.beatMatched)
            }
            if (style == Style.CUT) assertEquals(plan.overlapEnd, lanePlan.out(0.0), 1.0)
            if (style == Style.BLEND || style == Style.SMOOTH) {
                assertEquals(plan.overlapStart, lanePlan.out(0.0), 1.0)
                assertEquals(plan.overlapEnd, lanePlan.out(spec.lengthBars), 1.0)
            }
            // And it renders: A from the held end, B from its copy.
            val render = LaneMixer(lanePlan).render(tail, b, 0.9, 0.8)
            assertTrue(render.outgoing.frames > 0)
            println("  rendered in %.0f ms, peak %.2f, headroom %.1f dB".format(render.renderMillis, render.peakAfter, render.headroom.deepestDb))
        }
    }

    @Test
    fun `a next song whose first downbeat is its first frame still gives a spec that plays`() {
        val cut = Planner.planStyle(input(), Style.CUT)!!
        // The next song starting three frames after the line: its anchor a little before its first frame.
        val late = cut.bStart.coerceAtLeast(Math.round(cut.overlapEnd) + 3)
        val plan = com.dd3boh.outertune.mix.MixPlan(
            cut.style, cut.reason, cut.sampleRate, cut.channels, cut.tailFrames, late, cut.aMap, minOf(cut.aFirstProcessed, late), cut.aGone,
            cut.overlapStart, cut.overlapEnd, cut.gainA, cut.gainB, cut.filtersA, cut.filtersB, cut.headroom, cut.incomingLoad,
            cut.attenuationA, cut.attenuationB, cut.shapedUntil, cut.lockFrom, cut.lockTo, cut.speed, cut.bars,
        )
        val made = AutoSpec.of(plan, tailAnalysis, headAnalysis, "a", "b", 0.0, (songB.samples.size / 2).toLong(), 6L * rate)!!
        assertEquals(emptyList<String>(), made.spec.problems())
        assertEquals(0.0, made.spec.b.atMs, 0.0)
        assertTrue(made.facts.bBar1 < 0)
        val lanePlan = (SpecPlan.plan(made.spec, made.facts) as SpecPlan.Result.Planned).plan
        assertEquals(late, lanePlan.bStart)
    }

    @Test
    fun `the beat mix the planner picks is a Fade on bands`() {
        val plan = Planner.plan(input())
        assertEquals(Style.BEAT, plan.style)
        val made = AutoSpec.of(plan, tailAnalysis, headAnalysis, "a", "b", 0.0, (songB.samples.size / 2).toLong(), 6L * rate)!!
        assertEquals("Fade · ${plan.bars} bars", AutoSpec.label(made.spec))
        assertTrue(made.spec.bars in TransitionSpec.LENGTHS_BARS)
    }
}
