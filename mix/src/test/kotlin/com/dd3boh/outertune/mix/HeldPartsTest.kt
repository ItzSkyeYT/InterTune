/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.mix.engine.AutoSpec
import com.dd3boh.outertune.mix.engine.ChangeRender
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.SpecPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The held end playing a change rendered from copies, through the model of the sink: what it
 * plays is the render to the bit whatever the buffers, an abort at any frame leaves no step, a
 * next song that plays differently from its copy is crossed over to, and a plan from copies takes
 * the place of a first one only while nothing planned has been heard.
 */
class HeldPartsTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 2)) }
    private val headB by lazy { RegionAnalysis.analyse(songB.slice(0.0, 30.0), rate, 2, RegionKind.HEAD) }

    inner class Run(
        val seed: Long = 1,
        val attA: Double = 0.8,
        val attB: Double = 0.9,
        /** B's copy as the engine is given it; the model plays the song itself. */
        val copy: ShortArray = songB.samples,
        /** Hand the prototype's plan over first, then the change from copies to take its place, this many ticks later. */
        val firstThenLanesAfterTicks: Int? = null,
        val action: (Run) -> Unit = {},
    ) {
        val held = HeldTail(2, rate, 48 * rate).also {
            it.setAttenuation(attA)
            it.setNextAttenuation(attB)
        }
        val model = SinkModel(held, listOf(ModelSong("A", songA.samples), ModelSong("B", songB.samples)), Random(seed))
        var armFrame = -1
        var tail: ShortArray? = null
        var plan: MixPlan? = null
        var render: ChangeRender? = null
        var heldPlan: MixPlan? = null
        private var lanesAt = -1L
        var ticks = 0L

        fun go(): Run {
            while (!model.ended && model.clockMs < 200_000) {
                control()
                action(this)
                model.step()
                ticks++
            }
            return this
        }

        private fun control() {
            val (song, frame) = model.position()
            if (armFrame < 0 && song == 0 && frame >= songA.frames - 40 * rate) {
                if (held.arm()) armFrame = model.consumedPosition()
            }
            if (render == null && held.state == HeldTail.State.DRAIN) {
                val t = held.heldTail()!!.copyOf((held.written * 2).toInt())
                tail = t
                val analysis = RegionAnalysis.analyse(t, rate, 2, RegionKind.TAIL)
                val p = Planner.plan(PlanInput(analysis, headB, readMark = held.read, attenuationA = attA, attenuationB = attB, aDurationMs = 200_000, bDurationMs = 200_000))
                plan = p
                val made = AutoSpec.of(p, analysis, headB, "a", "b", 0.0, (copy.size / 2).toLong(), held.read + rate)!!
                val lanePlan = (SpecPlan.plan(made.spec, made.facts) as SpecPlan.Result.Planned).plan
                val r = LaneMixer(lanePlan).render(t, copy, attA, attB, analysis.guide, analysis.decimation)
                render = r
                heldPlan = r.heldPlan()
                val first = firstThenLanesAfterTicks
                if (first == null) {
                    held.offer(heldPlan!!, r.outgoing, r.incoming)
                } else {
                    held.offer(p, MixRender.renderOutgoing(p, t, analysis.guide, analysis.decimation, p.anchors))
                    lanesAt = ticks + first
                }
            }
            if (lanesAt >= 0 && ticks >= lanesAt) {
                held.offer(heldPlan!!, render!!.outgoing, render!!.incoming, replaces = true)
                lanesAt = -1
            }
        }

        /** What should have been heard: A as it is, the change as rendered, then B as it is. */
        fun expected(): ShortArray {
            val r = render!!
            val lp = r.plan
            val out = ShortList()
            val a = ShortArray((armFrame + lp.mixFrom).toInt() * 2) { attenuate(songA.samples[it], attA) }
            out.add(a, 0, a.size)
            val change = r.mix(tail!!, copy, lp.mixFrom, lp.mixTo)
            out.add(change, 0, change.size)
            val from = (lp.mixTo - lp.bStart).toInt()
            val b = ShortArray((songB.frames - from) * 2) { attenuate(songB.samples[from * 2 + it], attB) }
            out.add(b, 0, b.size)
            return out.toArray()
        }
    }

    @Test
    fun `a change from copies plays as rendered, to the bit, whatever the buffers`() {
        for (seed in 1L..3L) {
            val run = Run(seed = seed).go()
            val lp = run.render!!.plan
            println("seed $seed: ${run.heldPlan!!.describe()}; ${run.held.note}; rendered in %.0f ms".format(run.render!!.renderMillis))
            assertTrue(run.held.note, run.held.note.startsWith("planned"))
            assertEquals(0, run.model.stalls)
            assertEquals(0, run.model.underruns)
            assertEquals(run.armFrame + lp.bStart, run.model.checkpoints.first { it.song == 1 }.trackFrame)
            assertArrayEquals(run.expected(), run.model.heard.toArray())
            assertEquals(0L, run.held.clipped)
        }
    }

    private fun maxStep(s: ShortArray): Int {
        var m = 0
        for (i in 2 until s.size) m = max(m, abs(s[i] - s[i - 2]))
        return m
    }

    @Test
    fun `an abort at any frame of the change leaves no step`() {
        val plain = Run().go()
        val lp = plain.render!!.plan
        val beat = plain.render!!.incoming.beatFrames
        val bound = max(maxStep(plain.model.heard.toArray()), max(maxStep(songA.samples), maxStep(songB.samples))) + 200
        val from = lp.mixFrom - rate
        val to = lp.mixTo + rate / 2
        var k = from
        var runs = 0
        while (k < to) {
            val at = k
            var done = false
            val run = Run(action = { r ->
                val h = r.held
                val out = when (h.state) {
                    HeldTail.State.DRAIN, HeldTail.State.CAPTURE -> h.read
                    HeldTail.State.MIX -> lp.bStart + h.incomingFrames
                    HeldTail.State.PASS -> Long.MIN_VALUE
                }
                if (!done && out >= at) {
                    done = true
                    h.abort("a test", beat)
                }
            }).go()
            val heard = run.model.heard.toArray()
            val step = maxStep(heard)
            assertTrue("abort at ${at - lp.bStart} from B's start: a step of $step over $bound (${run.held.note})", step <= bound)
            assertEquals(0, run.model.stalls)
            // Whatever happened, B ends as itself.
            val end = ShortArray(rate * 2) { attenuate(songB.samples[(songB.frames - rate) * 2 + it], 0.9) }
            assertArrayEquals(end, heard.copyOfRange(heard.size - rate * 2, heard.size))
            runs++
            k += (to - from) / 23
        }
        assertTrue(runs >= 20)
    }

    @Test
    fun `a next song that plays differently from its copy is crossed over to`() {
        // The copy the engine was given is seven frames late against the song that plays.
        val late = ShortArray(14) + songB.samples
        val plain = Run().go()
        val bound = max(maxStep(plain.model.heard.toArray()), max(maxStep(songA.samples), maxStep(songB.samples))) + 200
        val run = Run(copy = late).go()
        println(run.held.note)
        assertTrue(run.held.note, run.held.note.startsWith("the next song plays differently from its copy"))
        assertEquals(0, run.model.stalls)
        val heard = run.model.heard.toArray()
        assertTrue(maxStep(heard) <= bound)
        val end = ShortArray(rate * 2) { attenuate(songB.samples[(songB.frames - rate) * 2 + it], 0.9) }
        assertArrayEquals(end, heard.copyOfRange(heard.size - rate * 2, heard.size))
    }

    @Test
    fun `a change from copies takes the place of a first plan only while nothing planned is heard`() {
        val inTime = Run(firstThenLanesAfterTicks = 1).go()
        assertTrue(inTime.held.note, inTime.held.note.startsWith("planned again"))
        assertArrayEquals(inTime.expected(), inTime.model.heard.toArray())

        val tooLate = Run(firstThenLanesAfterTicks = 3_000).go()
        assertTrue(tooLate.held.note, tooLate.held.note.startsWith("planned:") || tooLate.held.note.startsWith("kept the first plan"))
        assertEquals(0, tooLate.model.stalls)
        assertEquals(tooLate.armFrame + tooLate.plan!!.bStart, tooLate.model.checkpoints.first { it.song == 1 }.trackFrame)
    }
}
