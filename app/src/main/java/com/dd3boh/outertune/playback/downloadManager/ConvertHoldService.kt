/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.NotificationUtil
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.DownloadsShortcut
import com.dd3boh.outertune.playback.ExoDownloadService

/**
 * The foreground service the app is while it converts songs (Unreleased.DOWNLOAD_FORMATS), so that
 * Android does not stop the conversion when the person leaves the app: see [ConvertHold], which
 * starts and stops it. It does no work itself; the conversion runs where it always did.
 *
 * Its notification is the one ConvertNotice shows ("Converting to MP3", "3 of 12"), under the same
 * id: the one that is up is taken as it is, and ConvertNotice goes on updating it. Android removes
 * it when this service stops.
 *
 * Started with a plain startService, never startForegroundService, which closes the app when the
 * promotion does not follow. Android refuses the promotion to an app in the background (a download
 * that landed while the person was away): one line in the log, and the conversion goes on as it
 * did before this service. Android 15 gives a dataSync service six hours a day in the background,
 * shared with the download service: [onTimeout] stops at once, as Android asks.
 */
class ConvertHoldService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The download service makes the channel with the first download; an export may come before one.
        NotificationUtil.createNotificationChannel(this, ExoDownloadService.CHANNEL_ID, R.string.action_download, 0, NotificationUtil.IMPORTANCE_LOW)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = shown() ?: plain(intent?.getStringExtra(LABEL))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ConvertNotice.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ConvertNotice.ONGOING_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is in the background, or its six hours are used up.
            Log.w(TAG, "Android refused to keep the app up while it converts (${e.javaClass.simpleName}): the conversion goes on, and may stand still until the app is in front again")
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Android ended the time in the foreground of the service that keeps the app up while it converts (type $fgsType): it stops, the conversion goes on")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** ConvertNotice's notification as it is up now, or null when none is. */
    private fun shown(): Notification? = runCatching {
        getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == ConvertNotice.ONGOING_ID }?.notification
    }.getOrNull()

    /** "Converting to MP3", for a conversion whose notice is not up yet. */
    private fun plain(label: String?): Notification =
        NotificationCompat.Builder(this, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(getString(R.string.convert_notice_title, label.orEmpty()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(DownloadsShortcut.pendingIntent(this))
            .build()

    companion object {
        private const val TAG = "ConvertHoldService"
        private const val LABEL = "label"

        /**
         * The hold a converter takes for each song ([ConvertHold]), started and stopped through this
         * service. [log] gets a line when the app is held, when it is let go, and when Android
         * refuses to start the service at all (an app in the background with nothing running).
         */
        internal fun hold(context: Context, log: (String) -> Unit): ConvertHold {
            val app = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            return ConvertHold(
                start = { label ->
                    try {
                        app.startService(Intent(app, ConvertHoldService::class.java).putExtra(LABEL, label))
                        log("The app stays up while it converts to $label")
                        true
                    } catch (e: IllegalStateException) {
                        log("Android refused to keep the app up while it converts, the app is in the background (${e.javaClass.simpleName}): the conversion goes on, and may stand still until the app is in front again")
                        false
                    }
                },
                stop = {
                    runCatching { app.stopService(Intent(app, ConvertHoldService::class.java)) }
                    log("Nothing is converted any more: the app is no longer kept up for it")
                },
                later = { delayMs, block -> main.postDelayed({ block() }, delayMs) },
            )
        }
    }
}
