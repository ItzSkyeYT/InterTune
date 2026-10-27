/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.component.collapsedAnchor
import com.dd3boh.outertune.ui.component.expandedAnchor

/** Where a Back from the player's queue goes. See [queueBack]. */
internal enum class QueueBack {
    /** Out of the selection of songs, the queue staying as it is. */
    LEAVE_SELECTION,

    /** Out of the search, the queue staying open. */
    LEAVE_SEARCH,

    /** The queue goes down, to the song view: the cover or the lyrics. */
    SONG_VIEW,

    /**
     * The whole player closes in one step, as two swipes down would, and the queue stays open in
     * it, so that the player opens on the queue next time.
     */
    CLOSE_PLAYER,
}

/**
 * Where Back goes from the open queue (issue #30).
 *
 * Out of a selection of songs first, then out of a search, as it always was. Those two are
 * QueueContent's own handlers, and QueueContent is the queue sheet's content, so they are added
 * after the sheet's handler and go before it. Then the queue goes down to the song view, or, with
 * "Back from the queue closes the player" on ([closesPlayer]), the whole player closes and the
 * queue stays open in it.
 *
 * Not in a small window, a keypad phone's or a floating one ([compact]): the queue's bar has no
 * controls there, and Back is how the keys get from the queue to the player's. Nor on a tablet on
 * its side ([tabletPane]), whose queue is beside the player all the time, and whose sheet is a page
 * further in, with the saved queues.
 *
 * The queue's sheet being down or on its way down, and the player's, are the sheets' own business
 * (BottomSheetState.takesBack): Back is then not the queue's at all.
 */
internal fun queueBack(
    closesPlayer: Boolean,
    compact: Boolean,
    tabletPane: Boolean,
    selecting: Boolean = false,
    searching: Boolean = false,
): QueueBack = when {
    selecting -> QueueBack.LEAVE_SELECTION
    searching -> QueueBack.LEAVE_SEARCH
    closesPlayer && !compact && !tabletPane -> QueueBack.CLOSE_PLAYER
    else -> QueueBack.SONG_VIEW
}

/**
 * Where the queue's sheet starts when the app does: up if it was left open with "Back from the
 * queue closes the player" on, so that after a restart the player opens on the queue as it did
 * before. Down otherwise, as it always has.
 */
internal fun queueSheetStartAnchor(closesPlayer: Boolean, leftOpen: Boolean): Int =
    if (closesPlayer && leftOpen) expandedAnchor else collapsedAnchor

/**
 * What to store of where the queue was left, from where its sheet was last sent ([anchor]):
 * whether it is open. Null, nothing to store, with "Back from the queue closes the player" off,
 * which never reads it either.
 */
internal fun queueLeftOpenToStore(closesPlayer: Boolean, anchor: Int): Boolean? =
    if (closesPlayer) anchor == expandedAnchor else null
