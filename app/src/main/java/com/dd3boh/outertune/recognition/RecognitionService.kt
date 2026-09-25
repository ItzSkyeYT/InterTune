/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the microphone alive while the screen is off.
 *
 * The point of continuous recognition is a device left face down on a table filling a playlist by
 * itself, and none of that works from an Activity. Android stops a background process recording the
 * moment it loses foreground importance, so listening has to be a foreground service with the
 * microphone type, which in turn means a notification the user can see and stop. That notification
 * is not decoration; it is the price of the permission, and it is right that somebody can always
 * tell when an app is listening to a room.
 *
 * The service owns nothing but the lifetime. [RecognitionEngine] holds the loop and the state, so
 * the sheet can come and go without interrupting a run.
 */
@AndroidEntryPoint
class RecognitionService : Service() {

    @Inject lateinit var engine: RecognitionEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Stopping is its own collector so that it is prompt. It used to be a check at the top of
        // the redraw loop, which tied how quickly the service goes away to how often it redraws,
        // and the redraw interval is about to stop being one second.
        scope.launch {
            engine.running.collect { running -> if (!running) stopSelf() }
        }

        // Redrawn on a timer rather than only on events, because the progress bar has to move
        // between recognitions. The position is arithmetic on the last match, not a new request,
        // so a redraw is cheap, but cheap once a second for hours is not cheap: a continuous run
        // has no timeout and stops only when somebody stops it, so this was waking the process
        // 3600 times an hour for as long as it listened.
        //
        // With the screen off nobody is looking at the progress bar, and the only thing the timer
        // has to do is exist. So it drops to a slow tick and the notification is redrawn on the
        // way back: isInteractive is a cheap read, and the first pass after the screen comes on
        // repaints before anybody has focused on it.
        scope.launch {
            val power = getSystemService(PowerManager::class.java)
            while (engine.running.value) {
                val watching = power?.isInteractive != false
                if (watching) redraw()
                delay(if (watching) 1000L else 30_000L)
            }
        }

        // And straight away when the song changes or comes down, whatever the screen is doing.
        // The timer skips its redraw with the screen off and then sleeps for thirty seconds, so the
        // phone could be picked up to a song the engine had already dropped, still showing its
        // progress. Keyed on what the notification says rather than on every fresh estimate, so a
        // long run costs a redraw or two per song and not one per listen window. The first value is
        // skipped because onStartCommand draws it.
        scope.launch {
            engine.nowPlaying
                .map { it?.let { playing -> Triple(playing.title, playing.artist, playing.durationSeconds) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (engine.running.value) redraw() }
        }
        // The count under it, for the same reason.
        scope.launch {
            combine(engine.added, engine.recognised) { added, heard -> added.size to heard.size }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (engine.running.value) redraw() }
        }
    }

    /**
     * Posting needs POST_NOTIFICATIONS from API 33. Without it the update is simply dropped, which
     * is correct: the service is already foreground and the person refused to be told about it.
     */
    private fun redraw() {
        val notifier = NotificationManagerCompat.from(this)
        if (notifier.areNotificationsEnabled()) {
            notifier.notify(NOTIFICATION_ID, build())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Any ACTION_STOP, which is the notification button and RecognitionViewModel.stop
            // alike. Naming the notification sent me hunting a phantom tap.
            Log.i(TAG, "Stop requested")
            engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = build()
        // The microphone type exists from Android 11. On 10 the two-argument call already takes the
        // types declared in the manifest, which is the same microphone flag, so nothing changes there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Not sticky on purpose. If the system kills this, silently reopening the microphone later
        // without the user asking is exactly the behaviour nobody wants from a listening feature.
        return START_NOT_STICKY
    }

    private fun build(): android.app.Notification {
        val playing = engine.nowPlaying.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // A playlist's run adds to it. The screen's run adds to no playlist and lists what it heard
        // instead, where "Nothing added yet" sat under a list of songs for the whole run.
        val counted = if (engine.addsToPlaylist) {
            val added = engine.added.value.size
            if (added == 0) getString(R.string.recognition_service_none)
            else resources.getQuantityString(R.plurals.recognition_service_added, added, added)
        } else {
            val heard = engine.recognised.value.size
            if (heard == 0) getString(R.string.recognition_service_none_heard)
            else resources.getQuantityString(R.plurals.recognition_service_heard, heard, heard)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(open)
            .addAction(0, getString(R.string.recognition_service_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (playing == null) {
            return builder
                .setContentTitle(getString(R.string.recognition_service_title))
                .setContentText(counted)
                .build()
        }

        val position = playing.positionSeconds()
        builder
            .setContentTitle(listOfNotNull(playing.title, playing.artist).joinToString(" - "))
            .setSubText(counted)

        val duration = playing.durationSeconds
        if (duration != null && duration > 0) {
            // Where the room is in the song, not where this app is: nothing here is playing it.
            builder
                .setContentText("${time(position)} / ${time(duration)}")
                .setProgress(duration, position.coerceAtMost(duration), false)
        } else {
            builder.setContentText(getString(R.string.recognition_service_title))
        }
        return builder.build()
    }

    private fun time(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun createChannel() {
        // Channels arrived in API 26 and this app still runs on 24, where every call below throws.
        // NotificationManagerCompat posts fine without one.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recognition_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecognitionService"
        private const val CHANNEL_ID = "song_recognition"
        private const val NOTIFICATION_ID = 4243
        const val ACTION_STOP = "com.dd3boh.outertune.recognition.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RecognitionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecognitionService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
