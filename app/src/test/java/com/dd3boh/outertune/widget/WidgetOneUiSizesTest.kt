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

    @Test
    fun `what is playing and a list shows two rows at five by two and six from five by four up`() {
        assertEquals(listOf(2, 4, 6, 6, 6), portrait.map { (w, h) -> plan(w, h).rows })
        assertEquals(listOf(6, 6, 6, 6, 6), landscape.map { (w, h) -> plan(w, h).rows })
        // With what is playing above them, in a line, at every one of these sizes.
        (portrait + landscape).forEach { (w, h) -> assertEquals("$w x $h", NowShape.ROW, plan(w, h).shape) }
    }

    @Test
    fun `a list on its own shows three rows at five by two and six from five by four up`() {
        val list = WidgetSettings(content = WidgetContent.LIST)
        assertEquals(listOf(3, 5, 6, 6, 6), portrait.map { (w, h) -> plan(w, h, list).rows })
        assertEquals(listOf(6, 6, 6, 6, 6), landscape.map { (w, h) -> plan(w, h, list).rows })
    }

    @Test
    fun `every setting of what it shows and how many rows gets all the rows that fit`() {
        val problems = mutableListOf<String>()
        for ((w, h) in portrait + landscape) for (content in WidgetContent.entries) for (maxRows in 0..WidgetLayout.MAX_PICKS) {
            val p = plan(w, h, WidgetSettings(content = content, maxRows = maxRows))
            val at = "$w x $h, $content, rows set to $maxRows"
            if (content == WidgetContent.NOW_PLAYING) {
                if (p.rows != 0) problems += "$at: ${p.rows} rows on a widget set to show no list"
                continue
            }
            val allowed = if (maxRows > 0) maxRows else WidgetLayout.MAX_PICKS
            if (p.rows > allowed) problems += "$at: ${p.rows} rows, over the ceiling"
            if (WidgetLayout.heightOf(p, h) > h) problems += "$at: taller than the widget"
            // Fewer than were allowed only when one more, at the shortest a row can be, would not fit.
            val withOneMore = WidgetLayout.heightOf(p, h) - p.rows * p.rowHeight + (p.rows + 1) * WidgetLayout.LIST_ROW_MIN_DP
            if (p.rows < allowed && withOneMore <= h) problems += "$at: ${p.rows} rows where ${p.rows + 1} fit"
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `two rows under what is playing is the plan for 203 to 252 dp of height and for no other`() {
        val heights = (100..700).filter { h -> plan(405, h).rows == 2 }
        assertEquals((203..252).toList(), heights)
        // So the same two rows at five by four mean the list was short or the ceiling was two.
        val short = plan(405, 432, available = 2)
        assertEquals(2, short.rows)
        assertFalse("a list that ran out of songs starts at the top", short.centred)
        assertEquals(2, plan(405, 432, WidgetSettings(maxRows = 2)).rows)
        assertTrue(plan(405, 432).centred)
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
