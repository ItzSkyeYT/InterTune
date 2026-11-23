/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A Shelf widget in Arabic, Persian or Hebrew is the mirror image of its look (7e6043a92, 1 Nov
 * 2026). It broke again on the 0.13 line (91de0d194, 9 Nov) and no test saw it: At, which puts each
 * part of a look in its place, kept its padding on the left and the top alone, and Glance puts a
 * box's child at its start and top (glance-appwidget 1.2.0, res/layout/box_child_start_top_group_*.xml:
 * layout_gravity="start|top"), which right to left is the right. Every part went to the right edge
 * of the widget or of its row: the words and the buttons over the cover, Big cover's mosaic over
 * the big cover, and in Crate and Records every song's tap at the end of its row.
 *
 * This puts every part of every look where Android does, from At's padding ([Spot.around]) and
 * the part's own size (WidgetDrawAheadTest holds At to both), the app reading either way and the
 * views either way: an app language other than the phone's must change nothing at the planned size.
 */
class WidgetRightToLeftTest {

    /** The looks with settings that move their parts, as WidgetLayoutTest's sweep has them. */
    private val looks = buildList {
        for (look in ShelfLook.entries) {
            add(WidgetSettings(style = WidgetStyle.SHELF, look = look))
            add(WidgetSettings(style = WidgetStyle.SHELF, look = look, content = WidgetContent.LIST))
            add(WidgetSettings(style = WidgetStyle.SHELF, look = look, textSize = WidgetTextSize.LARGE))
            add(WidgetSettings(style = WidgetStyle.SHELF, look = look, buttons = WidgetButtons.PLAY_ONLY))
            add(WidgetSettings(style = WidgetStyle.SHELF, look = look, maxRows = 2))
        }
    }

    /** Sizes on WidgetLayoutTest's own grid, where every part is proved to lie inside the widget. */
    private val sizes = buildList {
        for (w in 40..600 step 12) for (h in 40..600 step 12) add(w to h)
        for (w in 606..1100 step 44) for (h in 40..800 step 28) add(w to h)
    }

    /**
     * Where Android draws a part At places: a box padded by [pad] fills its parent, [parentW] by
     * [parentH] dp, and its one child, [w] by [h] dp, goes to the top and the start of what the
     * padding leaves, the start being the right where the views read right to left.
     */
    private fun drawn(parentW: Int, parentH: Int, pad: Around, w: Int, h: Int, viewsRtl: Boolean): Spot =
        Spot(if (viewsRtl) parentW - pad.right - w else pad.left, pad.top, w, h)

    /** [spot] of a box [boxW] dp wide, seen in a mirror. */
    private fun mirrored(spot: Spot, boxW: Int): Spot = Spot(boxW - spot.right, spot.y, spot.w, spot.h)

    /** A part of a look: its spot in the widget, or in the part it is drawn in (a row of Big cover's mosaic, a row's picture). */
    private data class Part(val name: String, val spot: Spot, val parent: Spot? = null)

    /** Where [part] is in the widget as its look plans it, left to right. */
    private fun planned(part: Part): Spot = part.parent?.let { part.spot.at(it.x, it.y) } ?: part.spot

    /** Every part At places in a look, as MusicWidget's BigCoverLook and RowsLook give it. */
    private fun parts(p: WidgetPlan): List<Part> {
        val look = p.look ?: return emptyList()
        val big = p.shape == WidgetShape.BIG_COVER
        if (!big && p.shape != WidgetShape.CRATE && p.shape != WidgetShape.RECORDS) return emptyList()
        if (big && look.hero == null) return emptyList()
        val out = ArrayList<Part>()
        val picture = look.heroPicture
        if (look.hero != null && picture != null) out += Part("the cover's picture", picture)
        val words = look.words
        if (words != null) out += Part("the words", words)
        val glass = p.glass
        if (glass != null) out += Part("the pill", Spot(glass.x, glass.y, glass.w, glass.h))
        if (big) {
            for (row in look.picks.groupBy { it.y }.values) {
                val box = Spot(row.first().x, row.first().y, row.last().right - row.first().x, row.first().h)
                out += Part("a row of the mosaic", box)
                for (pick in row) out += Part("a cover of the mosaic", pick.at(-box.x, -box.y), box)
            }
        } else {
            val heading = look.heading
            if (heading != null) out += Part("the heading", heading)
            val taps = look.taps()
            var first = 0
            for ((r, rowPicture) in look.rowPictures.withIndex()) {
                val row = taps.subList(first, first + look.rows[r])
                val top = row.minOf { it.y }
                val bottom = row.maxOf { it.bottom }
                out += Part("a row's picture", rowPicture)
                for (tap in row) out += Part("a song's tap", Spot(tap.x - rowPicture.x, top - rowPicture.y, tap.w, bottom - top), rowPicture)
                first += look.rows[r]
            }
        }
        return out
    }

    /** Where Android draws [part] in a widget [w] by [h] dp, the app reading right to left or not ([rtl]), and the views ([viewsRtl]). */
    private fun place(part: Part, w: Int, h: Int, rtl: Boolean, viewsRtl: Boolean): Spot {
        val parent = part.parent ?: return drawn(w, h, part.spot.around(w, h, rtl), part.spot.w, part.spot.h, viewsRtl)
        val row = drawn(w, h, parent.around(w, h, rtl), parent.w, parent.h, viewsRtl)
        val inRow = drawn(parent.w, parent.h, part.spot.around(parent.w, parent.h, rtl), part.spot.w, part.spot.h, viewsRtl)
        return inRow.at(row.x, row.y)
    }

    private fun way(rtl: Boolean) = if (rtl) "right to left" else "left to right"

    @Test
    fun `every part of a look is drawn where its plan puts it, and right to left on its mirror image, whichever way the views read`() {
        val seen = HashMap<String, Int>()
        for (s in looks) for (font in listOf(1f, 1.3f)) for (available in listOf(0, 2, 6, WidgetLayout.MAX_PICKS)) for ((w, h) in sizes) {
            val p = WidgetLayout.plan(w, h, s, available, font)
            for (part in parts(p)) {
                seen.merge(part.name, 1, Int::plus)
                for (rtl in listOf(false, true)) for (viewsRtl in listOf(false, true)) {
                    val at = place(part, w, h, rtl, viewsRtl)
                    val want = if (rtl) mirrored(planned(part), w) else planned(part)
                    if (at != want) fail("${part.name} drawn at $at, not $want: the app ${way(rtl)}, the views ${way(viewsRtl)}, $s, font $font, $available songs, at ${w}x$h")
                }
            }
        }
        for (name in listOf("the cover's picture", "the words", "the pill", "a row of the mosaic", "a cover of the mosaic", "the heading", "a row's picture", "a song's tap")) {
            assertTrue("$name never placed: $seen", (seen[name] ?: 0) > 100)
        }
    }

    /**
     * While the launcher shows the widget at a new size and the app has not drawn it yet, each part
     * stays as far from the edge the language starts at as it was drawn: from the left left to right,
     * and right to left from the right, as the look's mirror image. A part in a row keeps its place
     * in it, the row being its own size whatever the widget's.
     */
    @Test
    fun `while the launcher shows a new size, each part stays as far from the edge the language starts at`() {
        var checked = 0
        for (s in looks) for ((w, h) in sizes) {
            val p = WidgetLayout.plan(w, h, s, 6)
            for (part in parts(p)) {
                if (part.parent != null) continue
                for ((dw, dh) in listOf(40 to 30, -24 to -20, 60 to 0, 0 to -16)) for (rtl in listOf(false, true)) {
                    val shown = drawn(w + dw, h + dh, part.spot.around(w, h, rtl), part.spot.w, part.spot.h, viewsRtl = rtl)
                    val want = if (rtl) mirrored(part.spot, w).at(dw, 0) else part.spot
                    if (shown != want) fail("${part.name} shown at $shown, not $want, ${way(rtl)}: planned at ${w}x$h, shown at ${w + dw}x${h + dh}, $s")
                    checked++
                }
            }
        }
        assertTrue("$checked", checked > 10_000)
    }

    /**
     * At as it was from 9 Nov, padded on the left and the top alone, at the mockups' four by three:
     * right to left each row of Big cover's mosaic went to the right edge, over the big cover, and
     * in a crate every song's tap went to the end of its row, one over the other.
     */
    @Test
    fun `padded on the left and the top alone, right to left the mosaic covers the big cover and a crate's taps pile up`() {
        val w = 360
        val h = 240
        fun leftAndTop(spot: Spot, boxW: Int, boxH: Int) = spot.around(boxW, boxH, true).copy(right = 0, bottom = 0)

        val big = WidgetLayout.plan(w, h, WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.BIG_COVER), 6)
        val hero = mirrored(big.look!!.hero!!, w)
        val rows = parts(big).filter { it.name == "a row of the mosaic" }
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val then = drawn(w, h, leftAndTop(row.spot, w, h), row.spot.w, row.spot.h, viewsRtl = true)
            val now = place(row, w, h, rtl = true, viewsRtl = true)
            assertTrue("then over the big cover: $then, the cover at $hero", then.overlaps(hero))
            assertFalse("now beside it: $now, the cover at $hero", now.overlaps(hero))
        }

        val crate = WidgetLayout.plan(w, h, WidgetSettings(style = WidgetStyle.SHELF, look = ShelfLook.CRATE), 6)
        var shared = 0
        for ((picture, row) in parts(crate).filter { it.name == "a song's tap" }.groupBy { it.parent!! }) {
            if (row.size < 2) continue
            val then = row.map { drawn(picture.w, picture.h, leftAndTop(it.spot, picture.w, picture.h), it.spot.w, it.spot.h, viewsRtl = true) }
            val now = row.map { place(it, w, h, rtl = true, viewsRtl = true) }
            assertEquals("then every tap ended at the row's end: $then", 1, then.map { it.right }.distinct().size)
            assertTrue("now none over another: $now", now.indices.all { i -> now.indices.none { j -> j > i && now[i].overlaps(now[j]) } })
            shared++
        }
        assertTrue("a crate row of two songs or more at four by three", shared > 0)
    }
}
