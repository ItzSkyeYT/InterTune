/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Simple sheet on a phone on its side, at font 1.3 in French (the short-screen look of 29 Oct
 * 2026 on the emulator): its top ran under the status bar, the clock and the battery drawn over its
 * corners, and the line under the waveforms was cut at two lines.
 */
class SimpleSheetSizeTest {

    @Test
    fun `upright it keeps the player showing above it, as before`() {
        // 2992 px tall, a status bar of 145 px and 24 px of room: 92% is lower than that already.
        assertEquals((2992 * 0.92f).toInt(), sheetHeight(2992, landscape = false, clearOfBarPx = 169))
    }

    @Test
    fun `on its side its top stays below the status bar`() {
        // The emulator on its side: 1344 px tall. 94% left 80 px above the sheet, under a 145 px bar.
        val h = sheetHeight(1344, landscape = true, clearOfBarPx = 169)
        assertEquals(1344 - 169, h)
        assertTrue(1344 - h >= 169)
        // A tall window on its side keeps the 94%.
        assertEquals((2000 * 0.94f).toInt(), sheetHeight(2000, landscape = true, clearOfBarPx = 100))
    }

    @Test
    fun `a window shorter than the bar gets no negative height`() {
        assertEquals(0, sheetHeight(100, landscape = true, clearOfBarPx = 169))
    }

    @Test
    fun `the line under the waveforms takes three lines before it is cut`() {
        assertEquals(3, WHERE_LINES)
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixSimpleSheet.kt").readText()
        val where = sheet.substringAfter("private fun WhereLine(").substringBefore("\n}\n")
        assertTrue(where, "maxLines = WHERE_LINES" in where)
        assertTrue("the sheet is sized by sheetHeight", "sheetHeight(constraints.maxHeight, landscape, clearOfBar)" in sheet)
    }
}
