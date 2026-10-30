/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The presets row keeps its lit chip in view. On the emulator (29 Oct 2026), Overlap picked in the
 * Volume row made the rows Fade's, and Fade lit eight chips along, off the right of the screen: the
 * line said Fade's sentence under a row where no chip was lit.
 */
class PresetChipScrollTest {

    // A phone's row: 1344 px wide, chips about 200 px each, 48 px of margin.

    @Test
    fun `a chip in view leaves the row where it is`() {
        assertNull(chipScrollTarget(left = 230, right = 430, scroll = 0, viewport = 1344, margin = 48))
        assertNull(chipScrollTarget(left = 1500, right = 1700, scroll = 600, viewport = 1344, margin = 48))
    }

    @Test
    fun `a chip off the right is brought in with its margin`() {
        // Fade at 1900..2100 in a row scrolled to its start.
        assertEquals(2100 + 48 - 1344, chipScrollTarget(left = 1900, right = 2100, scroll = 0, viewport = 1344, margin = 48))
    }

    @Test
    fun `a chip off the left is brought in, never past the row's start`() {
        assertEquals(230 - 48, chipScrollTarget(left = 230, right = 430, scroll = 900, viewport = 1344, margin = 48))
        assertEquals(0, chipScrollTarget(left = 48, right = 200, scroll = 300, viewport = 1344, margin = 48))
    }

    @Test
    fun `a row not measured yet is left alone`() {
        assertNull(chipScrollTarget(left = 1900, right = 2100, scroll = 0, viewport = 0, margin = 48))
    }

    @Test
    fun `the sheet scrolls the row to the lit chip whenever it changes`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixSimpleSheet.kt").readText()
        val choices = sheet.substringAfter("private fun ColumnScope.Choices(").substringBefore("\n}\n")
        assertTrue(choices, "LaunchedEffect(ui.lit, litEdges)" in choices)
        assertTrue("chipScrollTarget(edges.first, edges.last, chipsScroll.value, chipsScroll.viewportSize, chipPad)" in choices)
        assertTrue("horizontalScroll(chipsScroll)" in choices)
    }
}
