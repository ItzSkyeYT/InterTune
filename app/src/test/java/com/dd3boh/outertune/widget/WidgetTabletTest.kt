/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget on a tablet. Reported on 31 Oct 2026 from a Xiaomi Pad 7 Pro: made large, its content
 * stayed packed in the top left, a song row, its buttons and a few covers, and about half of the
 * widget was left empty. The plan had no shape for that size: a shelf never held more than six
 * covers however big the widget was, at most 120 dp each, so a big one had its six in a row or a
 * block and a bare widget round them; Up next pinned a small cover in the middle of a tall widget.
 *
 * What a tablet's plan must do is use the room it has: at every size from a phone's four by four
 * and a bit up to what a tablet's home screen gives (and a launcher that ignores the widget's
 * largest size can give more), no more than half of the widget is left empty across or down. The
 * boxes are where [WidgetFill] says a plan puts what it draws.
 */
class WidgetTabletTest {

    private fun plan(w: Int, h: Int, settings: WidgetSettings, available: Int = 12, fontScale: Float = 1f, upNext: Int = 0) =
        WidgetLayout.plan(w, h, settings, available, fontScale, upNext)

    private val cover = WidgetSettings(style = WidgetStyle.COVER)
    private val full = WidgetSettings(style = WidgetStyle.FULL_COVER)
    private val shelf = WidgetSettings(style = WidgetStyle.SHELF)

    /**
     * The widget left to one corner. Every tablet size, in every style and with every setting that
     * changes what is drawn, a list of twelve songs and a queue of four, in both orientations: what
     * the plan draws reaches across and down at least half of the widget. A strip (more than twice
     * as long as it is wide) has nothing to fill a tablet's room with, and nor has a list of
     * covers taller than a thousand dp, which is as many rows as a shelf has.
     */
    @Test
    fun `a tablet's widget uses the room it is given, with no more than half of it left empty across or down`() {
        val problems = ArrayList<String>()
        var checked = 0
        for (style in WidgetStyle.entries) for (content in listOf(WidgetContent.BOTH, WidgetContent.LIST)) {
            if (content == WidgetContent.LIST && style != WidgetStyle.SHELF) continue
            for (look in if (style == WidgetStyle.SHELF) ShelfLook.entries else listOf(ShelfLook.BIG_COVER))
            for (buttons in WidgetButtons.entries) for (size in WidgetTextSize.entries) for (showArtist in listOf(true, false)) {
                val settings = WidgetSettings(style = style, look = look, content = content, buttons = buttons, textSize = size, showArtist = showArtist)
                for (fontScale in listOf(1f, 1.3f)) for (upNext in if (style == WidgetStyle.SHELF) listOf(0) else listOf(0, 4)) {
                    for (w in 420..1100 step 10) for (h in 420..1100 step 10) {
                        if (maxOf(w, h) <= 650 || maxOf(w, h) > 2 * minOf(w, h)) continue
                        if (content == WidgetContent.LIST && h > 1000) continue
                        val p = plan(w, h, settings, fontScale = fontScale, upNext = upNext)
                        val share = WidgetFill.share(p, w, h, size.scale * fontScale)
                        checked++
                        if (share.across < 0.5f || share.down < 0.5f) {
                            problems += "$style $look $content, ${p.shape}, $buttons, $size, font $fontScale, $upNext next, ${w}x$h: " +
                                "${(share.across * 100).toInt()}% across, ${(share.down * 100).toInt()}% down"
                        }
                    }
                }
            }
        }
        assertTrue(checked > 100_000)
        assertEquals("${problems.size} of $checked plans leave over half empty:\n" + problems.take(12).joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `a tablet's Shelf shows up to twelve songs in each look, in the middle of the widget`() {
        for (look in ShelfLook.entries) {
            val s = shelf.copy(look = look)
            val wide = plan(900, 600, s)
            assertTrue("$look: ${wide.shown}", wide.shown >= 10)
            assertTrue("$look has its buttons and the heart", wide.play && wide.skips && wide.like)
            val share = WidgetFill.share(wide, 900, 600, 1f)
            assertTrue("$look fills the widget: $share", share.across >= 0.75f && share.down >= 0.6f)
            // In the middle: as far from one side as from the other.
            val boxes = WidgetFill.boxes(wide, 900, 600, 1f)
            val left = boxes.minOf { it.x }
            val right = 900 - boxes.maxOf { it.right }
            assertTrue("$look: $left from the start, $right from the end", kotlin.math.abs(left - right) <= 24 || look != ShelfLook.BIG_COVER)

            // A list alone is the same kind of block.
            val list = plan(900, 600, s.copy(content = WidgetContent.LIST))
            assertTrue("$look: ${list.shown}", list.shown >= 10)
            assertFalse(list.play)

            // Only the songs there are, and no more rows than the widget was told.
            assertEquals("$look", 5, plan(900, 600, s, available = 5).shown)
            assertEquals("$look", 2, plan(900, 600, s, available = 2).shown)
            if (look != ShelfLook.BIG_COVER) {
                assertEquals("$look", 1, plan(900, 600, s.copy(maxRows = 1)).look!!.rows.size)
                assertTrue("$look", plan(900, 600, s.copy(maxRows = 2)).look!!.rows.size <= 2)
                // The heading where it costs no song and shrinks none, and never once turned off.
                val titled = plan(1000, 1000, s.copy(content = WidgetContent.LIST))
                assertTrue("$look has its heading", titled.look!!.heading != null)
                assertTrue(titled.look!!.picks.all { it.y >= titled.look!!.heading!!.bottom })
                assertNull("$look without its heading", plan(1000, 1000, s.copy(content = WidgetContent.LIST, showHeading = false)).look!!.heading)
            }
        }
    }

    /** The lists keep up to twelve songs for a tablet to fill itself with. A phone's widget draws the six it was made for. */
    @Test
    fun `below a tablet's size a longer list changes nothing`() {
        val sizes = listOf(
            100 to 100, 150 to 150, 170 to 260, 400 to 188, 400 to 250, 400 to 340, 405 to 432, 405 to 648, 648 to 405,
            560 to 560, 600 to 600, 650 to 650, 640 to 300, 900 to 300, 1000 to 400, 1100 to 200, 300 to 1000,
        )
        val shelves = ShelfLook.entries.flatMap { listOf(shelf.copy(look = it), shelf.copy(look = it, content = WidgetContent.LIST)) }
        for (settings in listOf(cover, full) + shelves) for (upNext in listOf(0, 4)) {
            for ((w, h) in sizes) {
                assertEquals("$settings ${w}x$h", plan(w, h, settings, available = 6, upNext = upNext), plan(w, h, settings, available = 12, upNext = upNext))
            }
        }
        // The six a phone has, from a list that has more: never a seventh.
        for (look in ShelfLook.entries) assertTrue(plan(405, 648, shelf.copy(look = look)).shown <= 6)
    }

    @Test
    fun `Up next stays beside a cover that has the height for it, and a squarer tablet widget is the cover filling it`() {
        val wide = plan(900, 600, cover, upNext = 4)
        assertEquals(WidgetShape.PANES, wide.shape)
        assertTrue("the cover takes most of the height", wide.coverH * 100 >= (600 - 2 * wide.padH) * 60)
        // Squarer, the cover beside Up next would be a small part of the height: it is drawn without Up next.
        assertEquals(WidgetShape.TOP, plan(700, 700, cover, upNext = 4).shape)
        assertEquals(WidgetShape.TOP, plan(600, 800, cover, upNext = 4).shape)
        // Full cover is its cover whatever the shape, and keeps Up next.
        assertEquals(WidgetShape.PANES, plan(700, 700, full, upNext = 4).shape)
        // Not a tablet's size: as it was, whatever the cover.
        assertEquals(WidgetShape.PANES, plan(640, 300, cover, upNext = 4).shape)
        assertEquals(WidgetShape.PANES, plan(560, 560, cover, upNext = 4).shape)
        // Nothing queued: the cover beside the song, as before.
        assertEquals(WidgetShape.SIDE, plan(800, 600, cover).shape)
    }

    @Test
    fun `Cover's column on a tablet stays a column of words, with the cover and the column in the middle`() {
        val wide = plan(1100, 450, cover)
        assertEquals(WidgetShape.SIDE, wide.shape)
        assertEquals(WidgetLayout.BIG_COLUMN_MAX_DP, WidgetLayout.columnWidth(wide, 1100))
        assertTrue(wide.padH > 100)
        assertEquals(1100.0, (2 * wide.padH + wide.coverW + WidgetLayout.SIDE_GAP_DP + WidgetLayout.columnWidth(wide, 1100)).toDouble(), 1.0)
        // Where the column is narrower than that already, nothing moves.
        assertEquals(14, plan(800, 600, cover).padH)
        // No cover wanted: the column is the widget, as it was.
        assertEquals(14, plan(1100, 450, cover.copy(showArtwork = false)).padH)
    }

    @Test
    fun `a tablet's size is a long side over 650 dp and a short one of at least 420`() {
        assertTrue(WidgetLayout.isBig(651, 420))
        assertTrue(WidgetLayout.isBig(420, 651))
        assertTrue(WidgetLayout.isBig(1100, 800))
        assertFalse("a phone on its side, at One UI's widest", WidgetLayout.isBig(648, 405))
        assertFalse("a phone's tallest", WidgetLayout.isBig(405, 648))
        assertFalse(WidgetLayout.isBig(650, 650))
        assertFalse("a strip", WidgetLayout.isBig(1100, 419))
        assertEquals(12, WidgetLayout.MAX_PICKS)
        assertEquals(6, WidgetLayout.PHONE_PICKS)
    }
}
