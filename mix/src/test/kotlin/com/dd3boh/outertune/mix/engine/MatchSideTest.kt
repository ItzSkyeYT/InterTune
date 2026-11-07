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
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.toJson
import com.dd3boh.outertune.mix.edit.transitionSpecOf
import com.dd3boh.outertune.mix.toPcm16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which song the beat matcher adjusts. A, as it always was: A at B's tempo through the change. B:
 * A at its own speed, B at A's tempo laid on its own beats while both are heard, then back to its
 * own speed over two bars once A is gone, its own samples taking over on its own bar line, where
 * the song as it plays goes on from the very frame the change left it on.
 */
class MatchSideTest {

    private val rate = 44_100

    /** A at 128, B at 124: B is the slower, so adjusted it plays 3.2% faster till A is gone. */
    private fun spec(side: Side, entryMs: Double = 0.0, bAtMs: Double = 4_000.0) = TransitionSpec(
        a = Anchor("a", 60_000.0, 128.0), b = Anchor("b", bAtMs, 124.0), lanes = LaneSet.BANDS, bars = 8, beatMatch = true,
        preset = Preset.CUSTOM,
        moves = mapOf(
            LaneId(Side.A, Lane.LOWS) to Move(4.0, 0.0, How.CUT),
            LaneId(Side.A, Lane.MIDS) to Move(8.0, 0.0, How.CUT),
            LaneId(Side.A, Lane.HIGHS) to Move(8.0, 0.0, How.CUT),
            LaneId(Side.B, Lane.LOWS) to Move(4.0, 0.0, How.CUT),
            LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
            LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
        ),
        bStartMs = entryMs, matchSide = side,
    )

    @Test
    fun `A stays the default, and Auto's changes are A's`() {
        val plain = TransitionSpec(
            a = Anchor("a", 1.0, 120.0), b = Anchor("b", 1.0, 120.0), lanes = LaneSet.BANDS, bars = 8, preset = Preset.CUSTOM, moves = emptyMap(),
        )
        assertEquals(Side.A, plain.matchSide)
        assertFalse(spec(Side.A).bAdjusted)
        assertTrue(spec(Side.B).bAdjusted)
        // Not matched at all: no side is adjusted, whatever is asked.
        assertFalse(spec(Side.B).copy(beatMatch = false).bAdjusted)
    }

    @Test
    fun `the bars are the same either way, only how long they last differs`() {
        val a = spec(Side.A)
        val b = spec(Side.B)
        for (u in listOf(-2.0, 0.0, 3.5, 8.0)) {
            assertEquals(a.aMsAt(u), b.aMsAt(u), 1e-9)
            assertEquals(a.bMsAt(u), b.bMsAt(u), 1e-9)
        }
        // A bar of the change heard: B's own with A adjusted, A's own with B adjusted.
        assertEquals(4 * 60_000.0 / 124, a.heardBarMs, 1e-9)
        assertEquals(4 * 60_000.0 / 128, b.heardBarMs, 1e-9)
        // A's tempo ramps before bar 1 only where A is the one adjusted: with B in on its anchor,
        // the change starts 8 of A's beats earlier with A adjusted. With B adjusted, it starts
        // where the song as it plays comes in, before B is heard by what B's stretch gains (B is
        // the slower here, so it gains).
        val aIn = spec(Side.A, entryMs = 4_000.0)
        val bIn = spec(Side.B, entryMs = 4_000.0)
        val gainMs = -SpecPlan.bLayout(bIn, 48_000)!!.bStartAt * 1000.0 / 48_000
        assertTrue("$gainMs", gainMs > 0)
        assertEquals(8 * 60_000.0 / 128 - gainMs, SpecPlan.firstChangeMs(bIn) - SpecPlan.firstChangeMs(aIn), 1e-6)
    }

    @Test
    fun `the side is saved only where it is B, and a change saved before reads as A`() {
        val b = spec(Side.B)
        val json = b.toJson()
        assertTrue(json, json.contains("\"matchSide\":\"B\""))
        assertEquals(b, transitionSpecOf(json))
        val a = spec(Side.A)
        assertFalse(a.toJson().contains("matchSide"))
        assertEquals(a, transitionSpecOf(a.toJson()))
        assertEquals(Side.A, transitionSpecOf(a.toJson())!!.matchSide)
        // Anything else is not read at all.
        assertNull(transitionSpecOf(json.replace("\"matchSide\":\"B\"", "\"matchSide\":\"C\"")))
    }

    @Test
    fun `B adjusted follows A's beats, glides home and ends on its own bar line, where the song as it plays goes on`() {
        val songA = Synth.song(SynthSpec(bpm = 128.0, bars = 48, seed = 61)).samples
        val songB = Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 62)).samples
        for (entryMs in listOf(0.0, 4_000.0 + 8 * 4 * 60_000.0 / 124)) {
            val spec = spec(Side.B, entryMs, bAtMs = if (entryMs > 0) entryMs else 4_000.0)
            val facts = ChangeFacts(rate, 2, 60.0 * rate, (songA.size / 2).toLong(), spec.b.atMs * rate / 1000, (songB.size / 2).toLong())
            val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
            assertNotNull(plan.bStretch)
            val s = plan.bStretch!!
            println("entry $entryMs ms: ${plan.describe()}; live entry ${plan.bEntry}, drain at ${plan.bStart}, heard from ${s.heardFrom}, own from ${s.switchAt} at ${s.switchFrame}")
            // A plays as it is.
            assertTrue(plan.aMap.isIdentity)
            assertEquals(1.0, plan.speed, 0.0)
            // While both are heard B's bars fall on the change's bars, which are A's.
            val bBarOwn = spec.b.barMs * rate / 1000
            for (u in listOf(0.0, 1.0, 4.0, 8.0)) {
                assertEquals("bar $u", facts.bBar1 + u * bBarOwn, s.map.tailAt(plan.out(u)), 1.0)
            }
            // Back at its own speed where its own samples take over, on one of its own bar lines.
            assertEquals(1.0, s.map.speedAt(s.switchAt.toDouble()), 1e-12)
            val k = (s.switchFrame - facts.bBar1) / bBarOwn
            assertEquals(Math.round(k).toDouble(), k, 0.5 / bBarOwn * 2)
            assertTrue(s.switchAt > plan.aGone)
            // The song as it plays: its frame bEntry on output frame bStart, one for one after it,
            // which is B's own frame there.
            assertEquals(s.switchFrame, plan.bEntry + (s.switchAt - plan.bStart))
            assertEquals(s.switchFrame, s.switchAt - plan.bOrigin)
            // And the render ends on B's own samples, frame for frame, where it hands over.
            val render = LaneMixer(plan).render(songA, songB, 1.0, 1.0)
            var compared = 0
            for (o in (s.switchAt + s.switchFrames) until plan.mixTo) for (c in 0 until 2) {
                assertEquals("frame $o", songB[((o - plan.bOrigin) * 2 + c).toInt()], render.incoming.at(o, c))
                compared++
            }
            assertTrue(compared > 0)
            // The editor's preview after the change: the song as it plays.
            val after = render.mix(songA, songB, plan.mixTo, plan.mixTo + 50)
            for (i in 0 until 50) assertEquals(songB[((plan.mixTo + i - plan.bOrigin) * 2).toInt()], after[i * 2])
            // The end is held for the frame the song as it plays comes in on: the change's own
            // entry, as with the song before adjusted, so a change saved with the next song
            // adjusted after the end was held for the song before adjusted still plays.
            assertEquals(plan.bEntry, SpecPlan.liveEntryFrames(spec, rate))
            assertEquals(Math.round(entryMs * rate / 1000), plan.bEntry)
            assertEquals(SpecPlan.liveEntryFrames(spec.copy(matchSide = Side.A), rate), plan.bEntry)
            // What the editor makes sure was read of B is what the change reads of it.
            val reads = s.switchFrame + (plan.mixTo - s.switchAt)
            assertEquals(reads * 1000.0 / rate, SpecPlan.bReadToMs(spec)!!, 1.5)
            assertTrue(SpecPlan.bReadToMs(spec)!! * rate / 1000 >= reads)
            // Nothing of B is heard before its entry: A's part there is A under the shared headroom.
            for (o in render.outgoing.firstFrame until s.heardFrom) for (c in 0 until 2) {
                assertEquals("frame $o", toPcm16(songA[(o * 2 + c).toInt()] * render.headroom.at(o)), render.outgoing.at(o, c))
            }
        }
    }

    @Test
    fun `with A adjusted the plan is as it always was`() {
        val spec = spec(Side.A)
        val songA = Synth.song(SynthSpec(bpm = 128.0, bars = 48, seed = 61)).samples
        val songB = Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 62)).samples
        val facts = ChangeFacts(rate, 2, 60.0 * rate, (songA.size / 2).toLong(), spec.b.atMs * rate / 1000, (songB.size / 2).toLong())
        val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
        assertNull(plan.bStretch)
        assertFalse(plan.aMap.isIdentity)
        assertEquals(124.0 / 128.0, plan.speed, 1e-12)
        assertEquals(0L, SpecPlan.liveEntryFrames(spec, rate))
    }
}
