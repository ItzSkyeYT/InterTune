/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPlayerTest {

    private val peek = 48.dp

    /** What the player did before small windows had a layout of their own, written out by hand. */
    private fun before(bottomInset: Dp, twoPane: Boolean, queueAsButton: Boolean, songLoaded: Boolean): PlayerQueueLayout {
        val dismissedBound = peek + bottomInset
        val queueReserve = if (twoPane) dismissedBound else dismissedBound + peek
        val queueOnButton = queueAsButton && songLoaded
        return PlayerQueueLayout(
            compact = false,
            reserve = queueReserve,
            sheetDismissed = if (queueOnButton) 0.dp else dismissedBound,
            sheetCollapsed = if (queueOnButton) 0.dp else queueReserve,
            showHandle = !queueOnButton,
            queueButton = queueAsButton,
            artworkPadding = peek / 2,
            compactSheet = false,
        )
    }

    private fun layout(
        height: Dp,
        bottomInset: Dp = 48.dp,
        twoPane: Boolean = false,
        queueAsButton: Boolean = false,
        songLoaded: Boolean = true,
        landscape: Boolean = twoPane,
    ) = playerQueueLayout(height, bottomInset, landscape, twoPane, queueAsButton, songLoaded, peek)

    @Test
    fun `every window 480dp tall or more lays out exactly as before`() {
        for (inset in listOf(0.dp, 24.dp, 48.dp)) {
            for (twoPane in listOf(false, true)) {
                for (button in listOf(false, true)) {
                    for (loaded in listOf(false, true)) {
                        var height = 480.dp
                        while (height <= 1400.dp) {
                            assertEquals(
                                "$height, inset $inset, twoPane $twoPane, button $button, loaded $loaded",
                                before(inset, twoPane, button, loaded),
                                layout(height, inset, twoPane, button, loaded),
                            )
                            height += 7.dp
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `phones held upright are never small, down to the smallest screens still in use`() {
        // 480 x 800px and 480 x 854px at 240dpi, 720 x 1280px at 320dpi, and a 20:9 phone.
        for (height in listOf(533.dp, 569.dp, 640.dp, 915.dp)) {
            assertFalse("small at $height", layout(height).compact)
        }
    }

    @Test
    fun `landscape and tablet two-pane layouts keep their bare peek however short they are`() {
        // A 1440p phone in landscape is about 384dp tall, some phones well under that.
        for (height in listOf(300.dp, 384.dp, 411.dp)) {
            val l = layout(height, twoPane = true)
            assertFalse(l.compact)
            assertEquals(peek + 48.dp, l.reserve)
            assertEquals(l.reserve, l.sheetCollapsed)
            assertTrue(l.showHandle)
        }
    }

    @Test
    fun `a phone on its side too narrow for two panes lays out exactly as before`() {
        // 720 x 1280px at 320dpi on its side, the navigation bar beside the screen: 592 x 360dp.
        for (height in listOf(300.dp, 336.dp, 360.dp)) {
            for (button in listOf(false, true)) {
                assertEquals(
                    before(0.dp, twoPane = false, queueAsButton = button, songLoaded = true),
                    layout(height, bottomInset = 0.dp, queueAsButton = button, landscape = true),
                )
            }
        }
    }

    @Test
    fun `a keypad phone gives the queue strip up and gets the queue button`() {
        // 480 x 640px at 240dpi, with physical keys and so no navigation bar.
        val l = layout(427.dp, bottomInset = 0.dp)
        assertTrue(l.compact)
        assertEquals("only the gap under the controls", CompactQueueGrab, l.reserve)
        assertEquals("the grab rests where the controls leave their gap", l.reserve, l.sheetCollapsed)
        assertEquals("and cannot be dragged any lower", l.sheetCollapsed, l.sheetDismissed)
        assertFalse("nothing of the sheet shows", l.showHandle)
        assertTrue("the keys need something to reach the queue with", l.queueButton)
        assertEquals(0.dp, l.artworkPadding)
        assertTrue("the songs need the height the sheet's bar took", l.compactSheet)
    }

    @Test
    fun `the cover gets back all the height the strip took`() {
        val small = layout(427.dp, bottomInset = 48.dp)
        val old = before(48.dp, twoPane = false, queueAsButton = false, songLoaded = true)
        // The strip and the artwork padding either side, less the gap the controls kept anyway.
        val given = (old.reserve - small.reserve) + (old.artworkPadding - small.artworkPadding) * 2
        assertEquals(peek * 2 + peek - CompactQueueGrab, given)
        assertTrue("under 120dp given back at $given", given >= 120.dp)
    }

    @Test
    fun `a floating window held upright is small, however short`() {
        for (height in listOf(250.dp, 360.dp, 400.dp, 479.9.dp)) {
            val l = layout(height, bottomInset = 0.dp)
            assertTrue("not small at $height", l.compact)
            assertEquals(CompactQueueGrab, l.reserve)
        }
        assertFalse(layout(480.dp).compact)
    }

    @Test
    fun `a small window keeps the bottom bars clear of the grab strip`() {
        val l = layout(400.dp, bottomInset = 48.dp)
        assertEquals(48.dp + CompactQueueGrab, l.reserve)
        assertEquals(l.reserve, l.sheetCollapsed)
    }

    @Test
    fun `with the queue on its button a small window collapses the sheet to nothing, as anywhere else`() {
        val l = layout(427.dp, queueAsButton = true)
        assertTrue(l.compact)
        assertEquals(0.dp, l.sheetDismissed)
        assertEquals(0.dp, l.sheetCollapsed)
        assertFalse(l.showHandle)
        assertTrue(l.queueButton)
    }

    @Test
    fun `with nothing loaded a small window shows the usual strip, so its arrow can reach the queue`() {
        // No song, no controls, no queue button: the arrow is the only way to the saved queues.
        for (button in listOf(false, true)) {
            val l = layout(427.dp, queueAsButton = button, songLoaded = false)
            assertFalse(l.compact)
            assertTrue(l.showHandle)
            assertEquals(before(48.dp, false, button, false), l)
        }
    }

    @Test
    fun `the controls keep their sizes everywhere but a small player`() {
        val portrait = playerControlSizes(landscapePlayer = false, compact = false)
        assertEquals(PlayerControlSizes(72.dp, 36.dp, 24.dp, 36.dp, 32.dp, 12.dp), portrait)
        val landscape = playerControlSizes(landscapePlayer = true, compact = false)
        assertEquals(PlayerControlSizes(84.dp, 36.dp, 24.dp, 36.dp, 42.dp, 12.dp), landscape)
    }

    @Test
    fun `a small player's play button is smaller but still round when paused, and its other icons are not`() {
        val small = playerControlSizes(landscapePlayer = false, compact = true)
        val portrait = playerControlSizes(landscapePlayer = false, compact = false)
        assertTrue(small.playButton < portrait.playButton)
        assertTrue("under a 48dp touch target", small.playButton >= 48.dp)
        assertEquals(small.playButton / 2, small.pausedCorner)
        assertTrue(small.playingCorner < small.pausedCorner)
        assertTrue(small.playIcon < small.playButton)
        assertEquals(portrait.transportIcon, small.transportIcon)
        val saved = (portrait.playButton - small.playButton) + (portrait.gapAboveTransport - small.gapAboveTransport)
        assertEquals(24.dp, saved)
    }

    @Test
    fun `a small player draws no cover where it would be a sliver`() {
        for (height in listOf(0.dp, 3.dp, 47.dp)) assertFalse("cover at $height", compactCoverFits(height))
        for (height in listOf(48.dp, 110.dp, 300.dp)) assertTrue("no cover at $height", compactCoverFits(height))
    }

    @Test
    fun `connected's buttons under a small player keep their width where it fits and share the row where not`() {
        val gap = 6.dp
        // 320dp across less a 16dp gutter either side: four at 56dp fit.
        assertEquals(56.dp, compactQuickActionWidth(288.dp, 4, gap, 56.dp))
        // 240dp across: they would overrun 208dp, so they share it evenly and fill it exactly.
        val narrow = compactQuickActionWidth(208.dp, 4, gap, 56.dp)
        assertTrue(narrow < 56.dp)
        assertEquals(208.dp, narrow * 4 + gap * 3)
        // Never narrower than a usable button.
        assertEquals(CompactQuickActionMinWidth, compactQuickActionWidth(100.dp, 4, gap, 56.dp))
    }

    @Test
    fun `the mini player drops previous only where an ordinary phone never is`() {
        // Upright phones: 320dp for the oldest 480 x 800px at 240dpi, 360dp and up for the rest.
        for (width in listOf(320.dp, 360.dp, 411.dp, 600.dp, 915.dp)) {
            assertTrue("no previous at $width", miniPlayerShowsPrevious(width))
        }
        // A keypad phone at 160dpi, and floating windows.
        for (width in listOf(240.dp, 274.dp, 319.dp)) {
            assertFalse("previous at $width", miniPlayerShowsPrevious(width))
        }
    }

    @Test
    fun `two circles stacked beside the cover need their height, or they go in a row`() {
        assertTrue(classicSideColumnsFit(88.dp))
        assertTrue(classicSideColumnsFit(140.dp))
        // At 240 x 320 dp the space left for the cover is about 60dp.
        assertFalse(classicSideColumnsFit(60.dp))
    }
}
