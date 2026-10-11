/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * The buttons at the foot of a dialog or a bubble, kept to its end. What is in [start] (Reset,
 * how far a tour has got) is kept to its start, on the same line.
 *
 * Every button is as wide as its label. A Row gives the last one what the others leave, and in
 * French, or at a large font size, that was a sliver: "Faites-moi visiter" stood in a tall narrow
 * pill, broken inside its words. Here a button with no room left beside the others goes on a line
 * of its own under them, still at the end.
 *
 * Not for a little, though. A row a few dp too wide closes its gaps first, and then a button that
 * Material made wider than its label needs (OK is 58dp wide, for two letters) gives that up. The
 * labels stay whole and all but where they were. In French the lyrics search dialog was 1dp too
 * wide on a phone of 360dp, and had OK alone on a second line.
 */
@Composable
fun ButtonsRow(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) = Layout(contents = listOf(start, content), modifier = modifier) { (atStart, atEnd), constraints ->
    val measurables = atStart + atEnd
    val room = constraints.maxWidth
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
    val fits = natural.sum() <= room
    // Each is measured once, which is all a layout may do. Those too wide for one line are told
    // to be at least a pixel wide: a Material button keeps its 58dp only when nothing is asked of
    // it, and is then as wide as its label needs. Where it has more room than that, it stands in
    // the middle of it, which is where its label would be in a wider button.
    val placeables = measurables.map { it.measure(if (fits) loose else loose.copy(minWidth = minOf(1, room))) }
    val parts = placeables.mapIndexed { i, placeable ->
        RowPart(
            natural = if (fits) placeable.width else maxOf(natural[i], placeable.width),
            tight = placeable.width,
            // Nothing is drawn by what has no height: a Spacer between two buttons.
            gap = placeable.height == 0,
        )
    }
    val lines = buttonLines(parts.take(atStart.size), parts.drop(atStart.size), room)

    var next = 0
    val heights = lines.map { line ->
        val onLine = placeables.subList(next, next + line.start.size + line.end.size)
        next += onLine.size
        onLine.maxOfOrNull { it.height } ?: 0
    }
    val width = constraints.constrainWidth(lines.maxOf { it.width(room) })
    layout(width, constraints.constrainHeight(heights.sum())) {
        var index = 0
        var y = 0
        lines.forEachIndexed { l, line ->
            fun place(x: Int, has: Int) {
                val placeable = placeables[index++]
                placeable.placeRelative(
                    x = x + (has - placeable.width) / 2,
                    // To the pixel where a Row puts it, which rounds a half up.
                    y = y + Alignment.CenterVertically.align(placeable.height, heights[l]),
                )
            }
            var x = 0
            line.start.forEach { place(x, it); x += it }
            x = width - line.end.sum()
            line.end.forEach { place(x, it); x += it }
            y += heights[l]
        }
    }
}

/**
 * One thing in a row of buttons, in pixels: how wide it is left to itself ([natural]), how narrow
 * it can be with its label whole ([tight]), and whether it is only a [gap] between two others.
 */
internal data class RowPart(val natural: Int, val tight: Int = natural, val gap: Boolean = false)

/** One line of the row: the room each thing on it has, those kept at its [start] and those at its [end]. */
internal data class ButtonsLine(val start: List<Int>, val end: List<Int>) {
    /** A line with something at each end is as wide as the row can be, any other as wide as what is on it. */
    fun width(room: Int): Int =
        if (start.isNotEmpty() && end.isNotEmpty() && room != Constraints.Infinity) room else start.sum() + end.sum()
}

/**
 * The lines a row of buttons takes in [room], and the room each thing has on its line.
 *
 * One line whenever that can be done with every label whole: at their own widths, or closed up
 * (see [onOneLine]). Otherwise as many to a line as fit, in their order and at their own widths,
 * nothing closed up: the next line is there anyway.
 */
internal fun buttonLines(start: List<RowPart>, end: List<RowPart>, room: Int): List<ButtonsLine> {
    val all = start + end
    onOneLine(all, room)?.let { return listOf(ButtonsLine(it.take(start.size), it.drop(start.size))) }

    val lines = mutableListOf<ButtonsLine>()
    var atStart = mutableListOf<Int>()
    var atEnd = mutableListOf<Int>()
    var used = 0
    all.forEachIndexed { i, part ->
        // What is wider than the whole row has the row.
        val width = minOf(part.natural, room)
        if (used > 0 && used + width > room) {
            lines += ButtonsLine(atStart, atEnd)
            atStart = mutableListOf()
            atEnd = mutableListOf()
            used = 0
        }
        (if (i < start.size) atStart else atEnd) += width
        used += width
    }
    lines += ButtonsLine(atStart, atEnd)
    return lines
}

/**
 * The room each of [parts] has when they all stand on one line of [room], or null when they
 * cannot with every label whole.
 *
 * What is missing comes out of the gaps first, down to nothing, and only then out of the buttons
 * that are wider than their labels need, and never more than is missing.
 */
internal fun onOneLine(parts: List<RowPart>, room: Int): List<Int>? {
    val missing = parts.sumOf { it.natural } - room
    if (missing <= 0) return parts.map { it.natural }
    val gaps = parts.map { if (it.gap) it.natural else 0 }
    val spare = parts.map { if (it.gap) 0 else it.natural - it.tight }
    if (missing > gaps.sum() + spare.sum()) return null
    val fromGaps = shared(minOf(missing, gaps.sum()), gaps)
    val fromButtons = shared(missing - fromGaps.sum(), spare)
    return parts.indices.map { parts[it].natural - fromGaps[it] - fromButtons[it] }
}

/** [amount] taken from several, each of which can give so much ([most]): from each by its share. */
internal fun shared(amount: Int, most: List<Int>): List<Int> {
    val all = most.sum()
    if (amount <= 0 || all <= 0) return most.map { 0 }
    var before = 0L
    var taken = 0
    return most.map { can ->
        before += can
        val upToHere = (amount * before / all).toInt()
        (upToHere - taken).also { taken = upToHere }
    }
}
