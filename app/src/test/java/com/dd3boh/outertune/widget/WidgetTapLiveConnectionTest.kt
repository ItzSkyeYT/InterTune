/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The music service stops by itself when a queue ends with the app in the background, and the app's
 * screen keeps its connection to it until a new one arrives, a moment after the screen comes back.
 * A song tapped on the widget in that moment was handed to the stopped service, which plays nothing,
 * and the tap was spent: the app opened on silence (Pixel 5, 6 Nov 2026, every time once the service
 * had stopped). A tap, the Liked songs shortcut and a link now wait for a connection that is alive.
 */
class WidgetTapLiveConnectionTest {

    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun `a connection knows when its service has stopped`() {
        val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
        assertTrue("val isAlive: Boolean get() = !service.isDestroyed" in connection)
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("val isDestroyed: Boolean get() = destroyed" in service)
    }

    @Test
    fun `a widget tap is spent only on a live connection`() {
        val effect = main.substringAfter("LaunchedEffect(playerConnection, widgetSongPending, pendingWidgetTap.intent) {")
            .substringBefore("\n                    }\n")
        assertTrue(effect, "val live = playerConnection?.takeIf { it.isAlive }" in effect)
        assertEquals("both taps are played on the live one", 2, Regex("""playFromWidget\([^)]*, database, live, coroutineScope\)""").findAll(effect).count())
        assertTrue("if (live != null) widgetSongPending = false" in effect)
        assertTrue("if (live != null) pendingWidgetTap.intent = null" in effect)
        assertTrue("no tap is spent on the connection as it was", "if (playerConnection != null)" !in effect)
    }

    @Test
    fun `the Liked songs shortcut and links wait for a live connection too`() {
        assertTrue(main.contains("snapshotFlow { playerConnection?.takeIf { it.isAlive } }.filterNotNull().first()"))
        val link = main.substringAfter("LaunchedEffect(pendingLaunchLink, pendingLink.link, playerConnection, oobeStatus) {")
        assertTrue(link.substringBefore("snapshotFlow").contains("val connection = playerConnection?.takeIf { it.isAlive } ?: return@LaunchedEffect"))
    }
}
