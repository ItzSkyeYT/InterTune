/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeControlsWidthTest {

    private val play = 84.dp
    private val gutter = 24.dp
    private val icon = 42.dp

    /** The width each transport button gets inside the glass bar: the play button and 8dp either side come off first. */
    private fun slot(controls: Dp, slots: Int = 4): Dp = (controls - gutter * 2 - play - 16.dp) / slots

    @Test
    fun `a 1440p phone in landscape keeps the even split it always had`() {
        // 3120px at 600dpi is 832dp, and a cutout at the side leaves the row about 800dp.
        assertEquals(400f, landscapeControlsWidth(800.dp, play, 4, gutter).value, 0.01f)
    }

    @Test
    fun `every transport button fits at full size on any phone wide enough for two panes`() {
        // Two panes start at 600dp. A navigation bar at the side and a cutout opposite can take
        // 80dp of that, so the row can be as narrow as 520dp.
        var available = 520.dp
        while (available <= 1400.dp) {
            val controls = landscapeControlsWidth(available, play, 4, gutter)
            assertTrue("a slot narrower than the icon at $available", slot(controls) >= icon)
            assertTrue(
                "the artwork kept less than 38% at $available",
                available - controls >= available * 0.38f - 0.01.dp
            )
            available += 10.dp
        }
    }

    @Test
    fun `a narrow phone gets more than half, rather than crowding the buttons`() {
        // 640dp less a 40dp cutout: half would give each button a 38dp slot, narrower than its icon.
        val available = 600.dp
        assertTrue(slot(available / 2) < icon)
        assertTrue(landscapeControlsWidth(available, play, 4, gutter) > available / 2)
    }
}
