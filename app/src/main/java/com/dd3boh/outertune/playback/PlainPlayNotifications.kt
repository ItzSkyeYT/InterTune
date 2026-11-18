/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * media3's notification as it makes it, except that its play button starts the player's service
 * as a plain start, never as a foreground one (#184, #195, #204, #208, #216).
 *
 * media3 gives the play button of a paused player a PendingIntent.getForegroundService, and
 * Android then closes the app unless the service calls startForeground within its time, whatever
 * happens to the press. The press comes from outside the app (the media controls of Android 11
 * and 12, a phone maker's own controls, a watch or anything else that fires the notification's
 * buttons), so a trace of that close names no caller in the app: the five reports of 0.12.10 and
 * 0.12.11 had none, on Samsung and Xiaomi phones, after the media button receiver's crash was
 * fixed. The press is now a plain start, which Android allows from a notification's button,
 * as it allows the foreground the player then asks for when the song starts, and which owes
 * Android nothing when the song does not start.
 */
@OptIn(UnstableApi::class)
internal class PlainPlayNotifications(
    private val service: Service,
    notificationIdProvider: DefaultMediaNotificationProvider.NotificationIdProvider,
    channelId: String,
    @StringRes channelNameResourceId: Int,
) : DefaultMediaNotificationProvider(service, notificationIdProvider, channelId, channelNameResourceId) {

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray = super.addNotificationActions(mediaSession, mediaButtons, builder, PlainPlayActions(service, actionFactory))
}

/** media3's [actions], with the play button's press as a plain start of [service] (see [PlainPlayNotifications]). */
@OptIn(UnstableApi::class)
internal class PlainPlayActions(
    private val service: Service,
    private val actions: MediaNotification.ActionFactory,
) : MediaNotification.ActionFactory {

    override fun createMediaAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int): NotificationCompat.Action =
        if (command == Player.COMMAND_PLAY_PAUSE) NotificationCompat.Action(icon, title, playPause())
        else actions.createMediaAction(mediaSession, icon, title, command)

    override fun createCustomAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, customAction: String, extras: Bundle): NotificationCompat.Action =
        actions.createCustomAction(mediaSession, icon, title, customAction, extras)

    override fun createCustomActionFromCustomCommandButton(mediaSession: MediaSession, customCommandButton: CommandButton): NotificationCompat.Action =
        actions.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)

    override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Long): PendingIntent =
        if (command == Player.COMMAND_PLAY_PAUSE.toLong()) playPause()
        else actions.createMediaActionPendingIntent(mediaSession, command)

    override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent =
        actions.createNotificationDismissalIntent(mediaSession)

    /**
     * Play/pause, as media3 sends it to the service (a media button with the key), by
     * PendingIntent.getService. media3's onStartCommand finds the session without the session's
     * address in the intent (MediaSessionService.onGetSession), and the address is media3's own.
     */
    private fun playPause(): PendingIntent = PendingIntent.getService(
        service,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        playPauseIntent(service),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Marked as the plain press it is ([MediaButtonStart.EXTRA_PLAIN]), which the service's guard leaves alone. */
        fun playPauseIntent(service: Service): Intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(service, service.javaClass))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            .putExtra(MediaButtonStart.EXTRA_PLAIN, true)
    }
}
