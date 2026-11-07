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
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A change made on parts (vocals, drums, bass, melody), played from each song's parts as the editor's
 * parts store holds them: the four add up to the song, so equal lanes give what the song gives, and
 * a part taken away takes exactly its own samples with it.
 */
class LanePartsTest {

    private val rate = 44_100

    private fun spec(moves: Map<LaneId, Move>, muted: Set<LaneId> = emptySet(), beatMatch: Boolean = false) = TransitionSpec(
        a = Anchor("song-a", 20_000.0, 120.0), b = Anchor("song-b", 1_000.0, 120.0), lanes = LaneSet.PARTS,
        bars = 8, beatMatch = beatMatch, preset = Preset.CUSTOM, moves = moves, muted = muted,
    )

    private fun every(side: Side, move: Move) = LaneSet.PARTS.lanes.associate { LaneId(side, it) to move }

    private fun facts(a: ShortArray, b: ShortArray, parts: Boolean = true) =
        ChangeFacts(rate, 2, 20.0 * rate, (a.size / 2).toLong(), 1.0 * rate, (b.size / 2).toLong(), parts = parts)

    private fun planned(spec: TransitionSpec, facts: ChangeFacts): LanePlan = when (val r = SpecPlan.plan(spec, facts)) {
        is SpecPlan.Result.Planned -> r.plan
        is SpecPlan.Result.Refused -> throw AssertionError("refused: ${r.why}")
    }

    /** [music] cut into four parts that add up to it: a third, a quarter, a sixth and the rest. */
    private fun quarters(music: ShortArray): List<java.nio.ShortBuffer> = quarterArrays(music).map { java.nio.ShortBuffer.wrap(it) }

    private fun quarterArrays(music: ShortArray): List<ShortArray> {
        val v = ShortArray(music.size) { (music[it] / 3).toShort() }
        val d = ShortArray(music.size) { (music[it] / 4).toShort() }
        val b = ShortArray(music.size) { (music[it] / 6).toShort() }
        val m = ShortArray(music.size) { (music[it] - v[it] - d[it] - b[it]).toShort() }
        return listOf(v, d, b, m)
    }

    private fun alone(music: ShortArray, at: Int): List<java.nio.ShortBuffer> = List(4) { java.nio.ShortBuffer.wrap(if (it == at) music else ShortArray(music.size)) }

    private val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 31)).samples
    private val b = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 32)).samples

    @Test
    fun `parts play only where they are at hand, and not yet with a loop`() {
        val moves = every(Side.A, Move(2.0, 4.0, How.FADE)) + every(Side.B, Move(2.0, 4.0, How.FADE))
        val refused = SpecPlan.plan(spec(moves), facts(a, b, parts = false))
        assertTrue(refused is SpecPlan.Result.Refused)
        assertTrue(SpecPlan.plan(spec(moves), facts(a, b)) is SpecPlan.Result.Planned)
        val stretched = spec(moves, beatMatch = true).copy(b = Anchor("song-b", 1_000.0, 122.0))
        assertTrue(stretched.beatMatched)
        assertTrue("a stretch takes the parts at the song's positions", SpecPlan.plan(stretched, facts(a, b)) is SpecPlan.Result.Planned)
        val looped = SpecPlan.plan(spec(moves).copy(introLoop = com.dd3boh.outertune.mix.edit.IntroLoop(1, 0.0, 4)), facts(a, b))
        assertTrue("B's loop joins the parts where B's music says", looped is SpecPlan.Result.Planned)
    }

    @Test
    fun `looped, B's parts still add up to B looped`() {
        val moves = every(Side.A, Move(2.0, 4.0, How.FADE)) + every(Side.B, Move(2.0, 4.0, How.FADE))
        val plan = planned(spec(moves).copy(introLoop = com.dd3boh.outertune.mix.edit.IntroLoop(1, 0.0, 4)), facts(a, b))
        assertTrue("B is looped", plan.loop != null)
        val split = LaneMixer(plan).render(a, b, 1.0, 1.0, quarters(a), quarters(b))
        val whole = LaneMixer(plan).render(a, b, 1.0, 1.0, alone(a, 0), alone(b, 3))
        val x = split.mix(a, b, plan.mixFrom, plan.mixTo)
        val y = whole.mix(a, b, plan.mixFrom, plan.mixTo)
        var worst = 0
        for (i in x.indices) worst = maxOf(worst, abs(x[i] - y[i]))
        assertTrue("looped: the parts' sum strays $worst from the song's", worst <= 3)
    }

    @Test
    fun `stretched, A or B, the parts still add up to the song stretched`() {
        val moves = every(Side.A, Move(2.0, 4.0, How.FADE)) + every(Side.B, Move(2.0, 4.0, How.FADE))
        for (side in listOf(Side.A, Side.B)) {
            val s = spec(moves, beatMatch = true).copy(b = Anchor("song-b", 1_000.0, 122.0), matchSide = side)
            assertTrue(s.beatMatched)
            val plan = planned(s, facts(a, b))
            if (side == Side.A) assertTrue("A is stretched", !plan.aMap.isIdentity) else assertTrue("B is the one adjusted", plan.bStretch != null)
            val split = LaneMixer(plan).render(a, b, 1.0, 1.0, quarters(a), quarters(b))
            val whole = LaneMixer(plan).render(a, b, 1.0, 1.0, alone(a, 0), alone(b, 3))
            val x = split.mix(a, b, plan.mixFrom, plan.mixTo)
            val y = whole.mix(a, b, plan.mixFrom, plan.mixTo)
            var worst = 0
            for (i in x.indices) worst = maxOf(worst, abs(x[i] - y[i]))
            assertTrue("$side stretched: the parts' sum strays $worst from the song's", worst <= 3)
        }
    }

    @Test
    fun `equal lanes on parts give what the song itself gives`() {
        for (how in listOf(How.FADE, How.CUT, How.ECHO)) {
            val moves = every(Side.A, Move(2.0, 4.0, how)) + every(Side.B, Move(2.0, 4.0, if (how.forEntry) how else How.FADE))
            val plan = planned(spec(moves), facts(a, b))
            val split = LaneMixer(plan).render(a, b, 1.0, 1.0, quarters(a), quarters(b))
            val whole = LaneMixer(plan).render(a, b, 1.0, 1.0, alone(a, 0), alone(b, 3))
            val x = split.mix(a, b, plan.mixFrom, plan.mixTo)
            val y = whole.mix(a, b, plan.mixFrom, plan.mixTo)
            var worst = 0
            for (i in x.indices) worst = maxOf(worst, abs(x[i] - y[i]))
            assertTrue("$how: the parts' sum strays $worst from the song's", worst <= 3)
        }
    }

    @Test
    fun `a part taken away takes exactly its own samples with it`() {
        val voice = LaneId(Side.A, Lane.VOCALS)
        // Every lane of A stays at full through the change but A's voice, which is taken away.
        // B comes in only at the very end, so A plays alone where it is looked at, under no headroom.
        val moves = every(Side.A, Move(6.0, 0.0, How.CUT)) + every(Side.B, Move(7.9, 0.0, How.CUT))
        val plan = planned(spec(moves, muted = setOf(voice)), facts(a, b))
        val pieces = quarterArrays(a)
        val parts = pieces.map { java.nio.ShortBuffer.wrap(it) }
        val withoutVoice = ShortArray(a.size) { (a[it] - pieces[0][it]).toShort() }
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0, parts, alone(b, 3))
        val at = plan.mixFrom + (plan.mixTo - plan.mixFrom) / 4
        val bar = render.outgoing
        var worst = 0
        for (o in at until at + rate / 10) for (c in 0..1) {
            worst = maxOf(worst, abs(bar.at(o, c) - (withoutVoice[(o * 2 + c).toInt()] * render.attenuationA).toInt()))
        }
        assertTrue("A without its voice strays $worst from A minus the voice part", worst <= 2)
    }

    @Test
    fun `a change on bands opens on parts with each part moving as its band did`() {
        val bands = TransitionSpec(
            a = Anchor("song-a", 20_000.0, 120.0), b = Anchor("song-b", 1_000.0, 120.0), lanes = LaneSet.BANDS, bars = 8,
            preset = Preset.CUSTOM,
            moves = mapOf(
                LaneId(Side.A, Lane.LOWS) to Move(1.0, 2.0, How.CUT), LaneId(Side.A, Lane.MIDS) to Move(2.0, 3.0, How.FADE),
                LaneId(Side.A, Lane.HIGHS) to Move(3.0, 1.0, How.FADE), LaneId(Side.B, Lane.MIDS) to Move(0.0, 4.0, How.FADE),
            ),
        )
        val parts = Presets.onParts(bands)
        assertEquals(LaneSet.PARTS, parts.lanes)
        assertEquals(bands.moves[LaneId(Side.A, Lane.LOWS)], parts.moves[LaneId(Side.A, Lane.BASS)])
        assertEquals(bands.moves[LaneId(Side.A, Lane.HIGHS)], parts.moves[LaneId(Side.A, Lane.DRUMS)])
        assertEquals(bands.moves[LaneId(Side.A, Lane.MIDS)], parts.moves[LaneId(Side.A, Lane.VOCALS)])
        assertEquals(bands.moves[LaneId(Side.A, Lane.MIDS)], parts.moves[LaneId(Side.A, Lane.MELODY)])
        assertEquals(bands.moves[LaneId(Side.B, Lane.MIDS)], parts.moves[LaneId(Side.B, Lane.VOCALS)])
        // And back: the bands it plays on in the playlist are the ones it was opened from.
        assertEquals(bands.moves, Presets.onBands(parts).moves)
    }
}
