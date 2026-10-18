/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player

/**
 * The one player the rest of the app holds while casting can happen: the media session, the
 * screens (PlayerConnection), the queue board and the sleep timer all talk to this, and it passes
 * everything on to the phone's own ExoPlayer or, while casting, to a RemotePlayer.
 *
 * Switching the session's player alone would not do, because the screens never go through the
 * session: they use MusicService.player directly, in some ninety places, and would go on driving
 * an ExoPlayer that has stopped. This is the shape media3's own CastPlayer has from 1.9 on, built
 * on the ForwardingSimpleBasePlayer.setPlayer that 1.8 already has. Each switch is reported to
 * whoever listens as the changes between the two players' states, so the screens and the
 * notification follow without being told.
 *
 * Only built with Unreleased.CASTING on. With it off MusicService.player is the ExoPlayer itself,
 * and nothing about playback goes through here.
 */
class RoutedPlayer(local: Player) : ForwardingSimpleBasePlayer(Steady(local)) {
    private var routed: Player = local

    /** Where calls go now: the player itself, not the [Steady] layer in front of it. */
    val target: Player get() = routed

    /** Sends every call from now on to [target]. Main thread only. */
    fun routeTo(target: Player) {
        routed = target
        setPlayer(Steady(target))
    }

    /**
     * [player] as it is, except that it is never loading while idle or ended. ExoPlayer can say
     * both for a moment, as when the song playing is taken out of the queue, and the state this
     * class builds from it refuses that pair: an IllegalArgumentException on the main thread that
     * closed the app (18 Oct 2026, a song removed from the queue).
     */
    internal class Steady(player: Player) : ForwardingPlayer(player) {
        override fun isLoading(): Boolean {
            val state = playbackState
            return super.isLoading() && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        }
    }
}
