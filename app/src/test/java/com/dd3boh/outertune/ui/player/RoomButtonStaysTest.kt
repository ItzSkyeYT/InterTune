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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * In a room, the room's button moves no other button: his rule of 15 Nov 2026, after it joined the
 * player's top row between the heart and the menu and pushed the timer and the heart left. It goes at
 * the free end of its row, behind ROOM_AT_FREE_END until he has seen Connected's row hang off centre in
 * a room (the look he turned down for the queue button on 11 Nov 2026); without it, as in a release, the
 * room's button is where next-013 has it. In Connected's normal player it sits by the title instead, at the
 * free end of the pair, in every build (his pick of 19 Nov 2026): a full row on a 360 dp phone had no room for
 * it. Partly read from the source: what Compose draws where cannot be looked at on the JVM.
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
    fun `the room's button at the row's free end in every build since his pick of 20 Nov, and the old place still drawn without the flag`() {
        assertTrue("val ROOM_AT_FREE_END = true" in File(folder, "constants/Unreleased.kt").readText())
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
        // Connected's row holds the room's segment only in a small player (roomInRow): right before the menu with
        // next-013's shapes, centred with the others.
        assertInOrder(
            connectedRow,
            "if (queueLayout.queueButton) queueSegment()",
            "rowPins.forEachIndexed",
            "if (rowRoomBeforeMenu) roomSegment(rowPins.isEmpty() && !queueLayout.queueButton)",
            "contentDescription = stringResource(R.string.options)",
        )
        assertTrue("shape = connectedShape(first = !rowRoomFirst, last = false)" in connectedRow)
        assertTrue("connectedShape(first = i == 0 && !queueLayout.queueButton && !rowRoomFirst, last = false)" in connectedRow)
        assertTrue("connectedShape(first = rowPins.isEmpty() && !roomInRow && !queueLayout.queueButton, last = true)" in connectedRow)
        // The row never hangs the room any more: the normal player has it by the title, a small player counts it in.
        assertEquals("hangingFirst is asked for nowhere", 0, Regex("""hangingFirst = """).findAll(player).count())
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
        assertInOrder(connectedRow, "if (rowRoomFirst) roomSegment(true)", "if (queueLayout.queueButton) queueSegment()", "rowPins.forEachIndexed")
    }

    @Test
    fun `in Connected the room's button is a circle of its own by the title, and the row under the controls never takes it`() {
        // His pick of 19 Nov 2026, in every build; a small player keeps it in its row, counted in with the others. A
        // circle of its own beside the pair, sliding out from under it (Ti-Die, his yes of 20 Nov 2026). A long press
        // says its name as on every button (his word of 21 Nov 2026).
        assertTrue("val roomByTitle = inRoom && !compactPlayer" in player)
        val pair = player.substringAfter("val connectedButtons: @Composable RowScope.() -> Unit = {").substringBefore("val actionButtons")
        assertInOrder(
            pair,
            "AnimatedVisibility(\n                        visible = roomCircleShown && roomByTitle,",
            "enter = expandHorizontally(expandFrom = Alignment.End) + slideInHorizontally { it },",
            "exit = shrinkHorizontally(shrinkTowards = Alignment.End) + slideOutHorizontally { it },",
            "contentDescription = stringResource(R.string.together_in_room)",
            "shape = CircleShape,\n                                circle = true,",
            "onClick = { showRoom = true },",
            "if (titlePins.isNotEmpty()) Spacer(Modifier.width(RoomCircleGap))",
            "titlePins.forEachIndexed",
        )
        // Seen arriving: the player open and no sheet over it, then kept, a room coming back with a new service too. Held
        // outside the full view, which a collapsed player does not draw, so a room started from Home slides in as it opens.
        val arrival = player.substringAfter("var roomCircleShown by rememberSaveable { mutableStateOf(false) }").substringBefore("\n    }\n")
        assertTrue(arrival, "LaunchedEffect(circleRoom != null, state.isExpanded, windowFocused, circleRoomComing) {" in arrival)
        assertTrue(arrival, "circleRoom == null && (circleRoomComing || !playerConnection.isAlive) -> Unit" in arrival)
        assertTrue(arrival, "!roomCircleShown && state.isExpanded && windowFocused -> {" in arrival)
        assertTrue(player.indexOf("var roomCircleShown") in 0 until player.indexOf("val connectedButtons"))
        // The pair is the same in a room and out of one: the room takes nothing of it.
        assertTrue("titleSegment(pin, connectedShape(first = i == 0, last = i == titlePins.lastIndex))" in pair)
        val segment = player.substringAfter("private fun PlayerActionSegment(").substringBefore("\n}")
        assertTrue(segment, "OptionalLongPressLabel(contentDescription) {" in segment)
        assertFalse("onLongClick" in segment)
        assertTrue(segment, ".then(if (circle) Modifier.size(40.dp) else Modifier.height(40.dp).widthIn(min = width))" in segment)
        assertTrue("val roomInRow = inRoom && !roomByTitle" in connectedRow)
        assertTrue("val rowRoomFirst = roomAtFreeEnd && roomInRow" in connectedRow)
        assertTrue("val rowRoomBeforeMenu = roomBeforeMenu && roomInRow" in connectedRow)
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

    @Test
    fun `with the flag, a room's circle goes on top of the shorter column`() {
        // One pin: the queue and the pin on the left, the menu alone on the right, so the room joins the menu.
        // Two and two fit the 88dp the columns are drawn from, where three beside one squashed a circle.
        assertEquals(
            listOf(SideCircle.Queue, SideCircle.Pin(LIKE)) to listOf(SideCircle.Room, SideCircle.Menu),
            classicSideColumns(listOf(LIKE), inRoom = true, roomAtFreeEnd = true),
        )
        // As long as each other, the left one, above the queue.
        assertEquals(
            listOf(SideCircle.Room, SideCircle.Queue, SideCircle.Pin(TIMER)) to listOf(SideCircle.Pin(LIKE), SideCircle.Menu),
            classicSideColumns(listOf(TIMER, LIKE), inRoom = true, roomAtFreeEnd = true),
        )
        assertEquals(
            listOf(SideCircle.Room, SideCircle.Queue) to listOf(SideCircle.Menu),
            classicSideColumns(emptyList(), inRoom = true, roomAtFreeEnd = true),
        )
        for (pins in pinSets.plusElement(listOf(SHARE, TIMER, LIKE))) {
            val (left, right) = classicSideColumns(pins, inRoom = true, roomAtFreeEnd = true)
            assertTrue("$pins: ${left.size} and ${right.size}", abs(left.size - right.size) <= 1)
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

    // The widths: the others are fitted without the room's segment, and it hangs at an icon's width
    // before it gives up. Numbers at 3x, a phone 360dp wide: 888px between its gutters, and the hung
    // segment may start 72px into the left one (the gutter less the 8dp margin).

    private val at3x = Density(3f)
    private val phone360 = with(at3x) { (360.dp - 32.dp * 2).roundToPx() }

    @Test
    fun `where its own width fits, the room hangs at it`() = with(at3x) {
        // Lyrics, the timer and the menu, the row as it comes: the others start at 174px, the room at -12px.
        val three = IntArray(3) { 56.dp.roundToPx() }
        val fitted = quickRowWidths(intArrayOf(56.dp.roundToPx()) + three, 6.dp, phone360, 32.dp, true)
        assertTrue(fitted.hung)
        assertArrayEquals(IntArray(4) { 56.dp.roundToPx() }, fitted.widths)
    }

    @Test
    fun `on a 360dp phone with four others the room hangs at an icon's width and moves none of them`() = with(at3x) {
        // The others start at 81px. At its own 56dp the room would start at -105px, past the -72px it may
        // reach, and the row was centred with it in, the others moved 31dp. At 42dp it starts at -63px.
        val others = IntArray(4) { 56.dp.roundToPx() }
        val fitted = quickRowWidths(intArrayOf(56.dp.roundToPx()) + others, 6.dp, phone360, 32.dp, true)
        assertTrue(fitted.hung)
        assertEquals(QuickActionIconOnlyWidth.roundToPx(), fitted.widths[0])
        assertArrayEquals("the others keep their widths", others, fitted.widths.copyOfRange(1, fitted.widths.size))
        val alone = quickRowPositions(others, 6.dp, phone360, LayoutDirection.Ltr)
        for (direction in LayoutDirection.entries) {
            val hung = quickRowPositionsHanging(fitted.widths, 6.dp, phone360, 32.dp, direction)!!
            assertArrayEquals("and their places, $direction", quickRowPositions(others, 6.dp, phone360, direction), hung.copyOfRange(1, hung.size))
        }
        assertEquals(-63, quickRowPositionsHanging(fitted.widths, 6.dp, phone360, 32.dp, LayoutDirection.Ltr)!![0])
        assertEquals(81, alone[0])
    }

    @Test
    fun `a sleep timer over an hour leaves the others where they are, the room at an icon's width`() = with(at3x) {
        // "1:00:00" makes the timer's segment about 98dp: the room at 56dp would start 1dp past its reach,
        // and the row was centred with it in, then jumped back at 59:59. Now only the room is narrower.
        val others = intArrayOf(56.dp.roundToPx(), 98.dp.roundToPx(), 56.dp.roundToPx())
        val fitted = quickRowWidths(intArrayOf(56.dp.roundToPx()) + others, 6.dp, phone360, 32.dp, true)
        assertTrue(fitted.hung)
        assertEquals(QuickActionIconOnlyWidth.roundToPx(), fitted.widths[0])
        assertArrayEquals(others, fitted.widths.copyOfRange(1, fitted.widths.size))
    }

    @Test
    fun `a full row on a 360dp phone has no room for it and is centred with it in, as next-013 does`() = with(at3x) {
        // The queue, three pins and the menu: even at 42dp the room would start at -156px. What this row
        // should do is his word (into the margin, or a place by the title); until then, as a release does.
        val natural = IntArray(6) { 56.dp.roundToPx() }
        val fitted = quickRowWidths(natural, 6.dp, phone360, 32.dp, true)
        assertFalse(fitted.hung)
        assertArrayEquals(fitQuickActionWidths(natural, 6.dp.roundToPx(), quickRowRoom(phone360, 32.dp), QuickActionIconOnlyWidth.roundToPx()), fitted.widths)
    }

    @Test
    fun `held back, Connected's row gets next-013's widths`() {
        // Without ROOM_AT_FREE_END nothing hangs, and the widths are what CentredRow gave on next-013.
        for (scale in listOf(2f, 2.75f, 3f)) with(Density(scale)) {
            for (screen in listOf(320.dp, 360.dp, 393.dp, 411.dp, 600.dp)) for (count in 1..6) for (time in listOf(false, true)) {
                val natural = IntArray(count) { if (time && it == count - 2) 88.dp.roundToPx() else 56.dp.roundToPx() }
                val width = (screen - 32.dp * 2).roundToPx()
                val fitted = quickRowWidths(natural, 6.dp, width, 32.dp, false)
                assertFalse(fitted.hung)
                val next013 = fitQuickActionWidths(natural, 6.dp.roundToPx(), quickRowRoom(width, 32.dp), QuickActionIconOnlyWidth.roundToPx())
                assertArrayEquals("$scale, $screen, $count, time $time", next013, fitted.widths)
            }
        }
    }
}
