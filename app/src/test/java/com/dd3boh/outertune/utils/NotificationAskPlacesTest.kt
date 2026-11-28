/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where Android's question about notifications is put (NotificationAsk): every place whose
 * notification is the point of it calls the one function, and only MainActivity asks, once, while
 * it is on screen. What was used goes ahead whatever the answer.
 */
class NotificationAskPlacesTest {
    private val root = File("src/main/java/com/dd3boh/outertune")
    private fun read(path: String) = File(root, path).readText()
    private fun body(source: String, head: String) =
        source.substringAfter(head, missingDelimiterValue = "").substringBefore("\n    }")

    @Test
    fun `every start of a sleep timer from its sheet asks, after the timer has started`() {
        val sheet = read("ui/component/SleepTimer.kt").substringAfter("fun SleepTimerDialog(").substringBefore("\n/**")
        val starts = Regex("""timer\.start\(""").findAll(sheet).count()
        val startedThenAsked = Regex("""timer\.start\([^\n]*\)\n(\s*//[^\n]*\n)?\s*NotificationAsk\.atUse\(\)""").findAll(sheet).count()
        assertEquals("Start, an End of chip, and End of song under a running timer", 3, starts)
        assertEquals(starts, startedThenAsked)
    }

    @Test
    fun `every download asked for by hand asks, and Liked songs' and the move into the folder`() {
        val util = read("playback/DownloadUtil.kt")
        assertEquals("the downloads asked for by hand", 3, Regex("""fun download\(""").findAll(util).count())
        for (head in listOf(
            "fun download(songs: List<MediaMetadata>) {",
            "fun download(song: MediaMetadata) {",
            "fun download(song: SongEntity) {",
            "fun startLikedDownloads(mode: LikedAutodownloadMode = likedAutodownload) {",
            "fun startMigrate() {",
        )) {
            assertTrue(head, body(util, head).contains("NotificationAsk.atUse()"))
        }
    }

    @Test
    fun `a room started or joined asks`() {
        val together = read("ui/dialog/TogetherDialog.kt")
        for (head in listOf("fun open(code: RoomCode, owner: Boolean) {", "suspend fun join() {")) {
            val room = body(together, head)
            assertTrue(head, room.contains("startTogether(") && room.contains("NotificationAsk.atUse()"))
        }
    }

    @Test
    fun `only those places ask, and nothing at start`() {
        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("NotificationAsk.atUse()") }
            .map { it.relativeTo(root).path }
            .toSortedSet()
        assertEquals(sortedSetOf("playback/DownloadUtil.kt", "ui/component/SleepTimer.kt", "ui/dialog/TogetherDialog.kt"), callers)
    }

    @Test
    fun `MainActivity asks through its one launcher, and only while it is on screen`() {
        val main = read("MainActivity.kt")
        assertTrue(main.contains("val notificationLauncher =\n        registerForActivityResult(ActivityResultContracts.RequestPermission())"))
        assertTrue(main.contains("repeatOnLifecycle(Lifecycle.State.STARTED) {\n                NotificationAsk.used.collect { askForNotificationsOnce() }"))
        assertEquals("asked from nowhere else", 1, Regex("""askForNotificationsOnce\(\)""").findAll(main).count())
        // A timer started with the question up posted while it was up: on a yes it is posted again.
        assertTrue(main.contains("if (granted) playerConnection?.service?.sleepTimer?.postAgain()"))
    }

    @Test
    fun `the mark is left before Android's question, which a no before stops`() {
        val ask = read("utils/NotificationAsk.kt").substringAfter("suspend fun MainActivity.askForNotificationsOnce() {")
        val mark = ask.indexOf("NotificationAsk.markAsked(dataStore)")
        val launch = ask.indexOf("notificationLauncher.launch(permission)")
        assertTrue("the mark, then the question", mark in 0 until launch)
        assertTrue(ask.contains("askedBefore = dataStore.get(NotificationsAskedKey, defaultValue = false)"))
        assertTrue(ask.contains("saidNo = shouldShowRequestPermissionRationale(permission)"))
    }
}
