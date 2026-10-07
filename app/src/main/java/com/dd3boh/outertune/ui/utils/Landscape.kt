/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
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
     * [left] and [right] of it: its left edge and its width. Upright it is the whole width, as
     * it always was.
     *
     * Where the page has two halves (the test for two rows abreast) it sits under the second
     * one, the right unless [rtl], and is no wider than it. In the middle of the page it lay
     * across the join, and on any phone under about 430dp tall that put it over the buttons of
     * a header standing in the first half. Where the page is one column it is in the middle.
     */
    fun panelSpan(left: Dp, right: Dp, rtl: Boolean = false): Pair<Dp, Dp> {
        val available = (windowWidth - left - right).coerceAtLeast(0.dp)
        val halves = listColumns(available) > 1
        val width = if (halves) min(PanelMaxWidth, available / 2) else panelWidth(available)
        val spare = available - width
        val start = when {
            !halves -> left + spare / 2
            rtl -> left
            else -> left + spare
        }
        return Pair(start, width)
    }

    /**
     * The widest a single column of text (a settings page) is drawn. Upright it is [upright], the
     * cap the page already had. On its side it is [ReadingMaxWidth]: a label and the switch that
     * belongs to it should not be a hand's width apart. A page that asks for no cap gets none.
     */
    fun readingWidth(upright: Dp): Dp =
        if (active && upright.isSpecified) min(upright, ReadingMaxWidth) else upright

    /**
     * The side of the cover in a header that stands beside its list, with [room] left for it in
     * the header's half once the column next to it has what it needs (a playlist's row of five
     * buttons is 240dp). The cover gives way to the column: kept at its size it was the whole
     * first line of a half 360dp wide, the column went under it, and in a window 400dp tall that
     * put Play and Shuffle below the edge. Under [HeaderCoverMin] it would be a thumbnail, so
     * with less [room] than that it keeps its size and the column goes under it as before.
     * Upright, and with no [room] named, it is [upright].
     */
    fun headerCover(upright: Dp, room: Dp): Dp =
        if (active && room.isSpecified && room >= min(HeaderCoverMin, upright)) min(room, upright) else upright

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

        /** The smallest a header's cover is drawn: two thirds of what it is upright. */
        val HeaderCoverMin = 96.dp

        /**
         * The shape of a header's picture (an artist's) in its half of the window, where the 4:3
         * it has upright would be as tall as the window and push the buttons under the mini player.
         */
        const val HeaderPictureRatio = 16f / 9

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
 * Whether a page's header stands beside its list ([HeaderBesideList]) and not above it: where
 * the window has two upright widths to give, which is the test for two rows abreast. A small
 * phone on its side has not, and keeps its header above its list.
 */
@Composable
fun rememberHeaderBeside(): Boolean = rememberListColumns() > 1

/**
 * The width of the header's half of [HeaderBesideList], for a header that fits itself to it
 * ([Landscape.headerCover]). Unspecified where the header is above its list, as it is upright.
 */
@Composable
fun rememberHeaderPaneWidth(): Dp {
    if (!rememberHeaderBeside()) return Dp.Unspecified
    val landscape = LocalLandscape.current
    val insets = LocalPlayerAwareWindowInsets.current
    val direction = LocalLayoutDirection.current
    return with(LocalDensity.current) {
        (landscape.windowWidth - insets.getLeft(this, direction).toDp() - insets.getRight(this, direction).toDp()) / 2
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

/**
 * Two things that each ask for the whole width, on one line with a half each: the filter chips
 * and the sort row of a library screen, which upright are two lines one above the other.
 */
@Composable
fun SideBySide(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    }
}

/**
 * A page under a header (an album, a playlist, an artist) on a phone on its side. Upright the
 * header is the first row of the list. In a short window that row is all the window shows, and
 * the songs start below its edge, so here it stands in the left half, scrolling by itself where it
 * is taller than the window, and the list has the right half to itself.
 *
 * With no [header] the list has the whole width: a playlist being searched, whose header is gone
 * from above it upright too. [list] is handed the modifier that makes it its half, and pads its
 * own content with [paneInsets]. [headerInsets] is what the header keeps clear of.
 */
@Composable
fun HeaderBesideList(
    header: (@Composable () -> Unit)?,
    headerInsets: WindowInsets = headerPaneInsets(),
    list: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
    ) {
        if (header != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(headerInsets)
            ) { header() }
        }
        list(Modifier.weight(1f))
    }
}

/**
 * What the list's half of [HeaderBesideList] keeps clear of above and below: the mini player,
 * and the top bar. With [underTopBar] false it is the status bar alone above, for a half the bar
 * has nothing floating over: an album's songs, whose bar is the back button and stands over the
 * other half. Such a list starts with [paneTop].
 */
@Composable
fun paneInsets(underTopBar: Boolean = true): WindowInsets {
    val insets = LocalPlayerAwareWindowInsets.current
    return if (underTopBar) {
        insets.only(WindowInsetsSides.Vertical)
    } else {
        WindowInsets.systemBars.only(WindowInsetsSides.Top).add(insets.only(WindowInsetsSides.Bottom))
    }
}

/**
 * What the header's half of [HeaderBesideList] keeps clear of. Below it is the gesture bar and
 * no more: the mini player sits under the other half ([Landscape.panelSpan]) and is never over
 * this one, and a header that left room for it all the same lost 64dp of a window that has 400,
 * which on most phones is the difference between its Play button being in sight and not. Above
 * it is the top bar, or nothing with [underTopBar] false, for a picture that runs up under the
 * status bar as it does upright.
 */
@Composable
fun headerPaneInsets(underTopBar: Boolean = true): WindowInsets {
    val bottom = WindowInsets.systemBars.only(WindowInsetsSides.Bottom)
    return if (underTopBar) {
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top).add(bottom)
    } else {
        bottom
    }
}

/**
 * The first row of a list whose first row would otherwise be a song: a small gap under the
 * status bar, and the row the list holds on to. A lazy list keeps its first visible row where it
 * is when rows arrive above it. Upright that row is the header. With a song there instead, an
 * album whose first songs were the last to load opened scrolled down to the one that came first.
 */
fun LazyListScope.paneTop() {
    item(key = "pane top", contentType = "pane top") {
        Spacer(Modifier.height(8.dp))
    }
}
