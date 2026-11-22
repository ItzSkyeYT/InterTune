/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import androidx.media3.common.Player

/**
 * Catch up, and what could leave a guest where it was (dvd in #testers, 22 Nov 2026: "i can catch up but its not catching
 * up"). Catch up sends a guest that went its own way back to where the leader is, and three things undid it:
 *
 *  - Paused on its own, it caught up playing, and the room's queue, swapped in under the song a moment later, put back
 *    the pause it had seen when it was asked for: the guest ended paused here, silent. The swap now leaves the player as
 *    the room wants it ([swapPlays]).
 *  - Its player had stopped on an error while it was on its own, on the room's song (the app stops on a failed song
 *    unless skip on error is on). The room's look leaves a stopped player alone, so it stayed silent under "Following
 *    the host". Catch up starts it again ([restarts]); if the song fails here again, the room keeps it and says why.
 *  - A song change the room had asked for earlier and never got (the player taken elsewhere meanwhile) was still waited
 *    for at every look, which passed the leader's song by. Catch up asks for the leader's song afresh.
 */
internal object CatchUp {
    /** Whether Catch up starts the player again: stopped ([Player.STATE_IDLE]) on [playingId], the room's song. */
    fun restarts(playbackState: Int, playingId: String?, roomSongId: String?): Boolean =
        playbackState == Player.STATE_IDLE && playingId != null && playingId == roomSongId

    /**
     * Whether the player plays once the room's queue is in under the song: not while the listener paused it here, else
     * as the room does ([roomPlaying]), or as it does now ([playingHere]) before the room's first word.
     */
    fun swapPlays(pausedHere: Boolean, roomPlaying: Boolean?, playingHere: Boolean): Boolean =
        !pausedHere && (roomPlaying ?: playingHere)
}
