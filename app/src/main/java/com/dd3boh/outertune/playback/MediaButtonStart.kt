/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.dd3boh.outertune.R

/**
 * A service that a media button starts with nothing in the player meets Android's demand for the
 * foreground even when nothing comes of it (issue #98).
 *
 * A play press from a headset, a car or the widget with the app closed starts the service with
 * startForegroundService, and Android then waits for startForeground: 5 or 10 s before Android 12,
 * 30 s from it. media3 calls it once its notification is ready, after the saved queue is read and
 * the player holds a song, and never when there is no song: no saved queue after all, a read that
 * failed, or one still running when the time is up. Android then closed the app (a Galaxy A05s on
 * Android 15). The service now shows a plain notification under the id media3's notification takes
 * over: at once before Android 12, and from it only when the time is nearly up or there is nothing
 * to play, when it is shown and taken away at once and the service stops.
 */
internal object MediaButtonStart {

    /**
     * When the service looks again after a media button's start: 25 s from Android 12, whose limit
     * is 30 s; before it, where the foreground is held from the start, 30 s to give up on a start
     * that came to nothing.
     */
    fun guardMs(sdk: Int): Long = if (sdk >= Build.VERSION_CODES.S) 25_000L else 30_000L

    /**
     * The plain notification, on media3's channel, made here as media3 makes it when it is not there
     * yet (DefaultMediaNotificationProvider: low importance, no badge before Android 9), so media3
     * finds it and keeps it.
     */
    fun placeholder(context: Context, channelId: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService<NotificationManager>()?.let { manager ->
                if (manager.getNotificationChannel(channelId) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(channelId, context.getString(R.string.music_player), NotificationManager.IMPORTANCE_LOW).apply {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) setShowBadge(false)
                        }
                    )
                }
            }
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.media_button_resuming))
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }
}
