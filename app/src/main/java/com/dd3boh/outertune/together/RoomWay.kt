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
 * played: a guest who had paused heard the room's song aloud with no tap. Since the rooms of 26 Nov 2026 (Spotify's Jam,
 * RoomListen) a guest's pause is a mute, and nobody goes their own way: [OWN_WAY] is read from older files only.
 */
enum class RoomWay {
    /** With the room, and playing with it. */
    PLAYING,

    /** With the room, not playing here: the room is paused, or nothing has played here yet. */
    FOLLOWING,

    /** Paused here by the phone while the room plays (a call, headphones out): Play takes it to where the room is (PauseHere). */
    PAUSED_HERE,

    /** In a file from before 26 Nov 2026: on its own way until Catch up. Comes back paused here. */
    OWN_WAY,

    /** Muted here by its listener, in step with the room: unmuting is where the room is. */
    MUTED;

    companion object {
        /** The way of a phone that follows: [pausedHere] and [muted] as its session has them, [playing] as its player is. */
        fun of(pausedHere: Boolean, muted: Boolean, playing: Boolean): RoomWay = when {
            pausedHere -> PAUSED_HERE
            muted -> MUTED
            playing -> PLAYING
            else -> FOLLOWING
        }

        /**
         * How a room comes back after the service that had it ended, from the way kept with it ([saved]; null in a file
         * from before 26 Nov 2026). Muted, or paused here, as it was; on its own way in an older file, paused here. With
         * the room and playing, it goes on with the room. Else paused here: nothing plays aloud by itself as the app opens
         * again, and Play takes it to where the room is by then.
         */
        fun afterRestart(saved: RoomWay?): RoomWay = when (saved) {
            null, FOLLOWING, OWN_WAY -> PAUSED_HERE
            else -> saved
        }
    }
}
