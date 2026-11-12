/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The presets as rules: playable at every length, found again by match, and kept in time on bands. */
class PresetsTest {

    private val rules = Preset.entries.filter { it != Preset.AUTO && it != Preset.CUSTOM }

    private fun base(lanes: LaneSet, bars: Int): TransitionSpec {
        val fade = Specs.fadeOnBands(bars)
        return if (lanes == LaneSet.BANDS) fade else Presets.apply(fade.copy(lanes = LaneSet.PARTS, moves = emptyMap()), Preset.BLEND)
    }

    @Test
    fun `every preset writes a playable change at every length, on bands and on parts`() {
        for (lanes in LaneSet.entries) for (bars in TransitionSpec.LENGTHS_BARS) for (p in rules) {
            val spec = Presets.apply(base(lanes, bars), p)
            assertEquals("$p on ${lanes.key} at $bars bars: ${spec.problems()}", emptyList<String>(), spec.problems())
            assertEquals(p, spec.preset)
            assertEquals(lanes.ids.toSet(), spec.moves.keys)
        }
        // Timed in seconds too, as Auto writes a blend without a trusted grid.
        val seconds = Specs.fadeOnBands().copy(bars = 0, seconds = 6.0, a = Specs.a.copy(bpm = TransitionSpec.NOMINAL_BPM), b = Specs.b.copy(bpm = TransitionSpec.NOMINAL_BPM))
        for (p in listOf(Preset.BLEND, Preset.RISE, Preset.FADE, Preset.CUT, Preset.ECHO_OUT)) {
            assertTrue(Presets.apply(seconds, p).isValid)
        }
    }

    @Test
    fun `the mock's Fade and the Vocals over plan of 19 Oct, move for move`() {
        assertEquals(Specs.fadeOnBands().moves, Presets.moves(Preset.FADE, LaneSet.BANDS, 8.0, 0.25))
        assertEquals(Specs.vocalsOverCustom().moves, Presets.moves(Preset.VOCALS_OVER, LaneSet.PARTS, 8.0, 0.25))
        assertNull(Presets.moves(Preset.AUTO, LaneSet.BANDS, 8.0, 0.25))
        assertNull(Presets.moves(Preset.CUSTOM, LaneSet.BANDS, 8.0, 0.25))
    }

    @Test
    fun `match lights the preset that writes the moves, and Custom for anything else`() {
        for (lanes in LaneSet.entries) for (bars in TransitionSpec.LENGTHS_BARS) for (p in rules) {
            if (p.needsParts && lanes != LaneSet.PARTS) continue
            val spec = Presets.apply(base(lanes, bars), p)
            assertEquals("$p on ${lanes.key} at $bars bars", p, Presets.match(spec))
            val id = spec.moves.keys.first()
            val nudged = spec.copy(moves = spec.moves + (id to spec.moves.getValue(id).let { it.copy(u = it.u + spec.beat) }))
            assertEquals(Preset.CUSTOM, Presets.match(nudged))
            assertEquals(Preset.CUSTOM, Presets.match(spec.copy(muted = setOf(id))))
        }
        val auto = Presets.apply(Specs.fadeOnBands(), Preset.RISE).copy(preset = Preset.AUTO, from = Preset.RISE)
        assertEquals(Preset.AUTO, Presets.match(auto, auto = auto))
    }

    @Test
    fun `a change made on parts plays on bands with its own timing`() {
        val parts = Presets.apply(Specs.vocalsOverCustom(), Preset.VOCALS_OVER)
        val bands = Presets.onBands(parts)
        assertEquals(LaneSet.BANDS, bands.lanes)
        assertTrue(bands.problems().toString(), bands.isValid)
        assertEquals(Presets.moves(Preset.VOCALS_OVER, LaneSet.BANDS, 8.0, 0.25), bands.moves)
        assertEquals(Preset.VOCALS_OVER, bands.preset)
        // The voice's echo at bar 8 is A's mids; B's mids come in with its melody.
        assertEquals(Move(7.0, 1.0, How.ECHO), bands.moves[LaneId.of("A.mids")])
        assertEquals(Move(5.0, 1.0, How.FADE), bands.moves[LaneId.of("B.mids")])
        // A curve and a mute on a part go with the band that part is played on.
        val adjusted = Specs.vocalsOverCustom().copy(curves = mapOf(LaneId.of("A.bass")!! to LaneCurve(listOf(Key(0.0, 1.0), Key(4.0, 0.0)))))
        val mapped = Presets.onBands(adjusted)
        assertEquals(setOf(LaneId.of("A.lows")), mapped.curves.keys)
        assertEquals(emptySet<LaneId>(), mapped.muted)
        assertEquals(setOf(LaneId.of("B.mids")), Presets.onBands(adjusted.copy(muted = setOf(LaneId.of("B.melody")!!))).muted)
    }

    @Test
    fun `a new length writes a preset again and stretches a custom change`() {
        val fade = Specs.fadeOnBands()
        assertEquals(Specs.fadeOnBands(16).moves, Presets.withLength(fade, 16).moves)
        val custom = fade.copy(preset = Preset.CUSTOM, moves = fade.moves + (LaneId.of("A.mids")!! to Move(2.0, 4.0, How.MUFFLE)))
        val longer = Presets.withLength(custom, 16)
        assertEquals(16, longer.bars)
        assertEquals(Move(4.0, 8.0, How.MUFFLE), longer.moves[LaneId.of("A.mids")])
        assertTrue(longer.isValid)
    }
}
