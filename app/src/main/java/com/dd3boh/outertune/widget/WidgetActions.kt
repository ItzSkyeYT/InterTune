/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.session.MediaButtonReceiver
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.playback.MusicService

/**
 * The buttons on the widget.
 *
 * A press has to work whether or not the app is running, which is the whole difficulty of a media
 * widget. While the service is alive the key goes straight to it. While it is not, the same key
 * goes to media3's own media button receiver, which starts the service and asks it to resume the
 * queue it was on: the identical path a headset button or a car takes, already implemented here as
 * onPlaybackResumption. Nothing new decides what to play.
 */
object WidgetCommands {
    private const val TAG = "WidgetCommands"

    fun mediaKey(context: Context, keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
        val sent = runCatching {
            if (MusicService.isRunning) {
                intent.component = ComponentName(context, MusicService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } else {
                // Cold: the receiver starts the service and media3 waits for the session before it
                // delivers the key, so the queue is back by the time play means anything.
                intent.component = ComponentName(context, MediaButtonReceiver::class.java)
                context.sendBroadcast(intent)
            }
            true
        }.onFailure { Log.w(TAG, "Could not send $keyCode to the player", it) }.getOrDefault(false)

        // Whatever went wrong, the tap must not be silence: the app can always do it.
        if (!sent) openApp(context)
    }

    fun openApp(context: Context) = runCatching {
        context.startActivity(appIntent(context))
    }.onFailure { Log.w(TAG, "Could not open the app", it) }.getOrDefault(Unit)

    fun appIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Tapping a Quick pick opens the app on that song and plays it, exactly as tapping the card does. */
    fun playIntent(context: Context, song: WidgetSong): Intent =
        appIntent(context)
            .setAction(ACTION_PLAY_SONG)
            .putExtra(EXTRA_SONG_ID, song.id)
            .putExtra(EXTRA_SONG_TITLE, song.title)
            .putExtra(EXTRA_SONG_ARTIST, song.artist)
            .putExtra(EXTRA_SONG_THUMBNAIL, song.thumbnailUrl)
            .putExtra(EXTRA_SONG_DURATION, song.durationSec)
            // A distinct data uri per song, or the launcher hands every row the first song's
            // intent: PendingIntents that differ only by their extras are the same PendingIntent.
            .setData(android.net.Uri.parse("intertune://widget/song/${song.id}"))

    const val ACTION_PLAY_SONG = "com.dd3boh.outertune.widget.PLAY_SONG"
    const val EXTRA_SONG_ID = "widget_song_id"
    const val EXTRA_SONG_TITLE = "widget_song_title"
    const val EXTRA_SONG_ARTIST = "widget_song_artist"
    const val EXTRA_SONG_THUMBNAIL = "widget_song_thumbnail"
    const val EXTRA_SONG_DURATION = "widget_song_duration"
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetCommands.mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}
