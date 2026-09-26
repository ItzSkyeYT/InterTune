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
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.ResumingMediaButtonReceiver

/**
 * The buttons on the widget.
 *
 * A press has to work whether or not the app is running, which is the whole difficulty of a media
 * widget. While the service is alive the key goes straight to it. While it is not, the same key
 * goes to the media button receiver, which starts the service and asks it to resume the queue it
 * was on: the identical path a headset button or a car takes, already implemented here as
 * onPlaybackResumption. Nothing new decides what to play. With no queue saved there is nothing to
 * resume, and play opens the app instead.
 */
object WidgetCommands {
    private const val TAG = "WidgetCommands"

    suspend fun mediaKey(context: Context, keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
        // Only play and pause can start playback, and only playback calls startForeground, so only
        // they may go through startForegroundService. Next and previous went that way too: sent to
        // a service still alive after the paused foreground tail had ended, they left Android
        // waiting for a startForeground that never came, which it answers by crashing the app.
        // media3's own notification buttons follow the same rule.
        val startsPlayback = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        val sent = runCatching {
            if (MusicService.isRunning) {
                intent.component = ComponentName(context, MusicService::class.java)
                if (startsPlayback) ContextCompat.startForegroundService(context, intent)
                // Refused in the background once the service has left the foreground; the catch
                // below then opens the app instead.
                else context.startService(intent)
                true
            } else if (startsPlayback && ResumingMediaButtonReceiver.hasSavedQueue(context)) {
                // Cold: the receiver starts the service and media3 waits for the session before it
                // delivers the key, so the queue is back by the time play means anything.
                intent.component = ComponentName(context, ResumingMediaButtonReceiver::class.java)
                context.sendBroadcast(intent)
                true
            } else {
                // The receiver drops every key but play from a cold start, so next and previous did
                // nothing at all with the app closed. The app can do them. So can it play with no
                // queue saved, where the receiver now starts nothing rather than a service that
                // Android would kill the app over.
                false
            }
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
