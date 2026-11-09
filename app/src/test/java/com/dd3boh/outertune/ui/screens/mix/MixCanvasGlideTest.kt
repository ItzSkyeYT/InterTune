/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.ui.unit.Density
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.engine.BHeard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The canvas and the Simple waveforms draw the next song where it is heard: with it adjusted,
 * through its way back to its own tempo once the song before is gone, not on the change's bars;
 * and the wall where its read ends a loop's length later where a loop plays in front of it.
 */
class MixCanvasGlideTest {

    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)
    private val facts = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, 236_000.0, 308_000.0, gridA, focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    @Test
    fun `the next song adjusted is drawn where it plays, its way back included`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        assertTrue(e.setMatchSide(Side.B))
        val s = e.spec
        val layout = CanvasLayout(e, s, 720f, Density(2f), view = 0f, viewStart = Double.NaN, viewSpan = Double.NaN, adjust = null, small = false)
        val heard = BHeard(s)
        var u = e.fitStart(s)
        while (u <= e.fitEnd(s)) {
            assertEquals(heard.msAt(u), layout.bMs(u), 0.0)
            u += 0.25
        }
        // On the change's bars until the song before is gone, then off them by the glide.
        assertEquals(s.bMsAt(s.lengthBars - 1), layout.bMs(s.lengthBars - 1), 0.0)
        val off = abs(layout.bMs(e.fitEnd(s)) - s.bMsAt(e.fitEnd(s)))
        println("at the canvas's end, the next song is %.1f ms from where the change's bars put it".format(off))
        assertTrue("$off", off > 5.0)
        // The Simple waveforms take the same clock.
        val frame = WaveFrame(e, s, WaveView.of(e, s, null), 1200f, 400f, 3f)
        assertEquals(heard.msAt(s.lengthBars + 1.5), frame.bHeard.msAt(s.lengthBars + 1.5), 0.0)
    }

    @Test
    fun `the wall where the next song's read ends comes a loop's length later with a loop in front of it`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        val plain = e.bWallU()
        assertTrue(e.setIntroLoop(2, 2))
        assertEquals(plain + 4.0, e.bWallU(), 1e-9)
    }
}
