/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Issue #34, the lists whose first rows are not the size of the rest: a playlist's header, the filter
 * and sort lines above Library > Songs and the Albums grid, an album's cover. Dragging the thumb of one
 * must never send the list backwards while the finger goes forward, the thumb drawn from the list must
 * stay under the finger, so it does not jump when let go, and the list must reach its end just as the
 * thumb reaches the bottom. The fingers here move the way a real one does, slow stretches and short
 * quick flicks, not the steady quarter pixel of ScrollbarGeometryTest. The fakes are in ScrollbarFakes.kt.
 */
class ScrollbarOddRowsTest {

    /** A playlist: its header, the sort line, then the songs. */
    private fun playlist(songs: Int, headerDp: Float = 260f) = FakeList(listOf(headerDp, 48f) + List(songs) { 64f })

    /** Library > Songs: the filter line and the sort line, then the songs. */
    private fun librarySongs(songs: Int) = FakeList(listOf(56f, 48f) + List(songs) { 64f })

    /** Library > Albums as a grid: the filter and sort lines, then lines of three albums. */
    private fun albumsGrid(lines: Int) = FakeGrid(columns = 3, headersDp = listOf(56f, 48f), items = lines * 3, lineDp = 230f)

    /** An album: the cover and its buttons, the sort line, then the songs. */
    private fun album(songs: Int) = FakeList(listOf(330f, 48f) + List(songs) { 64f })

    /** History: a day's 48 dp sticky heading every 8 songs, and no top padding in the list. */
    private fun history(days: Int) = FakeList(
        (0 until days).flatMap { listOf(48f) + List(8) { 64f } },
        topPaddingDp = 0f, viewportDp = 851f - 94f, headers = (0 until days).map { it * 9 },
    )

    /** [slow] px a touch event for 25 events, then [quick] px for 4, over and over. */
    private fun bursts(slow: Float, quick: Float) = List(12) { List(25) { slow } + List(4) { quick } }.flatten()

    private val fingers = mapOf(
        "3 px" to List(350) { 3f },
        "3 px with 12 px bursts" to bursts(3f, 12f),
        "2 px with 9 px bursts" to bursts(2f, 9f),
        "4 px with 16 px bursts" to bursts(4f, 16f),
        "4 to 16 px" to List(300) { 10f + 6f * sin(it / 7f) },
    )

    private class Run(val steps: List<Double>, val backward: List<Double>, val apartPx: Float, val press: Press)

    /**
     * Grabs the thumb where the list shows it and moves the finger by each of [moves] in turn, until the
     * thumb is against the end of the track. After every event the list lays itself out again, as on screen.
     */
    private fun drag(list: FakeScrollable, moves: List<Float>, after: (Int) -> Unit = {}): Run {
        val press = Press(list)
        val steps = ArrayList<Double>()
        val backward = ArrayList<Double>()
        var apart = 0f
        for ((k, px) in moves.withIndex()) {
            val before = list.pos
            if (!press.finger(px)) break
            val step = list.pos - before
            steps += step
            if (px > 0f && step < -0.5) backward += step
            apart = max(apart, abs(restingTop(list) - press.top) * list.contentPx)
            after(k)
        }
        return Run(steps, backward, apart, press)
    }

    private fun assertFollows(what: String, list: FakeScrollable, moves: List<Float>) {
        val run = drag(list, moves)
        assertTrue("$what: the list never moved", run.steps.any { it > 0.0 })
        assertTrue(
            "$what: ${run.backward.size} steps went back, the worst ${"%.0f".format(-(run.backward.minOrNull() ?: 0.0) / DENSITY)} dp",
            run.backward.isEmpty()
        )
        assertTrue(
            "$what: the thumb drawn from the list got ${"%.1f".format(run.apartPx / DENSITY)} dp from the finger",
            run.apartPx <= 3f * DENSITY
        )
    }

    @Test
    fun `a playlist grabbed at the top follows the finger and never goes back`() {
        for (songs in listOf(1000, 3000)) for (header in listOf(220f, 260f)) for ((how, moves) in fingers) {
            assertFollows("playlist of $songs under a $header dp header, finger $how", playlist(songs, header), moves)
        }
    }

    @Test
    fun `Library Songs follows the finger from the top and from further down`() {
        for (songs in listOf(400, 2000)) for (start in listOf(0.0, 0.1, 0.5, 0.85)) for ((how, moves) in fingers) {
            val list = librarySongs(songs).also { it.pos = start * it.maxScroll }
            assertFollows("$songs songs from ${start * 100}%, finger $how", list, moves)
        }
    }

    @Test
    fun `the Albums grid follows the finger and never goes back`() {
        for (lines in listOf(60, 150)) for (start in listOf(0.0, 0.5)) for ((how, moves) in fingers) {
            val grid = albumsGrid(lines).also { it.lines.pos = start * it.maxScroll }
            assertFollows("Albums grid of $lines lines from ${start * 100}%, finger $how", grid, moves)
        }
    }

    @Test
    fun `History, with its sticky headings, follows the finger`() {
        for (start in listOf(0.0, 0.5)) for ((how, moves) in fingers) {
            val list = history(60).also { it.pos = start * it.maxScroll }
            assertFollows("History from ${start * 100}%, finger $how", list, moves)
        }
    }

    @Test
    fun `a slow drag reaches the end of the list just as the thumb reaches the bottom`() {
        val lists = mapOf(
            "playlist of 1000" to playlist(1000),
            "Library Songs of 400" to librarySongs(400),
            "Albums grid of 150 lines" to albumsGrid(150),
            "History" to history(60),
        )
        for ((what, list) in lists) {
            val rowPx = list.snapshot().rows.map { it.size }.sorted().let { it[it.size / 2] }
            var endAt: Float? = null
            val run = drag(list, List(4000) { 1f }) {
                if (endAt == null && list.pos >= list.maxScroll) endAt = it.toFloat()
            }
            assertTrue("$what: the thumb never reached the bottom", run.press.atBottom)
            assertEquals("$what: the list is not at its end with the thumb at the bottom", list.maxScroll, list.pos, 0.5)
            val reached = (endAt ?: 0f) / (run.steps.size - 1)
            assertTrue("$what: the list reached its end with the thumb ${"%.1f".format(reached * 100)}% of the way down", reached >= 0.99f)
            val usual = run.steps.sorted()[run.steps.size / 2]
            val last = run.steps.last()
            assertTrue(
                "$what: the last pixel of the thumb moved the list ${"%.0f".format(last / DENSITY)} dp",
                last <= max(rowPx.toDouble(), 2 * usual)
            )
        }
    }

    @Test
    fun `at rest, an album's thumb only moves forward and keeps its length`() {
        val lists = mapOf(
            "album of 15" to album(15),
            "album of 40" to album(40),
            "playlist of 300" to playlist(300),
            "Albums grid of 30 lines" to albumsGrid(30),
        )
        for ((what, list) in lists) {
            var last = restingTop(list)
            var worstBack = 0f
            val lengths = ArrayList<Float>()
            var at = 0.0
            while (at < list.maxScroll) {
                at = minOf(at + 3.0, list.maxScroll)
                list.scrollBy(3f)
                val s = list.snapshot()
                val g = requireNotNull(ScrollbarGeometry.of(s))
                val top = g.thumbTop(s)
                worstBack = max(worstBack, (last - top) * list.contentPx)
                last = top
                lengths += g.thumbFrac * list.contentPx
            }
            assertTrue("$what: the thumb went back ${"%.1f".format(worstBack / DENSITY)} dp", worstBack <= 0.5f)
            val change = lengths.max() - lengths.min()
            assertTrue("$what: the thumb's length changed by ${"%.1f".format(change / DENSITY)} dp", change <= 1f)
        }
    }

    @Test
    fun `a scroll from elsewhere during a press is undone by the finger's next move`() {
        val list = librarySongs(2000).also { it.pos = 0.5 * it.maxScroll }
        val run = drag(list, List(60) { 3f }) { k ->
            // The queue bringing the playing song into view, or a second finger on the list.
            if (k == 40) list.jumpTo(list.snapshot().firstIndex + 40, 0)
        }
        assertTrue(
            "the list stayed ${"%.1f".format(run.apartPx / DENSITY)} dp of thumb away from the finger",
            run.apartPx <= 3f * DENSITY
        )
    }

    @Test
    fun `a list that loads more while the thumb is held still reaches its real end at the bottom`() {
        // Online search results, browse pages, online playlists: 100 more rows come in whenever the
        // last 6 are laid out, twice.
        val list = FakeList(listOf(120f) + List(200) { 64f })
        val press = Press(list)
        val backward = ArrayList<Double>()
        var grown = 0
        var endAtBottom: Boolean? = null
        var apartAtBottom = 0f
        while (true) {
            val before = list.pos
            if (!press.finger(3f)) break
            if (list.pos - before < -0.5) backward += list.pos - before
            if (press.atBottom && endAtBottom == null) {
                // Looked at before the list has a chance to grow again.
                endAtBottom = list.pos >= list.maxScroll - 0.5
                apartAtBottom = abs(restingTop(list) - press.top) * list.contentPx
            }
            val lastLaidOut = list.snapshot().rows.maxOf { it.index }
            if (grown < 2 && lastLaidOut >= list.rowCount - 6) {
                list.grow(List(100) { 64f })
                grown++
            }
        }
        assertEquals("the list was not at its end with the thumb at the bottom", true, endAtBottom)
        assertEquals("the list grew", 2, grown)
        assertTrue("${backward.size} steps went back, the worst ${backward.minOrNull()} px", backward.isEmpty())
        assertTrue("let go at the bottom, the thumb jumps ${"%.1f".format(apartAtBottom / DENSITY)} dp", apartAtBottom <= 3f * DENSITY)
    }

    @Test
    fun `a grid's lines are counted, and a line turns into its first item`() {
        for (columns in listOf(1, 2, 3)) {
            val grid = FakeGrid(columns, listOf(56f, 48f), items = 300, lineDp = 230f)
            for (start in listOf(0.0, 0.3, 0.6, 1.0)) {
                grid.lines.pos = start * grid.maxScroll
                val cells = grid.cells()
                val s = grid.snapshot()
                assertEquals("lines with $columns columns", grid.rowCount, s.total)
                assertEquals("the first line with $columns columns from $start", grid.lines.snapshot().firstIndex, s.firstIndex)
                for (line in 0 until grid.rowCount) {
                    assertEquals(
                        "the first item on line $line, with $columns columns, lines ${cells.first().line} to ${cells.last().line} laid out",
                        grid.itemsOn(line).first,
                        ScrollbarGrid.firstItemOf(line, cells, columns, grid.totalItems)
                    )
                }
            }
        }
    }
}
