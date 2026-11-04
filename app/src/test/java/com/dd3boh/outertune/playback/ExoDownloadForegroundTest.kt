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
 * The download service never asks Android for the foreground in a place where a refusal closes the app.
 *
 * Issue #71: from Android 12 an app in the background may not call Service.startForeground. media3
 * calls it from its own callbacks on the main looper when a download starts (a queued one that
 * finds Wi-Fi again, one resumed at start, one added by a like), nothing there catches the refusal,
 * and the app closed. Service.startForeground is final, so the service cannot wrap it: from
 * Android 12 media3's promotion is off (FOREGROUND_NOTIFICATION_ID_NONE) and the service promotes
 * itself through ForegroundPromotion, which is tested on the JVM in ForegroundPromotionTest. This
 * test is the rule that it is the only way the service asks.
 */
class ExoDownloadForegroundTest {

    private val service = withoutComments(File("src/main/java/com/dd3boh/outertune/playback/ExoDownloadService.kt").readText())
    private val util = withoutComments(File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText())

    /** Comments blanked out, line breaks kept, so a position still has its line number. */
    private fun withoutComments(source: String): String {
        val blank = { m: MatchResult -> m.value.replace(Regex("[^\n]"), " ") }
        return source
            .replace(Regex("(?s)/\\*.*?\\*/"), blank)
            .replace(Regex("(?m)(^|\\s)//.*$"), blank)
    }

    private fun lineOf(code: String, at: Int) = code.substring(0, at).count { it == '\n' } + 1

    /** The braces from [open] to the one that closes them. */
    private fun braces(code: String, open: Int): IntRange {
        var depth = 0
        var close = open
        while (close < code.length) {
            if (code[close] == '{') depth++
            if (code[close] == '}' && --depth == 0) break
            close++
        }
        return open..close
    }

    /** The last of the arguments of the call whose opening parenthesis is at [open]. */
    private fun lastArgument(code: String, open: Int): String {
        var depth = 0
        var start = open + 1
        val arguments = mutableListOf<String>()
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) {
                    arguments += code.substring(start, i)
                    return arguments.map { it.trim() }.last { it.isNotEmpty() }
                }
                ',' -> if (depth == 1) {
                    arguments += code.substring(start, i)
                    start = i + 1
                }
            }
        }
        error("the call at $open is not closed")
    }

    @Test
    fun `media3 does not make the service a foreground service from Android 12`() {
        assertTrue(
            "the notification id media3 is given is FOREGROUND_ID",
            Regex("""DownloadService\(\s*FOREGROUND_ID,\s*UPDATE_INTERVAL_MS,""").containsMatchIn(service),
        )
        assertTrue(
            "FOREGROUND_ID is none exactly where the service promotes itself",
            Regex(
                """FOREGROUND_ID\s*=\s*if \(ForegroundPromotion\.selfPromotes\(Build\.VERSION\.SDK_INT\)\)\s*""" +
                    """DownloadService\.FOREGROUND_NOTIFICATION_ID_NONE\s*else\s*NOTIFICATION_ID""",
            ).containsMatchIn(service),
        )
    }

    @Test
    fun `the service asks for the foreground only through the guard`() {
        val asks = Regex("""\bstartForeground\s*\(""").findAll(service).map { it.range.first }.toList()
        assertTrue("ExoDownloadService asks for the foreground itself", asks.isNotEmpty())
        val guarded = Regex("""\bpromotion\.attempt\s*\{""").findAll(service).map { braces(service, it.range.last) }.toList()
        val bare = asks.filter { at -> guarded.none { at in it } }.map { lineOf(service, it) }
        assertEquals("startForeground outside promotion.attempt { }, on lines", emptyList<Int>(), bare)
        // The other ways in, which is how it was before: media3's own call throws where nobody can catch it.
        assertFalse("Util.setForegroundServiceNotification", "setForegroundServiceNotification" in service)
        assertFalse("startForegroundService", Regex("""\bstartForegroundService\s*\(""").containsMatchIn(service))
    }

    @Test
    fun `the service makes its own notification channel`() {
        // Given no notification id, media3 makes no channel, and Android drops a notification on a channel that is not there.
        assertTrue(
            "NotificationUtil.createNotificationChannel(this, CHANNEL_ID, ...) in onCreate",
            Regex("""NotificationUtil\.createNotificationChannel\(\s*this,\s*CHANNEL_ID,""").containsMatchIn(service),
        )
    }

    @Test
    fun `the notification follows the downloads the way media3's did`() {
        // media3's needsStartedService: the three states in which the service has to be a foreground service.
        for (state in listOf("STATE_DOWNLOADING", "STATE_REMOVING", "STATE_RESTARTING")) {
            assertTrue(state, "Download.$state" in service)
        }
        val listener = service.substringAfter("val downloadListener = object : DownloadManager.Listener")
        for (event in listOf("onInitialized", "onDownloadChanged", "onDownloadRemoved")) {
            assertTrue("$event refreshes", Regex("""override fun $event\([^)]*\)\s*(?:=\s*refresh\(\)|\{[^}]*\brefresh\(\))""").containsMatchIn(listener))
        }
        assertTrue("the ticks stop when the manager is idle", Regex("""override fun onIdle\([^)]*\)\s*\{[^}]*ticking = false""").containsMatchIn(listener))
    }

    @Test
    fun `a refused promotion is asked again when a screen comes to the front`() {
        val onCreate = service.substringAfter("override fun onCreate()").substringBefore("override fun onDestroy()")
        val onDestroy = service.substringAfter("override fun onDestroy()").substringBefore("\n    }\n")
        assertTrue("listens to the downloads", "downloadManager.addListener(downloadListener)" in onCreate)
        assertTrue("listens to the screens", "application.registerActivityLifecycleCallbacks(inFront)" in onCreate)
        assertTrue("stops listening to the downloads", "downloadManager.removeListener(downloadListener)" in onDestroy)
        assertTrue("stops listening to the screens", "application.unregisterActivityLifecycleCallbacks(inFront)" in onDestroy)
        assertTrue("and has the downloads' ticks stop", "handler.removeCallbacksAndMessages(null)" in onDestroy)
        val resumed = service.substringAfter("override fun onActivityResumed(").substringBefore("\n        }\n")
        assertTrue("a screen in front asks again, once something is owed", "if (promotion.owed) refresh()" in resumed)
    }

    @Test
    fun `nothing starts the download service in the foreground`() {
        // media3 promises that a service started in the foreground calls startForeground. From Android 12
        // that call is the service's own and it would not be made for such a start: it would close the app
        // ten seconds later. So every send says false, and a refused start is the guard's to log (#65).
        val sends = Regex("""\bDownloadService\s*\.\s*send\w+\s*\(""").findAll(util).toList()
        assertTrue("DownloadUtil sends to the download service", sends.isNotEmpty())
        val inForeground = sends.filter { lastArgument(util, it.range.last) != "false" }.map { lineOf(util, it.range.first) }
        assertEquals("DownloadService sends with foreground = true, on lines", emptyList<Int>(), inForeground)
        assertFalse("DownloadService.startForeground", Regex("""\bDownloadService\s*\.\s*startForeground\s*\(""").containsMatchIn(util))
    }
}
