/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.QueuePeekHeight

/**
 * The window height below which the stacked player is laid out for a small screen.
 *
 * 480dp is where Material's compact window height class ends. A phone held upright is never that
 * short: the smallest phone screens still around, 480 x 800px and 480 x 854px at 240dpi and
 * 720 x 1280px at 320dpi, are 533dp, 569dp and 640dp tall. What does come under it is the near
 * square screen of a keypad phone, 320 x 427dp whether it is 240 x 320px at 120dpi or
 * 480 x 640px at 240dpi, and a floating window on an ordinary phone. Those have no height to give
 * a fixed queue strip: about 144dp of it, a third of a keypad phone's screen, left the cover a
 * thumbnail and the controls squeezed together.
 *
 * Only upright or square windows. Every phone on its side is shorter than this, and a phone too
 * narrow on its side for the two-pane layout, a 16:9 one with its navigation bar beside the
 * screen, gets the stacked layout instead. That is an ordinary phone, which this leaves exactly as
 * it was.
 */
internal val CompactPlayerHeight = 480.dp

/**
 * The invisible strip along the bottom of a small player that still pulls the queue up.
 *
 * It lies over the gap the controls always leave under them, the same 24dp, so it takes no height
 * of its own and sits over nothing that can be pressed.
 */
internal val CompactQueueGrab = 24.dp

/**
 * How the player makes room for the queue sheet.
 *
 * @property compact whether the player is laid out for a small screen, see [CompactPlayerHeight].
 * @property reserve the height the player keeps clear at the bottom of the screen.
 * @property sheetDismissed the queue sheet's dismissed bound.
 * @property sheetCollapsed the queue sheet's collapsed bound, how much of it shows at rest.
 * @property showHandle whether the collapsed sheet shows its arrow.
 * @property queueButton whether the controls carry a button that opens the queue.
 * @property artworkPadding the vertical padding around swipeable artwork.
 * @property compactSheet whether the open queue sheet is laid out for a small window.
 */
internal data class PlayerQueueLayout(
    val compact: Boolean,
    val reserve: Dp,
    val sheetDismissed: Dp,
    val sheetCollapsed: Dp,
    val showHandle: Boolean,
    val queueButton: Boolean,
    val artworkPadding: Dp,
    val compactSheet: Boolean,
)

/**
 * Where the queue goes in the player, from the height of the window it is in.
 *
 * Normally the collapsed queue sheet shows a strip [peek] taller than the peek itself, with its
 * arrow at the top, and the player stays clear of all of it. The two-pane layouts collapse it to
 * the bare peek, see `queueReserve` in [BottomSheetPlayer].
 *
 * A small player, see [CompactPlayerHeight], gives the strip up. The player keeps nothing clear
 * for it beyond the gap under its controls, the sheet rests there as an invisible [CompactQueueGrab]
 * that a swipe up or a tap still opens, and the swipeable artwork drops the padding that kept it
 * off the strip. A keypad phone may have no touch screen at all, so the queue button joins the
 * controls there, where the keys can reach it, whether or not the queue is set to open from a
 * button. The open sheet is laid out small there too, see `compact` on [QueueSheet]: a keypad
 * phone's screen had room for the queue's title and transport controls and not one song.
 *
 * Only while a song is loaded. Without one there are no controls and so no button, and the
 * player has the height to spare for the usual strip, whose arrow the keys can reach.
 *
 * With the queue on its button ([queueAsButton], and a song loaded), the sheet collapses to
 * nothing, as it always has.
 *
 * @param windowHeight the height of the window the player fills.
 * @param bottomInset the system bars' height at the bottom of the window.
 * @param landscape whether the window is wider than it is tall.
 * @param twoPane whether the player is one of the two-pane layouts, landscape or tablet.
 */
internal fun playerQueueLayout(
    windowHeight: Dp,
    bottomInset: Dp,
    landscape: Boolean,
    twoPane: Boolean,
    queueAsButton: Boolean,
    songLoaded: Boolean,
    peek: Dp = QueuePeekHeight,
): PlayerQueueLayout {
    val compact = !landscape && !twoPane && songLoaded && windowHeight < CompactPlayerHeight
    val onButton = queueAsButton && songLoaded
    val reserve = when {
        compact -> bottomInset + CompactQueueGrab
        twoPane -> peek + bottomInset
        else -> peek * 2 + bottomInset
    }
    val dismissed = if (compact) reserve else peek + bottomInset
    return PlayerQueueLayout(
        compact = compact,
        reserve = reserve,
        sheetDismissed = if (onButton) 0.dp else dismissed,
        sheetCollapsed = if (onButton) 0.dp else reserve,
        showHandle = !onButton && !compact,
        queueButton = queueAsButton || compact,
        artworkPadding = if (compact) 0.dp else peek / 2,
        compactSheet = compact,
    )
}

/**
 * The smallest cover a small player draws, the mini player's.
 *
 * At 240 x 320dp, a keypad phone at 160dpi, the controls need all the height there is, and the
 * cover was left a strip a few pixels tall.
 */
internal val CompactCoverMinHeight = 48.dp

/** Whether a small player draws its cover in the [height] the controls leave for it. */
internal fun compactCoverFits(height: Dp): Boolean = height >= CompactCoverMinHeight

/**
 * The sizes of the transport controls under the seek bar.
 *
 * @property playButton the play button's side.
 * @property playIcon the play and pause icon inside it.
 * @property playingCorner the play button's corner radius while playing.
 * @property pausedCorner its corner radius while paused, round at the ordinary sizes.
 * @property transportIcon the skip, shuffle and repeat icons.
 * @property gapAboveTransport the space between the times under the seek bar and the controls.
 */
internal data class PlayerControlSizes(
    val playButton: Dp,
    val playIcon: Dp,
    val playingCorner: Dp,
    val pausedCorner: Dp,
    val transportIcon: Dp,
    val gapAboveTransport: Dp,
)

/**
 * The transport controls' sizes: larger in landscape, where there is room for them, and smaller in
 * a small player, see [CompactPlayerHeight].
 *
 * A small player's play button is 56dp, Material's ordinary floating button. There every dp of
 * height the controls do not take goes to the cover, and at 72dp the play button and the gap above
 * it took 84dp of a keypad phone's 355dp. The other icons keep their size.
 */
internal fun playerControlSizes(landscapePlayer: Boolean, compact: Boolean): PlayerControlSizes = when {
    compact -> PlayerControlSizes(
        playButton = 56.dp,
        playIcon = 28.dp,
        playingCorner = 18.dp,
        pausedCorner = 28.dp,
        transportIcon = 32.dp,
        gapAboveTransport = 4.dp,
    )

    landscapePlayer -> PlayerControlSizes(
        playButton = 84.dp,
        playIcon = 36.dp,
        playingCorner = 24.dp,
        pausedCorner = 36.dp,
        transportIcon = 42.dp,
        gapAboveTransport = 12.dp,
    )

    else -> PlayerControlSizes(
        playButton = 72.dp,
        playIcon = 36.dp,
        playingCorner = 24.dp,
        pausedCorner = 36.dp,
        transportIcon = 32.dp,
        gapAboveTransport = 12.dp,
    )
}

/** The narrowest each of Connected's buttons under a small player's controls gets. */
internal val CompactQuickActionMinWidth = 40.dp

/**
 * How wide each of the [count] buttons in Connected's row under a small player's controls is, in
 * a row [rowWidth] wide with [gap] between them: [preferred], their width everywhere else, where
 * that fits, and otherwise an even share of the row. At 240dp across, a keypad phone at 160dpi,
 * the four of them at full width ran over each other.
 */
internal fun compactQuickActionWidth(rowWidth: Dp, count: Int, gap: Dp, preferred: Dp): Dp =
    ((rowWidth - gap * (count - 1)) / count).coerceIn(CompactQuickActionMinWidth, preferred)

/**
 * The narrowest window whose mini player still has a previous button.
 *
 * 320dp is the narrowest an ordinary phone held upright gets. Below it, on a keypad phone at
 * 240 x 320dp or in a narrow floating window, the cover and three 48dp buttons left the title a
 * sliver too narrow for a single letter. The full player keeps previous, a tap away.
 */
internal val MiniPlayerPreviousMinWidth = 320.dp

/** Whether the mini player in a window [width] wide shows its previous button. */
internal fun miniPlayerShowsPrevious(width: Dp): Boolean = width >= MiniPlayerPreviousMinWidth

/**
 * The width of the window the app is drawn in, from the same Configuration the player's other
 * layout choices come from: its orientation, and the width `supportsWideScreen` reads.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
@ReadOnlyComposable
internal fun windowWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp
