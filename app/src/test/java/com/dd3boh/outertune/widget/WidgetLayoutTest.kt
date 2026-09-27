/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.widget.WidgetLayout.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The shape of the widget at every size, decided here rather than by dragging one around. */
class WidgetLayoutTest {

    private fun plan(w: Int, h: Int, settings: WidgetSettings = WidgetSettings(), available: Int = 6, fontScale: Float = 1f) =
        WidgetLayout.plan(w, h, settings, available, fontScale)

    private val nowOnly = WidgetSettings(content = WidgetContent.NOW_PLAYING)
    private val listOnly = WidgetSettings(content = WidgetContent.LIST)

    @Test
    fun `one cell is the cover with play over it, and a tall narrow one puts play under it`() {
        for (content in WidgetContent.entries.filter { it != WidgetContent.LIST }) {
            val cell = plan(70, 70, WidgetSettings(content = content))
            assertEquals(content.name, NowShape.TINY, cell.shape)
            assertTrue(cell.play)
            assertFalse(cell.buttonsBelow)
            assertEquals(0, cell.rows)
        }
        val tall = plan(70, 150)
        assertEquals(NowShape.TINY, tall.shape)
        assertTrue(tall.buttonsBelow)
        assertEquals(54, tall.cover)
        // Too small to spare a button: the tile just opens the app.
        assertFalse(plan(50, 50).play)
    }

    @Test
    fun `a single cell tall is one line with a small cover and small buttons`() {
        val slim = plan(407, 50)
        assertEquals(NowShape.SLIM, slim.shape)
        assertEquals(38, slim.cover)
        assertTrue(slim.skips)
        assertTrue(slim.showArtist)
        assertEquals(WidgetLayout.SLIM_BUTTON_DP, slim.button)
        // Flatter still, the cover goes and the song stays.
        assertEquals(0, plan(407, 40).cover)
    }

    @Test
    fun `a flat four by two is a line of song that fills its height`() {
        val flat = plan(407, 107)
        assertEquals(NowShape.ROW, flat.shape)
        assertEquals(0, flat.rows)
        assertEquals(WidgetLayout.ROW_COVER_MAX_DP, flat.cover)
        assertEquals(87, flat.nowHeight)
        assertTrue(flat.skips)
    }

    @Test
    fun `the usual four by two is a line of song with rows under it, sharing out the height`() {
        val usual = plan(406, 225)
        assertEquals(NowShape.ROW, usual.shape)
        assertEquals(2, usual.rows)
        assertTrue(usual.centred)
        assertTrue(usual.rowHeight > WidgetLayout.LIST_ROW_MIN_DP)
        assertEquals(1, plan(400, 170).rows)
    }

    @Test
    fun `wide and not quite tall keeps the song in a line rather than a cramped card`() {
        val wide = plan(880, 112)
        assertEquals(NowShape.ROW, wide.shape)
        assertEquals(0, wide.rows)
        assertTrue(wide.showArtist)
    }

    @Test
    fun `narrow and tall stacks the song above its buttons and still finds room for rows`() {
        val narrow = plan(150, 300)
        assertEquals(NowShape.STACKED, narrow.shape)
        assertFalse(narrow.skips)
        assertEquals(2, narrow.rows)
    }

    @Test
    fun `with no room for a row, a widget with a list is the song on its own`() {
        assertEquals(NowShape.POSTER, plan(150, 150).shape)
        // One row does fit at 180: a line of song, its cover given up for the buttons.
        assertEquals(1, plan(180, 180).rows)
        // And a list with nothing in it yet leaves the song the whole widget.
        assertEquals(NowShape.POSTER, plan(300, 300, available = 0).shape)
    }

    @Test
    fun `given only what is playing, the proportions choose the shape`() {
        val square = plan(260, 260, nowOnly)
        assertEquals(NowShape.POSTER, square.shape)
        assertTrue(square.buttonsBelow)
        assertTrue(square.skips)
        // A small square has play at the end of the song's line instead of a line of buttons.
        val small = plan(180, 180, nowOnly)
        assertEquals(NowShape.POSTER, small.shape)
        assertFalse(small.buttonsBelow)
        assertTrue(small.play)

        val wide = plan(400, 170, nowOnly)
        assertEquals(NowShape.CARD, wide.shape)
        assertEquals(146, wide.cover)
        assertTrue(wide.skips)
        assertEquals(NowShape.CARD, plan(300, 140, nowOnly).shape)

        val tall = plan(150, 300, nowOnly)
        assertEquals(NowShape.STACKED, tall.shape)
        assertEquals(126, tall.cover)
        assertEquals(0, tall.rows)

        // Never a list, however much room, because that is not what it holds.
        assertEquals(0, plan(300, 900, nowOnly).rows)
        // No cover wanted: a line, whatever the proportions.
        assertEquals(NowShape.ROW, plan(260, 260, nowOnly.copy(showArtwork = false)).shape)
    }

    @Test
    fun `a list widget is all list`() {
        assertEquals(NowShape.NONE, plan(406, 225, listOnly).shape)
        assertEquals(3, plan(406, 225, listOnly).rows)
        assertEquals(4, plan(400, 250, listOnly).rows)
        assertEquals(WidgetLayout.MAX_PICKS, plan(406, 900, listOnly, available = 20).rows)
        assertEquals(1, plan(406, 225, listOnly, available = 1).rows)
        assertEquals(0, plan(406, 225, listOnly, available = 0).rows)
    }

    @Test
    fun `a list that runs out of songs starts at the top, one that runs out of room is centred`() {
        val few = plan(170, 400, listOnly, available = 2)
        assertEquals(2, few.rows)
        assertFalse(few.centred)
        assertEquals(WidgetLayout.LIST_ROW_DP, few.rowHeight)
        assertTrue(plan(400, 250, listOnly).centred)
    }

    @Test
    fun `a list too narrow for covers keeps its words`() {
        assertFalse(plan(100, 300, listOnly).rowCover)
        assertTrue(plan(200, 300, listOnly).rowCover)
    }

    @Test
    fun `rows only ever grow with the height`() {
        for (content in WidgetContent.entries) {
            var last = 0
            for (h in 40..700 step 2) {
                val n = plan(406, h, WidgetSettings(content = content), available = WidgetLayout.MAX_PICKS).rows
                assertTrue("$content at $h dp went backwards", n >= last)
                last = n
            }
        }
    }

    /**
     * The one that matters: at every size a launcher can hand out, with every combination that
     * changes the arithmetic, what is planned fits, words included. A widget clips silently, so
     * this is the only place a mistake would ever be seen.
     */
    @Test
    fun `nothing is ever cut off, at any size`() {
        val variants = buildList {
            for (content in WidgetContent.entries) for (buttons in WidgetButtons.entries) for (size in WidgetTextSize.entries) {
                add(WidgetSettings(content = content, buttons = buttons, textSize = size))
            }
            add(WidgetSettings(showArtwork = false))
            add(WidgetSettings(showArtist = false, showHeading = false))
            add(WidgetSettings(content = WidgetContent.NOW_PLAYING, showArtwork = false))
            add(WidgetSettings(content = WidgetContent.LIST, showArtwork = false, maxRows = 2))
        }
        var checked = 0
        for (settings in variants) for (fontScale in listOf(1f, 1.3f)) for (available in listOf(0, 2, 6)) {
            val scale = settings.textSize.scale * fontScale
            for (w in 40..600 step 6) for (h in 40..600 step 6) {
                val p = plan(w, h, settings, available, fontScale)
                // Built only on a failure: a million and a half of these otherwise.
                fun ok(fits: Boolean, what: String) {
                    if (!fits) fail("$what: $settings, font $fontScale, $available songs, at ${w}x$h: $p")
                }
                ok(WidgetLayout.heightOf(p, h) <= h, "too tall")
                ok(WidgetLayout.widthOf(p) <= w, "too wide")
                ok(p.rows <= available, "more rows than songs")
                if (p.rows > 0) {
                    val words = line(14, scale) + if (p.rowShowsArtist) line(12, scale) else 0
                    ok(words + 4 <= p.rowHeight, "a row's words do not fit")
                    if (p.rowCover) ok(WidgetLayout.LIST_COVER_DP <= p.rowHeight, "a row's cover does not fit")
                }
                val controls = if (p.play) p.button else 0
                when (p.shape) {
                    NowShape.SLIM -> {
                        val words = line(14, scale) + if (p.showArtist) line(12, scale) else 0
                        ok(words <= p.nowHeight, "slim words")
                        ok(p.cover <= p.nowHeight && controls <= p.nowHeight, "slim cover or buttons")
                    }
                    NowShape.ROW -> {
                        val words = line(15, scale) + if (p.showArtist) line(13, scale) else 0
                        ok(words <= p.nowHeight, "line words")
                        ok(p.cover <= p.nowHeight && controls <= p.nowHeight, "line cover or buttons")
                    }
                    NowShape.STACKED -> {
                        val words = line(14, scale) + if (p.showArtist) line(12, scale) else 0
                        ok((if (p.cover > 0) p.cover + 6 else 0) + words + controls <= p.nowHeight, "stacked")
                    }
                    NowShape.CARD -> {
                        val words = p.titleLines * line(16, scale) + if (p.showArtist) line(13, scale) else 0
                        ok(words + (if (p.play) 6 + controls else 0) <= p.cover, "card words")
                        ok(p.cover + 2 * WidgetLayout.PAD_H_DP <= h, "card cover")
                    }
                    NowShape.POSTER -> {
                        val words = (if (p.titleLines > 0) line(16, scale) else 0) + if (p.showArtist) line(13, scale) else 0
                        val bottom = if (p.buttonsBelow) words + 4 + controls else maxOf(words, controls)
                        ok(2 * WidgetLayout.PAD_H_DP + bottom <= h, "poster")
                    }
                    NowShape.TINY -> {
                        if (p.buttonsBelow) ok(p.cover + 8 + controls <= h, "tiny, play under")
                        else ok(controls <= minOf(w, h), "tiny, play over")
                    }
                    NowShape.NONE -> Unit
                }
                checked++
            }
        }
        assertTrue(checked > 100_000)
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
        val two = WidgetSettings(maxRows = 2)
        assertEquals(2, plan(406, 900, two).rows)
        assertEquals(2, plan(406, 225, two).rows)
        // Two asked for, one to show.
        assertEquals(1, plan(406, 900, two, available = 1).rows)
        // Nought means as many as fit, which is what an unconfigured widget gets.
        assertEquals(WidgetLayout.MAX_PICKS, plan(406, 900).rows)
        assertEquals(2, plan(406, 225).rows)
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
