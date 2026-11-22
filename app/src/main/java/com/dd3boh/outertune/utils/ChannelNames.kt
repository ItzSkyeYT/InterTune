/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapAlert
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.SleepTimerNotification
import com.dd3boh.outertune.recognition.RecogniseLaterWorker
import com.dd3boh.outertune.recognition.RecognitionService

/**
 * The names of the app's notification channels, as Android's own Settings > Notifications > InterTune lists them,
 * in the language the app speaks.
 *
 * A channel takes its name when it is made, and Android keeps that name until the app makes the channel again. Six
 * were made only when missing, and media3 makes the music player's the same way, so their names stayed in the
 * language of the app's first run (an app switched to English on a Dutch phone still listed "Muziekspeler"). Making
 * a channel that is there again is Android's own way to rename it after a change of language: it keeps everything
 * the listener set on it (sound, importance, whether it shows at all).
 */
object ChannelNames {

    /** Every channel the app makes, by id, with the text that names it where it is made. */
    val NAMES: Map<String, Int> = mapOf(
        MusicService.CHANNEL_ID to R.string.music_player,
        ExoDownloadService.CHANNEL_ID to R.string.action_download,
        SleepTimerNotification.CHANNEL_ID to R.string.sleep_timer,
        RecognitionService.CHANNEL_ID to R.string.recognition_service_channel,
        RecognitionService.PHONE_CHANNEL_ID to R.string.recognition_service_title_phone,
        RecognitionService.RESULTS_CHANNEL_ID to R.string.recognition_phone_results_channel,
        RecogniseLaterWorker.CHANNEL_ID to R.string.recognise_later_channel,
        BackgroundCheckWorker.CHANNEL_ID to R.string.background_channel,
        RecapAlert.CHANNEL_ID to R.string.recap_name,
    )

    /**
     * Renames the channels that are there to the language of [context] (the app: its own resources speak the app's
     * language), at the start of the process and on every change of language (App). A channel never made stays
     * unmade. Off the main thread: it asks the system for the list.
     */
    fun follow(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            for (channel in manager.notificationChannels) {
                val name = context.getString(NAMES[channel.id] ?: continue)
                if (channel.name?.toString() == name) continue
                channel.name = name
                manager.createNotificationChannel(channel)
            }
        }.onFailure { Log.w(TAG, "Could not rename the notification channels", it) }
    }

    private const val TAG = "ChannelNames"
}
