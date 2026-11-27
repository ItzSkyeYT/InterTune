/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * How a guest who only listens in a room is muted and comes back (his word of 26 Nov 2026, Spotify's Jam; the review of
 * 27 Nov 2026). Only the listener's own pause is a mute: the screens' button (PlayerConnection.togglePlayPause), and a
 * pause asked through the media session, by the notification, a headset, a watch or a car (SessionPlayer.pause). A pause
 * the app makes itself (a swipe from recents, the mini player swiped away, Recognise, a stall, an error, the sleep
 * timer) or the phone makes (a call, headphones out) is a pause here: Play takes it back to where the room is.
 */
internal object RoomListen {
    /**
     * Whether a pause asked through the media session is a mute in its place: this phone only [listens], the room plays
     * ([roomPlaying], null before its word), and the phone is not paused here already ([pausedHere]).
     */
    fun pauseMutes(listens: Boolean, roomPlaying: Boolean?, pausedHere: Boolean): Boolean =
        listens && roomPlaying == true && !pausedHere

    /**
     * Whether the mute goes with a play: the listener's own ([byHand]) took a phone paused here ([wasPausedHere]) back to
     * the room ([pausedHere] now false). Its button said Play, not Unmute: a mute left on would play silence.
     */
    fun playUnmutes(wasPausedHere: Boolean, pausedHere: Boolean, byHand: Boolean, muted: Boolean): Boolean =
        wasPausedHere && !pausedHere && byHand && muted

    /**
     * The shuffle a phone's buttons show and ask the opposite of: the room's ([roomShuffle], as the one in control last
     * said it) while it [follows] the room, its own player's ([playerShuffle]) otherwise. A guest's copy of the room's
     * queue is in the room's order already, its own player's shuffle off, so the player's flag said off for good and
     * every tap asked for shuffle on (review of 27 Nov 2026).
     */
    fun shownShuffle(follows: Boolean, roomShuffle: Boolean?, playerShuffle: Boolean): Boolean =
        if (follows && roomShuffle != null) roomShuffle else playerShuffle
}
