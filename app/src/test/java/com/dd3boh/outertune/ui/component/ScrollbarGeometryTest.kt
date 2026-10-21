/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * Issue #34: dragging the scrollbar of a long list made the rows shake while the thumb held still.
 * The old scrollbar worked the list's position out again from its layout on every touch event,
 * and on our screens that count flips between two values as rows slide under the top padding, so
 * each event sent the list back and forth. These run the new arithmetic against a list laid out
 * the way Compose 1.9 lays one out, rows showing through the top padding included, with a Pixel
 * 5's measures: 851 dp tall, 94 dp of top padding (status bar and app bar), 168 dp at the bottom
 * (player and navigation bar), 2.75 px to the dp. ScrollbarFollowsFingerTest reads the source.
 */
class ScrollbarGeometryTest {

    /** Library > Songs: the filter row and the header row, then the songs. */
    private fun songs(count: Int) = FakeList(listOf(56f, 48f) + List(count) { 64f })

    /** History: a day's 48 dp sticky heading every 8 songs, and no top padding in the list. */
    private fun history(days: Int): FakeList {
        val sizes = (0 until days).flatMap { listOf(48f) + List(8) { 64f } }
        return FakeList(sizes, topPaddingDp = 0f, viewportDp = 851f - 94f, headers = (0 until days).map { it * 9 })
    }

    private fun geometry(list: FakeScrollable) = requireNotNull(ScrollbarGeometry.of(list.snapshot())) { "nothing to scroll" }

    /** Puts the list [top] of the way down, on a whole pixel as Compose keeps it. */
    private fun settle(list: FakeList, top: Float) {
        list.pos = floor(top * list.maxScroll).coerceIn(0.0, list.maxScroll)
    }

    private class Step(val px: Double, val inSongs: Boolean)

    /** Grabs the thumb with the list [startTop] of the way down and moves the finger [events] times by [fingerPx]. */
    private fun drag(list: FakeList, startTop: Float, events: Int = 400, fingerPx: Float = 0.25f, firstSong: Int = 2): Pair<Press, List<Step>> {
        settle(list, startTop)
        val press = Press(list)
        val steps = ArrayList<Step>()
        repeat(events) {
            val before = list.pos
            if (!press.finger(fingerPx)) return@repeat
            val clamped = list.pos <= 0.0 || list.pos >= list.maxScroll || press.atBottom
            // Rows of other sizes go by at their own speed, so only steps among songs are compared.
            val songs = !clamped && list.snapshot().firstIndex >= firstSong && before >= list.topOf(firstSong)
            steps += Step(list.pos - before, songs)
        }
        return press to steps
    }

    private fun assertSteady(what: String, steps: List<Step>) {
        val back = steps.filter { it.px < -0.5 }
        assertTrue("$what: ${back.size} steps went back, the worst ${back.minOfOrNull { it.px }} px", back.isEmpty())
        val inside = steps.filter { it.inSongs }
        assertTrue("$what: the list never moved", inside.isNotEmpty())
        val first = inside.first().px
        assertTrue("$what: the list does not move with the finger", first > 0.0)
        val uneven = inside.filter { abs(it.px - first) > 1.0 }
        assertTrue("$what: ${uneven.size} steps differ from the first (${"%.2f".format(first)} px) by more than 1 px", uneven.isEmpty())
    }

    @Test
    fun `a long list follows a steady finger, never going back`() {
        for (count in listOf(400, 2000)) for (start in listOf(0.1f, 0.5f, 0.85f)) {
            assertSteady("$count songs from $start", drag(songs(count), start).second)
        }
    }

    @Test
    fun `History, with mixed rows and sticky headings, never goes back`() {
        for (start in listOf(0.1f, 0.5f, 0.85f)) {
            val back = drag(history(60), start).second.filter { it.px < -0.5 }
            assertTrue("History from $start: ${back.size} steps went back", back.isEmpty())
        }
    }

    @Test
    fun `rows sliding under the top padding do not change how many rows the list shows`() {
        // 64 dp rows (176 px), a 94 dp top padding (259 px), 1619 px of content, so 9.20 rows.
        val list = FakeList(List(400) { 64f })
        for (offset in 0..175) {
            list.pos = 200.0 * 176 + offset
            val snapshot = list.snapshot()
            assertEquals(200, snapshot.firstIndex)
            // The layout itself flips: two rows show through the padding until the first has
            // scrolled 83 px, then one. Counting those rows is what shook the old scrollbar.
            val throughPadding = snapshot.rows.count { it.index < snapshot.firstIndex }
            assertEquals("rows in the top padding at offset $offset", if (offset < 83) 2 else 1, throughPadding)
            assertTrue(ScrollbarGeometry.contentRows(snapshot).none { it.index < snapshot.firstIndex })
            val visible = requireNotNull(ScrollbarGeometry.of(snapshot)).visibleRows
            assertTrue("$visible rows at offset $offset", abs(visible - 9.20f) < 0.05f)
        }
    }

    @Test
    fun `a pinned sticky header is left out, a header at the top is counted once`() {
        val rows = listOf(ScrollbarRow(150, 0, 48)) + (200..210).map { ScrollbarRow(it, (it - 200) * 64 - 10, 64) }
        val pinned = ScrollbarSnapshot(200, 10, rows, 400, 589)
        assertEquals((200..209).toList(), ScrollbarGeometry.contentRows(pinned).map { it.index })
        assertEquals(64f, requireNotNull(ScrollbarGeometry.of(pinned)).rowPx, 0.001f)

        val heading = listOf(ScrollbarRow(200, 0, 48)) + (201..210).map { ScrollbarRow(it, 48 + (it - 201) * 64, 64) }
        val atTop = ScrollbarSnapshot(200, 0, heading, 400, 589)
        assertEquals(1, ScrollbarGeometry.contentRows(atTop).count { it.index == 200 })
    }

    @Test
    fun `the ends are the first row and the end of the list`() {
        val g = geometry(songs(400))
        assertEquals(ScrollbarMove.To(0, 0f), g.move(0f))
        assertEquals(ScrollbarMove.End, g.move(g.maxThumbTop))
        assertEquals(0f, g.rowsFor(0f), 0.001f)
        assertEquals(g.endRows, g.rowsFor(g.maxThumbTop), g.endRows * 1e-5f)
    }

    @Test
    fun `the finger maps straight to a place in the list`() {
        val g = geometry(songs(2000))
        val tops = (0..100).map { it / 100f * g.maxThumbTop }
        val rows = tops.map { g.rowsFor(it) }
        val step = rows[1] - rows[0]
        assertTrue(step > 0f)
        rows.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, step * 1e-3f) }
    }

    @Test
    fun `every move is a row and a part of it, in order`() {
        val g = geometry(songs(2000))
        val move = g.move(0.5f)
        assertTrue("$move", move is ScrollbarMove.To)
        move as ScrollbarMove.To
        assertTrue(move.fraction >= 0f && move.fraction < 1f)
        assertEquals(g.rowsFor(0.5f), move.index + move.fraction, 1e-3f)

        val moves = (1..999).map { g.move(it / 1000f * g.maxThumbTop) as ScrollbarMove.To }
        moves.zipWithNext().forEach { (a, b) ->
            assertTrue("$a then $b", b.index > a.index || (b.index == a.index && b.fraction >= a.fraction))
        }
        // Inside a row laid out, by its own size, never past it into the next one.
        assertEquals(0, g.offsetPx(0f, 48))
        assertEquals(47, g.offsetPx(0.9999f, 48))
        assertEquals(88, g.offsetPx(0.5f, null))
    }

    @Test
    fun `let go, the thumb stays where the finger left it`() {
        val list = FakeList(List(400) { 64f })
        val (press, _) = drag(list, 0.5f, firstSong = 0)
        val apartPx = abs(restingTop(list) - press.top) * list.contentPx
        assertTrue("the thumb moved ${"%.2f".format(apartPx)} px when let go", apartPx <= 1f)
    }

    @Test
    fun `the thumb keeps its size during a drag and is never shorter than a tenth`() {
        val list = songs(2000)
        settle(list, 0.3f)
        val before = geometry(list)
        assertEquals(ScrollbarGeometry.MIN_THUMB, before.thumbFrac, 0f)
        val drag = ScrollbarDrag(before, 0.3f, list.contentPx.toFloat())
        repeat(200) { drag.moveBy(3f) }
        assertSame(before, drag.geometry)

        for (start in listOf(0f, 0.25f, 0.5f, 0.9f)) {
            settle(list, start)
            assertTrue(geometry(list).thumbFrac >= ScrollbarGeometry.MIN_THUMB)
        }

        // A short list gets a longer thumb: its share of the content.
        val short = FakeList(List(12) { 64f })
        assertEquals(short.contentPx / (12f * short.sizes[0]), geometry(short).thumbFrac, 0.01f)
    }

    @Test
    fun `empty rows between the songs do not take the thumb away`() {
        // A song, then an empty spacer, over and over.
        val rows = (0 until 20).map { ScrollbarRow(200 + it, (it + 1) / 2 * 176, if (it % 2 == 0) 176 else 0) }
        val g = requireNotNull(ScrollbarGeometry.of(ScrollbarSnapshot(200, 0, rows, 400, 1619)))
        assertEquals(176f, g.rowPx, 0f)
    }

    @Test
    fun `a list with nothing to scroll has no thumb`() {
        assertNull(ScrollbarGeometry.of(FakeList(List(5) { 64f }).snapshot()))
        assertNull(ScrollbarGeometry.of(ScrollbarSnapshot(0, 0, emptyList(), 0, 589)))
        // An album with a tall cover and a few songs scrolls a little, and gets a thumb.
        assertTrue(ScrollbarGeometry.of(FakeList(listOf(330f, 48f) + List(6) { 64f }).snapshot()) != null)
    }
}
