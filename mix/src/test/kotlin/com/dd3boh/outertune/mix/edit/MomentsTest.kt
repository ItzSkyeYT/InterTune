/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What happens when in a change, as the now line and the moments sheet tell it. */
class MomentsTest {

    private fun id(key: String) = LaneId.of(key)!!

    @Test
    fun `Vocals over, as DESIGN 3_9 tells it`() {
        val spec = Presets.apply(Specs.vocalsOverCustom(), Preset.VOCALS_OVER)
        val m = Moments.of(spec)
        // "1 · bar 1 · After Hours' hats come in", "2 · bar 3 · Drums swap", then the bass on bar 5.
        assertEquals(Moment(0.0, Moment.Kind.ENTERS, Lane.DRUMS, How.THIN), m[0])
        assertEquals(Moment(2.0, Moment.Kind.SWAP, Lane.DRUMS), m[1])
        assertEquals(Moment(4.0, Moment.Kind.SWAP, Lane.BASS), m[2])
        assertEquals(Moment(4.0, Moment.Kind.LEAVES, Lane.MELODY, How.MUFFLE), m[3])
        assertEquals(Moment(5.0, Moment.Kind.ENTERS, Lane.MELODY, How.FADE), m[4])
        assertEquals(Moment(7.0, Moment.Kind.LEAVES, Lane.VOCALS, How.ECHO), m[5])
        assertEquals(Moment(8.0, Moment.Kind.ENTERS, Lane.VOCALS, How.CUT), m[6])
        assertEquals(7, m.size)
    }

    @Test
    fun `the now line names the latest moment, a swap first where several fall together`() {
        val fade = Specs.fadeOnBands()
        // "Bar 5 of 8 · Bass swap": on bands, the lows.
        assertEquals(Moment(4.0, Moment.Kind.SWAP, Lane.LOWS), Moments.latestAt(fade, 4.5))
        assertEquals(Moment.Kind.ENTERS, Moments.latestAt(fade, 1.0)!!.kind)
        assertNull(Moments.latestAt(fade, -0.5))
        assertNull(Moments.latestAt(fade, 1.0, from = 2.0))
    }

    @Test
    fun `mutes, curves, kept lanes, B from its start, and a plain change`() {
        val fade = Specs.fadeOnBands()
        val muted = Moments.of(fade.copy(muted = setOf(id("A.highs"), id("B.mids"))))
        assertEquals(Moment(0.0, Moment.Kind.A_MUTED, Lane.HIGHS), muted.first { it.lane == Lane.HIGHS && it.kind.order == 1 })
        assertEquals(Moment(8.0, Moment.Kind.B_WAITS, Lane.MIDS), muted.first { it.kind == Moment.Kind.B_WAITS })
        val curved = Moments.of(fade.copy(curves = mapOf(id("A.mids") to LaneCurve(listOf(Key(2.0, 1.0), Key(6.0, 0.0))))))
        assertEquals(Moment(2.0, Moment.Kind.A_CURVE, Lane.MIDS), curved.first { it.kind == Moment.Kind.A_CURVE })
        val started = fade.copy(moves = fade.moves + (id("B.mids") to Move(0.0, 0.0, How.FROM_START)) + (id("A.highs") to Move(0.0, 0.0, How.KEEP)))
        val s = Moments.of(started)
        assertEquals(Moment(-fade.b.atMs / fade.b.barMs, Moment.Kind.B_STARTS), s.first { it.kind == Moment.Kind.B_STARTS })
        assertEquals(0, s.count { it.lane == Lane.HIGHS && it.kind == Moment.Kind.LEAVES })
        assertEquals(emptyList<Moment>(), Moments.of(fade.copy(plain = true)))
    }
}
