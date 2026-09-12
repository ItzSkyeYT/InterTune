/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape of the widget at every size, decided here rather than by dragging one around. */
class WidgetLayoutTest {

    @Test
    fun `one cell is the cover alone, whatever the widget was asked to hold`() {
        for (content in WidgetContent.entries.filter { it != WidgetContent.LIST }) {
            assertEquals(content.name, NowShape.TINY, WidgetLayout.nowShape(60, 60, content))
            assertEquals(content.name, NowShape.TINY, WidgetLayout.nowShape(100, 200, content))
            assertEquals(0, WidgetLayout.listCount(60, 60, content, 6))
        }
    }

    @Test
    fun `the usual four by two is a line of song with rows under it`() {
        val content = WidgetContent.BOTH
        assertEquals(NowShape.ROW, WidgetLayout.nowShape(406, 225, content))
        assertEquals(2, WidgetLayout.listCount(406, 225, content, 6))
        assertTrue(WidgetLayout.showsSkipButtons(406))
    }

    @Test
    fun `wide and flat keeps the song and loses the rows`() {
        assertEquals(NowShape.ROW, WidgetLayout.nowShape(880, 112, WidgetContent.BOTH))
        assertEquals(0, WidgetLayout.listCount(880, 112, WidgetContent.BOTH, 6))
    }

    @Test
    fun `narrow and tall stacks the song above its buttons and still finds room for rows`() {
        assertEquals(NowShape.STACKED, WidgetLayout.nowShape(150, 300, WidgetContent.BOTH))
        assertFalse(WidgetLayout.showsSkipButtons(150))
        assertEquals(2, WidgetLayout.listCount(150, 300, WidgetContent.BOTH, 6))
    }

    @Test
    fun `given only what is playing and room enough, the cover takes the widget`() {
        assertEquals(NowShape.ART, WidgetLayout.nowShape(300, 300, WidgetContent.NOW_PLAYING))
        assertEquals(0, WidgetLayout.listCount(300, 300, WidgetContent.NOW_PLAYING, 6))
        assertEquals(200, WidgetLayout.artHeightDp(300))
        // Too short for a cover: back to a line, and never a list, because that is not what it holds.
        assertEquals(NowShape.ROW, WidgetLayout.nowShape(300, 140, WidgetContent.NOW_PLAYING))
        assertEquals(0, WidgetLayout.listCount(300, 400, WidgetContent.NOW_PLAYING, 6))
    }

    @Test
    fun `a list widget is all list`() {
        assertEquals(NowShape.NONE, WidgetLayout.nowShape(406, 225, WidgetContent.LIST))
        assertEquals(3, WidgetLayout.listCount(406, 225, WidgetContent.LIST, 6))
        assertEquals(WidgetLayout.MAX_PICKS, WidgetLayout.listCount(406, 900, WidgetContent.LIST, 20))
        assertEquals(1, WidgetLayout.listCount(406, 225, WidgetContent.LIST, 1))
        assertEquals(0, WidgetLayout.listCount(406, 225, WidgetContent.LIST, 0))
    }

    @Test
    fun `rows only ever grow with the height`() {
        for (content in WidgetContent.entries) {
            var last = 0
            for (h in 40..700 step 4) {
                val n = WidgetLayout.listCount(406, h, content, WidgetLayout.MAX_PICKS)
                assertTrue("$content at $h dp went backwards", n >= last)
                last = n
            }
        }
    }

    @Test
    fun `a choice that was never made, or one from a later version, falls back rather than failing`() {
        assertEquals(WidgetContent.BOTH, WidgetContent.of(null))
        assertEquals(WidgetContent.BOTH, WidgetContent.of("SOMETHING_ELSE"))
        assertEquals(WidgetContent.LIST, WidgetContent.of("LIST"))
        assertEquals(WidgetList.QUICK_PICKS, WidgetList.of(null))
        assertEquals(WidgetList.KEEP_LISTENING, WidgetList.of("KEEP_LISTENING"))
        assertEquals(WidgetBackground.SYSTEM, WidgetBackground.of("ANYTHING"))
        assertEquals(WidgetBackground.ARTWORK, WidgetBackground.of("ARTWORK"))
        assertEquals(WidgetButtons.ALL, WidgetButtons.of(null))
        assertEquals(WidgetTextSize.NORMAL, WidgetTextSize.of(null))
        assertEquals(1f, WidgetTextSize.NORMAL.scale, 0f)
    }

    @Test
    fun `a ceiling on the rows is obeyed, and never invents rows the height cannot hold`() {
        val tall = WidgetSettings(maxRows = 2)
        assertEquals(2, tall.rowsAt(406, 900, 6))
        assertEquals(2, tall.rowsAt(406, 225, 6))
        // Two asked for, one to show.
        assertEquals(1, tall.rowsAt(406, 900, 1))
        // Nought means as many as fit, which is what an unconfigured widget gets.
        assertEquals(WidgetLayout.MAX_PICKS, WidgetSettings().rowsAt(406, 900, 6))
        assertEquals(2, WidgetSettings().rowsAt(406, 225, 6))
    }

    @Test
    fun `settings survive a trip through a widget's own state`() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        val wanted = WidgetSettings(
            content = WidgetContent.LIST,
            list = WidgetList.RECENT,
            maxRows = 4,
            background = WidgetBackground.ARTWORK,
            opacity = 60,
            showArtwork = false,
            showArtist = false,
            showHeading = false,
            buttons = WidgetButtons.PLAY_ONLY,
            textSize = WidgetTextSize.LARGE,
            rounded = false,
        )
        WidgetKeys.write(prefs, wanted)
        assertEquals(wanted, WidgetKeys.read(prefs))
        assertEquals(WidgetSettings(), WidgetKeys.read(androidx.datastore.preferences.core.emptyPreferences()))
    }

    @Test
    fun `a nonsense opacity or row count from an older file is brought back into range`() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        prefs[WidgetKeys.OPACITY] = 400
        prefs[WidgetKeys.MAX_ROWS] = 99
        val read = WidgetKeys.read(prefs)
        assertEquals(100, read.opacity)
        assertEquals(WidgetLayout.MAX_PICKS, read.maxRows)
    }
}
