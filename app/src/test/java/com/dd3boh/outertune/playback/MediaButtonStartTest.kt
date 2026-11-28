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
    private fun body(source: String, start: String): String {
        assertTrue("not found: $start", start in source)
        return source.substringAfter(start).substringBefore("\n    }\n")
    }
    private val holdStart = "private fun holdForegroundForMediaButton(key: Int?, handedAt: Long?)"

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
        val press = start.indexOf("holdForegroundForMediaButton(MediaButtonStart.keyOf(intent), MediaButtonStart.handedAtOf(intent, flags))")
        assertTrue(start, press >= 0 && press < start.indexOf("return super.onStartCommand("))
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("isRunning = true")
        assertFalse("not from onCreate any more", "holdForegroundForMediaButton" in onCreate)
        val hold = body(service, holdStart)
        assertTrue(hold, "if (mediaButtonPending || foregroundNow()) return" in hold)
    }

    @Test
    fun `a play press on a player that has a song is looked at again, and no other key`() {
        // The widget's play button and the receiver reach a paused service this way (and the play
        // button of media3's notification did, as a PendingIntent.getForegroundService, until
        // PlainPlayNotifications), and a song that then does not start left Android waiting. Next,
        // previous and pause come by startService, with no demand.
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_HEADSETHOOK))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(MediaButtonStart.startsPlayback(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(MediaButtonStart.startsPlayback(null))

        val hold = body(source("MusicService.kt"), holdStart)
        assertFalse("a song in the player no longer ends the look", "player.mediaItemCount > 0)) return" in hold)
        assertTrue(hold, "if (!MediaButtonStart.looksAgain(hasSong, key, Build.VERSION.SDK_INT)) return" in hold)
        val play = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val next = android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        // Nothing in the player: every start is looked at again, as before, whatever the key and the Android.
        for (sdk in 24..37) for (key in listOf(play, next, null)) assertTrue("$sdk $key", MediaButtonStart.looksAgain(hasSong = false, keyCode = key, sdk = sdk))
        // A song in the player: a play press, from Android 8, where Android waits on the start and its list of the
        // app's own services says whether media3 met it (C4, 21 Nov 2026: until then from Android 10 only).
        for (sdk in 26..37) assertTrue("$sdk", MediaButtonStart.looksAgain(hasSong = true, keyCode = play, sdk = sdk))
        for (sdk in 24..25) assertFalse("$sdk", MediaButtonStart.looksAgain(hasSong = true, keyCode = play, sdk = sdk))
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
    fun `held at once before Android 12 with nothing in the player, and on any Android with under 2 s left`() {
        val service = source("MusicService.kt")
        val hold = body(service, holdStart)
        assertTrue(hold, "val held = MediaButtonStart.holdsAtOnce(Build.VERSION.SDK_INT, hasSong, sinceStart) && holdForeground()" in hold)
        // The time left is known before the hold is decided.
        assertTrue(hold, hold.indexOf("val sinceStart = ") in 0 until hold.indexOf("val held = "))
        // As before: nothing in the player before Android 12, whatever the time.
        for (sdk in 26..30) assertTrue("$sdk", MediaButtonStart.holdsAtOnce(sdk, hasSong = false, sinceStartMs = null))
        for (sdk in 31..37) assertFalse("$sdk", MediaButtonStart.holdsAtOnce(sdk, hasSong = false, sinceStartMs = null))
        for (sdk in 26..37) assertFalse("$sdk", MediaButtonStart.holdsAtOnce(sdk, hasSong = true, sinceStartMs = null))
        // C2, his call of 21 Nov 2026: under 2 s of Android's time left, held at once, song or not, Android 12 or not.
        assertEquals(2_000L, MediaButtonStart.HOLD_AT_ONCE_UNDER_MS)
        assertTrue(MediaButtonStart.holdsAtOnce(33, hasSong = true, sinceStartMs = 28_500L))
        assertFalse(MediaButtonStart.holdsAtOnce(33, hasSong = true, sinceStartMs = 28_000L))
        assertTrue(MediaButtonStart.holdsAtOnce(31, hasSong = false, sinceStartMs = 8_500L))
        assertFalse(MediaButtonStart.holdsAtOnce(31, hasSong = false, sinceStartMs = 7_900L))
        assertTrue(MediaButtonStart.holdsAtOnce(28, hasSong = true, sinceStartMs = 8_001L))
        assertTrue(MediaButtonStart.holdsAtOnce(26, hasSong = true, sinceStartMs = 3_500L))
        // Past the limit already: holding is still the best try.
        assertTrue(MediaButtonStart.holdsAtOnce(37, hasSong = false, sinceStartMs = 40_000L))
        // A start that cannot be timed is never late.
        for (sdk in 26..37) assertFalse("$sdk", MediaButtonStart.tooLateToWait(sdk, null))
        assertTrue(hold, "mediaButtonHandler.postDelayed(mediaButtonGuard, MediaButtonStart.guardDelayMs(Build.VERSION.SDK_INT, held, sinceStart))" in hold)
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
    fun `a press that plays nothing is met a second after it, before Android may stop the service of an idle app`() {
        // 28 Nov 2026, Pixel 5 on Android 14: Play on the widget over a queue that had ended, the app idle for 18
        // minutes. The press paused the ended player, nothing played, and Android stopped the service for app idle 15 s
        // later, before the guard's look at 20 s: "Bringing down service while still waiting for start foreground".
        val ended = androidx.media3.common.Player.STATE_ENDED
        val idle = androidx.media3.common.Player.STATE_IDLE
        val buffering = androidx.media3.common.Player.STATE_BUFFERING
        val ready = androidx.media3.common.Player.STATE_READY
        fun look(fg: Boolean = false, way: Boolean = false, play: Boolean, state: Int) = MediaButtonStart.earlyLook(fg, way, play, state)
        val meet = MediaButtonStart.EarlyLook.MEET
        // That press: paused on an ended queue. Android refusing the sound (a call) leaves it paused too.
        assertEquals(meet, look(play = false, state = ended))
        assertEquals(meet, look(play = false, state = ready))
        assertEquals(meet, look(play = false, state = idle))
        assertEquals(meet, look(play = true, state = ended))
        // Playing or about to: media3 is in the foreground with its notification by itself.
        assertEquals(MediaButtonStart.EarlyLook.MEDIA3, look(play = true, state = buffering))
        assertEquals(MediaButtonStart.EarlyLook.MEDIA3, look(play = true, state = ready))
        for (play in listOf(true, false)) for (state in listOf(idle, buffering, ready, ended)) {
            assertEquals(MediaButtonStart.EarlyLook.MEDIA3, look(fg = true, play = play, state = state))
            // A saved queue being read, an empty player, a song tapped on the widget: not yet, and the guard as before.
            assertEquals(MediaButtonStart.EarlyLook.AGAIN, look(way = true, play = play, state = state))
        }
        // Set to play and stopped by an error: the app may try again, so it is left to the guard as before.
        assertEquals(MediaButtonStart.EarlyLook.AGAIN, look(play = true, state = idle))

        // A second: after media3 has taken the press (a double press's wait, 300 ms), before the guard on every Android,
        // and far before the 15 s that Android gave the idle app.
        assertEquals(1_000L, MediaButtonStart.EARLY_LOOK_MS)
        for (sdk in 26..37) assertTrue("$sdk", MediaButtonStart.EARLY_LOOK_MS < MediaButtonStart.guardMs(sdk, held = false))

        val service = source("MusicService.kt")
        val hold = body(service, holdStart)
        // Fresh for each press, and only when nothing holds the foreground from the press.
        val fresh = hold.indexOf("mediaButtonHandler.removeCallbacks(mediaButtonEarlyLook)")
        val posted = hold.indexOf("if (!held) mediaButtonHandler.postDelayed(mediaButtonEarlyLook, MediaButtonStart.EARLY_LOOK_MS)")
        assertTrue(hold, fresh >= 0 && posted > fresh && posted > hold.indexOf("val held = "))
        val early = service.substringAfter("private val mediaButtonEarlyLook = object : Runnable {").substringBefore("\n    }\n")
        assertTrue(early, "if (!mediaButtonPending || destroyed || holdingForMediaButton || !::player.isInitialized) return" in early)
        assertTrue(early, "val onItsWay = resumptionRunning || player.mediaItemCount == 0 || widgetSongOwed != null" in early)
        assertTrue(early, "MediaButtonStart.earlyLook(foregroundNow(), onItsWay, player.playWhenReady, player.playbackState)" in early)
        assertTrue(early, "MediaButtonStart.EarlyLook.AGAIN -> mediaButtonHandler.postDelayed(this, MediaButtonStart.EARLY_LOOK_MS)" in early)
        assertTrue(early, "MediaButtonStart.EarlyLook.MEET -> meetPressThatPlaysNothing()" in early)

        // Met and let go: the player's own notification into the foreground and out again as media3 leaves it after a
        // pause (DETACH, the notification stays); the plain one, up and away, when none shows. The service is not
        // stopped here: Android may stop it now, which is an ordinary stop.
        val met = body(service, "private fun meetPressThatPlaysNothing()")
        val settled = met.indexOf("mediaButtonPending = false")
        assertTrue(met, settled >= 0 && settled < met.indexOf("ServiceCompat.startForeground("))
        assertTrue(met, "mediaButtonHandler.removeCallbacks(mediaButtonGuard)" in met)
        assertTrue(met, "ReportTrail.thisRun.owedStart(ReportTrail.Owed.NOTHING_PLAYS, owedFor())" in met)
        val up = met.indexOf("ServiceCompat.startForeground(this, NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)")
        val away = met.indexOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)")
        assertTrue(met, up >= 0 && away > up)
        assertTrue(met, "if (holdForeground()) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)" in met)
        assertTrue("a refusal is caught: Android drops its demand before it decides", "} catch (e: Exception) {" in met)
        assertFalse(met, "stopSelf()" in met)
        val shown = service.substringAfter("private fun playerNotification(): Notification? = runCatching {").substringBefore("}.getOrNull()")
        assertTrue(shown, "activeNotifications?.firstOrNull { it.id == NOTIFICATION_ID }?.notification" in shown)
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
            Regex("""PlainPlayNotifications\(\s*this@MusicService,\s*\{ NOTIFICATION_ID },\s*CHANNEL_ID,\s*R\.string\.music_player""").containsMatchIn(service))
        assertTrue("which is media3's own provider", ") : DefaultMediaNotificationProvider(service, notificationIdProvider, channelId, channelNameResourceId) {" in source("PlainPlayNotifications.kt"))
    }

    /**
     * The guard's look on Android's clock for a press the app handed Android at [handed] and the
     * service saw at [seen], the service created at [created] in a process started at [process].
     */
    private fun look(sdk: Int, process: Long, handed: Long?, created: Long, seen: Long, first: Boolean = true): Long {
        val since = MediaButtonStart.sinceStart(handed, first, process, created, seen)
        return seen + MediaButtonStart.guardDelayMs(sdk, held = false, sinceStartMs = since) - (handed ?: process)
    }

    @Test
    fun `a press with the app closed is looked at no earlier than before, counted from when the receiver hands Android the start`() {
        // The review of 18 Nov: counted from the process's start, the look of a cold press through
        // the media button receiver (a headset, a car, the widget with the app closed) came seconds
        // earlier than before, since the process starts for the receiver and Android's time only
        // when the receiver hands it the start. Android 12: Application.onCreate takes 1.5 s, the
        // receiver's read of the saved queue 0.5 s, then the start is handed on (Android's 0), and
        // the service's onCreate takes 1 s.
        val sdk = 31
        val process = 100_000L
        val handed = process + 1_500 + 500
        val created = handed
        val seen = created + 1_000
        assertEquals("Android's time gone when the service sees the press", 1_000L, MediaButtonStart.sinceStart(handed, true, process, created, seen))
        // On next-013: 5 s after onStartCommand, 6 s on Android's clock of 10.
        val old = seen + MediaButtonStart.guardMs(sdk, held = false) - handed
        assertEquals(6_000L, old)
        assertEquals("the look the old guard had", old, look(sdk, process, handed, created, seen))
        // Counted from the process's start (3 s gone), it came at 3 s on Android's clock, 2 s after
        // the press: a saved queue that took 2.5 s to read was given up and the press played nothing.
        val fromProcess = seen + (MediaButtonStart.guardMs(sdk, held = false) - (seen - process)).coerceAtLeast(MediaButtonStart.MIN_LOOK_MS) - handed
        assertEquals(3_000L, fromProcess)
        assertTrue(look(sdk, process, handed, created, seen) > fromProcess)

        // Whatever the app took before it handed the start on, and for a service that starts in up
        // to 2 s, the look is exactly the old one, on every Android from 9 (Android 8 holds the
        // foreground at the press: see the next test).
        for (s in 28..37) for (before in listOf(0L, 500L, 2_000L, 5_000L, 12_000L)) for (start in listOf(0L, 300L, 1_000L, 2_000L)) {
            val h = process + before
            val oldLook = start + MediaButtonStart.guardMs(s, held = false)
            assertEquals("$s, $before ms before the hand-off, $start ms of service start", oldLook, look(s, process, h, h, h + start))
        }
        // From Android 13 a start of up to 7 s still leaves the old look 3 s before the limit, so it stays.
        for (start in listOf(3_000L, 5_000L, 7_000L)) assertEquals(start + 20_000L, look(33, process, process + 2_000, process + 2_000, process + 2_000 + start))
    }

    @Test
    fun `a slow start moves the look before Android's limit, never sooner than a second after the press`() {
        val process = 100_000L
        for (sdk in 26..37) {
            val limit = MediaButtonStart.limitMs(sdk)
            for (held in listOf(false, true)) {
                assertEquals("a start that cannot be timed: from now, as before", MediaButtonStart.guardMs(sdk, held), MediaButtonStart.guardDelayMs(sdk, held, null))
            }
            // Held since the press (before Android 12), nothing is owed any more: the look waits as before.
            assertEquals(MediaButtonStart.guardMs(sdk, held = true), MediaButtonStart.guardDelayMs(sdk, held = true, sinceStartMs = 4_000L))
            var start = 0L
            while (start + MediaButtonStart.MIN_LOOK_MS < limit) {
                val handed = process + 2_000
                val seen = handed + start
                val delay = MediaButtonStart.guardDelayMs(sdk, held = false, sinceStartMs = MediaButtonStart.sinceStart(handed, true, process, handed, seen))
                assertTrue("$sdk, $start: at least a moment for media3", delay >= MediaButtonStart.MIN_LOOK_MS)
                assertTrue("$sdk, $start: never later than before", delay <= MediaButtonStart.guardMs(sdk, held = false))
                val onAndroidsClock = start + delay
                assertTrue("$sdk, $start: the look at $onAndroidsClock, Android's limit $limit", onAndroidsClock < limit)
                // Where the start leaves the room, 3 s before the limit at the latest.
                if (start + MediaButtonStart.MIN_LOOK_MS <= limit - MediaButtonStart.LOOK_BEFORE_LIMIT_MS) {
                    assertTrue("$sdk, $start: $onAndroidsClock", onAndroidsClock <= limit - MediaButtonStart.LOOK_BEFORE_LIMIT_MS)
                }
                start += 250
            }
        }
        // Android 12: a 4 s start is looked at 3 s later (7 s of 10), a 6.5 s start 1 s later.
        assertEquals(3_000L, MediaButtonStart.guardDelayMs(31, held = false, sinceStartMs = 4_000L))
        assertEquals(MediaButtonStart.MIN_LOOK_MS, MediaButtonStart.guardDelayMs(31, held = false, sinceStartMs = 6_500L))
        // Android 8 holds at the press; unheld (the hold failed), it looks by 2 s of its 5.
        assertEquals(2_000L, MediaButtonStart.guardDelayMs(26, held = false, sinceStartMs = 0L))
    }

    @Test
    fun `the app's own starts count from when they were handed on, and only a start from outside counts from the process`() {
        // Handed on by the receiver or the widget: from that moment, however long the process has run.
        assertEquals(500L, MediaButtonStart.sinceStart(handedAt = 12_500L, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 12_600L, now = 13_000L))
        assertEquals(500L, MediaButtonStart.sinceStart(handedAt = 300_500L, firstStart = false, processStartedAt = 10_000L, serviceCreatedAt = 11_000L, now = 301_000L))
        assertEquals("never negative", 0L, MediaButtonStart.sinceStart(handedAt = 14_000L, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 12_000L, now = 13_000L))
        // From outside the app, the start that brought the process up: from the process's start.
        // Created 2 s after the process, the press 1 s after that: 3 s of Android's time are gone.
        assertEquals(3_000L, MediaButtonStart.sinceStart(handedAt = null, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 12_000L, now = 13_000L))
        // A service made long after its process (the app open for minutes): from now.
        assertEquals(null, MediaButtonStart.sinceStart(handedAt = null, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 300_000L, now = 301_000L))
        // A service a screen bound at once, and a press minutes later: from now.
        assertEquals(null, MediaButtonStart.sinceStart(handedAt = null, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 11_000L, now = 200_000L))
        // Not the start the service was created for: from now.
        assertEquals(null, MediaButtonStart.sinceStart(handedAt = null, firstStart = false, processStartedAt = 10_000L, serviceCreatedAt = 12_000L, now = 13_000L))

        val service = source("MusicService.kt")
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("setListener(object : Listener {")
        assertTrue(onCreate, "processStartedAt = android.os.Process.getStartUptimeMillis()" in onCreate)
        assertTrue(onCreate, "createdAt = SystemClock.uptimeMillis()" in onCreate)
        val hold = body(service, holdStart)
        assertTrue(hold, "val sinceStart = MediaButtonStart.sinceStart(handedAt, firstStart, processStartedAt, createdAt, SystemClock.uptimeMillis())" in hold)
        val start = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val first = start.indexOf("firstStart = false")
        val press = start.indexOf("holdForegroundForMediaButton(MediaButtonStart.keyOf(intent), MediaButtonStart.handedAtOf(intent, flags))")
        assertTrue(start, press >= 0 && first > press && first < start.indexOf("return super.onStartCommand("))

        // The receiver marks the very intent media3 then hands Android (media3 1.8.0 asks
        // shouldStartForegroundService and starts with the same intent right after), when it starts.
        val receiver = source("ResumingMediaButtonReceiver.kt")
        val should = receiver.substringAfter("override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {").substringBefore("\n    }\n")
        val mark = should.indexOf("if (startService) MediaButtonStart.markHanded(intent)")
        assertTrue(should, mark >= 0 && mark < should.indexOf("return startService"))
        // The widget's own start of the running player, the same way.
        val widget = File("src/main/java/com/dd3boh/outertune/widget/WidgetActions.kt").readText()
        assertTrue(widget, "if (startsPlayback) ContextCompat.startForegroundService(context, MediaButtonStart.markHanded(intent))" in widget)
        val marks = source("MediaButtonStart.kt")
        assertTrue(marks, "fun markHanded(intent: Intent): Intent = intent.putExtra(EXTRA_HANDED_AT, SystemClock.uptimeMillis())" in marks)
        // A start Android delivers again to a new process (the old one died before onStartCommand
        // returned; media3's service is START_STICKY) has its time started again there
        // (ActiveServices.sendServiceArgsLocked): its old mark is not read, and the new process's
        // start counts instead.
        val read = marks.substringAfter("fun handedAtOf(intent: Intent?, flags: Int): Long? {").substringBefore("\n    }\n")
        val retry = read.indexOf("if (flags and (Service.START_FLAG_RETRY or Service.START_FLAG_REDELIVERY) != 0) return null")
        assertTrue(read, retry >= 0 && retry < read.indexOf("intent?.takeIf { it.hasExtra(EXTRA_HANDED_AT) }?.getLongExtra(EXTRA_HANDED_AT, 0L)"))
        assertEquals(null, MediaButtonStart.handedAtOf(null, android.app.Service.START_FLAG_RETRY))
        assertEquals(3_000L, MediaButtonStart.sinceStart(handedAt = null, firstStart = true, processStartedAt = 10_000L, serviceCreatedAt = 12_000L, now = 13_000L))
    }

    @Test
    fun `the notification's plain play press owes Android nothing and is left out of the guard`() {
        // The review of 18 Nov: the press became a plain start (PlainPlayNotifications) but still
        // went through the guard, which held the foreground with the plain notification over a
        // paused player whose song did not start (a call holding the audio focus), and after a
        // cold start with nothing loaded could give up the resumption and stop the service.
        val service = source("MusicService.kt")
        val start = body(service, "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {")
        val plain = start.indexOf("else if (intent.action == Intent.ACTION_MEDIA_BUTTON && !MediaButtonStart.isPlain(intent))")
        val press = start.indexOf("holdForegroundForMediaButton(MediaButtonStart.keyOf(intent), MediaButtonStart.handedAtOf(intent, flags))")
        assertTrue(start, plain >= 0 && plain < press)
        // Nothing else in the service starts the guard, so the plain press reaches media3 alone: the media button's
        // start, and a song tapped on the widget (27 Nov 2026), which Android waits on as on a play press.
        assertEquals("two callers", 3, Regex("""holdForegroundForMediaButton\(""").findAll(service).count())
        assertTrue("holdForegroundForMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY, MediaButtonStart.handedAtOf(intent, flags))" in
            body(service, "private fun playWidgetSong(intent: Intent, flags: Int) {"))
        // Still the start the service may have been created for: the next one is not counted from the process.
        val first = start.indexOf("firstStart = false")
        assertTrue(start, first > press && first < start.indexOf("return super.onStartCommand("))
        // The mark, read where it is put.
        val marks = source("MediaButtonStart.kt")
        assertTrue(marks, "intent?.getBooleanExtra(EXTRA_PLAIN, false) == true" in marks)
        val play = source("PlainPlayNotifications.kt").substringAfter("fun playPauseIntent(service: Service): Intent = ")
        assertTrue(play, ".putExtra(MediaButtonStart.EXTRA_PLAIN, true)" in play.substringBefore("\n    }\n"))
        // Put once, on the play press: media3's own buttons (pause, next, previous, the dismissal)
        // and the receiver's and the widget's starts, which Android does wait on, carry no mark.
        val puts = listOf("ResumingMediaButtonReceiver.kt", "PlainPlayNotifications.kt", "MusicService.kt", "MediaButtonStart.kt")
            .sumOf { Regex("""EXTRA_PLAIN, true""").findAll(source(it)).count() }
        assertEquals("put once, on the play press", 1, puts)
        assertFalse("EXTRA_PLAIN" in File("src/main/java/com/dd3boh/outertune/widget/WidgetActions.kt").readText())
    }

    @Test
    fun `swiped away while a press is owed, the demand is met before the service stops`() {
        // Android closes the app at once when a service it waits on stops before startForeground
        // (ActiveServices.bringDownServiceLocked), and both ways out of onTaskRemoved may stop it.
        val service = source("MusicService.kt")
        val removed = body(service, "override fun onTaskRemoved(rootIntent: Intent?)")
        val meet = removed.indexOf("meetMediaButtonStartBeforeStop()")
        assertTrue(removed, meet >= 0 && meet < removed.indexOf("pauseAllPlayersAndStopSelf()") && meet < removed.indexOf("super.onTaskRemoved(rootIntent)"))
        val settle = body(service, "private fun meetMediaButtonStartBeforeStop()")
        assertTrue(settle, "if (!mediaButtonPending) return" in settle)
        assertTrue("nothing to meet when media3 or the plain notification holds the foreground", "if (holdingForMediaButton || foregroundNow()) return" in settle)
        val hold = settle.indexOf("if (holdForeground()) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)")
        assertTrue(settle, hold > settle.indexOf("mediaButtonHandler.removeCallbacks(mediaButtonGuard)"))
    }

    @Test
    fun `whether the service is in the foreground is Android's own record on 10 to 13, where the type outlives the foreground`() {
        // AOSP ActiveServices sets ServiceRecord.foregroundServiceType in startForeground only, up to
        // android-13.0.0_r83, and puts it back to 0 when the service leaves the foreground from
        // android-14.0.0_r1. Read on Android 10 to 13 it said "in the foreground" for a player paused
        // long enough for media3 to leave it, and for a service Android brought back after its
        // process died: a play press on it had no look (holdForegroundForMediaButton returned at
        // once) and settleAfterRestart did not meet what the dead process owed. Android closes the
        // app for either, 10 s later on 10 to 12L, 30 s on 13.
        val service = source("MusicService.kt")
        val now = service.substringAfter("private fun foregroundNow(): Boolean =").substringBefore("\n")
        assertEquals("Android's answer, media3's only where Android has none", " inForeground() ?: isPlaybackOngoing", now)
        val asked = body(service, "private fun inForeground(): Boolean? = when {")
        val type = asked.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE")
        // From Android 8 (C4, 21 Nov 2026): 8 and 9 have no type to read, and their list answers as 10 to 13's does.
        val listed = asked.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> MediaButtonStart.listedInForeground(this, ComponentName(this, MusicService::class.java))")
        assertTrue(asked, type >= 0 && listed > type && asked.indexOf("else -> null") > listed)
        // The type is read there and nowhere else in the service: never on its own below Android 14.
        assertEquals(1, Regex("""foregroundServiceType\s*!=""").findAll(service).count())
        // Android's list of the app's own services carries the record's isForeground, the flag
        // Android decides its demand by.
        val list = source("MediaButtonStart.kt")
            .substringAfter("fun listedInForeground(context: Context, component: ComponentName): Boolean? = runCatching {")
            .substringBefore("}.getOrNull()")
        assertTrue(list, "getRunningServices(" in list)
        assertTrue(list, ".firstOrNull { it.service == component }?.foreground" in list)
    }
}
