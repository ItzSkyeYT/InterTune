/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download notifications open the downloads, and the running one is built at a pace that
 * costs nothing. A tap on them did nothing, since every one was built with null for its intent.
 */
class DownloadNotificationTest {

    private val service = File("src/main/java/com/dd3boh/outertune/playback/ExoDownloadService.kt").readText()
    private val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun `no download notification is built with nothing to open`() {
        // media3's builders take the intent to open third, after the context and the icon.
        val built = Regex("""build(\w+)Notification\(\s*(\w+),\s*R\.drawable\.\w+,\s*([^,\s]+),""").findAll(service).toList()
        assertEquals("progress, completed and failed", setOf("Progress", "DownloadCompleted", "DownloadFailed"), built.map { it.groupValues[1] }.toSet())
        for (call in built) {
            assertFalse("${call.groupValues[1]} opens nothing", call.groupValues[3] == "null")
        }
        assertTrue("openDownloads by lazy { DownloadsShortcut.pendingIntent(this) }" in service)
        assertTrue("DownloadsShortcut.pendingIntent(context)" in service)
    }

    @Test
    fun `the intent opens the app and cannot be changed by whoever holds it`() {
        val shortcut = File("src/main/java/com/dd3boh/outertune/playback/DownloadsShortcut.kt").readText()
        assertTrue("PendingIntent.getActivity(" in shortcut)
        assertTrue("PendingIntent.FLAG_IMMUTABLE" in shortcut)
        assertTrue("Intent(context, MainActivity::class.java)" in shortcut)
    }

    @Test
    fun `the tap lands on Downloaded songs`() {
        assertEquals("auto_playlist/downloaded", DownloadsShortcut.ROUTE)
        assertTrue("route = \"auto_playlist/{playlistId}\"" in File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText())
        assertTrue("\"downloaded\" -> PlaylistType.DOWNLOAD" in File("src/main/java/com/dd3boh/outertune/ui/screens/playlist/AutoPlaylistScreen.kt").readText())
    }

    @Test
    fun `the app takes the tap with the app closed and with it open`() {
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("setContent")
        assertTrue("a cold start", "DownloadsShortcut.owed(intent?.action, savedInstanceState != null" in onCreate)
        val onNewIntent = activity.substringAfter("override fun onNewIntent(").substringBefore("\n    }\n")
        assertTrue("a running app", "intent.action == DownloadsShortcut.ACTION" in onNewIntent)
        assertTrue(activity.contains("navController.navigate(DownloadsShortcut.ROUTE)"))
    }

    @Test
    fun `the downloads open for a fresh tap only`() {
        val action = DownloadsShortcut.ACTION
        assertTrue(DownloadsShortcut.owed(action, hadSavedInstanceState = false, flags = 0))
        // A rotation or a restored process.
        assertFalse(DownloadsShortcut.owed(action, hadSavedInstanceState = true, flags = 0))
        // The task reopened from recents brings the tap's intent back.
        assertFalse(DownloadsShortcut.owed(action, hadSavedInstanceState = false, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertFalse(DownloadsShortcut.owed(Intent.ACTION_MAIN, hadSavedInstanceState = false, flags = 0))
        assertFalse(DownloadsShortcut.owed(null, hadSavedInstanceState = false, flags = 0))
    }

    @Test
    fun `the notification is built once a second at most, never for every byte`() {
        // DownloadService(notification id, update interval in ms, ...): media3 builds it again at
        // that interval while songs download, and at each change of state.
        val interval = Regex("""DownloadService\(\s*NOTIFICATION_ID,\s*(\d+)L""").find(service)?.groupValues?.get(1)?.toLong()
        assertTrue("an interval of $interval ms", interval != null && interval >= 1000)
        // Nothing else posts it: the download path itself never touches a notification.
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        assertFalse("setNotification(" in util)
        assertFalse(".notify(" in util)
    }

    @Test
    fun `the run is counted before the service builds the notification`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val made = util.substringAfter("val downloadManager: DownloadManager =").substringBefore("val downloads =")
        assertTrue("the run listens to the download manager", "addListener(downloadRun)" in made)
        assertTrue("the bar is the run's", "DownloadRun.bar(" in service && "downloadUtil.downloadRun.endedCount" in service)
        assertTrue("setProgress(bar.max, bar.position, bar.indeterminate)" in service)
    }

    private fun plural(text: String, name: String): String =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()

    @Test
    fun `the count is in English and in French, with both numbers`() {
        val english = plural(File("src/main/res/values/strings-ot.xml").readText(), "download_progress_songs")
        val french = plural(File("src/main/res/values-fr/strings-ot.xml").readText(), "download_progress_songs")
        val quantities = { text: String -> Regex("""quantity="(\w+)"""").findAll(text).map { it.groupValues[1] }.toSet() }
        assertEquals(setOf("one", "other"), quantities(english))
        assertEquals(setOf("one", "many", "other"), quantities(french))
        for (text in listOf(english, french)) {
            Regex("""<item quantity="\w+">(.*?)</item>""").findAll(text).forEach {
                val words = it.groupValues[1]
                assertTrue(words, "%1\$d" in words && "%2\$d" in words)
                assertFalse(words, "—" in words)
            }
        }
    }
}
