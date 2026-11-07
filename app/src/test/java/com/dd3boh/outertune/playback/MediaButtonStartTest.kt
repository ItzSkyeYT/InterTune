/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #98: a play press from a headset with the app closed, and Android closed the app because
 * the service never showed its notification in time (30 s from Android 12: nothing to play, or a
 * resumption still being read). Reproduced on a Pixel 5 with the debug build's files/fault-resume.
 */
class MediaButtonStartTest {

    private val dir = File("src/main/java/com/dd3boh/outertune/playback")
    private fun source(name: String) = File(dir, name).readText()
    private fun body(source: String, start: String) = source.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the guard looks before Android's 30 s from Android 12, and gives up at 30 s before it`() {
        assertEquals(25_000L, MediaButtonStart.guardMs(31))
        assertEquals(25_000L, MediaButtonStart.guardMs(35))
        assertEquals(30_000L, MediaButtonStart.guardMs(30))
    }

    @Test
    fun `the press is seen where the service receives it, running before or not`() {
        val service = source("MusicService.kt")
        val start = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val press = start.indexOf("if (intent?.action == Intent.ACTION_MEDIA_BUTTON) holdForegroundForMediaButton()")
        assertTrue(start, press >= 0 && press < start.indexOf("return super.onStartCommand("))
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("isRunning = true")
        assertFalse("not from onCreate any more", "holdForegroundForMediaButton" in onCreate)
        val hold = body(service, "private fun holdForegroundForMediaButton()")
        assertTrue(hold, "if (mediaButtonPending || foregroundNow() || (::player.isInitialized && player.mediaItemCount > 0)) return" in hold)
    }

    @Test
    fun `held at once only before Android 12, where nothing keeps media3 from the foreground later`() {
        val service = source("MusicService.kt")
        val hold = body(service, "private fun holdForegroundForMediaButton()")
        assertTrue(hold, "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) holdForeground()" in hold)
        assertTrue(hold, "mediaButtonHandler.postDelayed(mediaButtonGuard, MediaButtonStart.guardMs(Build.VERSION.SDK_INT))" in hold)
        val held = service.substringAfter("private fun holdForeground(): Boolean = try {").substringBefore("    } catch")
        assertTrue(held, "NOTIFICATION_ID,\n            MediaButtonStart.placeholder(this, CHANNEL_ID),\n            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK," in held)
    }

    @Test
    fun `media3 decides its notification as before, with nothing in front of it`() {
        // Gated or forced while the plain notification was up, media3 still took it away for a
        // player it saw as empty and could not come back into the foreground from the background.
        val update = body(source("MusicService.kt"), "override fun onUpdateNotification(")
        assertFalse(update, "holdingForMediaButton" in update || "mediaButtonPending" in update)
    }

    @Test
    fun `the guard keeps Android's time with a song in, and gives up without one`() {
        val service = source("MusicService.kt")
        val guard = service.substringAfter("private val mediaButtonGuard = object : Runnable {").substringBefore("\n    }\n")
        assertTrue(guard, "!holdingForMediaButton && foregroundNow() -> mediaButtonPending = false" in guard)
        assertTrue(guard, "if (!holdingForMediaButton && !foregroundNow()) holdForeground()" in guard)
        assertTrue(guard, "holdingForMediaButton && resumptionRunning -> mediaButtonHandler.postDelayed(this, 10_000)" in guard)
        assertTrue(guard, "else -> giveUpMediaButtonStart(" in guard)
    }

    @Test
    fun `giving up meets Android's demand first, keeps a player with a song, and stops the service`() {
        val service = source("MusicService.kt")
        val giveUp = body(service, "private fun giveUpMediaButtonStart(why: String)")
        val keep = giveUp.indexOf("player.mediaItemCount > 0) return")
        val meet = giveUp.indexOf("if (!holdingForMediaButton && !foregroundNow()) holdForeground()")
        val leave = giveUp.indexOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)")
        assertTrue(giveUp, keep in 0 until meet && meet < leave)
        assertTrue(giveUp, "stopSelf()" in giveUp)
        assertTrue("posted, so media3's answer to the resumption is in the player first",
            "mediaButtonHandler.post { giveUpMediaButtonStart(why) }" in service)

        val callback = source("MediaLibrarySessionCallback.kt")
        val resumption = callback.substringAfter("override fun onPlaybackResumption(")
        assertTrue("service.letGoOfMediaButtonHoldSoon(\"no saved queue to resume\")" in resumption)
        assertTrue("service.letGoOfMediaButtonHoldSoon(\"the saved queue could not be read\")" in resumption)
        assertTrue("service.resumptionRunning = true\n        try {" in resumption)
        assertTrue("} finally {\n            service.resumptionRunning = false\n        }" in resumption)
    }

    @Test
    fun `the placeholder is made on media3's channel as media3 makes it`() {
        val start = source("MediaButtonStart.kt")
        assertTrue("NotificationManager.IMPORTANCE_LOW" in start)
        assertTrue("R.string.music_player" in start)
        val service = source("MusicService.kt")
        assertTrue("media3's notification uses the same channel and its name",
            Regex("""DefaultMediaNotificationProvider\(\s*this@MusicService,\s*\{ NOTIFICATION_ID },\s*CHANNEL_ID,\s*R\.string\.music_player""").containsMatchIn(service))
    }
}
