/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind keypad navigation, on a 480 x 640 screen laid out like the app's: a list
 * filling the screen, a top bar, a floating button, the mini player and the navigation bar.
 */
class DpadFocusTest {

    private val list = Rect(0f, 0f, 480f, 640f)
    private val searchPill = Rect(24f, 48f, 456f, 120f)
    private val rowAbove = Rect(0f, 229f, 480f, 325f)
    private val rowJustAbovePlayer = Rect(0f, 300f, 480f, 390f)
    private val rowUnderPlayer = Rect(0f, 400f, 480f, 490f)
    private val fab = Rect(372f, 300f, 456f, 384f)
    private val miniPlayerSheet = Rect(0f, 394f, 480f, 640f)
    private val miniTitle = Rect(0f, 394f, 260f, 490f)
    private val miniPlay = Rect(340f, 412f, 400f, 472f)
    private val homeTab = Rect(0f, 490f, 111f, 568f)
    private val libraryTab = Rect(369f, 490f, 480f, 568f)

    @Test
    fun `a list around a floating control is never a candidate`() {
        // What stranded focus on floating controls: the list encloses them, so it is neither
        // above nor below them.
        for (direction in DpadDirection.entries) {
            assertFalse(isDpadCandidate(fab, list, direction))
            assertFalse(isDpadCandidate(homeTab, miniPlayerSheet, direction))
        }
    }

    @Test
    fun `up from the navigation bar reaches the mini player rather than skipping it`() {
        val candidates = listOf(rowAbove, miniTitle, miniPlay, miniPlayerSheet)
        assertEquals(1, dpadTargets(homeTab, DpadDirection.Up, candidates).first())
        assertEquals(2, dpadTargets(libraryTab, DpadDirection.Up, candidates).first())
    }

    @Test
    fun `down from the search pill reaches the first row under it`() {
        val candidates = listOf(rowJustAbovePlayer, rowAbove, homeTab)
        assertEquals(1, dpadTargets(searchPill, DpadDirection.Down, candidates).first())
    }

    @Test
    fun `up from the floating button goes back into the list`() {
        val candidates = listOf(searchPill, rowAbove, rowJustAbovePlayer)
        // The row beside the button overlaps it, so the one above it is next.
        assertEquals(1, dpadTargets(fab, DpadDirection.Up, candidates).first())
    }

    @Test
    fun `left and right move along a row of buttons`() {
        val candidates = listOf(miniTitle, miniPlay, homeTab)
        assertEquals(0, dpadTargets(miniPlay, DpadDirection.Left, candidates).first())
        assertEquals(listOf(1), dpadTargets(miniTitle, DpadDirection.Right, candidates))
    }

    @Test
    fun `a target straight ahead beats one off to the side that is not wholly nearer`() {
        val from = Rect(0f, 0f, 100f, 50f)
        val ahead = Rect(0f, 100f, 100f, 150f)
        val aside = Rect(200f, 60f, 300f, 110f)
        assertEquals(listOf(0, 1), dpadTargets(from, DpadDirection.Down, listOf(ahead, aside)))
    }

    @Test
    fun `down from a top bar reaches the page before a bar far below that lines up with it`() {
        // An album page: the back circle, the buttons beside the cover, and the mini player's
        // title far below, which shares the back circle's column.
        val back = Rect(18f, 48f, 90f, 120f)
        val like = Rect(270f, 300f, 315f, 360f)
        assertEquals(1, dpadTargets(back, DpadDirection.Down, listOf(miniTitle, like)).first())
    }

    @Test
    fun `nothing in that direction gives nothing`() {
        assertTrue(dpadTargets(homeTab, DpadDirection.Down, listOf(rowAbove, miniTitle)).isEmpty())
    }

    @Test
    fun `empty rectangles, such as a row scrolled out of sight, are skipped`() {
        val scrolledAway = Rect(0f, 120f, 480f, 120f)
        assertTrue(dpadTargets(homeTab, DpadDirection.Up, listOf(scrolledAway)).isEmpty())
    }

    @Test
    fun `a row whose middle is under a floating control is hidden`() {
        val overlays = listOf(miniPlayerSheet, searchPill)
        assertTrue(hiddenUnderOverlay(rowUnderPlayer, overlays))
        assertFalse(hiddenUnderOverlay(rowJustAbovePlayer, overlays))
        assertTrue(hiddenUnderOverlay(Rect(0f, 40f, 480f, 110f), overlays))
    }

    @Test
    fun `a focused row is scrolled to the pivot`() {
        // A 96px row at the bottom of a 640px list: its centre is moved to 40%, 256px.
        assertEquals(472f - 256f, dpadScrollDistance(offset = 424f, size = 96f, containerSize = 640f), 0.01f)
        // Already there: no scroll at all.
        assertEquals(0f, dpadScrollDistance(offset = 208f, size = 96f, containerSize = 640f), 0.01f)
    }

    @Test
    fun `going back up, a row only moves once it reaches the top bar`() {
        // Above the pivot and clear of the top 18% (115.2px): left where it is, so the first rows
        // of a list, below its top padding, ask for no scroll a pull to refresh could take.
        assertEquals(0f, scrollFor(150f), 0.01f)
        // Under the top bar: brought down just clear of it.
        assertEquals(48f - 115.2f, scrollFor(48f), 0.01f)
        // Partly above the list: the same.
        assertEquals(-40f - 115.2f, scrollFor(-40f), 0.01f)
    }

    @Test
    fun `an item too big to centre is kept whole rather than centred`() {
        // A 418px card in a 480px list would lose its top edge if centred at 40%.
        assertEquals(100f, dpadScrollDistance(offset = 100f, size = 418f, containerSize = 480f), 0.01f)
        // Taller than the list: its start is shown.
        assertEquals(50f, dpadScrollDistance(offset = 50f, size = 800f, containerSize = 640f), 0.01f)
    }

    @Test
    fun `only lists running down a portrait screen pivot`() {
        // The library on a 480 x 640 keypad phone, its list 640 tall.
        assertTrue(dpadPivots(containerSize = 640f, windowWidth = 480f))
        // A row of songs or cards scrolling sideways on the same screen.
        assertFalse(dpadPivots(containerSize = 480f, windowWidth = 480f))
        // A list in a dialog, or any list in landscape.
        assertFalse(dpadPivots(containerSize = 400f, windowWidth = 480f))
        assertFalse(dpadPivots(containerSize = 384f, windowWidth = 900f))
    }

    private fun scrollFor(offset: Float) = dpadScrollDistance(offset, 96f, 640f)

    @Test
    fun `seeking from the keys stays inside the song`() {
        assertEquals(40_000L, dpadSeekTarget(30_000L, 180_000L, forward = true))
        assertEquals(20_000L, dpadSeekTarget(30_000L, 180_000L, forward = false))
        assertEquals(0L, dpadSeekTarget(4_000L, 180_000L, forward = false))
        assertEquals(180_000L, dpadSeekTarget(175_000L, 180_000L, forward = true))
        // Unknown length: forward is not capped, back still stops at the start.
        assertEquals(15_000L, dpadSeekTarget(5_000L, -1L, forward = true))
        assertEquals(0L, dpadSeekTarget(5_000L, -1L, forward = false))
    }

    @Test
    fun `the screen is searched only from a floating control or with nothing focused`() {
        assertTrue(needsScreenSearch(anythingFocused = false, overlaysFocused = 0))
        assertTrue(needsScreenSearch(anythingFocused = true, overlaysFocused = 1))
        assertFalse(needsScreenSearch(anythingFocused = true, overlaysFocused = 0))
    }
}
