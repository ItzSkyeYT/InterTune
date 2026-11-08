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

/**
 * After a jump into a kept change (his word of 8 Nov 2026: "that's unacceptable that it drops the
 * transition just cuz i jumped through the song"): A's music is found by the stretches kept across
 * the change, and the change is joined where it can still be heard, B coming in where it would
 * have been by then.
 */
class StoredChangeJumpTest {

    private val rate = 44_100
    private val channels = 2

    private val a = com.dd3boh.outertune.mix.Synth.song(com.dd3boh.outertune.mix.SynthSpec(bpm = 120.0, bars = 24, seed = 41)).samples
    private val b = com.dd3boh.outertune.mix.Synth.song(com.dd3boh.outertune.mix.SynthSpec(bpm = 120.0, bars = 24, seed = 42)).samples

    // A fades from bar 1 over six bars; B comes in at its own 9 s, four bars into the change, and fades in over two.
    private val spec = TransitionSpec(
        a = Anchor("song-a", 20_000.0, 120.0), b = Anchor("song-b", 1_000.0, 120.0), lanes = LaneSet.PARTS,
        bars = 8, beatMatch = false, preset = Preset.CUSTOM,
        moves = LaneSet.PARTS.lanes.associate { LaneId(Side.A, it) to Move(0.0, 6.0, How.FADE) } +
            LaneSet.PARTS.lanes.associate { LaneId(Side.B, it) to Move(4.0, 2.0, How.FADE) },
        bStartMs = 9_000.0,
    )
    private val written = "the change as saved"

    private fun parts(music: ShortArray): List<ShortBuffer> {
        val v = ShortArray(music.size) { (music[it] / 3).toShort() }
        val d = ShortArray(music.size) { (music[it] / 4).toShort() }
        val bs = ShortArray(music.size) { (music[it] / 6).toShort() }
        val m = ShortArray(music.size) { (music[it] - v[it] - d[it] - bs[it]).toShort() }
        return listOf(v, d, bs, m).map { ShortBuffer.wrap(it) }
    }

    private val render: ChangeRender by lazy {
        val facts = ChangeFacts(rate, channels, 20.0 * rate, (a.size / channels).toLong(), 1.0 * rate, (b.size / channels).toLong(), parts = true)
        val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
        LaneMixer(plan).render(a, b, 1.0, 1.0, parts(a), parts(b))
    }

    private fun bytesOf(change: StoredChange) = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { change.write(it) } }.toByteArray()

    private val stored: StoredChange by lazy {
        StoredChange.read(DataInputStream(ByteArrayInputStream(bytesOf(StoredChange.of(written, render, a, 0.0, b)))))!!
    }

    /** A as held from its frame [from]. */
    private fun heldFrom(from: Long) = a.copyOfRange((from * channels).toInt(), a.size)

    private val open = StoredChange.EntryRule.Open(from = 0, speed = 20.0, margin = 0)

    @Test
    fun `the stretches of A across the change are kept with it and read back`() {
        assertTrue("stretches across the change", stored.probes.size >= 10)
        val step = rate.toLong() * StoredChange.PROBE_STEP_MS / 1000
        assertEquals(render.plan.mixFrom + step, stored.probes.first().from)
        for (p in stored.probes) {
            assertEquals((rate * StoredChange.PROBE_MS / 1000) * channels, p.samples.size)
            assertArrayEquals(a.copyOfRange((p.from * channels).toInt(), (p.from * channels).toInt() + p.samples.size), p.samples)
        }
        // A file kept before them reads as one without them, and still plays from its start.
        val newer = bytesOf(stored)
        val older = newer.copyOf(newer.size).let { bytes ->
            // The same file without its stretches: their count, nothing, and the first magic.
            val noStretches = bytesOf(StoredChange(
                stored.spec, stored.onParts, stored.rate, stored.channels, stored.aStartMs, stored.aBar1, stored.bBar1, stored.anchors,
                stored.attenuationA, stored.attenuationB, stored.mixFrom, stored.bStart, stored.mixTo, stored.outFirst, stored.outSamples,
                stored.headStart, stored.headStep, stored.headValues, stored.inFirst, stored.inSamples, stored.inEntry, stored.inBeatFrames,
                stored.peakBefore, stored.peakAfter, stored.aProbeFrom, stored.aProbe, stored.bProbeFrom, stored.bProbe,
            ))
            noStretches.copyOf(noStretches.size - 4).also { it[3] = 0x31 }
        }
        val back = StoredChange.read(DataInputStream(ByteArrayInputStream(older)))!!
        assertTrue(back.probes.isEmpty())
        assertTrue(!StoredChange.headOf(DataInputStream(ByteArrayInputStream(older)))!!.probes)
        assertTrue(StoredChange.headOf(DataInputStream(ByteArrayInputStream(newer)))!!.probes)
        assertTrue(back.replayFrom(written, spec, a, (a.size / channels).toLong(), 0.0, b, b.size / channels, 0, 1.0, 1.0, open).getOrThrow().joinedAt == null)
        // Found whole once, it is kept again with them, from the music held.
        val skip = 3_000L
        val more = back.withProbes(heldFrom(skip), (a.size / channels - skip), skip)
        assertEquals(stored.probes.map { it.from }, more.probes.map { it.from })
        for (i in more.probes.indices) assertArrayEquals(stored.probes[i].samples, more.probes[i].samples)
    }

    @Test
    fun `held from before the change it plays whole, as replay has it`() {
        val skip = 3_000L
        val held = heldFrom(skip)
        val frames = (held.size / channels).toLong()
        val whole = stored.replay(written, spec, held, frames, skip * 1000.0 / rate + 40.0, b, b.size / channels, 0, 1.0, 1.0).getOrThrow()
        val play = stored.replayFrom(written, spec, held, frames, skip * 1000.0 / rate + 40.0, b, b.size / channels, 0, 1.0, 1.0, StoredChange.EntryRule.Fixed(render.plan.bEntry)).getOrThrow()
        assertEquals(null, play.joinedAt)
        assertEquals(skip, play.shift)
        val p = whole.heldPlan()
        assertEquals(p.bStart, play.plan.bStart)
        assertEquals(p.aFirstProcessed, play.plan.aFirstProcessed)
        assertEquals(p.aGone, play.plan.aGone)
        assertEquals(p.shapedUntil, play.plan.shapedUntil)
        assertEquals(p.tailFrames, play.plan.tailFrames)
        assertEquals(0, play.plan.crossIn)
        assertEquals(whole.outgoing.firstFrame, play.outgoing.firstFrame)
        assertEquals(whole.incoming.firstFrame, play.incoming.firstFrame)
        assertArrayEquals(whole.outgoing.samples, play.outgoing.samples)
        assertArrayEquals(whole.incoming.samples, play.incoming.samples)
    }

    @Test
    fun `a jump into the change before the next song comes in is joined there, the next song in as planned`() {
        val plan = render.plan
        // Held from two seconds into the change, its start gone by; the clock a second out.
        val from = plan.mixFrom + 2L * rate
        assertTrue(from + rate < plan.bStart)
        val held = heldFrom(from)
        val frames = (held.size / channels).toLong()
        for (clockError in listOf(-900.0, 0.0, 1_100.0)) {
            val earliest = rate / 2L
            val play = stored.replayFrom(written, spec, held, frames, from * 1000.0 / rate + clockError, b, b.size / channels, earliest, 1.0, 1.0, open).getOrThrow()
            assertEquals(from, play.shift)
            assertEquals(earliest, play.joinedAt)
            assertEquals(earliest, play.plan.aFirstProcessed)
            assertTrue(play.plan.crossIn > 0)
            assertEquals(plan.bStart - from, play.plan.bStart)
            assertEquals(plan.bEntry, play.plan.bEntry)
            // From where it is joined, the change as rendered, moved to what is held.
            for (o in earliest until play.plan.bStart step 97) for (c in 0 until channels) assertEquals(render.outgoing.at(o + from, c), play.outgoing.at(o, c))
            for (o in play.plan.bStart until plan.mixTo - from step 97) for (c in 0 until channels) assertEquals(render.incoming.at(o + from, c), play.incoming.at(o, c))
        }
    }

    @Test
    fun `a jump past where the next song comes in brings it in where it would have been by then`() {
        val plan = render.plan
        val from = plan.bStart + 3L * rate
        val held = heldFrom(from)
        val frames = (held.size / channels).toLong()
        val earliest = rate.toLong()
        val play = stored.replayFrom(written, spec, held, frames, from * 1000.0 / rate - 300.0, b, b.size / channels, earliest, 1.0, 1.0, open).getOrThrow()
        val join = play.joinedAt!!
        assertTrue(join >= earliest)
        // A crossed into the change as the end drains, then B in where it would have been by then:
        // three seconds and the join further in, rising over the same few ms.
        val crossIn = play.plan.crossIn
        val change = play.plan.bStart
        assertEquals(join + crossIn, change)
        assertEquals(plan.bEntry + (change + from - plan.bStart), play.plan.bEntry)
        assertEquals(change, play.incoming.firstFrame)
        assertEquals(play.plan.bEntry, play.incoming.entry)
        // Skipped to there at twenty times real time by the time anything of the change is heard.
        assertTrue(join >= open.from + play.plan.bEntry / open.speed)
        for (k in 0 until crossIn) for (c in 0 until channels) {
            val g = (k + 1).toDouble() / crossIn
            assertEquals(Math.round(render.incoming.at(change + k + from, c) * g).toInt(), play.incoming.at(change + k, c).toInt())
        }
        for (o in change + crossIn until plan.mixTo - from step 89) for (c in 0 until channels) assertEquals(render.incoming.at(o + from, c), play.incoming.at(o, c))
        for (o in join until plan.mixTo - from step 89) for (c in 0 until channels) assertEquals(render.outgoing.at(o + from, c), play.outgoing.at(o, c))
        // Held for the next song from its first frame, it cannot come in where it would be: as rendered, or not at all.
        assertTrue(stored.replayFrom(written, spec, held, frames, from * 1000.0 / rate, b, b.size / channels, earliest, 1.0, 1.0, StoredChange.EntryRule.Fixed(plan.bEntry)).isFailure)
    }

    @Test
    fun `a slow skip brings the next song in later, where it still would have been, and never past what is held`() {
        val plan = render.plan
        val from = plan.mixFrom + rate.toLong()
        val held = heldFrom(from)
        val frames = (held.size / channels).toLong()
        val slow = StoredChange.EntryRule.Open(from = 0, speed = 1.2, margin = rate / 2L)
        val play = stored.replayFrom(written, spec, held, frames, from * 1000.0 / rate, b, b.size / channels, 0, 1.0, 1.0, slow).getOrThrow()
        val e = play.plan.bEntry
        assertTrue(play.joinedAt!! >= slow.from + e / slow.speed + slow.margin)
        // Later than planned: B's 9 s cannot be skipped to in time at that pace.
        assertTrue(play.plan.bStart > plan.bStart - from)
        assertEquals(plan.bEntry + (play.plan.bStart + from - plan.bStart), e)
        // A jump so near A's end that the next song would come in after it is not joined.
        val late = (a.size / channels) - rate / 4L
        val tail = heldFrom(late)
        assertTrue(stored.replayFrom(written, spec, tail, (tail.size / channels).toLong(), late * 1000.0 / rate, b, b.size / channels, rate.toLong(), 1.0, 1.0, open).isFailure)
    }

    @Test
    fun `nothing is joined on music that is not A's, or for a change edited since`() {
        val from = render.plan.mixFrom + 2L * rate
        val held = heldFrom(from)
        val frames = (held.size / channels).toLong()
        val other = com.dd3boh.outertune.mix.Synth.song(com.dd3boh.outertune.mix.SynthSpec(bpm = 120.0, bars = 24, seed = 43)).samples
        assertTrue(stored.replayFrom(written, spec, other, (other.size / channels).toLong(), from * 1000.0 / rate, b, b.size / channels, 0, 1.0, 1.0, open).isFailure)
        assertTrue(stored.replayFrom("edited", spec, held, frames, from * 1000.0 / rate, b, b.size / channels, 0, 1.0, 1.0, open).isFailure)
        // The clock three seconds out: further than A's music is looked for.
        assertTrue(stored.replayFrom(written, spec, held, frames, from * 1000.0 / rate + 3_000.0, b, b.size / channels, 0, 1.0, 1.0, open).isFailure)
    }
}
