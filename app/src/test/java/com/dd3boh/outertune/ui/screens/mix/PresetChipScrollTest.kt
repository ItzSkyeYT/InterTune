/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The presets row keeps its lit chip in view. On the emulator (29 Oct 2026), Overlap picked in the
 * Volume row made the rows Fade's, and Fade lit eight chips along, off the right of the screen: the
 * line said Fade's sentence under a row where no chip was lit.
 *
 * And it stays where it is when the finger has just been on it (10 Nov 2026, the Pixel 5): after a
 * save, a preset laid on or a delete the row ran back to Custom, near its start, because the page
 * kept calling Custom the lit chip under the listener's own. The chip lit is the one drawn lit; a
 * deleted one sends the row nowhere; a saved one, at the row's end, comes into view.
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
    fun `the row goes to the chip lit now, unless the one lit before was deleted`() {
        val users = listOf("u1", "u2")
        // Opening on a lit chip, a row's choice that lit another, a save that lit the new one: the row goes to it.
        assertTrue(goesTo(null, Preset.CUSTOM, users))
        assertTrue(goesTo(Preset.RISE, Preset.FADE, users))
        assertTrue(goesTo(Preset.CUSTOM, "u2", users))
        assertTrue(goesTo("u1", "u2", users))
        assertTrue(goesTo("u1", Preset.RISE, users))
        // A plain change lights none.
        assertFalse(goesTo(Preset.RISE, null, users))
        // The listener's chip that was lit is gone: Custom lights at the row's start, and the row stays.
        assertFalse(goesTo("u3", Preset.CUSTOM, users))
        // Taken back, a deleted chip is lit again where it was.
        assertTrue(goesTo(Preset.CUSTOM, "u3", users + "u3"))
    }

    @Test
    fun `a chip saved at the row's end is brought in whole, and one laid on in view leaves the row alone`() {
        // The row scrolled to 1100 when the new chip is made, at 2300..2540 in a viewport 1344 wide: it ends just in view.
        assertEquals(2540 + 48 - 1344, chipScrollTarget(left = 2300, right = 2540, scroll = 1100, viewport = 1344, margin = 48))
        // A chip tapped in view never moves the row.
        assertNull(chipScrollTarget(left = 2300, right = 2540, scroll = 1400, viewport = 1344, margin = 48))
    }

    @Test
    fun `both levels scroll their row to the lit chip, the listener's own when one is lit`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixSimpleSheet.kt").readText()
        val remembered = sheet.substringAfter("internal fun rememberPresetRow(").substringBefore("\n}\n")
        assertTrue(remembered, "LaunchedEffect(shown)" in remembered)
        assertTrue(remembered, "goesTo(was, shown, usersNow)" in remembered)
        assertTrue(remembered, "chipScrollTarget(edges.first, edges.last, scroll.value, scroll.viewportSize, padPx)" in remembered)
        // Simple (Choices) and Advanced (Lowers): the row's scroll is the helper's, every chip reports its place.
        val simple = sheet.substringAfter("private fun ColumnScope.Choices(").substringBefore("\n}\n")
        val page = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorScreen.kt").readText()
        val advanced = page.substringAfter("private fun Lowers(").substringBefore("\n}\n")
        for ((name, level) in listOf("Simple" to simple, "Advanced" to advanced)) {
            assertTrue(name, "rememberPresetRow(userLit?.id ?: ui.lit," in level)
            assertTrue(name, "horizontalScroll(presetRow.scroll)" in level)
            assertTrue(name, "modifier = presetRow.chip(p)," in level)
            assertTrue(name, "row = presetRow)" in level)
        }
        // The listener's own chips report their place by id.
        val chips = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixUserPresets.kt").readText()
        assertTrue("Box(if (row != null) row.chip(p.id) else Modifier)" in chips)
    }
}
