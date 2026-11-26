/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture

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

    /**
     * Called as a seek, a skip or a list of songs is asked of this player, before it goes on: what the screens and the
     * session (the notification, a headset, a car, a watch) ask, the listener's by hand. The service's own moves go to
     * [target] and never come here (MusicService.byHand, 26 Nov 2026). Main thread.
     */
    var onAsked: () -> Unit = {}

    /** Sends every call from now on to [target]. Main thread only. */
    fun routeTo(target: Player) {
        routed = target
        setPlayer(Steady(target))
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        onAsked()
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        onAsked()
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    /**
     * [player] as it is, except for two pairs ExoPlayer can report for a moment and the state this
     * class builds from it refuses, each an IllegalArgumentException on the main thread that closed
     * the app: loading while idle or ended (18 Oct 2026, a song removed from the queue), and an
     * empty queue while buffering or playing (#73, 3 Nov 2026, every song removed from the queue).
     * An empty queue reads as ended, where ExoPlayer itself goes next.
     */
    internal class Steady(player: Player) : ForwardingPlayer(player) {
        override fun getPlaybackState(): Int {
            val state = super.getPlaybackState()
            val stopped = state == Player.STATE_IDLE || state == Player.STATE_ENDED
            return if (!stopped && currentTimeline.isEmpty) Player.STATE_ENDED else state
        }

        override fun isLoading(): Boolean {
            val state = playbackState
            return super.isLoading() && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        }
    }
}
