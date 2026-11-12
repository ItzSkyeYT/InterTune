/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Glance ends a widget's session 45 seconds after it began, and a song changes minutes after the
 * last draw, so the change started a session through WorkManager (0.33 s on the Pixel 5) and
 * composed from cold (0.2 s more) before anything of the new song could show. The session is now
 * started a little before the song ends, by a draw that changes nothing.
 */
class WidgetWakeTest {
    @Test
    fun `a song playing with minutes left is woken for a little before it ends`() {
        assertEquals(168_000L, wakeDelayMs(remainingMs = 180_000, playing = true))
        assertEquals(1_000L, wakeDelayMs(remainingMs = 13_000, playing = true))
    }

    @Test
    fun `a song nearly over is woken for at once, since the change comes before any later wake`() {
        assertEquals(0L, wakeDelayMs(remainingMs = 12_000, playing = true))
        assertEquals(0L, wakeDelayMs(remainingMs = 5_000, playing = true))
        assertEquals(0L, wakeDelayMs(remainingMs = WAKE_TOO_LATE_MS, playing = true))
    }

    @Test
    fun `nothing is woken for when the song is not going to end by itself, or ends before a wake could help`() {
        assertNull("paused", wakeDelayMs(remainingMs = 180_000, playing = false))
        assertNull("the player cannot say", wakeDelayMs(remainingMs = null, playing = true))
        assertNull("it changes before a session could start", wakeDelayMs(remainingMs = WAKE_TOO_LATE_MS - 1, playing = true))
        assertNull(wakeDelayMs(remainingMs = 0, playing = true))
        assertNull(wakeDelayMs(remainingMs = -400, playing = true))
    }

    @Test
    fun `the wake is long enough before the change to be inside Glance's 45 seconds, and the store replaces the one before it`() {
        assertTrue(WAKE_BEFORE_END_MS in 8_000..30_000)
        val store = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()
        val wake = store.substringAfter("private fun wakeBeforeChange(").substringBefore("\n    }\n")
        assertTrue("waking?.cancel()" in wake)
        assertTrue("MusicWidget().updateAll(app)" in wake)
        assertTrue("hasWidgets(app)" in wake)
        // from setNowPlaying, once the lock is let go, and only for a widget on a home screen
        val set = store.substringAfter("suspend fun setNowPlaying(").substringBefore("\n    }\n")
        assertTrue("if (widgets) wakeBeforeChange(context, remaining, playingNow)" in set)
        // the service tells it how long the song has left
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val update = service.substringAfter("private fun updateWidget(").substringBefore("override fun onDestroy()")
        assertTrue("remainingMs = activePlayer.duration.takeIf { it > 0 }?.let { it - activePlayer.currentPosition }" in update)
    }
}
