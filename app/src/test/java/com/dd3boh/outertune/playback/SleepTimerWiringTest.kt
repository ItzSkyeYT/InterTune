/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the richer sleep timer is wired: the notification's countdown that runs by itself and its
 * three buttons, the service that takes its settings and its buttons, and the texts. No
 * notification runs on the JVM, so these read the source, as the other wiring tests do, and each
 * guards one rule a later change could quietly undo. The timer's own rules are SleepTimerRulesTest
 * and SleepTimerEndsTest; the sheet's are SleepTimerSheetTest.
 */
class SleepTimerWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val res = File("src/main/res")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val notification = fileCode("playback/SleepTimerNotification.kt")
    private val rawService = File(sources, "playback/MusicService.kt").readText()
    private val service = code(rawService)

    // ---- the notification

    @Test
    fun `the countdown runs by itself, to the end time, in the notification`() {
        assertTrue(".setWhen(stopAt)" in notification)
        assertTrue(".setShowWhen(true)" in notification)
        assertTrue(".setUsesChronometer(true)" in notification)
        assertTrue(".setChronometerCountDown(true)" in notification)
        // The four of them, together: a chronometer without the count down counts up from the end time.
        val block = notification.substringAfter(".setWhen(stopAt)").substringBefore("} else {")
        assertTrue(".setUsesChronometer(true)" in block && ".setChronometerCountDown(true)" in block)
    }

    @Test
    fun `nothing redraws it as the minutes pass`() {
        // The old countdown was posted again every thirty seconds with its minutes in the words.
        assertFalse("SLEEP_TIMER_NOTIF_TICK_MS" in service)
        assertFalse("delay(SLEEP_TIMER_NOTIF_TICK_MS)" in service)
        val observer = service.substringAfter("snapshotFlow { Triple(sleepTimer.triggerTime").substringBefore("it[SleepTimerFadeKey]")
        assertTrue("sleepTimerNotification.show(sleepTimerContent())" in observer)
        // The post waits only for a burst of changes to end, once; it never loops.
        assertFalse("while (" in observer)
        assertEquals(1, Regex("""delay\(""").findAll(observer).count())
        assertTrue("sleepTimerNotification.hide()" in observer)
    }

    @Test
    fun `it is posted again when what it says changes, and the timer counts those changes`() {
        val timer = fileCode("playback/SleepTimer.kt")
        for (callback in listOf("onMediaItemTransition", "onPlaybackStateChanged", "onIsPlayingChanged", "onPositionDiscontinuity")) {
            val body = timer.substringAfter("override fun $callback(").substringBefore("override fun ")
            assertTrue("$callback does not count a change", "changes++" in body)
        }
        assertTrue("changes++" in timer.substringAfter("private fun rearm()").substringBefore("}"))
    }

    @Test
    fun `the notification has the title of the end time, a line of what applies, and three buttons`() {
        assertTrue("R.string.sleep_notif_title_at" in notification)
        assertTrue("DateFormat.getTimeFormat(context)" in notification)
        for (line in listOf("sleep_notif_line_fade_after", "sleep_notif_line_fade", "sleep_notif_line_after", "sleep_the_song")) {
            assertTrue("$line is not used", "R.string.$line" in notification)
        }
        // +5 min, +15 min, Stop: in that order.
        val actions = notification.substringAfter(".addAction(").substringBefore("EXTRA_REQUEST_PROMOTED_ONGOING, true")
        assertEquals(3, Regex("""\.addAction\(""").findAll(notification).count())
        assertTrue(actions.indexOf("SleepTimerDefaults.EXTEND_MINUTES[0]") < actions.indexOf("SleepTimerDefaults.EXTEND_MINUTES[1]"))
        assertTrue(actions.indexOf("SleepTimerDefaults.EXTEND_MINUTES[1]") < actions.indexOf("R.string.sleep_notif_stop"))
        assertTrue("R.string.sleep_plus_minutes" in notification)
        assertEquals(listOf(5, 15), com.dd3boh.outertune.constants.SleepTimerDefaults.EXTEND_MINUTES.take(2))
    }

    @Test
    fun `the buttons are broadcasts to the service's own receiver`() {
        assertTrue("PendingIntent.getBroadcast(" in notification)
        assertTrue(".setPackage(context.packageName)" in notification)
        assertTrue("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT" in notification)
        assertTrue("Notification.Action.Builder(" in notification)
        // The receiver is registered for the life of the service and not exported, and let go with it.
        assertTrue("sleepTimerAction, android.content.IntentFilter(SleepTimerNotification.broadcastAction(this))" in service)
        assertTrue("RECEIVER_NOT_EXPORTED" in service.substringAfter("sleepTimerActionRegistered = runCatching").substringBefore("}.onFailure"))
        assertTrue("unregisterReceiver(sleepTimerAction)" in service)
        val receiver = service.substringAfter("private val sleepTimerAction = ").substringBefore("private var sleepTimerActionRegistered")
        assertTrue("minutes == SleepTimerNotification.STOP -> sleepTimer.clear()" in receiver)
        assertTrue("minutes > 0 -> sleepTimer.extend(minutes)" in receiver)
    }

    @Test
    fun `it keeps its status bar chip, which counts down by itself too`() {
        assertTrue("EXTRA_REQUEST_PROMOTED_ONGOING, true" in notification)
        assertTrue("\"android.requestPromotedOngoing\"" in notification)
        // A short text for the chip is drawn as it is and would have to be posted again to move.
        assertFalse("setShortCriticalText" in notification)
        // Still quiet, still ongoing: it must never make a sound for somebody falling asleep.
        assertTrue("NotificationManager.IMPORTANCE_LOW" in notification)
        assertTrue(".setOngoing(true)" in notification)
    }

    @Test
    fun `the system takes it down at the time, even when the phone slept through it`() {
        assertTrue("setTimeoutAfter(" in notification)
        // Asked for only from Android 8, where there is such a thing, and under the check lint reads.
        val condition = notification.substringAfter("var timeout = false").substringBefore("builder.setTimeoutAfter(")
        assertTrue("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O" in condition)
        // With Let the song finish the timer goes on after the time, so no timeout then.
        assertTrue("!content.afterSong" in condition)
        // A timeout left from an earlier post must not take down the one that replaces it.
        assertTrue("manager.cancel(NOTIFICATION_ID)" in notification.substringAfter("if (timeoutArmed && !timeout)"))
    }

    // ---- the service

    @Test
    fun `Let the song finish is read from its setting, with the fade, and reaches a running timer`() {
        val settings = service.substringAfter("it[SleepTimerFadeKey] ?: SleepTimerDefaults.FADE_ENABLED").substringBefore("ScheduledSleep.Settings(")
        assertTrue("it[SleepTimerSongFinishKey] ?: SleepTimerDefaults.LET_SONG_FINISH" in settings)
        assertTrue("sleepTimer.letSongFinish = letFinish" in settings)
        // The setters of the timer start its job again, which is how a running timer takes a change.
        val timer = fileCode("playback/SleepTimer.kt")
        for (name in listOf("fadeEnabled", "fadeDurationMs", "letSongFinish")) {
            val setter = timer.substringAfter("var $name").substringBefore("rearm()")
            assertTrue("$name does not look again", "set(value)" in setter)
        }
    }

    @Test
    fun `a timer set to the end of the queue keeps the radio from topping the queue up`() {
        val topUp = code(rawService.substringAfter("// Auto load more songs").substringBefore("\n        // The pause point"))
        assertTrue("sleepTimer.endOf != SleepEnd.QUEUE &&" in topUp)
        // In the one condition that decides whether a page is asked for, with the others.
        val condition = topUp.substringAfter("if (autoLoadMore &&").substringBefore(") {")
        assertTrue("sleepTimer.endOf != SleepEnd.QUEUE &&" in condition)
        assertTrue("together.value?.following != true &&" in condition)
    }

    @Test
    fun `the timer is one job and one notification for every end, and it is let go with the service`() {
        assertEquals(1, Regex("""SleepTimerNotification\(this\)""").findAll(service).count())
        assertTrue("sleepTimer.clear()" in service.substringAfter("override fun onDestroy()"))
        assertTrue("sleepTimerNotification.hide()" in service.substringAfter("override fun onDestroy()"))
        // The sleep timer still keeps the audio on the app's own path while it runs (BatteryUseWiringTest).
        assertTrue("snapshotFlow { sleepTimer.isActive }.distinctUntilChanged()" in service)
    }

    // ---- the texts

    private val names = listOf(
        "sleep_row_in", "sleep_row_at", "sleep_row_end_of", "sleep_minutes_short", "sleep_end_song", "sleep_end_album",
        "sleep_end_of_album", "sleep_end_of_queue", "sleep_let_finish", "sleep_fade_row", "sleep_every_night",
        "sleep_start", "sleep_stop", "sleep_close", "sleep_plus_minutes", "sleep_stops_at", "sleep_stops_at_after_song",
        "sleep_the_song", "sleep_notif_title_at", "sleep_notif_title_song", "sleep_notif_title_album",
        "sleep_notif_title_queue", "sleep_notif_line_fade", "sleep_notif_line_fade_after", "sleep_notif_line_after",
        "sleep_notif_stop",
    )

    private val english = File(res, "values/strings-ot.xml").readText()
    private val french = File(res, "values-fr/strings-ot.xml").readText()

    private fun text(xml: String, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    @Test
    fun `every text is in English, held from translators, with its French beside it`() {
        for (name in names) {
            assertNotNull("$name has no English", text(english, name))
            assertNotNull("$name has no French", text(french, name))
            assertTrue("$name is not held", Regex("""<string name="$name" translatable="false">""").containsMatchIn(english))
            assertFalse("$name is held in French", Regex("""<string name="$name" translatable="false">""").containsMatchIn(french))
        }
    }

    @Test
    fun `each text and its French take the same values`() {
        val placeholder = Regex("""%\d\$[sd]""")
        for (name in names) {
            assertEquals(
                "$name takes other values in French",
                placeholder.findAll(text(english, name)!!).map { it.value }.toList().sorted(),
                placeholder.findAll(text(french, name)!!).map { it.value }.toList().sorted(),
            )
        }
    }

    @Test
    fun `the texts are short labels, plain, with no long dash`() {
        for ((language, xml) in listOf("English" to english, "French" to french)) {
            for (name in names) {
                val text = text(xml, name)!!
                assertFalse("$language $name has a long dash", '—' in text || '–' in text)
                assertFalse("$language $name has an exclamation mark", '!' in text)
                assertTrue("$language $name runs to ${text.length} characters", text.length <= 60)
                assertTrue("$language $name is more than one sentence", Regex("""[.?](?=\s|$)""").findAll(text).count() <= 1)
            }
        }
    }

    @Test
    fun `the words are the ones agreed`() {
        assertEquals("In", text(english, "sleep_row_in"))
        assertEquals("At", text(english, "sleep_row_at"))
        assertEquals("End of", text(english, "sleep_row_end_of"))
        assertEquals("Let the song finish", text(english, "sleep_let_finish"))
        assertEquals("Every night", text(english, "sleep_every_night"))
        assertEquals("Stop timer", text(english, "sleep_stop"))
        assertEquals("Music stops at %1\$s", text(english, "sleep_notif_title_at"))
        assertEquals("Stops at %1\$s", text(english, "sleep_stops_at"))
        assertEquals("Stops at %1\$s, after the song", text(english, "sleep_stops_at_after_song"))
        assertEquals("+%1\$d min", text(english, "sleep_plus_minutes"))
        assertEquals("Fade out · %1\$d&#160;s", text(english, "sleep_fade_row"))
    }
}
