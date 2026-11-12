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
 * the service never showed its notification in time (30 s from Android 13, 10 s on 9 to 12L:
 * nothing to play, or a resumption still being read). Reproduced on a Pixel 5 with the debug
 * build's files/fault-resume.
 */
class MediaButtonStartTest {

    private val dir = File("src/main/java/com/dd3boh/outertune/playback")
    private fun source(name: String) = File(dir, name).readText()
    private fun body(source: String, start: String) = source.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `Android's time is 5 s on Android 8, 10 s from 9 to 12L and 30 s from 13`() {
        // AOSP: ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT at android-8.1.0_r1, 9, 10, 12 and
        // 12.1; ActivityManagerConstants.DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS from 13 to 17.
        for (sdk in 26..27) assertEquals("$sdk", 5_000L, MediaButtonStart.limitMs(sdk))
        for (sdk in 28..32) assertEquals("$sdk", 10_000L, MediaButtonStart.limitMs(sdk))
        for (sdk in 33..37) assertEquals("$sdk", 30_000L, MediaButtonStart.limitMs(sdk))
    }

    @Test
    fun `the guard looks before Android's time on every version, and a start held from the press gives up at 30 s`() {
        for (sdk in 26..37) {
            val limit = MediaButtonStart.limitMs(sdk)
            val guard = MediaButtonStart.guardMs(sdk, held = false)
            // A third of the time is left for what Android counts before the service sees the press.
            assertTrue("$sdk: $guard of $limit", guard > 0 && limit - guard >= limit / 3)
        }
        // Android 12 and 12L have 10 s, and the guard came at 25 s there (issues #116 and #131).
        assertEquals(5_000L, MediaButtonStart.guardMs(31, held = false))
        assertEquals(5_000L, MediaButtonStart.guardMs(32, held = false))
        assertEquals(20_000L, MediaButtonStart.guardMs(33, held = false))
        assertEquals(20_000L, MediaButtonStart.guardMs(37, held = false))
        for (sdk in 26..30) assertEquals("$sdk", 30_000L, MediaButtonStart.guardMs(sdk, held = true))
    }

    @Test
    fun `the press is seen where the service receives it, running before or not`() {
        val service = source("MusicService.kt")
        val start = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val press = start.indexOf("else if (intent.action == Intent.ACTION_MEDIA_BUTTON) holdForegroundForMediaButton(MediaButtonStart.keyOf(intent))")
        assertTrue(start, press >= 0 && press < start.indexOf("return super.onStartCommand("))
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("isRunning = true")
        assertFalse("not from onCreate any more", "holdForegroundForMediaButton" in onCreate)
        val hold = body(service, "private fun holdForegroundForMediaButton(key: Int?)")
        assertTrue(hold, "if (mediaButtonPending || foregroundNow()) return" in hold)
    }

    @Test
    fun `a play press on a player that has a song is looked at again, and no other key`() {
        // The widget's play button, the play button of media3's notification (a
        // PendingIntent.getForegroundService) and the receiver reach a paused service this way,
        // and a song that then does not start left Android waiting. Next, previous and pause come
        // by startService, with no demand.
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_HEADSETHOOK))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(MediaButtonStart.startsPlayback(null))

        val hold = body(source("MusicService.kt"), "private fun holdForegroundForMediaButton(key: Int?)")
        assertFalse("a song in the player no longer ends the look", "player.mediaItemCount > 0)) return" in hold)
        assertTrue(hold, "if (!MediaButtonStart.looksAgain(hasSong, key, Build.VERSION.SDK_INT)) return" in hold)
        val play = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val next = android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        // Nothing in the player: every start is looked at again, as before, whatever the key and the Android.
        for (sdk in 24..37) for (key in listOf(play, next, null)) assertTrue("$sdk $key", MediaButtonStart.looksAgain(hasSong = false, keyCode = key, sdk = sdk))
        // A song in the player: a play press, from Android 10, where the service can tell media3 is in the foreground.
        for (sdk in 29..37) assertTrue("$sdk", MediaButtonStart.looksAgain(hasSong = true, keyCode = play, sdk = sdk))
        for (sdk in 24..28) assertFalse("$sdk", MediaButtonStart.looksAgain(hasSong = true, keyCode = play, sdk = sdk))
        for (sdk in 24..37) for (key in listOf(next, null)) assertFalse("$sdk $key", MediaButtonStart.looksAgain(hasSong = true, keyCode = key, sdk = sdk))
        // What an earlier press left behind does not count for this one.
        val fresh = hold.indexOf("holdingForMediaButton = false")
        assertTrue(hold, fresh >= 0 && fresh < hold.indexOf("mediaButtonHandler.postDelayed(mediaButtonGuard"))
    }

    @Test
    fun `a service Android brings back after the process died meets what the old process owed, at once`() {
        // Android keeps its demand for the notification when the app's process dies before it is
        // met, restarts the service with no intent and waits again on the new process
        // (ActiveServices.performScheduleRestartLocked). That process recorded no caller, which is
        // how issue #137 looked, and nothing is coming in it that media3 would show.
        val service = source("MusicService.kt")
        val start = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val restart = start.indexOf("if (intent == null) settleAfterRestart()")
        assertTrue(start, restart >= 0 && restart < start.indexOf("return super.onStartCommand("))
        val settle = body(service, "private fun settleAfterRestart()")
        // Not over a press already being seen to, nor over media3 in the foreground.
        assertTrue(settle, "if (mediaButtonPending || foregroundNow()) return" in settle)
        // Under an id of its own, so a notification the old process left behind stays; met and
        // taken away in one go, with nothing to wait for.
        val meet = settle.indexOf("RESTART_NOTIFICATION_ID,\n                MediaButtonStart.placeholder(this, CHANNEL_ID),\n                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,")
        val leave = settle.indexOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)")
        assertTrue(settle, meet in 0 until leave)
        assertFalse("no look later: the time may be short", "postDelayed" in settle)
        // Android refuses it from the background when nothing was owed: caught, and the service
        // stays as Android brought it back.
        assertTrue(settle, "} catch (e: Exception) {" in settle)
        assertFalse(settle, "stopSelf()" in settle)
        assertTrue("const val NOTIFICATION_ID = 888" in service && "const val RESTART_NOTIFICATION_ID = 889" in service)
    }

    @Test
    fun `held at once only before Android 12 and only with nothing in the player`() {
        val service = source("MusicService.kt")
        val hold = body(service, "private fun holdForegroundForMediaButton(key: Int?)")
        assertTrue(hold, "val held = !hasSong && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && holdForeground()" in hold)
        assertTrue(hold, "mediaButtonHandler.postDelayed(mediaButtonGuard, MediaButtonStart.guardMs(Build.VERSION.SDK_INT, held))" in hold)
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
        assertTrue(guard, "if (holdingForMediaButton) mediaButtonHandler.postDelayed(mediaButtonLetGo, MediaButtonStart.LET_GO_MS)" in guard)
        assertTrue(guard, "holdingForMediaButton && resumptionRunning -> mediaButtonHandler.postDelayed(this, 10_000)" in guard)
        assertTrue(guard, "else -> giveUpMediaButtonStart(" in guard)
    }

    @Test
    fun `the plain notification goes a minute after a song that never started, and the service stays`() {
        assertEquals(60_000L, MediaButtonStart.LET_GO_MS)
        val letGo = body(source("MusicService.kt"), "private val mediaButtonLetGo = Runnable {")
        // Not while media3 holds the foreground itself or the song plays: the notification is then its own.
        val keep = letGo.indexOf("if (isPlaybackOngoing || activePlayer.isPlaying || !foregroundNow()) return@Runnable")
        val leave = letGo.indexOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)")
        assertTrue(letGo, keep in 0 until leave)
        assertFalse("a player with a song is not stopped", "stopSelf()" in letGo)
    }

    @Test
    fun `giving up meets Android's demand first, keeps a player with a song, and stops the service`() {
        val service = source("MusicService.kt")
        val giveUp = body(service, "private fun giveUpMediaButtonStart(why: String)")
        val keep = giveUp.indexOf("player.mediaItemCount > 0) return")
        val meet = giveUp.indexOf("if (!holdingForMediaButton && !foregroundNow()) holdForeground()")
        val leave = giveUp.indexOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)")
        assertTrue(giveUp, keep in 0 until meet && meet < leave)
        assertTrue("a player with a song keeps the guard, which still looks at it",
            keep < giveUp.indexOf("mediaButtonPending = false") && keep < giveUp.indexOf("mediaButtonHandler.removeCallbacks(mediaButtonGuard)"))
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
