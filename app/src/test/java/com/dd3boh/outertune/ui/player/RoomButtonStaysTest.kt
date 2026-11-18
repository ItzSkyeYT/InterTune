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
import com.dd3boh.outertune.ui.player.PlayerPin.LIKE
import com.dd3boh.outertune.ui.player.PlayerPin.LYRICS
import com.dd3boh.outertune.ui.player.PlayerPin.SHARE
import com.dd3boh.outertune.ui.player.PlayerPin.TIMER
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * In a room, the room's button moves no other button: his rule of 15 Nov 2026, after it joined the
 * player's top row between the heart and the menu and pushed the timer and the heart left. It goes at
 * the free end of its row, behind ROOM_AT_FREE_END until he has seen Connected's row hang off centre in
 * a room (the look he turned down for the queue button on 11 Nov 2026); without it, as in a release, the
 * room's button is where next-013 has it. Partly read from the source: what Compose draws where cannot
 * be looked at on the JVM.
 */
class RoomButtonStaysTest {
    private val folder = File("src/main/java/com/dd3boh/outertune")
    private val player = File(folder, "ui/player/Player.kt").readText()
    private val classicRow = player.substringAfter("val classicButtons: @Composable RowScope.() -> Unit = {").substringBefore("\n            }\n")
    private val connectedRow = player.substringAfter("val quickActions").substringBefore("val controlsContent")

    /** Classic's pins beside a small player's cover: none, one, the default two, and two others. */
    private val pinSets = listOf(emptyList(), listOf(LIKE), listOf(TIMER, LIKE), listOf(SHARE, LYRICS))

    private fun assertInOrder(text: String, vararg parts: String) {
        val at = parts.map { text.indexOf(it) }
        for ((i, part) in parts.withIndex()) assertTrue("missing: $part", at[i] >= 0)
        for (i in 1 until parts.size) assertTrue("${parts[i - 1]} comes before ${parts[i]}", at[i - 1] < at[i])
    }

    /** The side columns as next-013's code made them: the room counted in, right before the menu. */
    private fun next013Columns(pins: List<PlayerPin>, inRoom: Boolean): Pair<List<SideCircle>, List<SideCircle>> {
        val count = pins.size + 2 + (if (inRoom) 1 else 0)
        val onLeft = (count + 1) / 2
        val circles = (0 until count).map { i ->
            when {
                i == 0 -> SideCircle.Queue
                i == count - 1 -> SideCircle.Menu
                inRoom && i == count - 2 -> SideCircle.Room
                else -> SideCircle.Pin(pins[i - 1])
            }
        }
        return circles.take(onLeft) to circles.drop(onLeft)
    }

    @Test
    fun `held back, a release keeps the room's button where next-013 has it`() {
        assertTrue("val ROOM_AT_FREE_END = BuildConfig.PREVIEW_FEATURES" in File(folder, "constants/Unreleased.kt").readText())
        assertTrue("val roomAtFreeEnd = inRoom && Unreleased.ROOM_AT_FREE_END" in player)
        assertTrue("val roomBeforeMenu = inRoom && !Unreleased.ROOM_AT_FREE_END" in player)
        // Classic's circles: the queue, the pins, the room and the menu, as on next-013.
        assertInOrder(
            classicRow,
            "if (queueLayout.queueButton) {",
            "classicPins(queueCircle = queueLayout.queueButton).forEach { pin ->",
            "if (roomBeforeMenu) {\n                    roomCircle()",
            "menuCircle()",
        )
        // Connected's row: the room's segment right before the menu with next-013's shapes, centred with the others.
        assertInOrder(
            connectedRow,
            "if (queueLayout.queueButton) queueSegment()",
            "rowPins.forEachIndexed",
            "if (roomBeforeMenu) roomSegment(rowPins.isEmpty() && !queueLayout.queueButton)",
            "contentDescription = stringResource(R.string.options)",
        )
        assertTrue("shape = connectedShape(first = !roomAtFreeEnd, last = false)" in connectedRow)
        assertTrue("connectedShape(first = i == 0 && !queueLayout.queueButton && !roomAtFreeEnd, last = false)" in connectedRow)
        assertTrue("connectedShape(first = rowPins.isEmpty() && !inRoom && !queueLayout.queueButton, last = true)" in connectedRow)
        // Hung only with the flag: off, CentredRow lays the row out as next-013 did.
        assertTrue("val roomHangs = roomAtFreeEnd && !compactPlayer" in connectedRow)
        assertTrue("hangingFirst = roomHangs," in connectedRow)
        assertEquals("hangingFirst is given in one place", 1, Regex("""hangingFirst = """).findAll(player).count())
        // One circle and one segment, each drawn in the two places the flag chooses between.
        assertEquals(2, Regex("""roomCircle\(\)""").findAll(classicRow).count())
        assertEquals(1, Regex("""R\.string\.together_in_room""").findAll(connectedRow).count())
        assertEquals(2, Regex("""roomSegment\(""").findAll(connectedRow).count())
    }

    @Test
    fun `held back, a small player's columns are next-013's`() {
        assertTrue("classicSideColumns(pinned, inRoom, roomAtFreeEnd = Unreleased.ROOM_AT_FREE_END)" in player)
        for (pins in pinSets) for (inRoom in listOf(false, true)) {
            assertEquals("$pins, in a room $inRoom", next013Columns(pins, inRoom), classicSideColumns(pins, inRoom, roomAtFreeEnd = false))
        }
    }

    @Test
    fun `with the flag, a room's button goes first in each row, at its free end`() {
        assertInOrder(
            classicRow,
            "if (roomAtFreeEnd) {\n                    roomCircle()",
            "if (queueLayout.queueButton) {",
            "classicPins(queueCircle = queueLayout.queueButton)",
            "menuCircle()",
        )
        assertInOrder(connectedRow, "if (roomAtFreeEnd) roomSegment(true)", "if (queueLayout.queueButton) queueSegment()", "rowPins.forEachIndexed")
    }

    @Test
    fun `with the flag, no circle beside a small player's cover changes side when a room starts`() {
        for (pins in pinSets) {
            val (left, right) = classicSideColumns(pins, inRoom = false, roomAtFreeEnd = true)
            assertEquals("no room, no flag to read", next013Columns(pins, inRoom = false), left to right)
            val (roomLeft, roomRight) = classicSideColumns(pins, inRoom = true, roomAtFreeEnd = true)
            assertEquals("$pins", left, roomLeft - SideCircle.Room)
            assertEquals("$pins", right, roomRight - SideCircle.Room)
            assertEquals("$pins", 1, (roomLeft + roomRight).count { it == SideCircle.Room })
            assertTrue("$pins: on top of its column", roomLeft.first() == SideCircle.Room || roomRight.first() == SideCircle.Room)
        }
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
