/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * In a room, the room's button moves no other button: his rule of 15 Nov 2026, after it joined the
 * player's top row between the heart and the menu and pushed the timer and the heart left. It goes at
 * the free end of its row. Partly read from the source: what Compose draws where cannot be looked at
 * on the JVM.
 */
class RoomButtonStaysTest {
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()

    @Test
    fun `a room's circle goes first in Classic's row, at its free end`() {
        val row = player.substringAfter("val classicButtons: @Composable RowScope.() -> Unit = {").substringBefore("\n            }\n")
        val room = row.indexOf("roomCircle()")
        assertTrue(room > 0)
        for (other in listOf("queueCircle()", "classicPins(queueCircle = queueLayout.queueButton)", "menuCircle()")) {
            assertTrue(other, room < row.indexOf(other))
        }
        // Beside a small player's cover, no circle changes side for it: the halves are counted without it.
        val columns = player.substringAfter("val classicSideColumn: @Composable (Boolean) -> Unit = { left ->").substringBefore("\n            }\n")
        assertTrue("val count = pinned.size + 2\n" in columns)
        assertFalse("inRoom) 1 else 0" in columns)
    }

    @Test
    fun `a room's segment comes first in Connected's row and hangs off it`() {
        val row = player.substringAfter("CentredRow(\n").substringBefore("\n                }\n            }\n")
        assertTrue("hangingFirst = roomHangs," in row)
        assertTrue("val roomHangs = inRoom && !compactPlayer" in player)
        val room = row.indexOf("contentDescription = stringResource(R.string.together_in_room)")
        assertTrue(room > 0)
        assertTrue(room < row.indexOf("queueSegment()"))
        assertTrue(room < row.indexOf("rowPins.forEachIndexed"))
        assertEquals("one room segment", 1, Regex("""R\.string\.together_in_room""").findAll(row).count())
    }

    private val density = Density(2.75f)
    private val gap = 6.dp
    private val gutter = 32.dp
    private fun px(dp: Dp) = with(density) { dp.roundToPx() }

    @Test
    fun `the others stand where they stood without the room's segment`() = with(density) {
        val row = px(411.dp - gutter * 2)
        for (others in 1..4) {
            val without = IntArray(others) { px(56.dp) }
            val with = intArrayOf(px(56.dp)) + without
            for (direction in LayoutDirection.entries) {
                val alone = quickRowPositions(without, gap, row, direction)
                val hung = quickRowPositionsHanging(with, gap, row, gutter, direction)!!
                assertArrayEquals("$others others, $direction", alone, hung.copyOfRange(1, hung.size))
                // The room's segment is a gap before the first of them, on the start side.
                if (direction == LayoutDirection.Ltr) assertEquals(alone[0] - px(gap) - with[0], hung[0])
                else assertEquals(alone[0] + without[0] + px(gap), hung[0])
            }
        }
    }

    @Test
    fun `with no room left for it the segment is centred with the others`() = with(density) {
        // Five on a 411dp phone, or six in a 300dp window: one more hung past the start would reach the edge.
        assertNull(quickRowPositionsHanging(IntArray(6) { px(56.dp) }, gap, px(411.dp - gutter * 2), gutter, LayoutDirection.Ltr))
        val row = px(300.dp - gutter * 2)
        assertNull(quickRowPositionsHanging(IntArray(7) { px(56.dp) }, gap, row, gutter, LayoutDirection.Ltr))
        assertNull(quickRowPositionsHanging(intArrayOf(px(56.dp)), gap, row, gutter, LayoutDirection.Ltr))
    }
}
