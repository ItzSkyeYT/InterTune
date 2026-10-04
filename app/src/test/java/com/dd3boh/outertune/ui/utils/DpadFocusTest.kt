/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `up at the top of what a list holds scrolls the rest of the page back into view first`() {
        // A playlist scrolled a little, its cover half under the top bar, focus on the first
        // button of the header: nothing focusable above it in the list.
        assertTrue(dpadScrollsInstead(DpadDirection.Up, value = 40f, maxValue = 900f, reversed = false, furtherInList = false))
        // Once the list is at its start, Up leaves it for the top bar as before.
        assertFalse(dpadScrollsInstead(DpadDirection.Up, value = 0f, maxValue = 900f, reversed = false, furtherInList = false))
        // With a row above, Compose's own search moves to it, scrolling as it goes.
        assertFalse(dpadScrollsInstead(DpadDirection.Up, value = 40f, maxValue = 900f, reversed = false, furtherInList = true))
    }

    @Test
    fun `down at the end of what a list holds scrolls the rest into view while the list can go further`() {
        assertTrue(dpadScrollsInstead(DpadDirection.Down, value = 860f, maxValue = 900f, reversed = false, furtherInList = false))
        assertFalse(dpadScrollsInstead(DpadDirection.Down, value = 900f, maxValue = 900f, reversed = false, furtherInList = false))
        assertFalse(dpadScrollsInstead(DpadDirection.Down, value = 100f, maxValue = 900f, reversed = false, furtherInList = true))
    }

    @Test
    fun `sideways keys and reversed lists never scroll instead of moving`() {
        assertFalse(dpadScrollsInstead(DpadDirection.Left, value = 40f, maxValue = 900f, reversed = false, furtherInList = false))
        assertFalse(dpadScrollsInstead(DpadDirection.Right, value = 40f, maxValue = 900f, reversed = false, furtherInList = false))
        assertFalse(dpadScrollsInstead(DpadDirection.Up, value = 40f, maxValue = 900f, reversed = true, furtherInList = false))
    }

    @Test
    fun `one press never scrolls a list back past its start`() {
        // 30% of a 640 high list, unless less is left: the rest would go to a pull to refresh.
        assertEquals(-192f, dpadEdgeScrollDelta(DpadDirection.Up, height = 640f, value = 1000f, maxValue = 2000f, lazy = false))
        assertEquals(-40f, dpadEdgeScrollDelta(DpadDirection.Up, height = 640f, value = 40f, maxValue = 2000f, lazy = false))
        // A lazy list with its first item in view reports the exact offset.
        assertEquals(-40f, dpadEdgeScrollDelta(DpadDirection.Up, height = 640f, value = 40f, maxValue = 140f, lazy = true))
        // Further down a lazy list's value is an estimate, so nothing is scrolled back.
        assertNull(dpadEdgeScrollDelta(DpadDirection.Up, height = 640f, value = LazyScrollValuePerItem + 40f, maxValue = 700f, lazy = true))
        assertNull(dpadEdgeScrollDelta(DpadDirection.Up, height = 640f, value = 0f, maxValue = 140f, lazy = true))
    }

    @Test
    fun `one press down scrolls a share of the list, never past an end it knows`() {
        assertEquals(192f, dpadEdgeScrollDelta(DpadDirection.Down, height = 640f, value = 0f, maxValue = 2000f, lazy = false))
        assertEquals(60f, dpadEdgeScrollDelta(DpadDirection.Down, height = 640f, value = 1940f, maxValue = 2000f, lazy = false))
        assertEquals(192f, dpadEdgeScrollDelta(DpadDirection.Down, height = 640f, value = 40f, maxValue = 140f, lazy = true))
        assertNull(dpadEdgeScrollDelta(DpadDirection.Left, height = 640f, value = 40f, maxValue = 140f, lazy = false))
    }

    @Test
    fun `from the top bar to the mini player is a jump over the list, between neighbours it is not`() {
        val topBarBack = Rect(24f, 48f, 120f, 144f)
        // A cover filling the list between them: nothing in the list to stop at.
        assertTrue(dpadJumpsOverList(topBarBack, miniTitle, list, DpadDirection.Down))
        assertTrue(dpadJumpsOverList(miniTitle, topBarBack, list, DpadDirection.Up))
        // Nothing at all below, as without a mini player.
        assertTrue(dpadJumpsOverList(topBarBack, null, list, DpadDirection.Down))
        assertFalse(dpadJumpsOverList(homeTab, miniPlay, list, DpadDirection.Up))
        assertFalse(dpadJumpsOverList(fab, miniPlay, list, DpadDirection.Down))
        assertFalse(dpadJumpsOverList(searchPill, rowAbove, list, DpadDirection.Left))
    }

    @Test
    fun `up goes round to the tabs only when nothing is above`() {
        assertTrue(dpadWrapsToTabs(DpadDirection.Up, moved = false, redirected = false))
        // A move that happened, rows brought in from above included.
        assertFalse(dpadWrapsToTabs(DpadDirection.Up, moved = true, redirected = false))
        // A move refused by a closed part and made again by the search across the screen.
        assertFalse(dpadWrapsToTabs(DpadDirection.Up, moved = false, redirected = true))
        assertFalse(dpadWrapsToTabs(DpadDirection.Down, moved = false, redirected = false))
        assertFalse(dpadWrapsToTabs(DpadDirection.Left, moved = false, redirected = false))
    }

    @Test
    fun `the selected tab is the one reached, or the first when none is selected`() {
        val selectedThird = listOf(DpadTab(false, true), DpadTab(false, true), DpadTab(true, true), DpadTab(false, true))
        assertEquals(2, dpadTabToFocus(selectedThird))
        val noneSelected = listOf(DpadTab(false, true), DpadTab(false, true))
        assertEquals(0, dpadTabToFocus(noneSelected))
    }

    @Test
    fun `a tab that cannot take focus is never picked`() {
        // The selected tab out of sight: the first one in reach instead.
        assertEquals(1, dpadTabToFocus(listOf(DpadTab(true, false), DpadTab(false, true))))
        // The bar closed while the expanded player covers it, or no bar at all, as beside a rail.
        assertNull(dpadTabToFocus(listOf(DpadTab(true, false), DpadTab(false, false))))
        assertNull(dpadTabToFocus(emptyList()))
    }

    @Test
    fun `a list is hidden only under a floating control that covers nearly all of it`() {
        // A keypad phone's screen, 240 by 320, with the page's list as tall as the screen.
        val list = Rect(0f, 0f, 240f, 320f)
        // The mini player and the tabs: 164 high, so they reach past the middle of the list.
        val sheet = Rect(0f, 156f, 240f, 320f)
        assertTrue(sheet.contains(list.center))
        assertFalse(listHiddenUnderOverlay(list, listOf(sheet)))
        // The open search covers the whole screen, and the feed under it with it.
        assertTrue(listHiddenUnderOverlay(list, listOf(sheet, Rect(0f, 0f, 240f, 320f))))
        // A top bar and the sheet together still leave the list in sight.
        assertFalse(listHiddenUnderOverlay(list, listOf(Rect(0f, 0f, 240f, 56f), sheet)))
        assertFalse(listHiddenUnderOverlay(list, emptyList()))
    }

    @Test
    fun `on the shortest screen a move across the list that shows still scrolls it`() {
        // 240 by 320 with a navigation bar under the tabs: the mini player starts above the middle.
        val list = Rect(0f, 0f, 240f, 320f)
        val back = Rect(12f, 32f, 60f, 80f)
        val miniPlayer = Rect(0f, 158f, 144f, 218f)
        val homeTab = Rect(0f, 220f, 54f, 272f)
        val floatingButton = Rect(176f, 100f, 232f, 156f)
        assertTrue(miniPlayer.top < list.center.y)
        assertTrue(dpadJumpsOverList(back, miniPlayer, list, DpadDirection.Down))
        assertTrue(dpadJumpsOverList(miniPlayer, back, list, DpadDirection.Up))
        // Neighbours stay neighbours: the tabs under the mini player, the button just above it.
        assertFalse(dpadJumpsOverList(homeTab, miniPlayer, list, DpadDirection.Up))
        assertFalse(dpadJumpsOverList(miniPlayer, homeTab, list, DpadDirection.Down))
        assertFalse(dpadJumpsOverList(floatingButton, miniPlayer, list, DpadDirection.Down))
        assertFalse(dpadJumpsOverList(miniPlayer, floatingButton, list, DpadDirection.Up))
    }

    @Test
    fun `only a floating control as large as the screen covers it`() {
        val screen = Rect(0f, 0f, 240f, 320f)
        // The open search: the whole screen, or all of it under the status bar.
        assertTrue(dpadCoversScreen(Rect(0f, 0f, 240f, 320f), screen))
        assertTrue(dpadCoversScreen(Rect(0f, 24f, 240f, 320f), screen))
        // The closed search pill, a top bar, and the mini player with the tabs.
        assertFalse(dpadCoversScreen(Rect(8f, 28f, 232f, 76f), screen))
        assertFalse(dpadCoversScreen(Rect(0f, 0f, 240f, 56f), screen))
        assertFalse(dpadCoversScreen(Rect(0f, 156f, 240f, 320f), screen))
        // Full height but narrow, such as a rail, is not the screen either.
        assertFalse(dpadCoversScreen(Rect(0f, 0f, 80f, 320f), screen))
    }
}
