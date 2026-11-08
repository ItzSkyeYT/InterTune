/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player

/**
 * When the player passes over a song the listener chose not to play (DontPlay) that has just
 * become current: only where it came to the song by itself, at the end of the song before or by
 * next and previous. Never the song a queue starts on, one tapped in the queue sheet, one put in
 * by hand (Play next, Add to queue) nor the same song again under repeat one: the listener picked
 * those. While casting no tap is known, so a move by next or previous plays the song too.
 *
 * Where it goes from there is BlockedSkip's, as for a blocked artist's song.
 */
object DontPlaySkip {
    /**
     * @param reason why the song became current, media3's MEDIA_ITEM_TRANSITION_REASON_*.
     * @param chosen whether the move was the listener's own pick of it, a tap in the queue sheet.
     * @param byHand whether the listener put the song in the queue by hand.
     * @param casting whether the music is on a cast receiver, which says nothing of taps.
     */
    fun passes(reason: Int, chosen: Boolean, byHand: Boolean, casting: Boolean): Boolean = !byHand && when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> true
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> !chosen && !casting
        else -> false
    }
}
