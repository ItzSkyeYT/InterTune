/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.recap.RecapAlert
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.SleepTimerNotification
import com.dd3boh.outertune.recognition.RecogniseLaterWorker
import com.dd3boh.outertune.recognition.RecognitionService
import com.dd3boh.outertune.together.ProposalNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App language: the names of the notification channels in Android's own settings stayed in the language of the
 * app's first run, since six were made only when missing and media3 makes the music player's the same way.
 */
class ChannelNamesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = File(sources, path).readText()

    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    /** Each channel the app makes: where, by which id, named by which text. */
    private val made = listOf(
        Triple("playback/MusicService.kt", MusicService.CHANNEL_ID, "music_player"),
        Triple("playback/MediaButtonStart.kt", MusicService.CHANNEL_ID, "music_player"),
        Triple("playback/ExoDownloadService.kt", ExoDownloadService.CHANNEL_ID, "action_download"),
        Triple("playback/SleepTimerNotification.kt", SleepTimerNotification.CHANNEL_ID, "sleep_timer"),
        Triple("recognition/RecognitionService.kt", RecognitionService.CHANNEL_ID, "recognition_service_channel"),
        Triple("recognition/RecognitionService.kt", RecognitionService.PHONE_CHANNEL_ID, "recognition_service_title_phone"),
        Triple("recognition/RecognitionService.kt", RecognitionService.RESULTS_CHANNEL_ID, "recognition_phone_results_channel"),
        Triple("recognition/RecogniseLaterWorker.kt", RecogniseLaterWorker.CHANNEL_ID, "recognise_later_channel"),
        Triple("utils/BackgroundChecks.kt", BackgroundCheckWorker.CHANNEL_ID, "background_channel"),
        Triple("together/ProposalNotice.kt", ProposalNotice.CHANNEL_ID, "together_proposals_channel"),
        Triple("recap/RecapAlert.kt", RecapAlert.CHANNEL_ID, "recap_name"),
    )

    @Test
    fun `every channel is named where it is made by the text the renaming uses`() {
        for ((file, id, text) in made) {
            assertTrue("$file no longer names a channel with R.string.$text", "R.string.$text" in source(file))
            assertEquals("$id in ChannelNames", text, ChannelNames.NAMES[id]?.let(nameOf::get))
        }
        assertEquals(made.map { it.second }.toSet(), ChannelNames.NAMES.keys)
    }

    @Test
    fun `no channel is made only when missing, which kept its first name`() {
        val guarded = sources.walkTopDown().filter { it.extension == "kt" && "getNotificationChannel(" in it.readText() }.map { it.name }.toList()
        assertTrue("made only when missing in $guarded", guarded.isEmpty())
    }

    @Test
    fun `the channels that are there are renamed at start and at each change of language`() {
        val app = source("App.kt")
        val collector = app.substringAfter("AppLanguage.changes.collect {").substringBefore("\n            }\n")
        assertTrue(collector, "ChannelNames.follow(this@App)" in collector)
        assertTrue("GlobalScope.launch(Dispatchers.IO) { ChannelNames.follow(this@App) }" in app)
        val follow = source("utils/ChannelNames.kt").substringAfter("fun follow(context: Context) {")
        assertTrue("for (channel in manager.notificationChannels) {" in follow)
        assertTrue("if (channel.name?.toString() == name) continue" in follow)
        assertTrue("manager.createNotificationChannel(channel)" in follow)
    }
}
