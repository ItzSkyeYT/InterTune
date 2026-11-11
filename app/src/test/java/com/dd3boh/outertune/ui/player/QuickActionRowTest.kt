/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Connected's row under the controls, centred whatever it holds (GitHub #142: with the queue
 * button on, the row sat half a button and a gap to the left of the middle, because the queue
 * button was left out of the centring).
 */
class QuickActionRowTest {

    private val density = Density(2.75f)
    private val gap = 6.dp
    private val gutter = 32.dp
    private fun px(dp: Dp) = with(density) { dp.roundToPx() }

    private val button = 56.dp
    private val withTime = 88.dp

    /** The row as the player lays it out: [natural] widths in a row [width] wide, between gutters. */
    private class Laid(val widths: IntArray, val positions: IntArray, val width: Int, val room: Int)

    private fun lay(natural: List<Dp>, width: Dp, rtl: Boolean = false, gutter: Dp = this.gutter): Laid = with(density) {
        val w = px(width)
        val room = quickRowRoom(w, gutter)
        val widths = fitQuickActionWidths(natural.map { px(it) }.toIntArray(), px(gap), room, QuickActionIconOnlyWidth.roundToPx())
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        Laid(widths, quickRowPositions(widths, gap, w, direction), w, room)
    }

    private fun Laid.spaceBefore() = positions.min()
    private fun Laid.spaceAfter() = width - (0 until positions.size).maxOf { positions[it] + widths[it] }

    private fun assertCentred(what: String, laid: Laid) {
        // A pixel of rounding, as a Row has.
        assertTrue("$what: ${laid.spaceBefore()} before, ${laid.spaceAfter()} after", abs(laid.spaceBefore() - laid.spaceAfter()) <= 1)
    }

    @Test
    fun `the row is centred with the queue button on or off, with one button to six`() {
        // A phone 360dp wide, an ordinary 411dp one, a phone on its side with its narrow column,
        // and a tablet's. The row is as wide as the screen less a gutter either side.
        for (screen in listOf(360.dp, 411.dp, 592.dp, 840.dp)) {
            val row = screen - gutter * 2
            for (buttons in 1..6) {
                val laid = lay(List(buttons) { button }, row)
                assertCentred("$buttons buttons in $screen", laid)
                assertCentred("$buttons buttons in $screen, right to left", lay(List(buttons) { button }, row, rtl = true))
            }
        }
    }

    @Test
    fun `turning the queue button on keeps the whole row in the middle and does not push it to the left`() {
        // What the tester's pictures showed: queue, like, lyrics and the menu, half a button and a
        // gap left of the middle. The same four, as one row, are in the middle.
        val row = 411.dp - gutter * 2
        val off = lay(listOf(button, button, button), row)
        val on = lay(listOf(button, button, button, button), row)
        assertCentred("without the queue button", off)
        assertCentred("with it", on)
        // The queue button is the first of the row, and the others stand where the wider row puts them.
        assertTrue(on.positions[0] < off.positions[0])
        assertEquals(px(button) + px(gap), on.positions[1] - on.positions[0])
        // The five of the tester's other picture, the queue button and four more.
        assertCentred("five buttons", lay(List(5) { button }, row))
    }

    @Test
    fun `a button with the timer's time in the row leaves it centred`() {
        val row = 411.dp - gutter * 2
        for (at in 0..3) {
            val natural = List(4) { if (it == at) withTime else button }
            assertCentred("time at $at", lay(natural, row))
            assertCentred("time at $at, right to left", lay(natural, row, rtl = true))
        }
        // With the queue button, three pins and the menu, one of them showing the time.
        assertCentred("five with the time", lay(listOf(button, button, withTime, button, button), row))
    }

    @Test
    fun `a row that fits is left exactly as it is`() {
        val natural = intArrayOf(154, 154, 242, 154)
        assertEquals(natural.toList(), fitQuickActionWidths(natural, gap = 17, room = 900, floor = 116).toList())
        assertEquals(emptyList<Int>(), fitQuickActionWidths(IntArray(0), gap = 17, room = 900, floor = 116).toList())
        assertEquals(listOf(154), fitQuickActionWidths(intArrayOf(154), gap = 17, room = 154, floor = 116).toList())
        // One too wide by a pixel gives way, among the buttons that are only an icon, by what it must.
        val tight = fitQuickActionWidths(intArrayOf(154, 154, 154), gap = 17, room = 154 * 3 + 34 - 1, floor = 116)
        assertEquals(listOf(153, 153, 153), tight.toList())
    }

    @Test
    fun `where the buttons fit they land where a centred Row with the same gap puts them`() {
        val width = px(411.dp - gutter * 2)
        for (count in 1..5) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val widths = IntArray(count) { px(button) }
            val row = IntArray(count)
            with(density) {
                with(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) { arrange(width, widths, direction, row) }
            }
            val mine = with(density) { quickRowPositions(widths, gap, width, direction) }
            for (i in 0 until count) assertTrue("$count buttons, button $i, $direction: ${mine[i]} against ${row[i]}", abs(mine[i] - row[i]) <= 1)
        }
    }

    @Test
    fun `six buttons on a narrow phone share their width and keep the row on the screen, centred`() {
        // The queue button, three pins, a listening room and the menu on a phone 360dp wide: 366dp
        // of buttons, more than the screen.
        val screen = 360.dp
        val laid = lay(List(6) { button }, screen - gutter * 2)
        assertCentred("six buttons", laid)
        assertTrue("narrower than the 56dp they have", laid.widths.all { it < px(button) })
        assertTrue("never less than an icon needs", laid.widths.all { it >= with(density) { QuickActionIconOnlyWidth.roundToPx() } })
        val before = laid.spaceBefore()
        val reach = px(gutter - QuickRowEdgeMargin)
        // No nearer to the screen's edge than the margin, which is the gutter less the reach.
        assertTrue("$before before it, the most it may reach is $reach", before >= -reach)
        assertTrue(laid.spaceAfter() >= -reach)
        // And the whole of it fits in its room, to a pixel.
        assertTrue(laid.widths.sum() + px(gap) * 5 <= laid.room)
    }

    @Test
    fun `the button that shows the time keeps its width when the others give way`() {
        val screen = 360.dp
        val natural = List(6) { if (it == 2) withTime else button }
        val laid = lay(natural, screen - gutter * 2)
        assertEquals("the time's button is as wide as its words", px(withTime), laid.widths[2])
        assertTrue("the others are narrower", laid.widths.filterIndexed { i, _ -> i != 2 }.all { it < px(button) })
        assertCentred("six with the time", laid)
    }

    @Test
    fun `a row never gives up more than an icon's width, however many are in a narrow window`() {
        // A floating window 240dp wide, the narrowest, with a row of six: nothing fits, and the
        // buttons stop at what an icon needs rather than clip it.
        val laid = lay(List(6) { button }, 240.dp - 16.dp * 2, gutter = 16.dp)
        val floor = with(density) { QuickActionIconOnlyWidth.roundToPx() }
        assertTrue(laid.widths.all { it == floor })
        assertCentred("six in a narrow window", laid)
    }

    @Test
    fun `the row reaches into the gutters evenly and is not moved to one side`() {
        // Five buttons are 304dp, wider than 296dp between the gutters of a phone 360dp wide: they
        // overhang the row by the same on both sides and need no shrinking.
        val laid = lay(List(5) { button }, 360.dp - gutter * 2)
        assertEquals(List(5) { px(button) }, laid.widths.toList())
        assertTrue(laid.spaceBefore() < 0)
        assertCentred("five on 360dp", laid)
    }

    // The rule in the player's own source, which the layout above cannot see.

    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()

    @Test
    fun `the queue button is one of the row's buttons, not a button left out of its centring`() {
        val row = player.substringAfter("val quickActions").substringBefore("val controlsContent")
        assertTrue(row.contains("CentredRow("))
        assertTrue("the queue button is drawn inside the row", row.contains("if (queueLayout.queueButton) queueSegment()"))
        assertFalse("nothing stands in front of the row, outside its centring", row.contains("leading ="))
        assertFalse(player.contains("CentredRowWithLeading"))
        assertFalse(player.contains("LeadingEdgeMargin"))
    }

    @Test
    fun `an icon-only button is as narrow as the floor says`() {
        // QuickActionIconOnlyWidth is the icon's 22dp and 10dp either side, from PlayerActionSegment.
        val segment = player.substringAfter("private fun PlayerActionSegment(").substringBefore("\n}\n")
        assertTrue(segment.contains(".padding(horizontal = 10.dp)"))
        assertTrue(segment.contains(".size(22.dp)"))
        assertEquals(22.dp + 10.dp * 2, QuickActionIconOnlyWidth)
    }
}
