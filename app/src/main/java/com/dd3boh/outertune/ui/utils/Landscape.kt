/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.min
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.constants.Unreleased

/**
 * What a phone on its side gets instead of the upright layout pulled to twice its width.
 *
 * One rule is behind every number here: nothing is drawn wider than it is upright. A song row,
 * the search pill and the mini player are all made for a width of about 400dp. At 900dp they are
 * the same things with a hole in the middle, the title at one edge of the screen and its menu at
 * the other, and the height they cost is the same, in a window that has less than half of it. So
 * a wide, short window is laid out as two upright widths side by side: lists run two rows
 * abreast, the floating panels keep the width they have upright, and a page under a header (an
 * album, a playlist) puts the header in one half and the songs in the other.
 *
 * A window is on its side when it is short, under 480dp, which is the compact height class the
 * slim navigation bar already goes by, and at least 600dp wide. A tablet is taller than that
 * whichever way it is held and keeps the layout it has. A phone held upright is never that wide.
 * A pop up window is short without being wide. None of them sees any of this, and neither does
 * anyone while [Unreleased.LANDSCAPE] is off.
 *
 * MainActivity measures the window once and hands this down as [LocalLandscape]. Everything a
 * screen asks of it is arithmetic on the numbers below, so LandscapeTest can check it without a
 * screen.
 */
@Immutable
data class Landscape(
    val windowWidth: Dp,
    val windowHeight: Dp,
    val enabled: Boolean = Unreleased.LANDSCAPE,
) {
    /** Whether the window is on its side, and so whether anything below differs from upright. */
    val active: Boolean = enabled && windowHeight < ShortHeight && windowWidth >= MinWidth

    /**
     * How many rows of a list go side by side in [width]: two once each has [ListColumnMinWidth],
     * which is a narrow phone held upright, and never more, because a third column on the widest
     * phones would be the only place the app has three of anything.
     */
    fun listColumns(width: Dp): Int =
        if (active && width >= ListColumnMinWidth * 2) 2 else 1

    /** The width a floating panel (the search pill, the mini player) takes of the [available] width. */
    fun panelWidth(available: Dp): Dp =
        if (active) min(available, PanelMaxWidth) else available

    /**
     * Where the mini player sits in a window with the navigation rail and the cutout taking
     * [left] and [right] of it: its left edge and its width, centred in what is between them.
     * Upright it is the whole width, as it always was.
     */
    fun panelSpan(left: Dp, right: Dp): Pair<Dp, Dp> {
        val available = (windowWidth - left - right).coerceAtLeast(0.dp)
        val width = panelWidth(available)
        return Pair(left + (available - width) / 2, width)
    }

    /**
     * The widest a single column of text (a settings page) is drawn. Upright it is [upright], the
     * cap the page already had. On its side it is [ReadingMaxWidth]: a label and the switch that
     * belongs to it should not be a hand's width apart. A page that asks for no cap gets none.
     */
    fun readingWidth(upright: Dp): Dp =
        if (active && upright.isSpecified) min(upright, ReadingMaxWidth) else upright

    companion object {
        /** Under this the window is short: Material's compact height class. */
        val ShortHeight = 480.dp

        /** At least this wide, or it is a small window and not a phone on its side. */
        val MinWidth = 600.dp

        /** A floating panel upright on a large phone, less its gutters. */
        val PanelMaxWidth = 440.dp

        /** The narrowest a song row is drawn: a small phone held upright. */
        val ListColumnMinWidth = 320.dp

        /** A settings row upright on a large phone, with a little to spare. */
        val ReadingMaxWidth = 600.dp

        /** A window nothing is done for: what previews and tests get, and release builds. */
        val Upright = Landscape(0.dp, 0.dp, enabled = false)
    }
}

/** The window's shape, from MainActivity. [Landscape.Upright] where nothing provides one. */
val LocalLandscape = staticCompositionLocalOf { Landscape.Upright }

/**
 * How many rows abreast a list that fills the page gets: [Landscape.listColumns] of the window,
 * less what the rail and the cutout take of it.
 */
@Composable
fun rememberListColumns(): Int {
    val landscape = LocalLandscape.current
    if (!landscape.active) return 1
    val insets = LocalPlayerAwareWindowInsets.current
    val direction = LocalLayoutDirection.current
    return with(LocalDensity.current) {
        landscape.listColumns(
            landscape.windowWidth - insets.getLeft(this, direction).toDp() - insets.getRight(this, direction).toDp()
        )
    }
}

/**
 * The rows of a list, [columns] abreast.
 *
 * With one column this is itemsIndexed and nothing else, so an upright list is the list it was.
 * With more, each lazy item is a line of [columns] rows sharing the width, each in a box of its
 * own, since a row (a song with its swipe) does not pass its modifier to its outermost layout.
 * The index is the row's place in [items] either way: that is what a tap plays from.
 */
inline fun <T> LazyListScope.itemsInColumns(
    items: List<T>,
    columns: Int,
    noinline key: (index: Int, item: T) -> Any,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
) {
    if (columns <= 1) {
        itemsIndexed(items = items, key = key, contentType = contentType) { index, item ->
            itemContent(index, item)
        }
        return
    }
    val lines = (items.size + columns - 1) / columns
    items(
        count = lines,
        key = { line -> key(line * columns, items[line * columns]) },
        contentType = { line -> contentType(line * columns, items[line * columns]) },
    ) { line ->
        Row(Modifier.fillMaxWidth()) {
            for (column in 0 until columns) {
                val index = line * columns + column
                // The last line of an odd list keeps its half: the other is an empty box.
                Box(Modifier.weight(1f)) {
                    if (index < items.size) itemContent(index, items[index])
                }
            }
        }
    }
}
