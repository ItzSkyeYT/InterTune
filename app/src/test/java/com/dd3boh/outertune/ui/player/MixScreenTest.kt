/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.automix.MixOnScreen
import com.dd3boh.outertune.utils.makeTimeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The player's screen as a change starts playing as a mix: the stage says so (the change is
 * announced) a moment before the player names the incoming song, and from the very frame it does,
 * the screen shows the outgoing song's title and bar under the covers coming apart. On the Pixel 5
 * (8 Nov 2026, Lush Life into Lean On) one frame showed the incoming song's title and its bar at
 * 0:00 over the outgoing cover, while the words were still waiting for the change to be followed.
 */
class MixScreenTest {

    /** Where the player is in the song it names, in ms. */
    @Volatile private var position = 0L

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when {
            method.name == "getCurrentPosition" -> position
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == java.lang.Long.TYPE -> 0L
            method.returnType == java.lang.Integer.TYPE -> 0
            method.returnType == java.lang.Float.TYPE -> 0f
            method.returnType == java.lang.Double.TYPE -> 0.0
            else -> null
        }
    } as Player

    private fun song(id: String, seconds: Int) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = seconds, genre = null)

    private val lushLife = song("lush-life", 202)
    private val leanOn = song("lean-on", 178)

    /** As on the Pixel 5: from 166.8 s of the outgoing song, 36.1 s, the incoming song taking over half way. */
    private val change = MixOnScreen(
        id = 5, outgoing = lushLife, outgoingLengthMs = 201_141, outgoingFromMs = 166_800,
        incoming = leanOn, entryMs = 0, lengthMs = 36_100, handoverMs = 18_050, gains = null,
    )

    private val announced = mutableStateOf<MixOnScreen?>(null)
    private val current = mutableStateOf<MediaMetadata?>(lushLife)
    private val lyrics = mutableStateOf(false)
    private val failed = mutableStateOf(false)
    private val screen = MixScreen(player, announced, current, lyrics, failed)

    /** Frames on demand, for the words' fade: each one at once, at the clock's time. */
    private val frames = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = onFrame(System.nanoTime())
    }

    /** The bar as the seek bar draws it, with the player at [at] of the incoming song's 2:58. */
    private fun bar(at: Long) = MixLook.bar(screen.target, screen.showsOutgoing, at, length = 178_000, dragging = false)

    @Test
    fun `before the player names the incoming song, the screen is today's`() {
        position = 200_700
        announced.value = change
        assertNull(screen.target)
        assertFalse(screen.active)
        assertFalse(screen.showsOutgoing)
    }

    @Test
    fun `the frame the player names the incoming song, the title and the bar are still the outgoing song's`() {
        announced.value = change
        position = 200_700
        // The player names Lean On, 123 ms into it as the Pixel 5 had it; nothing has followed the change yet.
        current.value = leanOn
        position = 123
        assertEquals(change, screen.target)
        assertTrue(screen.active)
        assertTrue("the outgoing song's title, artists and buttons", screen.showsOutgoing)
        val bar = bar(position)
        assertEquals(166_923L, bar.at)
        assertEquals("the length its own bar showed a moment before", "3:21", makeTimeString(bar.length))
        // With the covers just starting to part, as the music has them.
        assertEquals(MixLook.covers(change, 123), screen.covers())
    }

    @Test
    fun `followed, the words stay on the outgoing song until the incoming one takes over, then fade through to it`() = runBlocking(frames) {
        announced.value = change
        current.value = leanOn
        position = 123
        val follow = launch { screen.follow(screen.target) { false } }
        delay(50)
        assertTrue("taken over as it was", screen.showsOutgoing)
        assertEquals(1f, screen.wordsAlpha, 0f)
        // Past the handover, the words fade out, change song and fade back in.
        position = 18_500
        withTimeout(5_000) { while (screen.showsOutgoing) delay(20) }
        withTimeout(5_000) { while (screen.wordsAlpha < 1f) delay(20) }
        assertEquals(MixLook.Bar(18_500, 178_000, 18_500f), bar(position))
        // The outgoing song gone and the covers together again: the screen is today's.
        position = 36_100L + MixLook.MORPH_MS
        withTimeout(5_000) { follow.join() }
        assertFalse(screen.active)
        assertFalse(screen.showsOutgoing)
    }

    @Test
    fun `dropped part way, the screen is today's at once`() = runBlocking(frames) {
        announced.value = change
        current.value = leanOn
        position = 5_000
        val follow = launch { screen.follow(screen.target) { false } }
        delay(50)
        assertTrue(screen.showsOutgoing)
        announced.value = null
        follow.cancel()
        screen.follow(screen.target) { false }
        assertNull(screen.target)
        assertFalse(screen.active)
        assertFalse(screen.showsOutgoing)
        assertEquals(MixLook.Bar(5_000, 178_000, 5_000f), bar(position))
    }

    @Test
    fun `with the lyrics up, the screen is today's`() {
        announced.value = change
        current.value = leanOn
        position = 123
        lyrics.value = true
        assertNull(screen.target)
        assertFalse(screen.showsOutgoing)
    }
}
