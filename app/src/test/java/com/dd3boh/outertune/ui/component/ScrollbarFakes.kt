/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Lists and grids for the scrollbar's tests, laid out the way Compose 1.9 lays them out, rows showing
// through the top padding included, with a Pixel 5's measures: 851 dp tall, 94 dp of top padding (status
// bar and app bar), 168 dp at the bottom (player and navigation bar), 2.75 px to the dp.

internal const val DENSITY = 2.75f

/** What the scrollbar drives: a list, or a grid counted in lines. */
internal interface FakeScrollable {
    /** The content area between the paddings, which is also the scrollbar's track. */
    val contentPx: Int

    /** How far the list has scrolled, and how far it can. */
    val pos: Double
    val maxScroll: Double

    /** Rows, or lines of a grid. */
    val rowCount: Int
    fun snapshot(): ScrollbarSnapshot

    /** The size of [row] while it is laid out. */
    fun laidOutSize(row: Int): Int?
    fun scrollBy(px: Float)

    /** Puts [row] at the top of the content area, [offsetPx] into it, as scrollToItem does. */
    fun jumpTo(row: Int, offsetPx: Int)
    fun jumpToEnd()
}

internal class FakeList(
    sizesDp: List<Float>,
    topPaddingDp: Float = 94f,
    bottomPaddingDp: Float = 168f,
    viewportDp: Float = 851f,
    /** Rows that are sticky headers, pinned at the top while their section scrolls under them. */
    private val headers: List<Int> = emptyList(),
) : FakeScrollable {
    val sizes = ArrayList(sizesDp.map { (it * DENSITY).roundToInt() })
    private var tops = tops()
    private val topPadding = (topPaddingDp * DENSITY).roundToInt()
    private val bottomPadding = (bottomPaddingDp * DENSITY).roundToInt()
    override val contentPx = (viewportDp * DENSITY).roundToInt() - topPadding - bottomPadding
    override val maxScroll get() = max(0, tops.last() - contentPx).toDouble()
    override var pos = 0.0
    override val rowCount get() = sizes.size

    private fun tops() = IntArray(sizes.size + 1).also { t -> sizes.forEachIndexed { i, s -> t[i + 1] = t[i] + s } }

    /** More rows at the end, as a list that loads more near its end gets them. */
    fun grow(moreDp: List<Float>) {
        sizes += moreDp.map { (it * DENSITY).roundToInt() }
        tops = tops()
    }

    /** The scroll position that puts [row] at the top of the content area. */
    fun topOf(row: Int) = tops[row].toDouble()

    override fun scrollBy(px: Float) {
        pos = (pos + px).coerceIn(0.0, maxScroll)
    }

    override fun jumpTo(row: Int, offsetPx: Int) {
        pos = (tops[row.coerceIn(0, sizes.lastIndex)] + offsetPx).toDouble().coerceIn(0.0, maxScroll)
    }

    override fun jumpToEnd() = jumpTo(sizes.lastIndex, 0)

    override fun laidOutSize(row: Int) = snapshot().rows.firstOrNull { it.index == row }?.size

    override fun snapshot(): ScrollbarSnapshot {
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

/**
 * A grid of [items] cells in [columns] columns, under full width header items of [headersDp], one to a
 * line, as in Library > Albums. Its lines are a [FakeList].
 */
internal class FakeGrid(val columns: Int, headersDp: List<Float>, items: Int, lineDp: Float) : FakeScrollable {
    private val headers = headersDp.size
    val totalItems = headers + items
    val lines = FakeList(headersDp + List((items + columns - 1) / columns) { lineDp })
    override val contentPx get() = lines.contentPx
    override val pos get() = lines.pos
    override val maxScroll get() = lines.maxScroll
    override val rowCount get() = lines.rowCount

    fun lineOf(item: Int) = if (item < headers) item else headers + (item - headers) / columns
    fun itemsOn(line: Int): IntRange =
        if (line < headers) line..line
        else (headers + (line - headers) * columns).let { it until min(it + columns, totalItems) }

    /** The cells laid out, as LazyGridState.layoutInfo lists them. */
    fun cells(): List<ScrollbarCell> = lines.snapshot().rows.flatMap { row ->
        itemsOn(row.index).map { ScrollbarCell(it, row.index, if (row.index < headers) 0 else (it - headers) % columns, row.offset, row.size) }
    }

    override fun snapshot(): ScrollbarSnapshot {
        val s = lines.snapshot()
        return ScrollbarGrid.snapshot(cells(), itemsOn(s.firstIndex).first, s.firstOffset, totalItems, columns, s.contentEnd, 0)
    }

    override fun laidOutSize(row: Int) = cells().filter { it.line == row }.maxOfOrNull { it.size }

    fun scrollToItem(item: Int, offsetPx: Int) = lines.jumpTo(lineOf(item.coerceIn(0, totalItems - 1)), offsetPx)

    override fun jumpTo(row: Int, offsetPx: Int) = scrollToItem(ScrollbarGrid.firstItemOf(row, cells(), columns, totalItems), offsetPx)
    override fun jumpToEnd() = scrollToItem(totalItems - 1, 0)
    override fun scrollBy(px: Float) = lines.scrollBy(px)
}

/** Where the thumb sits for the list as it is now, with no finger on it. */
internal fun restingTop(list: FakeScrollable): Float {
    val s = list.snapshot()
    return requireNotNull(ScrollbarGeometry.of(s)) { "nothing to scroll" }.thumbTop(s)
}

/**
 * One press on the thumb of [list], grabbed where the list shows it, and driven the way ScrollBar.kt
 * drives the real list. The only place the tests touch the drag's own steps.
 */
internal class Press(private val list: FakeScrollable) {
    val geometry: ScrollbarGeometry
    private val drag: ScrollbarDrag

    init {
        val s = list.snapshot()
        geometry = requireNotNull(ScrollbarGeometry.of(s)) { "nothing to scroll" }
        drag = ScrollbarDrag(geometry, geometry.thumbTop(s), list.contentPx.toFloat())
    }

    /** Where the finger holds the thumb's top, as a part of the track. */
    val top: Float get() = drag.top
    val atBottom: Boolean get() = drag.top >= drag.geometry.maxThumbTop

    /** Moves the finger by [px]; false when the thumb is already against that end. */
    fun finger(px: Float): Boolean {
        if (!drag.moveBy(px)) return false
        drag.listHas(list.rowCount)
        when (val move = drag.moveFor(drag.top)) {
            is ScrollbarMove.To -> list.jumpTo(move.index, drag.geometry.offsetPx(move.fraction, list.laidOutSize(move.index)))
            ScrollbarMove.End -> list.jumpToEnd()
        }
        return true
    }
}
