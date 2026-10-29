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
     * Since the styles (25 Oct 2026) the list is a Shelf's covers, in full rows only. Five by three
     * is square enough to take the four by four's shape: its buttons on a line of their own and
     * three bigger covers rather than four. From five by four up there is room for all six.
     */
    @Test
    fun `a Shelf shows four covers at five by two and all six from five by four up`() {
        assertEquals(listOf(4, 3, 6, 6, 6), portrait.map { (w, h) -> plan(w, h, shelf).shown })
        assertEquals(listOf(6, 6, 5, 6, 6), landscape.map { (w, h) -> plan(w, h, shelf).shown })
        (portrait + landscape).forEach { (w, h) -> assertEquals("$w x $h", WidgetShape.SHELF, plan(w, h, shelf).shape) }
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
    fun `every setting of what a Shelf holds and how many rows gets the covers that fit, in full rows`() {
        val problems = mutableListOf<String>()
        for ((w, h) in portrait + landscape) for (content in listOf(WidgetContent.BOTH, WidgetContent.LIST)) for (maxRows in 0..WidgetLayout.MAX_PICKS) {
            val p = plan(w, h, shelf.copy(content = content, maxRows = maxRows))
            val at = "$w x $h, $content, rows set to $maxRows"
            if (maxRows > 0 && p.shelfRows > maxRows) problems += "$at: ${p.shelfRows} rows, over the ceiling"
            if (p.shown > WidgetLayout.MAX_PICKS || p.shown > p.columns * p.shelfRows) problems += "$at: ${p.shown} covers"
            if (p.shelfRows > 1 && p.columns * p.shelfRows > WidgetLayout.MAX_PICKS) problems += "$at: a row not full"
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
