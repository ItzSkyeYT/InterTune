/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.DownloadsShortcut
import com.dd3boh.outertune.playback.ExoDownloadService

/**
 * What the phone shows while the downloads kept inside the app are moved into the download folder
 * (Settings > Storage > Migrate, and the move offered once a folder is chosen): "Moving downloads",
 * "120 of 360", and when the move is over a line that stays, either that it is done or how many
 * songs could not be moved and stay inside the app (his word of 12 Nov 2026, "a notification when
 * transferring"). The quiet move after a download shows nothing.
 *
 * Built like ConvertNotice and beside it: the downloads' channel, quiet, and ConvertNotice's
 * ongoing id, so a move that also converts is one line in the shade, Converting while a song is
 * encoded and Moving between two. Made only where it can be seen ([canShow]); where it cannot, the
 * move tells what it told before.
 */
internal class MoveNotice(
    private val context: Context,
    private val total: Int,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var lastPosted = 0L

    init {
        ConvertNotice.makeChannel(context)
        Log.i(TAG, "Moving $total downloads into the folder, with the notification")
    }

    /** Song number [index], counting from 0, is being moved. Not more often than Android shows. */
    fun starting(index: Int) {
        val now = SystemClock.elapsedRealtime()
        if (index > 0 && now - lastPosted < MIN_GAP_MS) return
        lastPosted = now
        val builder = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(context.getString(R.string.dl_move_notice_title))
            .setContentText(context.getString(R.string.dl_move_notice_text, index + 1, total))
            .setProgress(total, index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(DownloadsShortcut.pendingIntent(context))
        ConvertNotice.post(manager, ConvertNotice.ONGOING_ID, builder.build())
    }

    /** The move is over, [moved] of the songs in the folder now: the ongoing line goes and the result stays. */
    fun finish(moved: Int) {
        manager.cancel(ConvertNotice.ONGOING_ID)
        val left = stayInside(total, moved)
        Log.i(TAG, "Moved $moved of $total downloads into the folder, $left stay inside the app")
        val builder = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(DownloadsShortcut.pendingIntent(context))
        if (left == 0) {
            builder.setContentTitle(context.getString(R.string.dl_move_notice_done))
        } else {
            val text = context.resources.getQuantityString(R.plurals.dl_move_notice_failed, left, left)
            builder.setContentTitle(context.getString(R.string.dl_move_notice_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        ConvertNotice.post(manager, RESULT_ID, builder.build())
    }

    companion object {
        private const val TAG = "MoveNotice"

        /** The result's id; the ongoing line is ConvertNotice.ONGOING_ID. */
        const val RESULT_ID = 5

        /** Android drops the updates of one notification that come faster than about five a second. */
        private const val MIN_GAP_MS = 250L

        /** Where no notification shows, the move says how far it is in a toast every so many songs. */
        const val TOAST_EVERY = 20

        /** How many of [total] songs stay inside the app when [moved] of them reached the folder. */
        fun stayInside(total: Int, moved: Int): Int = (total - moved).coerceAtLeast(0)

        /**
         * Whether the move says how far it is in a toast, [done] songs in: one way of telling, so
         * never beside the notification, and never for the quiet move after a download.
         */
        fun toastDue(done: Int, quiet: Boolean, noticeShown: Boolean): Boolean =
            !quiet && !noticeShown && done > 0 && done % TOAST_EVERY == 0

        /**
         * Whether the notification would be seen: notifications allowed for the app (on Android 13
         * and later, the permission) and the downloads' channel not turned off.
         */
        fun canShow(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = runCatching { manager.getNotificationChannelCompat(ExoDownloadService.CHANNEL_ID) }.getOrNull()
            return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }
    }
}
