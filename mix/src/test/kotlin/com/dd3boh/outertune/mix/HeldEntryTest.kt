/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.ChangeRender
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.LanePlan
import com.dd3boh.outertune.mix.engine.SpecPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Changes anywhere, through the model of the sink: the outgoing song cut on a line far from its
 * end, up to 70 s before it, with the incoming one brought in at a bar of its middle that lands on
 * the box's first bar, sample for sample. Its frames before that bar are skipped in front of the
 * sink as they are decoded and never heard; the held end plays on meanwhile, so nothing runs dry;
 * and the position counts the incoming song from its entry. Boxes of 8, 16 and 32 bars. A skip
 * that is not done before the change lets the plan go before any of it is heard.
 */
class HeldEntryTest {

    private val rate = 44_100

    // Long songs: A has to reach well past the 110 s the end is held from, B past a late entry and its box.
    private val songA by lazy { Synth.song(SynthSpec(bpm = 128.0, bars = 120, seed = 41)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 124.0, bars = 110, seed = 42)) }

    inner class Run(
        val seed: Long = 1,
        val attA: Double = 0.8,
        val attB: Double = 0.9,
        /** The box: B comes in on its first bar, A is cut on its last line. */
        val boxBars: Int = 8,
        /** A's line, the end of the box, this far before A's end. */
        val exitLeftS: Double = 70.0,
        /** B comes in at its own bar nearest this. */
        val entryS: Double = 30.0,
        /**
         * The end is held from this far before A's end: the 85 s before the end the box starts on,
         * its tempo ramp, and the time the model's decoder, at about 13 times real time, takes to
         * hold the end and skip B to its entry.
         */
        val armLeftS: Double = 110.0,
        val capacityS: Double = 112.0,
        /** The entry the end is armed with, where it differs from the plan's (a plan for another entry). */
        val armEntry: Long? = null,
        val buffersPerTick: Int = 4,
        /** The song the beat matcher adjusts. */
        val side: Side = Side.A,
    ) {
        val held = HeldTail(2, rate, (capacityS * rate).toInt()).also {
            it.setAttenuation(attA)
            it.setNextAttenuation(attB)
        }
        val model = SinkModel(held, listOf(ModelSong("A", songA.samples), ModelSong("B", songB.samples)), Random(seed), buffersPerTick = buffersPerTick)

        /** B's bar it comes in on, in its frames, and the spec of the box. */
        val entry: Long = Math.round(songB.downbeats.minBy { abs(it - entryS) } * rate)
        val spec: TransitionSpec = box()
        var armFrame = -1
        var tail: ShortArray? = null
        var lanePlan: LanePlan? = null
        var render: ChangeRender? = null
        var heldPlan: MixPlan? = null

        /** A's line, the box's last, in A's own frames. */
        val lineFrame: Long get() = Math.round(spec.aMsAt(boxBars.toDouble()) * rate / 1000.0)

        private fun box(): TransitionSpec {
            // A's line: its downbeat nearest the time asked for; the box starts that many of its
            // bars before (one of A's bars plays for each of B's once A runs at B's tempo).
            val line = songA.downbeats.minBy { abs(it - (songA.seconds - exitLeftS)) }
            val aBar = 4 * 60.0 / songA.spec.bpm
            val start = line - boxBars * aBar
            // B's anchor is its entry, to the frame, so its bar 1 is the frame it comes in on.
            val bMs = entry * 1000.0 / rate
            val half = boxBars / 2.0
            val moves = mapOf(
                // One bass at a time: A's goes and B's comes on the middle bar line.
                LaneId(Side.A, Lane.LOWS) to Move(half, 0.0, How.CUT),
                LaneId(Side.A, Lane.MIDS) to Move(boxBars.toDouble(), 0.0, How.CUT),
                LaneId(Side.A, Lane.HIGHS) to Move(boxBars.toDouble(), 0.0, How.CUT),
                LaneId(Side.B, Lane.LOWS) to Move(half, 0.0, How.CUT),
                LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
                LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
            )
            return TransitionSpec(
                a = Anchor("a", start * 1000.0, songA.spec.bpm), b = Anchor("b", bMs, songB.spec.bpm),
                lanes = LaneSet.BANDS, bars = boxBars, beatMatch = true, preset = Preset.CUSTOM, moves = moves,
                bStartMs = bMs, matchSide = side,
            )
        }

        fun go(): Run {
            while (!model.ended && model.clockMs < 600_000) {
                control()
                model.step()
            }
            return this
        }

        private fun control() {
            val (song, frame) = model.position()
            if (armFrame < 0 && song == 0 && frame >= songA.frames - armLeftS * rate) {
                if (held.arm(entry = armEntry ?: SpecPlan.liveEntryFrames(spec, rate))) armFrame = model.consumedPosition()
            }
            // The worker plans the moment the end is held, from the held end and B's copy.
            if (render == null && held.state == HeldTail.State.DRAIN) {
                val t = held.heldTail()!!.copyOf((held.written * 2).toInt())
                tail = t
                val facts = ChangeFacts(
                    rate, 2, aBar1 = spec.a.atMs * rate / 1000.0 - armFrame, aFrames = held.written,
                    bBar1 = spec.b.atMs * rate / 1000.0, bFrames = songB.frames.toLong(), earliest = held.read + rate,
                )
                val lp = when (val r = SpecPlan.plan(spec, facts)) {
                    is SpecPlan.Result.Planned -> r.plan
                    is SpecPlan.Result.Refused -> throw AssertionError("refused: ${r.why}")
                }
                lanePlan = lp
                val r = LaneMixer(lp).render(t, songB.samples, attA, attB)
                render = r
                heldPlan = r.heldPlan()
                held.offer(heldPlan!!, r.outgoing, r.incoming)
            }
        }

        /** What should have been heard: A as it is, the change as rendered, then B as it is from where the change leaves it. */
        fun expected(): ShortArray {
            val r = render!!
            val lp = r.plan
            val out = ShortList()
            val a = ShortArray((armFrame + lp.mixFrom).toInt() * 2) { attenuate(songA.samples[it], attA) }
            out.add(a, 0, a.size)
            val change = r.mix(tail!!, songB.samples, lp.mixFrom, lp.mixTo)
            out.add(change, 0, change.size)
            val from = (lp.mixTo - lp.bOrigin).toInt()
            val b = ShortArray((songB.frames - from) * 2) { attenuate(songB.samples[from * 2 + it], attB) }
            out.add(b, 0, b.size)
            return out.toArray()
        }

        /** What a change left as it is sounds like: all of A, then B from its entry. */
        fun gaplessFromEntry(): ShortArray {
            val out = ShortList()
            val a = ShortArray(songA.samples.size) { attenuate(songA.samples[it], attA) }
            out.add(a, 0, a.size)
            val from = entry.toInt()
            val b = ShortArray((songB.frames - from) * 2) { attenuate(songB.samples[from * 2 + it], attB) }
            out.add(b, 0, b.size)
            return out.toArray()
        }
    }

    @Test
    fun `B comes in at a bar of its middle, on the box's first bar, with A cut 70 s before its end`() {
        for (seed in 1L..2L) {
            val run = Run(seed = seed).go()
            val lp = run.lanePlan!!
            val heldPlan = run.heldPlan!!
            val skip = (run.model.skipEndMs - run.model.skipStartMs) / 1000.0
            println(
                "seed $seed: ${heldPlan.describe()}; ${run.held.note}; B's first %.1f s skipped in %.1f s of the model; A's line %.1f s before its end"
                    .format(run.entry.toDouble() / rate, skip, (songA.frames - run.lineFrame).toDouble() / rate),
            )
            assertTrue(run.held.note, run.held.note.startsWith("planned"))
            assertEquals(run.entry, heldPlan.bEntry)
            // B's entry is the box's first bar, to the frame, and A's downbeat there within half a frame.
            assertEquals(lp.bStart.toDouble(), lp.bar1, 1e-6)
            assertEquals(lp.bar1, lp.aMap.outAt(lp.facts.aBar1), 0.5)
            // The sink starts counting B at its entry, on the output frame the plan put it on.
            val cp = run.model.checkpoints.first { it.song == 1 }
            assertEquals(run.armFrame + lp.bStart, cp.trackFrame)
            assertEquals(run.entry, cp.songFrame)
            // A is cut on its line, 70 s before its end, and nothing of it is heard after.
            assertEquals(70.0, (songA.frames - run.lineFrame).toDouble() / rate, 1.0)
            assertTrue(lp.aMap.tailAt(lp.aGone.toDouble()) + run.armFrame <= run.lineFrame + 0.02 * rate)
            // Every sample as rendered, B's frames before its entry nowhere in it.
            assertArrayEquals(run.expected(), run.model.heard.toArray())
            assertEquals(0, run.model.stalls)
            assertEquals("no underruns", 0, run.model.underruns)
            assertEquals(0L, run.held.clipped)
        }
    }

    @Test
    fun `with B adjusted, the song as it plays takes over on B's own bar line, to the bit`() {
        for (seed in 1L..2L) {
            val run = Run(seed = seed, side = Side.B).go()
            val lp = run.lanePlan!!
            val s = lp.bStretch!!
            println("seed $seed: ${run.heldPlan!!.describe()}; ${run.held.note}; live from B's frame ${lp.bEntry}, own samples from output ${s.switchAt}")
            // Taken, and the song as it plays agreed with its copy from the first frame it came in on.
            assertTrue(run.held.note, run.held.note.startsWith("planned"))
            assertEquals(lp.bEntry, run.heldPlan!!.bEntry)
            // A plays as it is; B is heard from its entry on the box's first bar.
            assertTrue(lp.aMap.isIdentity)
            assertEquals(lp.bar1, s.heardFrom.toDouble(), 0.5)
            val cp = run.model.checkpoints.first { it.song == 1 }
            assertEquals(run.armFrame + lp.bStart, cp.trackFrame)
            assertEquals(lp.bEntry, cp.songFrame)
            assertArrayEquals(run.expected(), run.model.heard.toArray())
            assertEquals(0, run.model.stalls)
            assertEquals(0, run.model.underruns)
            assertEquals(0L, run.held.clipped)
        }
    }

    @Test
    fun `boxes of 16 and 32 bars play from a later entry too`() {
        for ((bars, exit) in listOf(16 to 50.0, 32 to 20.0)) {
            val run = Run(boxBars = bars, exitLeftS = exit, entryS = 40.0).go()
            val lp = run.lanePlan!!
            println("$bars bars: ${run.heldPlan!!.describe()}; ${run.held.note}; the change %.1f s".format(lp.seconds))
            assertTrue(run.held.note, run.held.note.startsWith("planned"))
            assertEquals(bars, run.heldPlan!!.bars)
            assertTrue(lp.mixTo - lp.bStart >= bars * lp.barFrames)
            assertArrayEquals(run.expected(), run.model.heard.toArray())
            assertEquals(run.entry, run.model.checkpoints.first { it.song == 1 }.songFrame)
            assertEquals(0, run.model.stalls)
            assertEquals(0, run.model.underruns)
        }
    }

    @Test
    fun `an entry not skipped in time lets the plan go before it is heard`() {
        // B comes in 185 s into it: skipping that takes longer than the time left before the change.
        val run = Run(entryS = 185.0).go()
        println("${run.held.note}; skipped from %.1f to %.1f s, the change was due at %.1f s".format(
            run.model.skipStartMs / 1000.0, run.model.skipEndMs / 1000.0,
            (songA.frames - run.armLeftS * rate + run.lanePlan!!.mixFrom) / rate,
        ))
        assertTrue(run.held.note, run.held.note.startsWith("let go before it was heard"))
        assertArrayEquals(run.gaplessFromEntry(), run.model.heard.toArray())
        assertEquals(run.entry, run.model.checkpoints.first { it.song == 1 }.songFrame)
        assertEquals(0, run.model.stalls)
        assertEquals(0, run.model.underruns)
    }

    @Test
    fun `a plan for another entry than the one armed is refused`() {
        val run = Run(armEntry = 10L * rate).go()
        assertTrue(run.held.note, run.held.note.startsWith("refused: planned for the next song from its frame"))
        // B still comes in where the end was armed for, after all of A.
        val out = ShortList()
        val a = ShortArray(songA.samples.size) { attenuate(songA.samples[it], run.attA) }
        out.add(a, 0, a.size)
        val from = 10 * rate
        val b = ShortArray((songB.frames - from) * 2) { attenuate(songB.samples[from * 2 + it], run.attB) }
        out.add(b, 0, b.size)
        assertArrayEquals(out.toArray(), run.model.heard.toArray())
        assertEquals(0, run.model.underruns)
    }
}
