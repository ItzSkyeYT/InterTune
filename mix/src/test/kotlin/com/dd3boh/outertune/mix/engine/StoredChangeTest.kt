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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ShortBuffer

/**
 * A change rendered once in the editor and kept (8 Nov 2026: "plays the transition from storage
 * instead of having to re-render it"): it plays as it was rendered wherever the playlist's held end
 * starts, and never where the change, a level or either song's music is not what it was made from.
 */
class StoredChangeTest {

    private val rate = 44_100
    private val channels = 2

    private val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 41)).samples
    private val b = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 42)).samples

    private val spec = TransitionSpec(
        a = Anchor("song-a", 20_000.0, 120.0), b = Anchor("song-b", 1_000.0, 120.0), lanes = LaneSet.PARTS,
        bars = 8, beatMatch = false, preset = Preset.CUSTOM,
        moves = LaneSet.PARTS.lanes.associate { LaneId(Side.A, it) to Move(2.0, 4.0, How.FADE) } +
            LaneSet.PARTS.lanes.associate { LaneId(Side.B, it) to Move(2.0, 4.0, How.FADE) },
    )
    private val written = "the change as saved"

    private fun parts(music: ShortArray): List<ShortBuffer> {
        val v = ShortArray(music.size) { (music[it] / 3).toShort() }
        val d = ShortArray(music.size) { (music[it] / 4).toShort() }
        val bs = ShortArray(music.size) { (music[it] / 6).toShort() }
        val m = ShortArray(music.size) { (music[it] - v[it] - d[it] - bs[it]).toShort() }
        return listOf(v, d, bs, m).map { ShortBuffer.wrap(it) }
    }

    /** The change as the editor renders it: A's window from [aStartMs] of A, B's from its first frame. */
    private fun rendered(aStartMs: Double = 0.0): ChangeRender {
        val facts = ChangeFacts(rate, channels, 20.0 * rate, (a.size / channels).toLong(), 1.0 * rate, (b.size / channels).toLong(), parts = true)
        val plan = when (val r = SpecPlan.plan(spec, facts)) {
            is SpecPlan.Result.Planned -> r.plan
            is SpecPlan.Result.Refused -> throw AssertionError(r.why)
        }
        return LaneMixer(plan).render(a, b, 1.0, 1.0, parts(a), parts(b))
    }

    private fun kept(render: ChangeRender, aStartMs: Double = 0.0): StoredChange {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { StoredChange.of(written, render, a, aStartMs, b).write(it) } }.toByteArray()
        return StoredChange.read(DataInputStream(ByteArrayInputStream(bytes))) ?: throw AssertionError("the kept change did not read back")
    }

    @Test
    fun `a kept change plays as rendered, moved to where the held end starts`() {
        val render = rendered()
        val stored = kept(render, aStartMs = 1_000.0)
        // The playlist holds A from 3000 frames further on, its clock a few ms out.
        for ((skip, clockError) in listOf(0 to 0.0, 3_000 to 37.0, 3_000 to -120.0)) {
            val held = a.copyOfRange(skip * channels, a.size)
            val heldStartMs = 1_000.0 + skip * 1000.0 / rate + clockError
            val r = stored.replay(written, spec, held, (held.size / channels).toLong(), heldStartMs, b, b.size / channels, 0, 1.0, 1.0).getOrThrow()
            assertEquals(render.plan.mixFrom - skip, r.plan.mixFrom)
            assertEquals(render.plan.bStart - skip, r.plan.bStart)
            assertEquals(render.plan.mixTo - skip, r.plan.mixTo)
            assertEquals(render.outgoing.firstFrame - skip, r.outgoing.firstFrame)
            assertEquals(render.incoming.firstFrame - skip, r.incoming.firstFrame)
            assertArrayEquals(render.outgoing.samples, r.outgoing.samples)
            assertArrayEquals(render.incoming.samples, r.incoming.samples)
            assertEquals(render.headroom.start - skip, r.headroom.start)
            // What the stage plays from it is what the editor played.
            val from = render.plan.mixFrom - rate
            val to = render.plan.mixTo + rate
            assertArrayEquals(render.mix(a, b, from, to), r.mix(held, b, from - skip, to - skip))
        }
    }

    @Test
    fun `a decoder's rounding is let through`() {
        val stored = kept(rendered())
        val held = a.copyOf().also { m -> for (i in m.indices step 11) m[i] = (m[i] + 2).toShort() }
        assertTrue(stored.replay(written, spec, held, (held.size / channels).toLong(), 0.0, b, b.size / channels, 0, 1.0, 1.0).isSuccess)
    }

    @Test
    fun `nothing plays from it once anything it was made from moved`() {
        val stored = kept(rendered())
        val frames = (a.size / channels).toLong()
        fun replay(written: String = this.written, held: ShortArray = a, copy: ShortArray = b, levelA: Double = 1.0, levelB: Double = 1.0, earliest: Long = 0) =
            stored.replay(written, spec, held, (held.size / channels).toLong(), 0.0, copy, copy.size / channels, earliest, levelA, levelB)
        assertTrue(replay().isSuccess)
        assertTrue("the change was edited since", replay(written = "edited").isFailure)
        assertTrue("A plays louder", replay(levelA = 1.2).isFailure)
        assertTrue("B plays quieter", replay(levelB = 0.8).isFailure)
        val other = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 43)).samples
        assertTrue("another A", replay(held = other).isFailure)
        assertTrue("another B", replay(copy = other).isFailure)
        assertTrue("A's music too far from where the clock says", replay(held = a.copyOfRange(3 * rate * channels, a.size)).isFailure)
        // Bluetooth: heard 400 ms after it is held, still found (8 Nov 2026).
        val late = (0.4 * rate).toInt()
        assertTrue("a clock 400 ms behind the music held", replay(held = a.copyOfRange(late * channels, a.size)).isSuccess)
        assertTrue("it would start before what has gone out", replay(earliest = frames).isFailure)
    }

    @Test
    fun `a change the editor heard on bands plays on bands`() {
        // No parts in the editor: the preview renders the change on bands, and so is it kept.
        val window = Preview.render(spec, Copy(a, rate, channels), Copy(b, rate, channels)).getOrThrow()
        assertEquals(LaneSet.BANDS, window.render.plan.spec.lanes)
        val stored = StoredChange.of(written, window.render, a, 0.0, b)
        assertTrue(!stored.onParts)
        val r = stored.replay(written, spec, a, (a.size / channels).toLong(), 0.0, b, b.size / channels, 0, 1.0, 1.0).getOrThrow()
        assertEquals(LaneSet.BANDS, r.plan.spec.lanes)
        assertEquals(window.render.plan.mixFrom, r.plan.mixFrom)
        assertArrayEquals(window.render.outgoing.samples, r.outgoing.samples)
    }

    @Test
    fun `a file that is not a kept change reads as none`() {
        assertNull(StoredChange.read(DataInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))))
        assertNull(StoredChange.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
    }

    @Test
    fun `A's music is looked for where it sounds, not in silence`() {
        // A that falls silent where the change starts: the probe is taken from music before it.
        val quietA = a.copyOf().also { m ->
            val from = (rendered().plan.mixFrom - rate / 2) * channels
            for (i in from.toInt() until m.size) m[i] = 0
        }
        val render = run {
            val facts = ChangeFacts(rate, channels, 20.0 * rate, (quietA.size / channels).toLong(), 1.0 * rate, (b.size / channels).toLong(), parts = true)
            val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
            LaneMixer(plan).render(quietA, b, 1.0, 1.0, parts(quietA), parts(b))
        }
        val stored = StoredChange.of(written, render, quietA, 0.0, b)
        var loud = 0
        for (s in stored.aProbe) if (kotlin.math.abs(s.toInt()) > 64) loud++
        assertTrue("the probe holds music", loud > stored.aProbe.size / 4)
        val skip = 2_000
        val held = quietA.copyOfRange(skip * channels, quietA.size)
        val r = stored.replay(written, spec, held, (held.size / channels).toLong(), skip * 1000.0 / rate, b, b.size / channels, 0, 1.0, 1.0).getOrThrow()
        assertEquals(render.plan.mixFrom - skip, r.plan.mixFrom)
    }
}
