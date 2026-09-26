/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** What ends a sleep timer set to the end of the song. */
class SleepTimerSongEndTest {

    // Never called: the timer here has no fade, and finishing is replaced below.
    private val player = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java)
    ) { _, method, _ -> error("unexpected call to ${method.name}") } as Player

    private var finished = 0

    private fun armed() = SleepTimer(CoroutineScope(Dispatchers.Unconfined), player).apply {
        fadeEnabled = false
        onFinish = { finished++ }
        start(-1)
    }

    @Test
    fun `the song ending by itself ends the timer`() {
        val timer = armed()
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `a song starting over on repeat ends it too`() {
        val timer = armed()
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals(1, finished)
    }

    @Test
    fun `next or previous carries the timer over to the song chosen`() {
        val timer = armed()
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(0, finished)
        assertTrue(timer.isActive)
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, finished)
    }

    @Test
    fun `starting a new queue carries it over as well`() {
        val timer = armed()
        timer.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(0, finished)
        assertTrue(timer.isActive)
    }

    @Test
    fun `the queue running out ends it`() {
        val timer = armed()
        timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }
}
