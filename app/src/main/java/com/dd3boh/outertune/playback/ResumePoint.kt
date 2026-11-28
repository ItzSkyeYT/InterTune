/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Where a queue should pick up again, decided apart from the player so it can be tested.
 *
 * A queue keeps one resume point, lastSongPos, and it is only true for the song at the queue's
 * current position. Both of these guard that: one against a player that is not holding the queue
 * at all, the other against a point that belonged to the song before.
 */
object ResumePoint {

    /**
     * The resume point to store for a queue when the service stops.
     *
     * The player's position only says something about the queue when the player is holding it,
     * on the song the queue calls current. A service run that never loaded the queue has an empty
     * player at position 0, and writing that over the saved point sent the next resume, from a
     * headset, the system's media controls or the app's own play button, back to the start of
     * the song. That happened whenever the app was opened and left without pressing play, and
     * whenever the car or the system bound to the service without playing anything.
     *
     * @param playerSongId the id of the song the player is on, null when it holds nothing
     * @param queueSongId the id of the song the queue calls current
     */
    fun onStop(playerSongId: String?, queueSongId: String?, playerPositionMs: Long, savedPositionMs: Long): Long =
        if (playerSongId != null && playerSongId == queueSongId) playerPositionMs else savedPositionMs

    /**
     * The resume point once the player has moved to another song, before the new place is saved.
     *
     * The point is written on a pause, and every song change saves the queue's new place with
     * whatever point it held, so pausing one song at 2:30, skipping, and having the process killed
     * brought the next song back at 2:30. A change of song starts it from the top, so the old point
     * goes. A repeat stays on the same song and keeps it. A new list keeps the position the player
     * was just given, because that is a resume point too: a queue restored at 40:00 of a mix is at
     * 40:00, and dropping that would lose it to a kill before the next pause.
     */
    fun afterTransition(reason: Int, playerPositionMs: Long, savedPositionMs: Long): Long = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> savedPositionMs
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
            if (playerPositionMs > 0) playerPositionMs else C.TIME_UNSET
        else -> C.TIME_UNSET
    }

    /**
     * Whether a saved queue had played to its end: it stands on the last of its [size] songs in the order they play
     * ([index]), at that song's end ([positionMs]). The song's length is the one it is stored with, in whole seconds
     * ([durationSec], 0 or less when unknown), and the player ends a song at its real length, which that rounds:
     * Highway to Hell ended at 208430 ms and is stored as 208 s. A point no more than [END_SLACK_MS] before it, or
     * past it, is the end.
     */
    fun playedToEnd(index: Int, size: Int, positionMs: Long, durationSec: Int): Boolean =
        size > 0 && index == size - 1 && durationSec > 0 && positionMs != C.TIME_UNSET &&
            positionMs >= durationSec * 1_000L - END_SLACK_MS

    /** How far before a song's stored length a saved point still counts as its end ([playedToEnd]). */
    const val END_SLACK_MS = 2_000L

    /**
     * Where Play loads a saved queue into an empty player: its song [index] at [positionMs], as saved, unless it had
     * played to its end ([playedToEnd]), when it starts again from its first song, as Play does on a queue that ends
     * while the app runs (playSafe). Loaded where it ended, Play played the last moment of its last song and stopped,
     * and only a second Play started it again: the widget's, and the mini player's after a restart (28 Nov 2026).
     */
    fun playFrom(index: Int, size: Int, positionMs: Long, durationSec: Int): Pair<Int, Long> =
        if (playedToEnd(index, size, positionMs, durationSec)) 0 to C.TIME_UNSET else index to positionMs
}
