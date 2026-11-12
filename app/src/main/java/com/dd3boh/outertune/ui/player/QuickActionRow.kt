/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * How close to the edge of the screen Connected's row under the controls may come. With many
 * buttons the row is wider than the space between its gutters, and it then reaches into them
 * evenly on both sides, no nearer than this to the edge.
 */
internal val QuickRowEdgeMargin = 8.dp

/**
 * The least a button of that row takes with nothing but its icon: the icon's 22dp and 10dp of
 * padding either side of it (PlayerActionSegment). Narrower than this and the icon is clipped.
 */
internal val QuickActionIconOnlyWidth = 42.dp

/**
 * The width the row under the controls may take: the width it is given and the gutters beside it,
 * less [QuickRowEdgeMargin] from the edge, either side.
 */
internal fun Density.quickRowRoom(width: Int, gutter: Dp): Int =
    width + 2 * (gutter - QuickRowEdgeMargin).coerceAtLeast(0.dp).roundToPx()

/**
 * The widths the buttons of Connected's row under the controls are drawn at, in px.
 *
 * [natural] is the width each takes of itself, [gap] the space between neighbours and [room] the
 * most the whole row may take, see [quickRowRoom]. A row that fits is left exactly as it is,
 * whatever it holds. One that does not takes the difference from the buttons that are only an
 * icon, the narrowest ones, evenly, down to [floor] at the least. A button that carries the sleep
 * timer's time is as wide as its words need, and keeps that.
 */
internal fun fitQuickActionWidths(natural: IntArray, gap: Int, room: Int, floor: Int): IntArray {
    if (natural.isEmpty()) return natural
    val gaps = gap * (natural.size - 1)
    if (natural.sum() + gaps <= room) return natural
    val iconOnly = natural.min()
    val count = natural.count { it == iconOnly }
    val others = natural.sum() - iconOnly * count + gaps
    val each = ((room - others) / count).coerceIn(minOf(floor, iconOnly), iconOnly)
    return IntArray(natural.size) { if (natural[it] == iconOnly) each else natural[it] }
}

/**
 * Where each of [widths] starts in a row [width] wide: the buttons together centred in it and
 * [gap] apart, the first of them on the start side. Where the buttons fit that is where a Row
 * centred and spaced [gap] apart puts them. One wider than [width], reaching into the gutters, is
 * centred the same way, so it overhangs by the same on both sides.
 */
internal fun Density.quickRowPositions(widths: IntArray, gap: Dp, width: Int, layoutDirection: LayoutDirection): IntArray {
    val gapPx = gap.roundToPx()
    val occupied = widths.sum() + gapPx * (widths.size - 1).coerceAtLeast(0)
    var x = ((width - occupied) / 2f).roundToInt()
    return IntArray(widths.size) { index ->
        val start = x
        x += widths[index] + gapPx
        // Start is on the left in a left-to-right layout and on the right otherwise.
        if (layoutDirection == LayoutDirection.Ltr) start else width - (start + widths[index])
    }
}
