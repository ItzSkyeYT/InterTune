/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.round

/** Three of the UI scan's smaller notes (experiments/codebase-scan/2026-11-05-ui.md, 5 Nov 2026). */
class ScanNotesTest {
    private fun source(path: String) = File("src/main/$path").readText()

    // The player menu's own grid of speeds.
    private val speeds = (0..35).map { round((0.25f + it * 0.05f) * 100) / 100 }

    @Test
    fun `the speed steps from a value on the grid as before`() {
        assertEquals(0.95f, stepValue(speeds, 1.0f, up = false))
        assertEquals(1.05f, stepValue(speeds, 1.0f, up = true))
        assertNull(stepValue(speeds, 0.25f, up = false))
        assertNull(stepValue(speeds, 2.0f, up = true))
    }

    @Test
    fun `a speed off the grid steps to its neighbours instead of closing the app`() {
        // Set by a media controller, say: indexOf was -1, and "-" asked for the place before the first.
        assertEquals(1.0f, stepValue(speeds, 1.02f, up = false))
        assertEquals(1.05f, stepValue(speeds, 1.02f, up = true))
        assertEquals(0.25f, stepValue(speeds, 0.1f, up = true))
        assertNull(stepValue(speeds, 0.1f, up = false))
        assertEquals(-1, stepValue((-12..12).toList(), 0, up = false))
    }

    @Test
    fun `removing a selection's downloads asks in its own words`() {
        val menu = source("java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt")
        assertFalse("remove_download_playlist_confirm, \"selection\"" in menu)
        assertTrue("R.string.remove_download_selection_confirm" in menu)
        for (folder in listOf("values", "values-fr")) {
            assertTrue(folder, "name=\"remove_download_selection_confirm\"" in source("res/$folder/strings-ot.xml"))
        }
    }

    @Test
    fun `a YouTube playlist's menu reads the downloads once`() {
        val menu = source("java/com/dd3boh/outertune/ui/menu/YouTubePlaylistMenu.kt")
        assertEquals(1, Regex("""downloadUtil\.downloads\.collect""").findAll(menu).count())
    }
}
