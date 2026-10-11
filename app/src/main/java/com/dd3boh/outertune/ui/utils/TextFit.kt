/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.BreakIterator

/**
 * For a label that has to be whole. It is drawn at this style's size when it fits, and otherwise
 * at the largest size, down to [smallest], at which no word is broken across two lines and
 * nothing is cut off. In French at a large font size Home wrote "Statistiqu" over "es", and six
 * settings pages lost the end of their title. Only a label too long at [smallest] as well is cut,
 * the way its own overflow says.
 *
 * Handed to a Text as its autoSize, beside the style it was made from, which has to give its
 * size in sp.
 */
fun TextStyle.shrinkToFit(smallest: TextUnit): TextAutoSize {
    require(fontSize.isSp && smallest.isSp) { "sizes in sp, not $fontSize and $smallest" }
    return ShrinkToFit(largest = fontSize, smallest = smallest)
}

/**
 * [shrinkToFit] for one label of a [row] whose labels all take one size: this one is drawn no
 * larger than the smallest of them needs. [text] is what it says, and what it goes by in the row.
 *
 * The Text it is handed to goes in a `key` of it. Text leaves autoSize out of what it compares to
 * see whether anything changed (Compose 1.9): handed only a new one, it keeps the size it had.
 * This call itself stays outside that key, or the label would be forgotten by its row each time.
 */
@Composable
fun TextStyle.shrinkToFit(smallest: TextUnit, text: String, row: SameTextSize): TextAutoSize {
    require(fontSize.isSp && smallest.isSp) { "sizes in sp, not $fontSize and $smallest" }
    // A label that is gone (a tab taken off the bar) no longer holds the others down.
    DisposableEffect(row, text) { onDispose { row.forget(text) } }
    return ShrinkToFit(largest = fontSize, smallest = smallest, row = row, atMost = row.size)
}

/**
 * One size for the labels of a row: the smallest any of them needs to be whole. Home's four
 * shortcuts stand side by side, and so do the bottom bar's tabs. With each name shrunk by
 * itself, "Statistiques" was smaller than "Historique" beside it, and "Bibliothèque" than the
 * three other tabs.
 *
 * Made once for the row, with remember, and handed to each label's [shrinkToFit]. A label only
 * learns what it needs when it is measured, which is after the row was composed: a row whose
 * labels do not all fit is drawn once with each at its own size, and is right on the next frame.
 */
@Stable
class SameTextSize {
    private val needed = mutableStateMapOf<String, Float>()

    /** The size the row is at, in sp: the smallest any label needs, and no limit while none has said. */
    val size: Float by derivedStateOf { needed.values.minOrNull() ?: Float.POSITIVE_INFINITY }

    /** [label] is whole at [size] and no larger. It says so while it is measured. */
    internal fun needs(label: String, size: Float) = Snapshot.withoutReadObservation {
        if (needed[label] != size) needed[label] = size
    }

    internal fun forget(label: String) {
        needed.remove(label)
    }
}

/**
 * [row] and [atMost] are for a label of a row that takes one size (see [SameTextSize]): what the
 * row is at when the label is composed. Alone, a label has neither.
 */
private data class ShrinkToFit(
    val largest: TextUnit,
    val smallest: TextUnit,
    val row: SameTextSize? = null,
    val atMost: Float = Float.POSITIVE_INFINITY,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val own = largestWhole(sizesToTry(largest.value, smallest.value)) { size ->
            performLayout(constraints, text, size.sp).isWhole(text.text)
        }
        // What the row is at is in this very object, and the label is made again with a new one
        // when that changes: a Text whose room did not change is not asked its size twice.
        row?.needs(text.text, own)
        return minOf(own, atMost).sp
    }
}

/** Nothing cut off at its end, and no line ended inside a word. */
private fun TextLayoutResult.isWhole(text: String): Boolean {
    if (hasVisualOverflow || (lineCount > 0 && isLineEllipsized(lineCount - 1))) return false
    return !breaksAWord(text, (0 until lineCount - 1).map { getLineEnd(it) })
}

/** How far apart the sizes tried are, in sp. A difference under half a point does not show on a label. */
private const val STEP = 0.5f

/** The sizes a label is tried at, largest first: its own, then a step less each time, down to [smallest]. */
internal fun sizesToTry(largest: Float, smallest: Float): List<Float> = buildList {
    var size = largest
    while (size > smallest) {
        add(size)
        size -= STEP
    }
    add(minOf(largest, smallest))
}

/** The first of [sizes] at which the label [isWhole], or the last of them when it is whole at none. */
internal fun largestWhole(sizes: List<Float>, isWhole: (Float) -> Boolean): Float =
    sizes.firstOrNull(isWhole) ?: sizes.last()

/**
 * Whether one of the lines of [text], which end at [lineEnds], ends where its language lets no
 * line end: inside a word. A line is only ever ended there when the word alone is wider than
 * the room it has.
 */
internal fun breaksAWord(text: String, lineEnds: List<Int>): Boolean {
    if (lineEnds.isEmpty()) return false
    val mayEnd = BreakIterator.getLineInstance().apply { setText(text) }
    return lineEnds.any { it in 1 until text.length && !mayEnd.isBoundary(it) }
}

/** Whether [text] can go on two lines without a word being broken: not when it is one word. */
fun canWrap(text: String): Boolean {
    val mayEnd = BreakIterator.getLineInstance().apply { setText(text) }
    mayEnd.first()
    val firstEnd = mayEnd.next()
    return firstEnd != BreakIterator.DONE && firstEnd < text.trimEnd().length
}
