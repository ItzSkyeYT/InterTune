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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** What each move sounds like, lane by lane, as the editor draws it and the engine plays it. */
class ShapesTest {

    private fun id(key: String) = LaneId.of(key)!!

    @Test
    fun `a fade pair keeps the summed power within 0,2 dB`() {
        val spec = Specs.fadeOnBands()
        // The lows swap: both over the beat before bar 5.
        var u = 3.7
        while (u <= 4.05) {
            val a = spec.laneAt(id("A.lows"), u).gain
            val b = spec.laneAt(id("B.lows"), u).gain
            val db = 10 * log10(a * a + b * b)
            assertTrue("%.3f: %.3f dB".format(u, db), abs(db) < 0.2)
            u += 0.01
        }
        assertEquals(1.0, spec.laneAt(id("A.lows"), 3.75).gain, 0.0)
        assertEquals(0.0, spec.laneAt(id("B.lows"), 3.75).gain, 0.0)
        assertEquals(sqrt(0.5), spec.laneAt(id("A.lows"), 3.875).gain, 1e-12)
        assertEquals(sqrt(0.5), spec.laneAt(id("B.lows"), 3.875).gain, 1e-12)
        assertEquals(0.0, spec.laneAt(id("A.lows"), 4.0).gain, 0.0)
        assertEquals(1.0, spec.laneAt(id("B.lows"), 4.0).gain, 0.0)
    }

    @Test
    fun `a cut is a step on its beat`() {
        val spec = Specs.vocalsOverCustom()
        assertEquals(1.0, spec.laneAt(id("A.drums"), 1.999).gain, 0.0)
        assertEquals(0.0, spec.laneAt(id("A.drums"), 2.0).gain, 0.0)
        val unmuted = spec.copy(muted = emptySet())
        assertEquals(0.0, unmuted.laneAt(id("B.vocals"), 7.999).gain, 0.0)
        assertEquals(1.0, unmuted.laneAt(id("B.vocals"), 8.0).gain, 0.0)
    }

    @Test
    fun `an echo sends the lane into it for the move, then its dry sound stops`() {
        val spec = Specs.vocalsOverCustom()
        assertEquals(LaneState(1.0), spec.laneAt(id("A.vocals"), 6.9))
        assertEquals(LaneState(1.0, echoSend = 1.0), spec.laneAt(id("A.vocals"), 7.5))
        assertEquals(LaneState(0.0), spec.laneAt(id("A.vocals"), 8.0))
    }

    @Test
    fun `muffle closes a low-pass, thin opens on the last beat`() {
        val spec = Specs.vocalsOverCustom().copy(curves = emptyMap())
        val start = spec.laneAt(id("A.melody"), 4.0 + 1e-9)
        assertEquals(20_000.0, start.lowPassHz!!, 1.0)
        assertEquals(sqrt(20_000.0 * 600.0), spec.laneAt(id("A.melody"), 5.0).lowPassHz!!, 1e-6)
        assertEquals(0.0, spec.laneAt(id("A.melody"), 6.0).gain, 0.0)
        // B's drums held thin at 300 Hz until the beat before bar 3, then opened down to 20 Hz.
        assertEquals(300.0, spec.laneAt(id("B.drums"), 0.5).highPassHz!!, 0.0)
        assertEquals(300.0, spec.laneAt(id("B.drums"), 1.74).highPassHz!!, 0.0)
        assertEquals(sqrt(300.0 * 20.0), spec.laneAt(id("B.drums"), 1.875).highPassHz!!, 1e-6)
        assertEquals(LaneState(1.0), spec.laneAt(id("B.drums"), 2.0))
        assertEquals(0.0, spec.laneAt(id("B.drums"), -0.5).gain, 0.0)
    }

    @Test
    fun `a curve wins over the lane's move`() {
        val spec = Specs.vocalsOverCustom()
        assertEquals(0.7, spec.laneAt(id("A.melody"), 4.5).gain, 1e-12)
        assertEquals((1.0 + 0.7) / 2, spec.laneAt(id("A.melody"), 4.25).gain, 1e-12)
        assertEquals(sqrt(20_000.0 * 600.0), spec.laneAt(id("A.melody"), 5.0).lowPassHz!!, 1e-6)
        assertNull(spec.laneAt(id("A.melody"), 5.0).highPassHz)
        assertEquals(1.0, spec.laneAt(id("A.melody"), -2.0).gain, 0.0)
        assertEquals(0.0, spec.laneAt(id("A.melody"), 7.0).gain, 0.0)
    }

    @Test
    fun `muted lanes and a plain change`() {
        val spec = Specs.vocalsOverCustom()
        assertEquals(0.0, spec.laneAt(id("B.vocals"), 8.0 - 1e-9).gain, 0.0)
        assertEquals(1.0, spec.laneAt(id("B.vocals"), 8.0).gain, 0.0)
        val plain = Specs.fadeOnBands().copy(plain = true)
        for (u in listOf(-1.0, 0.0, 4.0, 7.9)) {
            assertEquals(1.0, plain.laneAt(id("A.mids"), u).gain, 0.0)
            assertEquals(0.0, plain.laneAt(id("B.mids"), u).gain, 0.0)
        }
        assertEquals(0.0, plain.unheardMs(308_000.0), 0.0)
    }

    @Test
    fun `keep and from the start`() {
        val spec = Specs.fadeOnBands()
        val kept = spec.copy(moves = spec.moves + (id("A.highs") to Move(0.0, 0.0, How.KEEP)) + (id("B.mids") to Move(0.0, 0.0, How.FROM_START)))
        assertEquals(1.0, kept.laneAt(id("A.highs"), 7.99).gain, 0.0)
        assertEquals(0.0, kept.laneAt(id("A.highs"), 8.0).gain, 0.0)
        assertEquals(1.0, kept.laneAt(id("B.mids"), -2.0).gain, 0.0)
        assertEquals(8.0, kept.goneAt(id("A.highs")), 0.0)
    }

    @Test
    fun `where A is gone, and what of it is never heard`() {
        val spec = Specs.fadeOnBands()
        assertEquals(4.0, spec.goneAt(id("A.lows")), 0.0)
        assertEquals(8.0, spec.goneAt(id("A.mids")), 0.0)
        assertEquals(8.0, spec.aGoneAt(), 0.0)
        // Wake Me Up (5:08) loses its last 0:09, as the mock says.
        val unheard = spec.unheardMs(308_000.0)
        assertEquals(308_000.0 - (281_340.0 + 8 * 240_000.0 / 111), unheard, 1e-6)
        assertEquals(9, (unheard / 1000).toInt())
        val curved = Specs.vocalsOverCustom()
        assertEquals(6.0, curved.goneAt(id("A.melody")), 0.0)
        assertEquals(2.0, curved.goneAt(id("A.drums")), 0.0)
        assertEquals(8.0, curved.aGoneAt(), 0.0)
        assertEquals(0.0, curved.copy(muted = setOf(id("A.drums"))).goneAt(id("A.drums")), 0.0)
    }

    @Test
    fun `easing is a raised cosine, in log frequency for a cutoff`() {
        val keys = listOf(Key(0.0, 0.0), Key(2.0, 1.0))
        assertEquals(0.0, Shapes.eased(keys, -1.0), 0.0)
        assertEquals(0.5, Shapes.eased(keys, 1.0), 1e-12)
        assertEquals((1 - kotlin.math.cos(Math.PI * 0.25)) / 2, Shapes.eased(keys, 0.5), 1e-12)
        assertEquals(1.0, Shapes.eased(keys, 3.0), 0.0)
        assertEquals(sqrt(20.0 * 20_000.0), Shapes.easedHz(listOf(Key(0.0, 20.0), Key(2.0, 20_000.0)), 1.0)!!, 1e-6)
        assertNull(Shapes.easedHz(emptyList(), 1.0))
    }
}
