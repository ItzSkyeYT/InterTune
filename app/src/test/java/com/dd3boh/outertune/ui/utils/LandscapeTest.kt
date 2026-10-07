/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeTest {

    private fun window(width: Int, height: Int, enabled: Boolean = true) = Landscape(width.dp, height.dp, enabled)

    /** A large phone on its side, the one the layout was drawn on: 997 x 448dp. */
    private val large = window(997, 448)

    /** The same phone upright. */
    private val upright = window(448, 997)

    @Test
    fun `a phone on its side is on its side, whatever its size`() {
        assertTrue(large.active)
        assertTrue(window(891, 412).active)
        assertTrue(window(800, 360).active)
        assertTrue(window(640, 360).active)
    }

    @Test
    fun `nothing upright is on its side`() {
        for (width in 240..840 step 20) {
            for (height in 480..1400 step 40) {
                assertFalse("$width x $height", window(width, height).active)
            }
        }
    }

    @Test
    fun `a tablet keeps the layout it has, whichever way it is held`() {
        assertFalse(window(1280, 800).active)
        assertFalse(window(800, 1280).active)
        assertFalse(window(960, 600).active)
        // Unfolded, and at the smallest width that is called a tablet.
        assertFalse(window(841, 701).active)
        assertFalse(window(1024, 600).active)
    }

    @Test
    fun `a small window is short without being wide`() {
        assertFalse(window(300, 250).active)
        assertFalse(window(448, 300).active)
        assertFalse(window(599, 479).active)
        assertTrue(window(600, 479).active)
        assertFalse(window(600, 480).active)
    }

    @Test
    fun `with the switch off nothing is on its side`() {
        val off = window(997, 448, enabled = false)
        assertFalse(off.active)
        assertEquals(1, off.listColumns(864.dp))
        assertEquals(864.dp, off.panelWidth(864.dp))
        assertEquals(Pair(133.dp, 864.dp), off.panelSpan(left = 133.dp, right = 0.dp))
        assertEquals(720.dp, off.readingWidth(720.dp))
    }

    @Test
    fun `upright every answer is the one there was before`() {
        var width = 240.dp
        while (width <= 840.dp) {
            assertEquals(1, upright.listColumns(width))
            assertEquals(width, upright.panelWidth(width))
            assertEquals(720.dp, upright.readingWidth(720.dp))
            width += 13.dp
        }
        assertEquals(Pair(0.dp, 448.dp), upright.panelSpan(left = 0.dp, right = 0.dp))
        assertFalse(Landscape.Upright.active)
        assertEquals(1, Landscape.Upright.listColumns(2000.dp))
    }

    @Test
    fun `a list is two rows abreast once each row has a small phone's width`() {
        assertEquals(1, large.listColumns(639.dp))
        assertEquals(2, large.listColumns(640.dp))
        // What is left of 997dp beside the rail and the cutout.
        assertEquals(2, large.listColumns(864.dp))
        // A 640dp phone on its side has 560dp beside the rail: one column, as upright.
        assertEquals(1, window(640, 360).listColumns(560.dp))
    }

    @Test
    fun `a list is never three abreast`() {
        var width = 640.dp
        while (width <= 2000.dp) {
            assertEquals(2, large.listColumns(width))
            width += 40.dp
        }
    }

    @Test
    fun `a floating panel is never wider than it is upright, nor than the room it has`() {
        assertEquals(440.dp, large.panelWidth(864.dp))
        assertEquals(440.dp, large.panelWidth(440.dp))
        assertEquals(300.dp, large.panelWidth(300.dp))
        assertEquals(0.dp, large.panelWidth(0.dp))
    }

    @Test
    fun `the mini player sits in the middle of what the rail and the cutout leave`() {
        // The rail and the cutout take 133dp on the left: 864dp are left, of which it takes 440.
        val (left, width) = large.panelSpan(left = 133.dp, right = 0.dp)
        assertEquals(440.dp, width)
        assertEquals(345.dp, left)
        // As much room either side of it.
        assertEquals(left - 133.dp, 997.dp - (left + width))
    }

    @Test
    fun `the mini player never leaves the room it has`() {
        for (windowWidth in 600..1400 step 23) {
            for (left in listOf(0, 80, 133)) {
                for (right in listOf(0, 48)) {
                    val (start, width) = window(windowWidth, 400).panelSpan(left.dp, right.dp)
                    val label = "$windowWidth, $left, $right"
                    assertTrue(label, start >= left.dp)
                    assertTrue(label, start + width <= (windowWidth - right).dp + Dp.Hairline)
                    assertTrue(label, width <= Landscape.PanelMaxWidth)
                }
            }
        }
    }

    @Test
    fun `insets wider than the window leave nothing, not less than nothing`() {
        val (_, width) = window(600, 400).panelSpan(left = 400.dp, right = 400.dp)
        assertEquals(0.dp, width)
    }

    @Test
    fun `a page of settings is narrower on its side, and never wider than its own cap`() {
        assertEquals(600.dp, large.readingWidth(720.dp))
        assertEquals(480.dp, large.readingWidth(480.dp))
    }
}
