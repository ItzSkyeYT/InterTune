/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Any phone: a slide or the start screen on a phone shorter than the one they are laid out for is
 * drawn smaller to fit, as the design is zoomed on a small phone, but only upright, never below a
 * floor, and never with large text made smaller than usual.
 */
class RecapFitTest {
    @Test
    fun `a phone with room enough draws everything at its own size`() {
        assertEquals(1f, RecapFit.zoom(412.dp, 760.dp, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(448.dp, RecapFit.ROOM, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(393.dp, 851.dp, RecapFit.START, 1.3f))
    }

    @Test
    fun `a small phone held upright draws a slide and the start screen smaller to fit`() {
        // 360 x 640 dp: about 440 dp between the bars and the song, the whole 640 for the start screen.
        assertEquals(440f / 600f, RecapFit.zoom(360.dp, 440.dp, RecapFit.ROOM, 1f), 1e-4f)
        assertEquals(640f / 736f, RecapFit.zoom(360.dp, 640.dp, RecapFit.START, 1f), 1e-4f)
    }

    @Test
    fun `on its side nothing is made smaller, it scrolls`() {
        assertEquals(1f, RecapFit.zoom(997.dp, 300.dp, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(997.dp, 448.dp, RecapFit.START, 1.3f))
    }

    @Test
    fun `never below the floor, and large text never below its usual size`() {
        assertEquals(RecapFit.LEAST, RecapFit.zoom(320.dp, 330.dp, RecapFit.ROOM, 1f), 1e-4f)
        assertEquals(1f / 1.3f, RecapFit.zoom(360.dp, 440.dp, RecapFit.ROOM, 1.3f), 1e-4f)
        for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
            val z = RecapFit.zoom(360.dp, 400.dp, RecapFit.ROOM, scale)
            assertTrue("text at $scale drawn at ${scale * z}", scale * z >= 1f - 1e-4f || z == RecapFit.LEAST)
        }
    }
}
