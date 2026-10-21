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
import kotlin.math.roundToInt

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

    /** Rows of [sizesDp], laid out as Compose would lay them out at [pos] px scrolled. */
    private class FakeList(
        sizesDp: List<Float>,
        density: Float = 2.75f,
        topPaddingDp: Float = 94f,
        bottomPaddingDp: Float = 168f,
        viewportDp: Float = 851f,
        /** Rows that are sticky headers, pinned at the top while their section scrolls under them. */
        private val headers: List<Int> = emptyList(),
    ) {
        val sizes = sizesDp.map { (it * density).roundToInt() }
        private val tops = IntArray(sizes.size + 1).also { t -> sizes.forEachIndexed { i, s -> t[i + 1] = t[i] + s } }
        private val topPadding = (topPaddingDp * density).roundToInt()
        private val bottomPadding = (bottomPaddingDp * density).roundToInt()

        /** The content area between the paddings, which is also the scrollbar's track. */
        val contentPx = (viewportDp * density).roundToInt() - topPadding - bottomPadding
        val maxScroll = (tops.last() - contentPx).toDouble()
        var pos = 0.0

        fun scrollBy(px: Float) {
            pos = (pos + px).coerceIn(0.0, maxScroll)
        }

        fun scrollTo(index: Int, offset: Int) {
            pos = (tops[index.coerceIn(0, sizes.lastIndex)] + offset).toDouble().coerceIn(0.0, maxScroll)
        }

        fun snapshot(): ScrollbarSnapshot {
            val p = pos.toInt()
            val found = tops.binarySearch(p, 0, sizes.size)
            val first = if (found >= 0) found else -found - 2
            // Rows above the first one stay laid out while any of them shows through the top padding.
            var from = first
            while (from > 0 && tops[from] - p > -topPadding) from--
            var to = first
            while (to < sizes.lastIndex && tops[to + 1] - p < contentPx + bottomPadding) to++
            val rows = (from..to).map { ScrollbarRow(it, tops[it] - p, sizes[it]) }.toMutableList()
            val header = headers.lastOrNull { it <= first }
            if (header != null && header < first) {
                // The section's header is pinned at the top, pushed up by the next one as it comes.
                rows.removeAll { it.index == header }
                val next = headers.firstOrNull { it > header }
                val offset = if (next == null) 0 else minOf(0, tops[next] - p - sizes[header])
                rows.add(0, ScrollbarRow(header, offset, sizes[header]))
            }
            return ScrollbarSnapshot(first, p - tops[first], rows, sizes.size, contentPx)
        }
    }

    private class Step(val px: Double, val atEnd: Boolean)

    /** Library > Songs: the filter row and the header row, then the songs. */
    private fun songs(count: Int) = FakeList(listOf(56f, 48f) + List(count) { 64f })

    /** History: a day's 48 dp sticky heading every 8 songs, and no top padding in the list. */
    private fun history(days: Int): FakeList {
        val sizes = (0 until days).flatMap { listOf(48f) + List(8) { 64f } }
        return FakeList(sizes, topPaddingDp = 0f, viewportDp = 851f - 94f, headers = (0 until days).map { it * 9 })
    }

    private fun geometry(list: FakeList) = requireNotNull(ScrollbarGeometry.of(list.snapshot())) { "nothing to scroll" }

    /** Puts the list where the thumb at rest shows [top]. */
    private fun settle(list: FakeList, top: Float) {
        repeat(5) {
            val g = geometry(list)
            list.pos = (top / g.maxThumbTop * g.maxScrollPx).toDouble().coerceIn(0.0, list.maxScroll)
        }
    }

    /** Grabs the thumb where it shows [startTop] and moves the finger [events] times by [fingerPx]. */
    private fun drag(list: FakeList, startTop: Float, events: Int = 400, fingerPx: Float = 0.25f): Pair<ScrollbarDrag, List<Step>> {
        settle(list, startTop)
        val snapshot = list.snapshot()
        val g = requireNotNull(ScrollbarGeometry.of(snapshot))
        val drag = ScrollbarDrag(g, g.thumbTop(snapshot), list.contentPx.toFloat())
        val steps = ArrayList<Step>()
        repeat(events) {
            val before = list.pos
            if (!drag.moveBy(fingerPx)) {
                steps += Step(0.0, atEnd = true)
                return@repeat
            }
            val top = drag.top
            val move = drag.moveFor(top)
            when (move) {
                is ScrollbarMove.By -> list.scrollBy(move.px)
                is ScrollbarMove.To -> list.scrollTo(move.index, move.offsetPx)
            }
            drag.moved(top)
            list.snapshot() // the list lays itself out again after every step, as on screen
            val clamped = list.pos <= 0.0 || list.pos >= list.maxScroll
            steps += Step(list.pos - before, atEnd = move is ScrollbarMove.To || clamped)
        }
        return drag to steps
    }

    private fun assertSteady(what: String, steps: List<Step>) {
        val back = steps.filter { it.px < -0.5 }
        assertTrue("$what: ${back.size} steps went back, the worst ${back.minOfOrNull { it.px }} px", back.isEmpty())
        val inside = steps.filter { !it.atEnd }
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
    fun `History, with mixed rows and sticky headings, follows the finger too`() {
        for (start in listOf(0.1f, 0.5f, 0.85f)) {
            assertSteady("History from $start", drag(history(60), start).second)
        }
    }

    @Test
    fun `rows sliding under the top padding do not change how many rows the list shows`() {
        // At 1 px to the dp: 64 px rows, a 94 px top padding, 589 px of content, so 9.20 rows.
        val list = FakeList(List(400) { 64f }, density = 1f)
        for (offset in 0..63) {
            list.pos = 200.0 * 64 + offset
            val snapshot = list.snapshot()
            assertEquals(200, snapshot.firstIndex)
            // The layout itself flips: two rows show through the padding until the first has
            // scrolled 30 px, then one. Counting those rows is what shook the old scrollbar.
            val throughPadding = snapshot.rows.count { it.index < snapshot.firstIndex }
            assertEquals("rows in the top padding at offset $offset", if (offset < 30) 2 else 1, throughPadding)
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
    fun `the ends are the first row and the last`() {
        val g = geometry(songs(400))
        assertEquals(ScrollbarMove.To(0, 0), g.move(0f, 5000f))
        assertEquals(ScrollbarMove.To(g.lastIndex, 0), g.move(g.maxThumbTop, 5000f))
        assertEquals(0f, g.targetPx(0f), 0.001f)
        assertEquals(g.maxScrollPx, g.targetPx(g.maxThumbTop), g.maxScrollPx * 1e-5f)
    }

    @Test
    fun `the finger maps straight to a position`() {
        val g = geometry(songs(2000))
        val tops = (0..100).map { it / 100f * g.maxThumbTop }
        val targets = tops.map { g.targetPx(it) }
        val step = targets[1] - targets[0]
        assertTrue(step > 0f)
        targets.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, step * 1e-3f) }
    }

    @Test
    fun `a long jump is one row and an offset inside it, in order`() {
        val g = geometry(songs(2000))
        val target = g.targetPx(0.5f)
        val move = g.move(0.5f, 0f)
        assertTrue("a jump of ${target}px was scrolled", move is ScrollbarMove.To)
        move as ScrollbarMove.To
        assertTrue(move.offsetPx >= 0 && move.offsetPx < g.rowPx)
        assertEquals(target, move.index * g.rowPx + move.offsetPx, g.rowPx)

        val jumps = (1..999).map { g.jumpFor(g.targetPx(it / 1000f * g.maxThumbTop)) }
        jumps.zipWithNext().forEach { (a, b) ->
            assertTrue("$a then $b", b.index > a.index || (b.index == a.index && b.offsetPx >= a.offsetPx))
        }
    }

    @Test
    fun `let go, the thumb stays where the finger left it`() {
        val list = FakeList(List(400) { 64f })
        val (drag, _) = drag(list, 0.5f)
        val snapshot = list.snapshot()
        val after = requireNotNull(ScrollbarGeometry.of(snapshot)).thumbTop(snapshot)
        val apartPx = abs(after - drag.top) * list.contentPx
        assertTrue("the thumb moved ${"%.2f".format(apartPx)} px when let go", apartPx <= 1f)
    }

    @Test
    fun `the thumb keeps its size during a drag and is never shorter than a tenth`() {
        val list = songs(2000)
        val before = geometry(list.also { settle(it, 0.3f) })
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
    fun `a list with nothing to scroll has no thumb`() {
        assertNull(ScrollbarGeometry.of(FakeList(List(5) { 64f }).snapshot()))
        assertNull(ScrollbarGeometry.of(ScrollbarSnapshot(0, 0, emptyList(), 0, 589)))
    }
}
