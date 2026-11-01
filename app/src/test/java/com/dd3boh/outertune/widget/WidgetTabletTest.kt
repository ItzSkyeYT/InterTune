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
            for (buttons in WidgetButtons.entries) for (size in WidgetTextSize.entries) for (showArtist in listOf(true, false)) {
                val settings = WidgetSettings(style = style, content = content, buttons = buttons, textSize = size, showArtist = showArtist)
                for (fontScale in listOf(1f, 1.3f)) for (upNext in if (style == WidgetStyle.SHELF) listOf(0) else listOf(0, 4)) {
                    for (w in 420..1100 step 10) for (h in 420..1100 step 10) {
                        if (maxOf(w, h) <= 650 || maxOf(w, h) > 2 * minOf(w, h)) continue
                        if (content == WidgetContent.LIST && h > 1000) continue
                        val p = plan(w, h, settings, fontScale = fontScale, upNext = upNext)
                        val share = WidgetFill.share(p, w, h, size.scale * fontScale)
                        checked++
                        if (share.across < 0.5f || share.down < 0.5f) {
                            problems += "$style $content, ${p.shape}, $buttons, $size, font $fontScale, $upNext next, ${w}x$h: " +
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
    fun `a tablet's Shelf is a block of whole rows of covers in the middle of the widget, as many as the list has to fill it`() {
        val wide = plan(900, 600, shelf)
        assertEquals(WidgetShape.SHELF, wide.shape)
        assertEquals(12, wide.shown)
        assertEquals(6, wide.columns)
        assertEquals(2, wide.shelfRows)
        assertTrue(wide.captions && wide.heading)
        assertTrue("the line of what is playing is beside its buttons", wide.buttonsInline && wide.like)
        assertTrue("a size up from a phone's", wide.headerCover > 48 && wide.titleSp > 15 && wide.tile > WidgetLayout.SHELF_MAX_DP)
        // In the middle: the block is as far from one side as from the other, and from the top as from the bottom.
        val block = wide.columns * wide.tile + (wide.columns - 1) * wide.shelfGap
        assertEquals(900.0, (2 * wide.padH + block).toDouble(), 1.0)

        // Tall, the buttons have a line of their own and the covers are three rows of four.
        val tall = plan(700, 800, shelf)
        assertFalse(tall.buttonsInline)
        assertEquals(4, tall.columns)
        assertEquals(3, tall.shelfRows)
        assertEquals(5, tall.slots)

        // A list alone is its heading and the same kind of block.
        val list = plan(900, 600, shelf.copy(content = WidgetContent.LIST))
        assertEquals(WidgetShape.GRID, list.shape)
        assertTrue(list.heading && list.captions)
        assertEquals(12, plan(1000, 700, shelf.copy(content = WidgetContent.LIST)).shown)

        // Only the songs there are, in whole rows, and no more rows than the widget was told.
        assertEquals(5, plan(900, 600, shelf, available = 5).shown)
        assertEquals(10, plan(700, 500, shelf, available = 11).shown)
        assertEquals(1, plan(900, 600, shelf.copy(maxRows = 1)).shelfRows)
        assertTrue(plan(900, 600, shelf.copy(maxRows = 2)).shelfRows <= 2)
        // With a heading turned off there is none, and its room goes to the covers.
        assertFalse(plan(900, 600, shelf.copy(showHeading = false)).heading)
        // Too few songs for a grid of covers a tablet's size: the shelf is the ordinary one.
        assertEquals(2, plan(900, 600, shelf, available = 2).shown)
        assertEquals(0, plan(900, 600, shelf, available = 0).shown)
    }

    /** The lists keep up to twelve songs for a tablet to fill itself with. A phone's widget draws the six it was made for. */
    @Test
    fun `below a tablet's size a longer list changes nothing`() {
        val sizes = listOf(
            100 to 100, 150 to 150, 170 to 260, 400 to 188, 400 to 250, 400 to 340, 405 to 432, 405 to 648, 648 to 405,
            560 to 560, 600 to 600, 650 to 650, 640 to 300, 900 to 300, 1000 to 400, 1100 to 200, 300 to 1000,
        )
        for (settings in listOf(cover, full, shelf, shelf.copy(content = WidgetContent.LIST))) for (upNext in listOf(0, 4)) {
            for ((w, h) in sizes) {
                assertEquals("$settings ${w}x$h", plan(w, h, settings, available = 6, upNext = upNext), plan(w, h, settings, available = 12, upNext = upNext))
            }
        }
        // The six a phone has, from a list that has more: never a seventh.
        assertTrue(plan(405, 648, shelf).shown <= 6)
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
