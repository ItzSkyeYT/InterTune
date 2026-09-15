/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player

/**
 * How far the listener has been carried, rather than how far they have chosen to go.
 *
 * The engine multiplies a listen's worth by how much the listener chose it, and this number is
 * what that rests on: a song sixth down a radio chain is worth a fraction of one that was picked.
 * No Android in here, so it is tested.
 *
 * The rule the player cannot express on its own is the difference between next and previous. Both
 * arrive as [Player.MEDIA_ITEM_TRANSITION_REASON_SEEK], so previous used to take the branch
 * written for next and the depth grew. That had it exactly backwards: reaching for a song again is
 * about the strongest thing a listener can say about it, and it was making the app value that play
 * less than the one before it. The queue index is the only thing that separates the two.
 */
object AutoplayDepth {

    /** A seek that lands earlier in the queue than it started: the listener went back for this. */
    fun wentBack(reason: Int, lastIndex: Int, newIndex: Int): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && lastIndex >= 0 && newIndex < lastIndex

    /**
     * The depth this song starts at.
     *
     * [chosen] is a new queue or a tap in the queue sheet, both of which mark the choice before the
     * transition arrives. A repeat is the same choice again, so it holds. Everything else is one
     * step further from whatever the listener last asked for.
     */
    fun next(current: Int, chosen: Boolean, wentBack: Boolean, reason: Int): Int = when {
        chosen || wentBack || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> 0
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> current
        else -> current + 1
    }
}
