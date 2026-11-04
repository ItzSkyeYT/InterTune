/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plan at the sizes One UI Home reports, for the report that a widget taking up much of the
 * page "only shows like two songs and the now playing" (TODO.md section 36, a Galaxy S25 Ultra on
 * One UI 8).
 *
 * One UI Home gives a widget its size to the dp, the same for the smallest and the largest: 405 by
 * 216 dp for five columns by two rows on that phone, so about 108 dp for each row of the grid. The
 * plan gives every one of those sizes as many rows as fit, up to the six a widget ever shows. Two
 * rows under what is playing is what it plans for a height of 203 to 252 dp and for no other, so a
 * taller widget drawn with two had a list of two songs, a ceiling of two, or was drawn for a size
 * it no longer had.
 */
class WidgetOneUiSizesTest {

    /** Five columns wide, two to six rows of the grid tall. */
    private val portrait = listOf(405 to 216, 405 to 324, 405 to 432, 405 to 540, 405 to 648)

    /** The same shapes on their side, which is as near as a home screen in landscape can be guessed without the phone. */
    private val landscape = portrait.map { (w, h) -> h to w }

    private fun plan(w: Int, h: Int, settings: WidgetSettings = WidgetSettings(), available: Int = WidgetLayout.MAX_PICKS) =
        WidgetLayout.plan(w, h, settings, available)

    private val shelf = WidgetSettings(style = WidgetStyle.SHELF)

    /**
     * Since the looks (1 Nov 2026) a Shelf is a big cover with a mosaic, a crate or records. At
     * every One UI size from five by two to five by six, upright and on its side, each look is
     * drawn with the list in it, at least four of its songs, and never a seventh.
     */
    @Test
    fun `a Shelf shows the list in its look at every One UI size from five by two up`() {
        for (look in ShelfLook.entries) for ((w, h) in portrait + landscape) {
            val p = plan(w, h, shelf.copy(look = look))
            assertTrue("$look $w x $h: ${p.shape}", p.look != null)
            assertTrue("$look $w x $h: ${p.shown}", p.shown in 4..6)
            assertTrue("$look $w x $h", p.play && p.skips)
        }
    }

    @Test
    fun `Cover and Full cover draw what is playing at every one of these sizes, never a list`() {
        for ((w, h) in portrait + landscape) for (style in listOf(WidgetStyle.COVER, WidgetStyle.FULL_COVER)) {
            val p = plan(w, h, WidgetSettings(style = style))
            assertEquals("$w x $h $style", 0, p.shown)
            assertTrue("$w x $h $style", p.play && p.skips)
        }
        assertEquals(WidgetShape.SIDE, plan(405, 216, WidgetSettings(style = WidgetStyle.COVER)).shape)
        assertEquals(WidgetShape.TOP, plan(405, 432, WidgetSettings(style = WidgetStyle.COVER)).shape)
    }

    @Test
    fun `every setting of what a Shelf holds and how many rows gets covers, in no more rows than asked`() {
        val problems = mutableListOf<String>()
        for (look in ShelfLook.entries) for ((w, h) in portrait + landscape) for (content in listOf(WidgetContent.BOTH, WidgetContent.LIST)) for (maxRows in 0..WidgetLayout.MAX_PICKS) {
            val p = plan(w, h, shelf.copy(look = look, content = content, maxRows = maxRows))
            val at = "$look $w x $h, $content, rows set to $maxRows"
            val rows = p.look?.rows.orEmpty()
            if (maxRows > 0 && rows.size > maxRows) problems += "$at: ${rows.size} rows, over the ceiling"
            if (p.shown > WidgetLayout.PHONE_PICKS) problems += "$at: ${p.shown} covers"
            if (p.shown == 0) problems += "$at: no covers"
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    /**
     * The second thing reported from that phone: "the how many rows category cannot be scrolled
     * through". Its seven chips stood in a plain Row, about 443 dp of them in a dialog 270 to 345
     * dp wide, and the ones past 3 could not be reached. They wrap now, and so must any chip added
     * to that screen.
     */
    @Test
    fun `every chip in the widget's settings stands in a row that wraps`() {
        val screen = File("src/main/java/com/dd3boh/outertune/widget/WidgetConfigActivity.kt").readText()
        val wrapping = Regex("""\bFlowRow\s*\([^{]*\{""").findAll(screen).map { it.range.last..closingBrace(screen, it.range.last) }.toList()
        assertTrue("no row that wraps was found", wrapping.isNotEmpty())
        val chips = Regex("""(?<![A-Za-z.])Chip\(""").findAll(screen).map { it.range.first }
            .filterNot { screen.substring(0, it).endsWith("private fun ") }.toList()
        assertTrue("no chips were found", chips.size >= 4)
        val outside = chips.filter { at -> wrapping.none { at in it } }.map { screen.substring(0, it).count { c -> c == '\n' } + 1 }
        assertEquals("chips outside a FlowRow, at lines", emptyList<Int>(), outside)
    }

    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return text.length
    }
}
