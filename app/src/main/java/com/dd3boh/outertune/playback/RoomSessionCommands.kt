/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player

/**
 * What the media session offers outside the app (the notification, the lock screen, a headset, a watch, a car) while
 * this phone only listens in a listening room (listensOnly, his word of 26 Nov 2026, Spotify's Jam): no previous, next,
 * move in the song, shuffle or repeat. Given per controller with MediaSession.setAvailableCommands, which media3 also
 * applies to the platform session for the notification's controller; never by wrapping the session player's listeners,
 * which once cut every event but one off from the session (review of 27 Nov 2026). Play and pause stay: a pause there is
 * a guest's mute (SessionPlayer.pause).
 */
internal object RoomSessionCommands {
    val LOCKED = intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
    )

    /** [base], the commands a controller is given, without [LOCKED] while a room [locked] them. */
    fun playerCommands(base: Player.Commands, locked: Boolean): Player.Commands =
        if (!locked) base else base.buildUpon().removeAll(*LOCKED).build()

    /** The notification's own buttons ([buttons]) without shuffle and repeat while a room [locked] them. */
    fun buttons(buttons: List<MediaControlButton>, locked: Boolean): List<MediaControlButton> =
        if (!locked) buttons else buttons.filter { it != MediaControlButton.SHUFFLE && it != MediaControlButton.REPEAT }
}
