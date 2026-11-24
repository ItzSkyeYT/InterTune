/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.player.QueueHeld.Edge
import com.dd3boh.outertune.ui.player.QueueHeld.Laid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song playing is held at the edge of a long queue once the list is scrolled away from it (#81,
 * his pick of 5 Nov 2026): at the top under the status bar, or at the bottom over the bar of
 * controls. Numbers from the Pixel 5: rows 176 px tall, 1,764 px of list in clear sight above the
 * glass panel.
 */
class QueueHeldRowTest {
    private val row = 176
    private val bottom = 1764

    /** [count] rows from the [first]th, the first of them starting at [offset]. */
    private fun laid(first: Int, offset: Int, count: Int = 12) = List(count) { Laid(first + it, offset + it * row, row) }

    /** The list with the [playing]th song's row starting at [at], twenty rows on either side of it. */
    private fun edgeWithRowAt(at: Int, playing: Int = 30, top: Int = 0, end: Int = bottom): Edge =
        QueueHeld.edge(laid(playing - 20, at - 20 * row, count = 41), playing, top, end)

    @Test
    fun `a row in full sight is not held`() {
        assertEquals(Edge.NONE, edgeWithRowAt(3 * row))
        assertEquals(Edge.NONE, edgeWithRowAt(500))
    }

    @Test
    fun `a row at rest right against an edge is in sight`() {
        assertEquals(Edge.NONE, edgeWithRowAt(0))
        assertEquals(Edge.NONE, edgeWithRowAt(bottom - row))
    }

    @Test
    fun `a row cut by the top is held there, by a pixel, by half or all but a pixel`() {
        assertEquals(Edge.TOP, edgeWithRowAt(-1))
        assertEquals(Edge.TOP, edgeWithRowAt(-row / 2))
        assertEquals(Edge.TOP, edgeWithRowAt(-row + 1))
    }

    @Test
    fun `a row gone up out of the list is held at the top`() {
        assertEquals(Edge.TOP, QueueHeld.edge(laid(20, -40), playing = 15, top = 0, bottom = bottom))
        assertEquals(Edge.TOP, QueueHeld.edge(laid(16, 0), playing = 15, top = 0, bottom = bottom))
    }

    @Test
    fun `a row going under the bar is held at the bottom, and one gone down out of the list too`() {
        assertEquals(Edge.BOTTOM, edgeWithRowAt(bottom - row + 1))
        assertEquals(Edge.BOTTOM, edgeWithRowAt(bottom - row / 2))
        // All of it under the glass, still laid out there.
        assertEquals(Edge.BOTTOM, edgeWithRowAt(bottom + 20))
        assertEquals(Edge.BOTTOM, QueueHeld.edge(laid(0, 0), playing = 40, top = 0, bottom = bottom))
    }

    @Test
    fun `nothing jumps as the row crosses an edge, and it is never shown twice`() {
        // Every place the row can be, a pixel at a time, from well above the list to well below it.
        val seen = mutableListOf<Edge>()
        for (at in -3 * row..bottom + 3 * row) {
            val edge = edgeWithRowAt(at)
            if (seen.lastOrNull() != edge) seen += edge
            val shownFrom = maxOf(at, 0)
            val shownTo = minOf(at + row, bottom)
            when (edge) {
                // What is left of the row in sight is all under the copy held at the top...
                Edge.TOP -> assertTrue("at $at", shownTo <= 0 + row)
                // ...or under the copy held at the bottom...
                Edge.BOTTOM -> assertTrue("at $at", shownFrom >= bottom - row)
                // ...and with no copy, the whole row is in sight.
                Edge.NONE -> assertTrue("at $at", at >= 0 && at + row <= bottom)
            }
        }
        // Held, then not, then held at the other edge: one change at each edge, no flicker between.
        assertEquals(listOf(Edge.TOP, Edge.NONE, Edge.BOTTOM), seen)
    }

    @Test
    fun `nothing is held in an empty list, before the rows are laid out, or with no song playing`() {
        assertEquals(Edge.NONE, QueueHeld.edge(emptyList(), playing = 15, top = 0, bottom = bottom))
        assertEquals(Edge.NONE, QueueHeld.edge(emptyList(), playing = -1, top = 0, bottom = 0))
        assertEquals(Edge.NONE, QueueHeld.edge(laid(0, -40), playing = -1, top = 0, bottom = bottom))
    }

    @Test
    fun `a list too short to give up a row holds nothing`() {
        val short = row * QueueHeld.MIN_ROWS - 1
        assertEquals(Edge.NONE, edgeWithRowAt(-row / 2, end = short))
        assertEquals(Edge.NONE, QueueHeld.edge(laid(20, 0, count = 3), playing = 15, top = 0, bottom = short))
        assertEquals(Edge.TOP, edgeWithRowAt(-row / 2, end = short + 1))
    }

    @Test
    fun `nothing is held over a search, a selection, a dragged row or another queue`() {
        assertTrue(QueueHeld.holds(searching = false, selecting = false, detached = false, dragging = false, placed = true))
        assertFalse(QueueHeld.holds(searching = true, selecting = false, detached = false, dragging = false, placed = true))
        assertFalse(QueueHeld.holds(searching = false, selecting = true, detached = false, dragging = false, placed = true))
        assertFalse(QueueHeld.holds(searching = false, selecting = false, detached = true, dragging = false, placed = true))
        assertFalse(QueueHeld.holds(searching = false, selecting = false, detached = false, dragging = true, placed = true))
    }

    @Test
    fun `nothing is held while the queue opens, before it is put at the song playing`() {
        assertFalse(QueueHeld.holds(searching = false, selecting = false, detached = false, dragging = false, placed = false))
        // Put there for the queue playing, by the effect that marks it so once the scroll is done.
        assertTrue(songList.contains("val placed = scrolledFor == \"playing \" + qb.getCurrentQueue()?.id"))
        assertTrue(queue.contains("val showing = \"playing \" + qb.getCurrentQueue()?.id"))
    }

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val held = File("src/main/java/com/dd3boh/outertune/ui/player/QueueHeldRow.kt").readText()
    private val songList = queue.substringAfter("val songList: @Composable ColumnScope.(PaddingValues) -> Unit")
        .substringBefore("val searchBar: @Composable ColumnScope.() -> Unit")

    @Test
    fun `the song list holds the playing song over itself, from what is on show and where`() {
        assertTrue(songList.contains("QueueHeld.holds(isSearching, inSelectMode, detachedHead, reorderableState.isAnyItemDragging, placed)"))
        assertTrue(songList.contains("val heldSong = visibleSongs.getOrNull(currentWindowIndex)"))
        // In the rows' own offsets: the top of the list, and the top of what floats over its foot.
        assertTrue(songList.contains("top = 0,"))
        assertTrue(songList.contains("bottom = info.viewportEndOffset - heldRoomPx,"))
        assertTrue(songList.contains("val heldRoom = maxOf(contentPadding.calculateBottomPadding(), heldBottomRoom)"))
        // Drawn over the list, in the same box, and put where the edge says.
        val box = songList.substringAfter(".movesListFromHeldRow(lazySongsListState)")
        assertTrue(box.indexOf("LazyColumn(") < box.indexOf("QueueHeldRow("))
        assertTrue(box.contains("Modifier.align(Alignment.TopCenter)"))
        assertTrue(box.contains("Modifier.align(Alignment.BottomCenter).padding(bottom = heldRoom)"))
    }

    @Test
    fun `a tap puts the list where the queue opens, and the menu is the row's own`() {
        assertTrue(songList.contains("lazySongsListState.scrollToWhenLaidOut(heldAt)"))
        assertTrue(songList.contains("onMenu = { showSongMenu(heldSong) }"))
        assertTrue(songList.contains("onClick = { showSongMenu(window) }"))
        assertEquals("one menu for a song's ⋮, wherever it is", 1, Regex("""PlayerMenu\(""").findAll(queue).count())
    }

    @Test
    fun `the held row is the playing row as a bar in its highlight's colour`() {
        val items = File("src/main/java/com/dd3boh/outertune/ui/component/items/Items.kt").readText()
        // The playing row's highlight, which the bar's colour has to match for the two to read as one.
        assertTrue(items.contains("else MaterialTheme.colorScheme.secondaryContainer"))
        assertTrue(held.contains(".background(MaterialTheme.colorScheme.secondaryContainer)"))
        assertTrue(held.contains("isActive = true,"))
        // And while it is held, the row in the list does not: under the bar at the foot it showed the song twice.
        assertTrue(songList.contains("val playingRow = (index == currentWindowIndex && !detachedHead && heldEdge == QueueHeld.Edge.NONE) || index == detachedPlace"))
        assertTrue(songList.contains("isActive = playingRow,"))
        assertTrue(held.contains("if (top) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore"))
        assertTrue(held.contains("onClickLabel = stringResource(R.string.queue_held_row_label)"))
    }

    @Test
    fun `in the list the playing row is the held row's bar too, edge to edge, so it keeps its shape as it is held`() {
        // Ti-Die, #testers 23 Nov 2026: "it'd look a little cleaner if the current song highlight box was *always* edge
        // to edge, like it is when clamped up or down, since right now it kind of instantly changes shape".
        // Across the whole row, square, in the held bar's colour, under the row's own rounded highlight (which melts into
        // it as it does into the held bar), and in the row, so it slides with a swipe and moves with a drag.
        assertTrue(songList.contains(".then(if (playingRow) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)"))
        // Over the list's room at its sides too, which the held bar covers (a phone on its side): drawn around the row
        // while it is at rest, from the list's own padding, the row's height and not the Mix's line under it.
        assertTrue(held.contains("internal fun Modifier.playingRowSides("))
        assertTrue(songList.contains("Modifier.playingRowSides(\n                            on = playingRow,"))
        assertTrue(songList.contains("start = contentPadding.calculateStartPadding(listDirection),\n                            end = contentPadding.calculateEndPadding(listDirection),"))
        assertTrue(songList.contains("height = ListItemHeight,"))
        val sides = held.substringAfter("internal fun Modifier.playingRowSides(").substringBefore("\n\n")
        assertTrue(sides, "if (swiped()) return@drawBehind" in sides)
        assertTrue(sides, "layoutDirection == LayoutDirection.Ltr" in sides)
        // One colour for all of it: the held bar, the playing row and its sides, and the band over the songs.
        assertTrue(songList.contains("on = playingRow,\n                            color = MaterialTheme.colorScheme.secondaryContainer,"))
    }

    @Test
    fun `the status bar's room over the songs wears the song's colour, as the held row and the bar at the foot do`() {
        // Ti-Die, #testers 23 Nov 2026: "maybe make this header (which is great btw) have the same accent color as the
        // song/bottom" (his picture: the strip under the clock, over the song held at the top).
        assertTrue(held.contains("internal fun Modifier.queueHeaderBand(color: Color, height: Dp): Modifier"))
        val band = held.substringAfter("internal fun Modifier.queueHeaderBand(").substringBefore("\n\n")
        assertTrue(band, "if (height <= 0.dp) this" in band)
        // The list's top room, only where it is the top of the sheet: not under the search field, whose results keep the
        // room below it; nothing on a phone on its side or with the queues open, where the list has no top room.
        assertTrue(songList.contains("val headerBand = if (isSearching) Modifier else Modifier.queueHeaderBand(MaterialTheme.colorScheme.secondaryContainer, contentPadding.calculateTopPadding())"))
        // On whichever stands in the status bar's room: the room's line on a guest, else the box of the list.
        assertTrue(songList.contains("modifier = headerBand.padding("))
        assertTrue(songList.contains(".then(if (roomLine) Modifier else headerBand)\n                .padding(top = if (roomLine) 0.dp else contentPadding.calculateTopPadding())"))
    }

    @Test
    fun `a drag on the held row moves the list without joining the scrolls handed up to the sheet`() {
        val drag = held.substringAfter("internal fun Modifier.movesListFromHeldRow(list: LazyListState): Modifier")
        assertTrue(drag.contains("draggable("))
        assertFalse("a scrollable would take part in the sheet's nested scroll", drag.contains(".scrollable("))
        // The sheet's link stays on the list itself.
        assertTrue(songList.contains("modifier = if (songsScroll != null) Modifier.nestedScroll(songsScroll) else Modifier"))
    }
}
