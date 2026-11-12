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
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import com.dd3boh.outertune.R

/**
 * A service that a media button starts with nothing in the player meets Android's demand for the
 * foreground even when nothing comes of it (issue #98).
 *
 * A play press from a headset, a car or the widget with the app closed starts the service with
 * startForegroundService, and Android then waits for startForeground ([limitMs]). media3 calls it
 * once its notification is ready, after the saved queue is read and the player holds a song, and
 * never when there is no song: no saved queue after all, a read that failed, or one still running
 * when the time is up. Android then closed the app (a Galaxy A05s on Android 15). The service now
 * shows a plain notification under the id media3's notification takes over: at once before
 * Android 12, and from it only when the time is nearly up or there is nothing to play, when it is
 * shown and taken away at once and the service stops.
 *
 * A play press also reaches a service that is running, paused and out of the foreground, the same
 * way: from the widget, from the play button of media3's notification, from the receiver. media3
 * then plays and shows at once, unless the song does not start (Android refuses the audio focus
 * during a call, say), so the guard looks at that start too.
 *
 * And when the app's process dies while Android still waits, Android brings the service back in
 * a new process, with no intent, and waits again there: issue #137, a trace with no caller in the
 * app, since the call was made by the process that died (MusicService.settleAfterRestart).
 */
internal object MediaButtonStart {

    /**
     * How long Android waits for startForeground after startForegroundService before it closes the
     * app: 5 s on Android 8, 10 s from Android 9 to 12L, 30 s from Android 13 (AOSP:
     * ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT, then ActivityManagerConstants'
     * DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS). Issues #116 and #131 were Android 12.
     */
    fun limitMs(sdk: Int): Long = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> 30_000L
        sdk >= Build.VERSION_CODES.P -> 10_000L
        else -> 5_000L
    }

    /**
     * When the service looks again after a media button's start. [held], the plain notification
     * up since the press, it is 30 s, to give up on a start that came to nothing. Otherwise it is
     * well before Android's limit, 20 s of the 30 and half of the shorter ones: Android counts
     * from when it hands the start to the app, the service from when it sees the press, and the
     * service's own start lies between the two (2 s on a Pixel 5, debug build).
     */
    fun guardMs(sdk: Int, held: Boolean): Long = when {
        held -> 30_000L
        sdk >= Build.VERSION_CODES.TIRAMISU -> 20_000L
        sdk >= Build.VERSION_CODES.P -> 5_000L
        else -> 2_500L
    }

    /** How long the plain notification stays over a player whose song never started, before it goes. */
    const val LET_GO_MS = 60_000L

    /**
     * Whether a media key asks to play: the keys that media3's receiver, the widget and the play
     * button of media3's notification send with startForegroundService.
     */
    fun startsPlayback(keyCode: Int?): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK

    /**
     * Whether the service looks again after a media button's start that finds it out of the
     * foreground. With nothing in the player, always: nothing may come of it. With a song, media3
     * plays and shows at once, so only for a key that asks to play, the one that comes with
     * Android's demand, and only from Android 10, where the service can tell that media3 has met it.
     */
    fun looksAgain(hasSong: Boolean, keyCode: Int?, sdk: Int): Boolean =
        !hasSong || (startsPlayback(keyCode) && sdk >= Build.VERSION_CODES.Q)

    /** The key of a media button's start, null when it carries none. */
    fun keyOf(intent: Intent?): Int? = runCatching {
        intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }?.keyCode
    }.getOrNull()

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
