/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Play pill floats over the Advanced page above the player and the bars. On a Pixel 5 (851 dp)
 * the canvas and what is over it take 593 dp, the player and the bar 168: 10 dp were left above the
 * pill, which sat over the presets row and hid the middle of it, and no scroll could take a row out
 * from under it (10 Nov 2026). Where there is no room above the pill the page scrolls as one, so a
 * row scrolls up clear of it as a row does of any floating bar, and the pill does not move.
 */
class MixEditorRoomTest {

    private val source = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorScreen.kt").readText()

    @Test
    fun `a Pixel 5 has no room above the pill, so its page scrolls as one`() {
        assertFalse(lowerPinned(roomDp = 851f - 593f, barsDp = 168f))
        // Nor does a phone a little taller.
        assertFalse(lowerPinned(roomDp = 915f - 593f, barsDp = 168f))
    }

    @Test
    fun `a screen with room for a row above the pill keeps the lower part's own scroll`() {
        // 1040 dp: 447 dp under the canvas, 199 of them above the pill.
        assertTrue(lowerPinned(roomDp = 1040f - 593f, barsDp = 168f))
        assertTrue(lowerPinned(roomDp = 140f + 80f + 168f, barsDp = 168f))
        assertFalse(lowerPinned(roomDp = 140f + 80f + 168f - 1f, barsDp = 168f))
    }

    @Test
    fun `the pill's room is the pill and the dp under it`() {
        val transport = source.substringAfter("private fun Transport(").substringBefore("/** On its side, the top bar's controls")
        assertTrue("the pill is 64 dp tall", "Modifier.height(64.dp)" in transport)
        assertTrue("with 16 dp under it", ".padding(bottom = 16.dp, end = if (landscape) 24.dp else 0.dp)" in source)
        assertTrue(PILL_ZONE_DP == 64f + 16f)
    }

    @Test
    fun `the page's end leaves room for the pill and for the bars under it`() {
        val room = source.substringAfter("private fun PillRoom(").substringBefore("\n}\n")
        assertTrue(room, "Modifier.windowInsetsBottomHeight(insets)" in room)
        assertTrue(room, "Modifier.height(104.dp)" in room)
        // The bars' room was the top inset's height of the bottom inset, which is none.
        assertTrue("windowInsetsTopHeight(insets.only(WindowInsetsSides.Bottom))" !in source)
        // Both ways the page lays out its lower part end with it.
        assertTrue(source.substringAfter("if (pinned) {").substringBefore("FloatingTopBar").split("PillRoom(insets)").size == 3)
    }
}
