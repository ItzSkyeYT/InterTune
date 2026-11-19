/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #206 (0.12.11, a realme on Android 11): the phone's system failed to draw the player's
 * notification ("Couldn't inflate contentViews", a ConcurrentModificationException in its own
 * drawing) and Android closed the app for it. The buttons were sent again on every change to the
 * playing song's row, and each send had media3 post the notification anew. The same buttons are
 * no longer sent twice.
 */
class NotificationButtonsOnceTest {

    @Test
    fun `the same buttons are not sent again`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val update = service.substringAfter("fun updateNotification() {").substringBefore("\n    }\n")
        val same = update.indexOf("if (layout == sentLayout) return")
        val kept = update.indexOf("sentLayout = layout")
        val sent = update.indexOf("mediaSession.setCustomLayout(layout)")
        assertTrue(update, same >= 0 && same < kept && kept < sent)
        assertTrue("private var sentLayout: List<CommandButton>? = null" in service)
        // Still sent on every song change and setting change: the check is on what is sent, not on who asks.
        assertTrue("currentSong.collect(scope) {\n            updateNotification()" in service)
    }
}
