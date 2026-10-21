/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

// The scrollbar's arithmetic, kept apart from Compose so plain JUnit runs it (ScrollbarGeometryTest,
// ScrollbarOddRowsTest, ScrollbarEndsTest). Lengths are pixels. A row is one list item, or one whole line of a grid.
// Offsets count from the top of the list's content area, as Compose reports them, so a row showing
// through the top padding has a negative offset.
//
// Where the list is, is counted in rows: the row at the top and how far into it the list has gone.
// A row's real size is only known while it is laid out, and many lists start with rows unlike the
// rest (a playlist's header, the filter and sort lines, an album's cover). Counted in pixels from an
// average, those rows put the estimate and the list out of step, and the list jumped back during a
// drag. Counted in rows, the thumb moves only forward as the list does, and a drag lands exactly
// where the finger says. A part of a row is always measured by that row's own size, so a row off
// screen is laid out before the list goes into it.

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
 * The list measured once. At rest the thumb is drawn from a fresh one each frame. A press starts from
 * the one taken when the thumb was grabbed, keeps its thumb's length, and bends its line where the
 * list shows more of itself (ScrollbarDrag).
 */
data class ScrollbarGeometry(
    /** A usual row, gap included ([usualRow]). */
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
        else -> at(rows)
    }

    /** The move to [rows]: the row it falls in and how far into it. */
    fun at(rows: Float): ScrollbarMove.To {
        val index = floor(rows).toInt().coerceIn(0, lastIndex)
        return ScrollbarMove.To(index, (rows - index).coerceIn(0f, 1f))
    }

    /**
     * How many pixels into a row [fraction] goes, by the row's own size ([laidOutSize]), so the list
     * never runs on into the next row and comes back. A usual row stands in only for a row that could
     * not be laid out.
     */
    fun offsetPx(fraction: Float, laidOutSize: Int?): Int {
        val stride = if (laidOutSize != null) (laidOutSize + spacing).toFloat() else rowPx
        return floor(fraction * stride).toInt().coerceIn(0, max(0, ceil(stride).toInt() - 1))
    }

    companion object {
        /** The thumb is never shorter than a tenth of the track, so a long list keeps one to grab. */
        const val MIN_THUMB = 0.1f

        /** Close enough, in rows, to count as the same place. */
        const val NEAR = 1e-3f

        /**
         * The rows laid out from the first one in the content area down, in order and without gaps,
         * the ones under the bottom padding too. Compose keeps the rows showing through the top
         * padding in its layout, one or two of them depending on how far the first has scrolled,
         * and counting them is what shook the list in issue #34, so they are left out. A pinned
         * sticky header belongs to a row above the first one, so it is left out too.
         */
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

        /**
         * Where the list will be, in rows, at its end, once its last row is laid out; null before.
         * Every row from the top of the content area to the last is laid out then, at its own size,
         * so the answer is exact, and it does not depend on where the list is.
         */
        fun knownEnd(s: ScrollbarSnapshot): Float? {
            if (s.total <= 0) return null
            val rows = chain(s)
            if (rows.isEmpty() || rows.last().index < s.total - 1) return null
            return endRows(s, rows, 1f)
        }

        /**
         * A usual row of [rows], gap included: the size of the row under the middle pixel of them all.
         * Not the average, so a playlist's header or a grid's sort line does not make every row below
         * it count as smaller or bigger; nor the middle row, which on Home is a 48 dp title between
         * shelves five times its size. The rows under the bottom padding count too, so a small screen
         * or landscape has enough of them. The list's first row is left out while others are laid
         * out, as in most lists it is a header: a playlist's, an album's cover, the filter line.
         * Empty rows take no pixels, so they are no guide either.
         */
        fun usualRow(rows: List<ScrollbarRow>, spacing: Int): Float? {
            fun sizes(of: List<ScrollbarRow>) = of.map { it.size + spacing }.filter { it > 0 }.sorted()
            val sizes = sizes(rows.filter { it.index > 0 }).ifEmpty { sizes(rows) }
            if (sizes.isEmpty()) return null
            val half = sizes.sumOf { it.toLong() } / 2.0
            var seen = 0L
            for (size in sizes) {
                seen += size
                if (seen >= half) return size.toFloat()
            }
            return sizes.last().toFloat()
        }

        /** Where the list is, in rows, as [s] shows it. */
        fun placeOf(s: ScrollbarSnapshot): Float {
            val rows = chain(s)
            return if (rows.isEmpty()) s.firstIndex.toFloat() else rowsScrolled(s, rows, 1f)
        }

        /** The list as [s] shows it, or null when it has nothing to scroll. */
        fun of(s: ScrollbarSnapshot): ScrollbarGeometry? {
            if (s.total <= 0 || s.contentEnd <= 0) return null
            val rows = chain(s)
            if (rows.isEmpty()) return null
            val rowPx = usualRow(rows, s.spacing) ?: return null
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

/** The list a press moves. */
interface ScrollbarList {
    /** The list as it is laid out now. */
    fun layout(): ScrollbarSnapshot

    /**
     * Puts [row] at the top of the content area, [offsetPx] into it, laid out before the next frame,
     * as scrollToItem does. An offset past the end of the list leaves it at its end.
     */
    suspend fun scrollTo(row: Int, offsetPx: Int)
}

/**
 * One press on the thumb, from the grab to the release. The finger alone moves the thumb, and the
 * thumb alone says where the list goes, always as a place in the list, never by an amount. So the
 * list cannot push the thumb, and a scroll from elsewhere is undone by the finger's next move.
 */
class ScrollbarDrag(geometry: ScrollbarGeometry, startTop: Float, val trackPx: Float) {
    var geometry: ScrollbarGeometry = geometry
        private set

    /** The thumb's top as a part of the track. */
    var top: Float = startTop.coerceIn(0f, geometry.maxThumbTop)
        private set

    // The thumb maps to rows along a straight line from the top of the track to the bottom. When the
    // rows laid out show the end elsewhere than the line leads, because the list got longer or
    // shorter, or rows unlike the ones at the grab came into view, or the last row did and the end
    // is exact, the line bends where the list is: above, the rows as they were; below, on to the end
    // as it is now. So the bottom of the track meets the end, and the list never jumps there.
    private var bendTop = 0f
    private var bendRows = 0f

    /** The thumb's top for the last move. */
    private var placedTop = top

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
    fun moveFor(top: Float): ScrollbarMove {
        val rows = rowsFor(top)
        return when {
            top <= 0f -> ScrollbarMove.To(0, 0f)
            // The end itself, so the list reaches it whatever the estimate said. Only a list that
            // grew while the thumb was at the bottom keeps its new rows for the next press.
            top >= geometry.maxThumbTop && rows >= geometry.endRows - ScrollbarGeometry.NEAR -> ScrollbarMove.End
            else -> geometry.at(rows)
        }
    }

    /** Takes [list] to where the thumb at [top] wants it. */
    suspend fun goTo(top: Float, list: ScrollbarList) {
        // A list that loads more near its end can have grown since the last move, and one that lost
        // rows there been pulled back to its new end.
        val before = list.layout()
        if (before.total > 0 && before.total != geometry.lastIndex + 1) listHas(before)
        when (val move = moveFor(top)) {
            is ScrollbarMove.To -> {
                // A row's size is known only while it is laid out. One off screen is brought to the
                // top first, which lays it out, so the list goes into it by its own size. Measured
                // by a usual row instead, the next move would measure it again by its own and send
                // the list back: a playlist's header, a grid's sort line.
                val size = before.sizeOf(move.index) ?: if (move.fraction > 0f) {
                    list.scrollTo(move.index, 0)
                    list.layout().sizeOf(move.index)
                } else null
                list.scrollTo(move.index, geometry.offsetPx(move.fraction, size))
            }

            ScrollbarMove.End -> {
                val last = geometry.lastIndex
                val size = before.sizeOf(last) ?: run {
                    list.scrollTo(last, 0)
                    list.layout().sizeOf(last)
                }
                // Past the top of the last row by its size, which the list pulls back to its end: a
                // last row taller than the screen is seen to its bottom.
                list.scrollTo(last, size ?: 0)
            }
        }
        placedTop = top
        endIs(list.layout())
    }

    /**
     * Where the rows laid out in [now] put the end: exactly once the last row is among them, else
     * by the same estimate as at the grab. When the line leads elsewhere, it bends where the list is.
     */
    private fun endIs(now: ScrollbarSnapshot) {
        val exact = ScrollbarGeometry.knownEnd(now)
        val end = exact ?: ScrollbarGeometry.of(now)?.endRows ?: return
        val off = abs(end - geometry.endRows)
        if (off < if (exact != null) ScrollbarGeometry.NEAR else GUESS_OFF) return
        bendAt(ScrollbarGeometry.placeOf(now), end)
    }

    /**
     * The list now has [now]'s rows. Lists that load more near their end (online search results,
     * browse pages, online playlists) grow during a press: the list stays where it is, the thumb
     * stays under the finger, and the rest of the track takes in the rows that came.
     */
    private fun listHas(now: ScrollbarSnapshot) {
        val g = geometry
        val end = ScrollbarGeometry.knownEnd(now) ?: max(0f, g.endRows + (now.total - (g.lastIndex + 1)))
        geometry = g.copy(lastIndex = now.total - 1)
        bendAt(ScrollbarGeometry.placeOf(now), end)
    }

    /**
     * Bends the line at the last move's thumb, where the list is now, [here], on to [end] at the
     * bottom. Where the list is, is read only for this, so the line goes on from the list itself,
     * even after rows at the end were taken away and pulled it back.
     */
    private fun bendAt(here: Float, end: Float) {
        bendTop = placedTop
        bendRows = here.coerceIn(0f, max(0f, end))
        geometry = geometry.copy(endRows = end)
    }

    private companion object {
        /** How far, in rows, an estimate of the end may be from the line's before the line bends. */
        const val GUESS_OFF = 0.5f
    }
}

private fun ScrollbarSnapshot.sizeOf(row: Int): Int? = rows.firstOrNull { it.index == row }?.size

/**
 * Where a press let go of the thumb, and the list as it left it. Measured afresh from the list, the
 * thumb can land elsewhere and at another length: the press went along its own line, bent where the
 * list showed more of itself, and only the thumb takes a touch, so a second grab where the finger
 * let go could miss it. So until the list moves or changes, the thumb stays as the finger left it,
 * and a grab there carries on the same press.
 */
class ScrollbarLetGo(val drag: ScrollbarDrag, private val left: ScrollbarSnapshot) {
    private var moved = false

    /**
     * The press that let go, while [now] is still the list as it left it, on a track of [trackPx].
     * Once the list has moved, never again, even when it comes back to the same place, where the
     * thumb measured afresh is already drawn.
     */
    fun stillAt(now: ScrollbarSnapshot, trackPx: Float): ScrollbarDrag? {
        if (!moved && (now != left || drag.trackPx != trackPx)) moved = true
        return if (moved) null else drag
    }
}
