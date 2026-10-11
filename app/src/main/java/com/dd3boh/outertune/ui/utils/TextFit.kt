/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
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

private data class ShrinkToFit(val largest: TextUnit, val smallest: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit =
        largestWhole(sizesToTry(largest.value, smallest.value)) { size ->
            performLayout(constraints, text, size.sp).isWhole(text.text)
        }.sp
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
