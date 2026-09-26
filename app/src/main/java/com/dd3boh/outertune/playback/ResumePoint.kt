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
}
