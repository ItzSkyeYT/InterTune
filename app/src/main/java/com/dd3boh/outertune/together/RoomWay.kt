/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import androidx.media3.common.Player

/**
 * How this phone listens in a room someone else is in control of, kept so that a room that comes back as a new session
 * keeps it (26 Nov 2026): the room moved to a new code when its host sent someone out (MusicService.moveTogether), or
 * joined again after the service ended (RoomMemory, MusicService.rejoinSavedRoom). A new session followed the room and
 * played: a guest who had paused just for themselves heard the room's song aloud with no tap, and one on their own music
 * had the room's queue put in its place.
 */
enum class RoomWay {
    /** With the room, and playing with it. */
    PLAYING,

    /** With the room, not playing here: the room is paused, or nothing has played here yet. */
    FOLLOWING,

    /** Paused here by its listener while the room plays on: Play takes it to where the room is (PauseHere). */
    PAUSED_HERE,

    /** On its own way until Catch up. */
    OWN_WAY;

    companion object {
        /** The way of a phone that follows: [ownWay] and [pausedHere] as its session has them, [playing] as its player is. */
        fun of(ownWay: Boolean, pausedHere: Boolean, playing: Boolean): RoomWay = when {
            ownWay -> OWN_WAY
            pausedHere -> PAUSED_HERE
            playing -> PLAYING
            else -> FOLLOWING
        }

        /**
         * How a room comes back after the service that had it ended, from the way kept with it ([saved]; null in a file
         * from before 26 Nov 2026). On its own way, or paused here, as it was. With the room and playing, it goes on with
         * the room. Else paused here: nothing plays aloud by itself as the app opens again, and Play takes it to where the
         * room is by then.
         */
        fun afterRestart(saved: RoomWay?): RoomWay = if (saved == null || saved == FOLLOWING) PAUSED_HERE else saved
    }
}

/**
 * A phone that follows a room while nobody plays for it (TogetherSession.goAlone, 26 Nov 2026): whoever was in control went
 * quiet, or the host is away. It plays on its own meanwhile, on its own way, and when someone plays for the room again it
 * comes back by itself only if its listener did nothing of their own meanwhile and nothing it hears would change.
 */
internal object NobodyPlays {
    /**
     * Someone plays [roomSong] for the room again: whether this phone, on its own way only because nobody played, goes back
     * with the room by itself. Yes when it plays nothing ([playWhenReady] off, or its player stopped or at its end), or
     * plays that very song. Not when it plays another one: never pulled out of the middle of a song, Catch up offered.
     */
    fun backWithRoom(playWhenReady: Boolean, playbackState: Int, songHere: String?, roomSong: String): Boolean =
        !playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || songHere == roomSong
}
