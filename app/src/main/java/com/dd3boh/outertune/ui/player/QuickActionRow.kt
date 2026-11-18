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

/**
 * Where each of [widths] starts in a row [width] wide when the first of them hangs off the start of
 * the others: they are placed where [quickRowPositions] puts them without it, and it goes [gap]
 * before the first of them. A button that comes and goes, a listening room's, then moves none of the
 * others (his rule of 15 Nov 2026). Null when it would come nearer than [QuickRowEdgeMargin] to the
 * edge of the screen, past the [gutter] beside the row: the row is then centred with it in, as any.
 */
internal fun Density.quickRowPositionsHanging(widths: IntArray, gap: Dp, width: Int, gutter: Dp, layoutDirection: LayoutDirection): IntArray? {
    if (widths.size < 2) return null
    val rest = quickRowPositions(widths.copyOfRange(1, widths.size), gap, width, LayoutDirection.Ltr)
    val start = rest[0] - gap.roundToPx() - widths[0]
    if (start < -(gutter - QuickRowEdgeMargin).coerceAtLeast(0.dp).roundToPx()) return null
    val ltr = intArrayOf(start) + rest
    // Start is on the left in a left-to-right layout and on the right otherwise.
    return if (layoutDirection == LayoutDirection.Ltr) ltr else IntArray(ltr.size) { width - (ltr[it] + widths[it]) }
}

/** The widths a row's buttons are drawn at, in px, and whether its first one hangs off the others. */
internal class QuickRowWidths(val widths: IntArray, val hung: Boolean)

/**
 * The widths of Connected's row under the controls, [natural] widths in a row [width] wide between
 * [gutter]s: what [fitQuickActionWidths] gives, unless [hangingFirst] asks for the first of them to
 * hang off the start of the others (a listening room's) and there is room for it.
 *
 * Hung, the others keep the widths and places they have without it: they are fitted among
 * themselves, so it never narrows them, and it goes a [gap] before the first of them at its own
 * width or, where that reaches nearer the edge than [QuickRowEdgeMargin], at an icon's
 * ([QuickActionIconOnlyWidth]). With no room even for that, the row is fitted and centred with it
 * in, as any row.
 */
internal fun Density.quickRowWidths(natural: IntArray, gap: Dp, width: Int, gutter: Dp, hangingFirst: Boolean): QuickRowWidths {
    val gapPx = gap.roundToPx()
    val room = quickRowRoom(width, gutter)
    val floor = QuickActionIconOnlyWidth.roundToPx()
    if (hangingFirst && natural.size >= 2) {
        val others = fitQuickActionWidths(natural.copyOfRange(1, natural.size), gapPx, room, floor)
        for (first in intArrayOf(natural[0], minOf(natural[0], floor))) {
            val widths = intArrayOf(first) + others
            if (quickRowPositionsHanging(widths, gap, width, gutter, LayoutDirection.Ltr) != null) return QuickRowWidths(widths, hung = true)
        }
    }
    return QuickRowWidths(fitQuickActionWidths(natural, gapPx, room, floor), hung = false)
}

