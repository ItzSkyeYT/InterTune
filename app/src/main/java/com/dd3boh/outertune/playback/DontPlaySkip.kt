/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue

/**
 * When the player passes over a song the listener chose not to play (DontPlay) that has just
 * become current: only where it came to the song by itself, at the end of the song before or by
 * next and previous, or where a list or an album started whole begins with it, with its Play or
 * Shuffle button. Never a song picked: the one a queue starts on when it was tapped, started from
 * or the only song of its list, one tapped in the queue sheet, one put in by hand (Play next, Add
 * to queue), nor the same song again under repeat one. While casting no tap is known, so a move by
 * next or previous plays the song too.
 *
 * Where it goes from there is BlockedSkip's, as for a blocked artist's song.
 */
object DontPlaySkip {
    /**
     * @param reason why the song became current, media3's MEDIA_ITEM_TRANSITION_REASON_*.
     * @param chosen whether the move was the listener's own pick of it, a tap in the queue sheet.
     * @param byHand whether the listener put the song in the queue by hand.
     * @param casting whether the music is on a cast receiver, which says nothing of taps.
     * @param startPicked for a queue just loaded, whether the song it starts on was picked
     * ([startIsPick]). A queue restored at launch or handed over by the car says nothing, and is.
     */
    fun passes(reason: Int, chosen: Boolean, byHand: Boolean, casting: Boolean, startPicked: Boolean): Boolean = !byHand && when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> true
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> !chosen && !casting
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> !startPicked
        else -> false
    }

    /**
     * Whether the song [queue] starts on is one the listener picked: a song tapped in a list
     * ([Queue.songPicked]), the song a radio is started from ([Queue.preloadItem]), or the only
     * song of a list. Not the first song of a list or an album of more, started whole with its
     * Play or Shuffle button: that one is the list's or the shuffle's.
     */
    fun startIsPick(queue: Queue): Boolean =
        queue.songPicked || queue.preloadItem != null || (queue as? ListQueue)?.items?.size == 1
}
