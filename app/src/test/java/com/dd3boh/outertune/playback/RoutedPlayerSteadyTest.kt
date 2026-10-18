/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The routed player never sees "loading" from an idle or ended ExoPlayer: media3's state refuses
 * that pair, and a song removed from the queue closed the app with it (18 Oct 2026).
 */
class RoutedPlayerSteadyTest {
    private fun player(state: Int, loading: Boolean): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "isLoading" -> loading
                else -> null
            }
        } as Player

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
}
