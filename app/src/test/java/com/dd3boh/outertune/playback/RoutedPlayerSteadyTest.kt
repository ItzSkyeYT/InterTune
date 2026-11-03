/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The routed player never passes on a pair media3's state refuses, each of which closed the app on
 * the main thread: "loading" from an idle or ended ExoPlayer (18 Oct 2026, a song removed from the
 * queue), and an empty queue while buffering or playing (#73, 3 Nov 2026, every song removed).
 */
class RoutedPlayerSteadyTest {
    private fun player(state: Int, loading: Boolean = false, timeline: Timeline = OneSong): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "isLoading" -> loading
                "getCurrentTimeline" -> timeline
                else -> null
            }
        } as Player

    /** A queue of one song: all the routed player asks of it is whether it is empty. */
    private object OneSong : Timeline() {
        override fun getWindowCount() = 1
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long) = window
        override fun getPeriodCount() = 1
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) = period
        override fun getIndexOfPeriod(uid: Any) = 0
        override fun getUidOfPeriod(periodIndex: Int): Any = 0
    }

    @Test
    fun `never loading while idle or ended`() {
        assertFalse(RoutedPlayer.Steady(player(Player.STATE_ENDED, loading = true)).isLoading)
        assertFalse(RoutedPlayer.Steady(player(Player.STATE_IDLE, loading = true)).isLoading)
    }

    @Test
    fun `loading as it says otherwise`() {
        assertTrue(RoutedPlayer.Steady(player(Player.STATE_BUFFERING, loading = true)).isLoading)
        assertTrue(RoutedPlayer.Steady(player(Player.STATE_READY, loading = true)).isLoading)
        assertFalse(RoutedPlayer.Steady(player(Player.STATE_READY, loading = false)).isLoading)
    }

    @Test
    fun `an empty queue reads as ended, never as playing or buffering`() {
        assertEquals(Player.STATE_ENDED, RoutedPlayer.Steady(player(Player.STATE_READY, timeline = Timeline.EMPTY)).playbackState)
        assertEquals(Player.STATE_ENDED, RoutedPlayer.Steady(player(Player.STATE_BUFFERING, timeline = Timeline.EMPTY)).playbackState)
        assertFalse(RoutedPlayer.Steady(player(Player.STATE_BUFFERING, loading = true, timeline = Timeline.EMPTY)).isLoading)
    }

    @Test
    fun `idle stays idle, and a queue with songs keeps its state`() {
        assertEquals(Player.STATE_IDLE, RoutedPlayer.Steady(player(Player.STATE_IDLE, timeline = Timeline.EMPTY)).playbackState)
        assertEquals(Player.STATE_READY, RoutedPlayer.Steady(player(Player.STATE_READY)).playbackState)
        assertEquals(Player.STATE_BUFFERING, RoutedPlayer.Steady(player(Player.STATE_BUFFERING)).playbackState)
    }
}
