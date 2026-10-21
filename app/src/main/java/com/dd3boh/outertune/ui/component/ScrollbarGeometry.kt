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

// The scrollbar's arithmetic, kept apart from Compose so plain JUnit runs it (ScrollbarGeometryTest).
// Lengths are pixels. A row is one list item, or one whole line of a grid. Offsets count from the
// top of the list's content area, as Compose reports them, so a row showing through the top
// padding has a negative offset.

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

/** How the list gets to where the thumb wants it. */
sealed interface ScrollbarMove {
    /** A short way: scrolled, so the rows slide. */
    data class By(val px: Float) : ScrollbarMove

    /** A long way: one jump, to a row and an offset into it. */
    data class To(val index: Int, val offsetPx: Int) : ScrollbarMove
}

/**
 * The list measured once. At rest the thumb is drawn from a fresh one each frame; a drag keeps the
 * one taken when the thumb was grabbed until it is let go.
 */
data class ScrollbarGeometry(
    /** The average row in the content area, gap included. */
    val rowPx: Float,
    /** The content area: the list's height less both paddings. */
    val viewportPx: Float,
    /** How far the list scrolls, as if every row were rowPx. */
    val maxScrollPx: Float,
    /** The thumb's length as a part of the track. */
    val thumbFrac: Float,
    val lastIndex: Int,
) {
    /** How many rows fill the content area. */
    val visibleRows: Float get() = viewportPx / rowPx

    /** The lowest the thumb's top goes. */
    val maxThumbTop: Float get() = 1f - thumbFrac

    fun scrolledPx(s: ScrollbarSnapshot): Float = s.firstIndex * rowPx + s.firstOffset

    /** Where the thumb's top sits, as a part of the track, for the list as [s] shows it. */
    fun thumbTop(s: ScrollbarSnapshot): Float {
        // With the last row in full view the list is at its end, so the thumb touches the bottom
        // even when the rows above made the estimate run a little long.
        val last = s.rows.lastOrNull { it.index == lastIndex }
        if (last != null && last.offset + last.size <= s.contentEnd) return maxThumbTop
        return (scrolledPx(s) / maxScrollPx).coerceIn(0f, 1f) * maxThumbTop
    }

    /** Where the list belongs for the thumb's top at [top]: in a straight line, in order. */
    fun targetPx(top: Float): Float = top / maxThumbTop * maxScrollPx

    /** [targetPx] as one row and an offset inside it, for a way too long to scroll. */
    fun jumpFor(targetPx: Float): ScrollbarMove.To {
        val index = floor(targetPx / rowPx).toInt().coerceIn(0, lastIndex)
        val offset = (targetPx - index * rowPx).toInt().coerceIn(0, max(0, ceil(rowPx).toInt() - 1))
        return ScrollbarMove.To(index, offset)
    }

    /** What takes the list from [appliedPx], where it was last sent, to where the thumb at [top] wants it. */
    fun move(top: Float, appliedPx: Float): ScrollbarMove {
        val target = targetPx(top)
        return when {
            top <= 0f -> ScrollbarMove.To(0, 0)
            // The last row, so the list reaches its real end whatever the estimate said.
            top >= maxThumbTop -> ScrollbarMove.To(lastIndex, 0)
            abs(target - appliedPx) <= viewportPx -> ScrollbarMove.By(target - appliedPx)
            // Scrolling this far would lay out every row on the way.
            else -> jumpFor(target)
        }
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

        /** The list as [s] shows it, or null when it has nothing to scroll. */
        fun of(s: ScrollbarSnapshot): ScrollbarGeometry? {
            val rows = contentRows(s)
            if (rows.isEmpty() || s.total <= 0 || s.contentEnd <= 0) return null
            val rowPx = rows.sumOf { it.size }.toFloat() / rows.size + s.spacing
            val viewportPx = s.contentEnd.toFloat()
            val contentPx = s.total * rowPx - s.spacing
            if (contentPx <= viewportPx) return null
            return ScrollbarGeometry(
                rowPx = rowPx,
                viewportPx = viewportPx,
                maxScrollPx = contentPx - viewportPx,
                thumbFrac = (viewportPx / contentPx).coerceIn(MIN_THUMB, 1f),
                lastIndex = s.total - 1,
            )
        }
    }
}

/**
 * One press on the thumb, from the grab to the release. The finger alone moves the thumb, and the
 * thumb alone says where the list goes: nothing the list does on the way is read back, so the
 * list cannot push the thumb, and the thumb cannot push the list back.
 */
class ScrollbarDrag(val geometry: ScrollbarGeometry, startTop: Float, private val trackPx: Float) {
    /** The thumb's top as a part of the track. */
    var top: Float = startTop.coerceIn(0f, geometry.maxThumbTop)
        private set

    /** Where the list was last sent, kept here rather than measured from its layout. */
    private var appliedPx = geometry.targetPx(top)

    /** Moves the thumb by the finger's [deltaPx]; false when it is already against that end. */
    fun moveBy(deltaPx: Float): Boolean {
        val next = (top + deltaPx / trackPx).coerceIn(0f, geometry.maxThumbTop)
        if (next == top) return false
        top = next
        return true
    }

    /** The move that takes the list to where the thumb at [top] wants it. */
    fun moveFor(top: Float): ScrollbarMove = geometry.move(top, appliedPx)

    /** Marks the move for [top] as done. */
    fun moved(top: Float) {
        appliedPx = geometry.targetPx(top)
    }
}
