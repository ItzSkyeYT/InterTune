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
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.toPcm16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A next song brought in at a bar of its middle: its entry on bar 1 to the frame, its own samples from there, nothing of it before. */
class LaneEntryTest {

    private val rate = 44_100

    @Test
    fun `B's entry frame is the change's bar 1, and its part is B from there`() {
        val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 51)).samples
        val b = Synth.song(SynthSpec(bpm = 120.0, bars = 40, seed = 52)).samples
        // B comes in 30 s into it, on its own bar 1 there, as A leaves on the same bar line.
        val entryMs = 30_000.0
        val entry = (entryMs * rate / 1000).toLong()
        val moves = LaneSet.BANDS.lanes.associate { LaneId(Side.A, it) to Move(0.0, 0.0, How.CUT) } +
            LaneSet.BANDS.lanes.associate { LaneId(Side.B, it) to Move(0.0, 0.0, How.FROM_START) }
        val spec = TransitionSpec(
            a = Anchor("song-a", 20_000.0, 120.0), b = Anchor("song-b", entryMs, 120.0), lanes = LaneSet.BANDS,
            bars = 8, beatMatch = false, preset = Preset.CUSTOM, moves = moves, bStartMs = entryMs,
        )
        val facts = ChangeFacts(rate, 2, 20.0 * rate, (a.size / 2).toLong(), entryMs * rate / 1000, (b.size / 2).toLong())
        val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
        assertEquals(entry, plan.bEntry)
        assertEquals(plan.bStart.toDouble(), plan.bar1, 0.0)
        assertEquals(20L * rate, plan.bStart)
        assertEquals(plan.bStart - entry, plan.bOrigin)

        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        val part = render.incoming
        assertEquals(plan.bStart, part.firstFrame)
        assertEquals(entry, part.entry)
        assertEquals(entry, render.heldPlan().bEntry)
        // Nothing of B before its entry: A's part there is A's own samples, under the shared
        // headroom that bends a moment before the two meet.
        assertTrue(render.outgoing.firstFrame < plan.bStart - rate / 10)
        for (o in render.outgoing.firstFrame until plan.bStart - rate / 10) for (c in 0 until 2) {
            assertEquals("frame $o", toPcm16(a[(o * 2 + c).toInt()] * render.headroom.at(o)), render.outgoing.at(o, c))
        }
        // And B's own frames before its entry change nothing at all: noise in their place renders
        // the very same change, A's part and B's, to the bit.
        val noisy = b.copyOf()
        val random = java.util.Random(7)
        for (i in 0 until (entry * 2).toInt()) noisy[i] = (random.nextInt(65536) - 32768).toShort()
        val again = LaneMixer(plan).render(a, noisy, 1.0, 1.0)
        assertTrue(again.outgoing.samples.contentEquals(render.outgoing.samples))
        assertTrue(again.incoming.samples.contentEquals(render.incoming.samples))
        // Its first 5 ms rise from silence, so the middle of a song never starts with a click...
        val fade = LaneMixer.ENTRY_FADE_MS * rate / 1000
        for (n in 0 until fade) for (c in 0 until 2) {
            val s = b[((entry + n) * 2 + c).toInt()].toInt()
            assertTrue(abs(part.at(plan.bStart + n, c).toInt()) <= abs(s) + 1)
        }
        // ...then it is B's own samples, frame for frame from its entry, once A's cut is over.
        val cut = (0.02 * rate).toInt()
        var compared = 0
        for (n in cut until part.frames) for (c in 0 until 2) {
            assertEquals("frame $n", b[((entry + n) * 2 + c).toInt()], part.at(plan.bStart + n, c))
            compared++
        }
        assertTrue(compared > rate / 2)
        // And so in the preview, after the change: B from its entry.
        val after = render.mix(a, b, plan.mixTo, plan.mixTo + 100)
        for (i in 0 until 100) assertEquals(b[((plan.mixTo - plan.bOrigin + i) * 2).toInt()], after[i * 2])
    }
}
