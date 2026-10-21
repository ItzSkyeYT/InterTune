/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The scrollbar's arithmetic, kept apart from Compose so plain JUnit runs it (ScrollbarGeometryTest,
// ScrollbarOddRowsTest). Lengths are pixels. A row is one list item, or one whole line of a grid.
// Offsets count from the top of the list's content area, as Compose reports them, so a row showing
// through the top padding has a negative offset.
//
// Where the list is, is counted in rows: the row at the top and how far into it the list has gone.
// A row's real size is only known while it is laid out, and many lists start with rows unlike the
// rest (a playlist's header, the filter and sort lines, an album's cover). Counted in pixels from an
// average, those rows put the estimate and the list out of step, and the list jumped back during a
// drag. Counted in rows, the thumb moves only forward as the list does, and a drag lands exactly
// where the finger says.

/** One row the list has laid out. */
data class ScrollbarRow(val index: Int, val offset: Int, val size: Int)

/** The list's layout at one moment. */
data class ScrollbarSnapshot(
    /** The row at the top of the content area and how far it has scrolled up under the padding. */
    val firstIndex: Int,
    val firstOffset: Int,
    /** Every row laid out, the ones showing through the paddings and a pinned sticky header too. */
    val rows: List<ScrollbarRow>,
    val total: Int,
    /** Where the content area ends: the viewport's end less the bottom padding. */
    val contentEnd: Int,
    /** The gap between two rows. */
    val spacing: Int = 0,
)

/** Where the list goes for the thumb. Every move is to a place, never by an amount. */
sealed interface ScrollbarMove {
    /** To row [index], [fraction] of the way into it. */
    data class To(val index: Int, val fraction: Float) : ScrollbarMove

    /** To the end of the list, however long it is by then. */
    data object End : ScrollbarMove
}

/**
 * The list measured once. At rest the thumb is drawn from a fresh one each frame; a drag keeps the
 * one taken when the thumb was grabbed until it is let go.
 */
data class ScrollbarGeometry(
    /** A usual row, gap included: the middle size of the rows in the content area. */
    val rowPx: Float,
    /** Where the list is, in rows, once it is at its end. */
    val endRows: Float,
    /** The thumb's length as a part of the track. */
    val thumbFrac: Float,
    val lastIndex: Int,
    val spacing: Int = 0,
) {
    /** How many rows fill the content area at the end of the list. */
    val visibleRows: Float get() = lastIndex + 1 - endRows

    /** The lowest the thumb's top goes. */
    val maxThumbTop: Float get() = 1f - thumbFrac

    /** Where the thumb's top sits, as a part of the track, for the list as [s] shows it. */
    fun thumbTop(s: ScrollbarSnapshot): Float {
        val rows = chain(s)
        if (rows.isEmpty()) return 0f
        if (atEnd(s, rows)) return maxThumbTop
        return (rowsScrolled(s, rows, rowPx) / endRows).coerceIn(0f, 1f) * maxThumbTop
    }

    /** Where the list belongs, in rows, for the thumb's top at [top]: in a straight line, in order. */
    fun rowsFor(top: Float): Float = top / maxThumbTop * endRows

    fun move(top: Float): ScrollbarMove = moveTo(top, rowsFor(top))

    /** The move for the thumb's top at [top] when the list belongs at [rows]. */
    fun moveTo(top: Float, rows: Float): ScrollbarMove = when {
        top <= 0f -> ScrollbarMove.To(0, 0f)
        // The end itself, so the list reaches it whatever the estimate said.
        top >= maxThumbTop -> ScrollbarMove.End
        else -> {
            val index = floor(rows).toInt().coerceIn(0, lastIndex)
            ScrollbarMove.To(index, (rows - index).coerceIn(0f, 1f))
        }
    }

    /**
     * How many pixels into a row [fraction] goes: by the row's own size while it is laid out
     * ([laidOutSize]), so the list never runs on into the next row and comes back, else by a usual row.
     */
    fun offsetPx(fraction: Float, laidOutSize: Int?): Int {
        val stride = if (laidOutSize != null) (laidOutSize + spacing).toFloat() else rowPx
        return floor(fraction * stride).toInt().coerceIn(0, max(0, ceil(stride).toInt() - 1))
    }

    companion object {
        /** The thumb is never shorter than a tenth of the track, so a long list keeps one to grab. */
        const val MIN_THUMB = 0.1f

        /**
         * The rows really in the content area. Compose keeps the rows showing through the top
         * padding in its layout, one or two of them depending on how far the first has scrolled,
         * and counting them is what shook the list in issue #34. A pinned sticky header belongs
         * to a row above the first one, so it is left out too.
         */
        fun contentRows(s: ScrollbarSnapshot): List<ScrollbarRow> =
            s.rows.filter { it.index >= s.firstIndex && it.offset < s.contentEnd }

        /** The rows laid out from the first one in the content area down, in order and without gaps. */
        fun chain(s: ScrollbarSnapshot): List<ScrollbarRow> {
            val out = ArrayList<ScrollbarRow>()
            var next = s.firstIndex
            for (row in s.rows.filter { it.index >= s.firstIndex }.sortedBy { it.index }) {
                if (row.index < next) continue
                if (row.index > next) break
                out += row
                next++
            }
            return out
        }

        /** The distance from the top of [rows]'s row at [k] to the next one's. */
        private fun stride(rows: List<ScrollbarRow>, k: Int, spacing: Int): Int =
            if (k + 1 < rows.size) rows[k + 1].offset - rows[k].offset else rows[k].size + spacing

        /** Where the list is, in rows: the first row's index and how far into it the list has gone. */
        private fun rowsScrolled(s: ScrollbarSnapshot, rows: List<ScrollbarRow>, rowPx: Float): Float {
            val stride = if (rows.isEmpty()) rowPx else stride(rows, 0, s.spacing).toFloat()
            return s.firstIndex + if (stride > 0f) (s.firstOffset / stride).coerceIn(0f, 1f) else 0f
        }

        /** The last row in full view: the list can go no further. */
        private fun atEnd(s: ScrollbarSnapshot, rows: List<ScrollbarRow>): Boolean {
            val last = rows.last()
            return last.index >= s.total - 1 && last.offset + last.size <= s.contentEnd
        }

        /**
         * Where the list will be, in rows, at its end. The rows laid out count at their own size and
         * the rest at [rowPx], so the answer is exact once the last row is laid out, and before that
         * only the rows not yet seen are guessed. It does not change as the list scrolls past rows
         * of other sizes, so the thumb does not jump back when an album's cover leaves the screen.
         */
        private fun endRows(s: ScrollbarSnapshot, rows: List<ScrollbarRow>, rowPx: Float): Float {
            if (atEnd(s, rows)) return rowsScrolled(s, rows, rowPx)
            val last = rows.last()
            val bottom = last.offset + last.size + (s.total - 1 - last.index) * rowPx
            // How much further the list scrolls, and so where the top of the content area ends up.
            val further = bottom - s.contentEnd
            if (further <= 0f) return rowsScrolled(s, rows, rowPx)
            for (k in rows.indices) {
                val stride = stride(rows, k, s.spacing)
                if (further < rows[k].offset + stride) {
                    return rows[k].index + ((further - rows[k].offset) / stride).coerceIn(0f, 1f)
                }
            }
            val afterLast = last.offset + last.size + s.spacing
            return last.index + 1 + (further - afterLast) / rowPx
        }

        /** The list as [s] shows it, or null when it has nothing to scroll. */
        fun of(s: ScrollbarSnapshot): ScrollbarGeometry? {
            if (s.total <= 0 || s.contentEnd <= 0) return null
            val rows = chain(s)
            if (rows.isEmpty()) return null
            // The middle size, not the average: a playlist's header or a grid's sort line on screen
            // does not make every row below it count as bigger. Empty rows are no guide either.
            val sizes = contentRows(s).ifEmpty { rows }.map { it.size + s.spacing }.filter { it > 0 }.sorted()
            if (sizes.isEmpty()) return null
            val rowPx = sizes[(sizes.size - 1) / 2].toFloat()
            val endRows = endRows(s, rows, rowPx)
            if (endRows <= 0f) return null
            return ScrollbarGeometry(
                rowPx = rowPx,
                endRows = endRows,
                thumbFrac = ((s.total - endRows) / s.total).coerceIn(MIN_THUMB, 1f),
                lastIndex = s.total - 1,
                spacing = s.spacing,
            )
        }
    }
}

/** One cell a grid has laid out: its item, the line it sits on, and that line's offset and size. */
data class ScrollbarCell(val index: Int, val line: Int, val column: Int, val offset: Int, val size: Int)

/** A grid seen as lines, which is how the scrollbar counts it. */
object ScrollbarGrid {
    fun snapshot(
        cells: List<ScrollbarCell>,
        firstItem: Int,
        firstOffset: Int,
        totalItems: Int,
        columns: Int,
        contentEnd: Int,
        spacing: Int,
    ): ScrollbarSnapshot {
        val lines = cells.groupBy { it.line }
            .map { (line, c) -> ScrollbarRow(line, c.minOf { it.offset }, c.maxOf { it.size }) }
            .sortedBy { it.index }
        val first = cells.firstOrNull { it.index == firstItem }?.line ?: lines.firstOrNull()?.index ?: 0
        return ScrollbarSnapshot(first, firstOffset, lines, lineCount(cells, totalItems, columns), contentEnd, spacing)
    }

    /** The grid's lines: the ones laid out, then full lines for the items after them. */
    fun lineCount(cells: List<ScrollbarCell>, totalItems: Int, columns: Int): Int {
        val last = cells.maxByOrNull { it.index } ?: return 0
        val c = max(1, columns)
        return last.line + 1 + max(0, (totalItems - 1 - last.index + c - 1) / c)
    }

    /** The height of [line] while it is laid out. */
    fun lineSize(line: Int, cells: List<ScrollbarCell>): Int? = cells.filter { it.line == line }.maxOfOrNull { it.size }

    /**
     * The first item on [line]. A line laid out says so itself. Below the lines laid out, every
     * line is a full one. Above them, the grid starts with header lines of one item each across
     * the width (the filter and sort lines), and where a full line laid out starts tells how many.
     */
    fun firstItemOf(line: Int, cells: List<ScrollbarCell>, columns: Int, totalItems: Int): Int {
        val c = max(1, columns)
        val lines = cells.groupBy { it.line }
        lines[line]?.let { return it.minOf { cell -> cell.index } }
        if (lines.isEmpty()) return (line * c).coerceIn(0, max(0, totalItems - 1))
        val item = if (line > lines.keys.max()) {
            val lastLine = lines.keys.max()
            val last = lines.getValue(lastLine)
            last.minOf { it.index } + last.size + (line - lastLine - 1) * c
        } else {
            // A full line laid out at line L starting with item F has F = h + (L - h) * c, with h
            // the number of header lines above it.
            val (fullLine, full) = lines.entries.filter { it.key > line }.let { above ->
                above.firstOrNull { it.value.size == c } ?: above.minBy { it.key }
            }.toPair()
            val first = full.minOf { it.index }
            val headers = if (c > 1 && full.size == c && (fullLine * c - first) % (c - 1) == 0) (fullLine * c - first) / (c - 1) else -1
            when {
                headers in 0..fullLine -> if (line < headers) line else headers + (line - headers) * c
                else -> first - (fullLine - line) * c
            }
        }
        return item.coerceIn(0, max(0, totalItems - 1))
    }
}

/**
 * One press on the thumb, from the grab to the release. The finger alone moves the thumb, and the
 * thumb alone says where the list goes, always as a place in the list: nothing the list does on the
 * way is read back, so the list cannot push the thumb, and a scroll from elsewhere is undone by the
 * finger's next move.
 */
class ScrollbarDrag(geometry: ScrollbarGeometry, startTop: Float, private val trackPx: Float) {
    var geometry: ScrollbarGeometry = geometry
        private set

    /** The thumb's top as a part of the track. */
    var top: Float = startTop.coerceIn(0f, geometry.maxThumbTop)
        private set

    // The thumb maps to rows along a straight line from the top of the track to the bottom. When the
    // list changes length during the press, the line bends where the thumb is then: above, the rows as
    // they were; below, the rest of the list as it is now, so the bottom is still the end.
    private var bendTop = 0f
    private var bendRows = 0f

    /** Moves the thumb by the finger's [deltaPx]; false when it is already against that end. */
    fun moveBy(deltaPx: Float): Boolean {
        val next = (top + deltaPx / trackPx).coerceIn(0f, geometry.maxThumbTop)
        if (next == top) return false
        top = next
        return true
    }

    /** Where the list belongs, in rows, for the thumb's top at [top]. */
    fun rowsFor(top: Float): Float = when {
        top <= bendTop -> if (bendTop > 0f) top / bendTop * bendRows else 0f
        else -> bendRows + (top - bendTop) / (geometry.maxThumbTop - bendTop) * (geometry.endRows - bendRows)
    }

    /** The move that takes the list to where the thumb at [top] wants it. */
    fun moveFor(top: Float): ScrollbarMove = geometry.moveTo(top, rowsFor(top))

    /**
     * The list now has [total] rows. Lists that load more near their end (online search results,
     * browse pages, online playlists) grow during a press: the list stays where it is, the thumb
     * stays under the finger, and the rest of the track takes in the rows that came.
     */
    fun listHas(total: Int) {
        val g = geometry
        if (total <= 0 || total == g.lastIndex + 1) return
        val here = rowsFor(top)
        val endRows = max(0f, g.endRows + (total - (g.lastIndex + 1)))
        bendTop = top
        bendRows = min(here, endRows)
        geometry = g.copy(endRows = endRows, lastIndex = total - 1)
    }
}
