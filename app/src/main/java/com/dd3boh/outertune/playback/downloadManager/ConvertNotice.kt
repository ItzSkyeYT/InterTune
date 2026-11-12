/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.NotificationUtil
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadFormat
import com.dd3boh.outertune.playback.DownloadsShortcut
import com.dd3boh.outertune.playback.ExoDownloadService

/**
 * What the phone shows while it converts songs (Unreleased.DOWNLOAD_FORMATS): "Converting to MP3",
 * "3 of 12", in the downloads' own channel, and, when the run is over, a line that stays if some
 * songs could not be converted and were saved as they came. The notification of the downloads
 * themselves ends when the last song has landed; the conversion that follows it is this one.
 *
 * Posted from the conversion's own thread, and quiet: low importance, no sound, not repeated. Where
 * the notification permission has been refused nothing is shown and nothing fails.
 */
internal class ConvertNotice(
    private val context: Context,
    private val format: DownloadFormat,
    private val total: Int,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var fellBack = 0

    init {
        makeChannel(context)
    }

    /** Song number [index], counting from 0, is being converted. */
    fun starting(index: Int, title: String?) {
        val text = context.getString(R.string.convert_notice_text, index + 1, total)
        val builder = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(context.getString(R.string.convert_notice_title, label(format)))
            .setContentText(text)
            .setSubText(title)
            .setProgress(total, index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(DownloadsShortcut.pendingIntent(context))
        post(ONGOING_ID, builder.build())
    }

    /** A song could not be converted and was saved as it came. */
    fun fellBack() {
        fellBack++
    }

    /** The run is over: the ongoing notice goes, and a line stays when some songs were not converted. */
    fun finish() {
        manager.cancel(ONGOING_ID)
        if (fellBack > 0) {
            val builder = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
                .setSmallIcon(R.drawable.download)
                .setContentTitle(context.getString(R.string.convert_fallback_title))
                .setContentText(context.resources.getQuantityString(R.plurals.convert_fallback_text, fellBack, fellBack))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(DownloadsShortcut.pendingIntent(context))
            post(FALLBACK_ID, builder.build())
        }
    }

    private fun post(id: Int, notification: android.app.Notification) {
        post(manager, id, notification)
    }

    companion object {
        /** Its own ids: 1 is the downloads' and 2000 on are their failures'. MoveNotice shares the ongoing one. */
        const val ONGOING_ID = 3
        const val FALLBACK_ID = 4

        /** The service makes the channel with the first download of a run; an export or a move may come before one. */
        fun makeChannel(context: Context) {
            NotificationUtil.createNotificationChannel(context, ExoDownloadService.CHANNEL_ID, R.string.action_download, 0, NotificationUtil.IMPORTANCE_LOW)
        }

        @SuppressLint("MissingPermission")
        fun post(manager: NotificationManagerCompat, id: Int, notification: android.app.Notification) {
            // Refused on Android 13 and later without the permission: no notice, and no reason to fail a conversion or a move.
            runCatching { manager.notify(id, notification) }
        }

        /** The format as a person says it. */
        fun label(format: DownloadFormat): String = when (format) {
            DownloadFormat.M4A -> "M4A"
            else -> format.name
        }

        /**
         * A notice left up by a run that Android ended with the app: nothing is converting when the
         * app starts, so any ongoing one is stale.
         */
        fun clearStale(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(ONGOING_ID) }
        }
    }
}
