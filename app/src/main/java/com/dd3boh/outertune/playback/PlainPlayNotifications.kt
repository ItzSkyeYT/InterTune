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
import androidx.media3.session.SessionCommand
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
 *
 * Its custom buttons (like, shuffle, radio...) are made once per session and given again on every
 * update ([OncePerButton], #271).
 */
@OptIn(UnstableApi::class)
internal class PlainPlayNotifications(
    private val service: Service,
    notificationIdProvider: DefaultMediaNotificationProvider.NotificationIdProvider,
    channelId: String,
    @StringRes channelNameResourceId: Int,
) : DefaultMediaNotificationProvider(service, notificationIdProvider, channelId, channelNameResourceId) {

    /** Kept with the provider, which lives as long as the player's service, so that it holds from one update to the next. */
    private val buttons = OncePerButton<MediaSession, PendingIntent>()

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray = super.addNotificationActions(mediaSession, mediaButtons, builder, PlainPlayActions(service, actionFactory, buttons))
}

/**
 * media3's [actions], with the play button's press as a plain start of [service] (see [PlainPlayNotifications]), and each
 * custom button's PendingIntent the one made the first time ([buttons]).
 */
@OptIn(UnstableApi::class)
internal class PlainPlayActions(
    private val service: Service,
    private val actions: MediaNotification.ActionFactory,
    private val buttons: OncePerButton<MediaSession, PendingIntent>,
) : MediaNotification.ActionFactory {

    override fun createMediaAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int): NotificationCompat.Action =
        if (command == Player.COMMAND_PLAY_PAUSE) NotificationCompat.Action(icon, title, playPause())
        else actions.createMediaAction(mediaSession, icon, title, command)

    override fun createCustomAction(mediaSession: MediaSession, icon: IconCompat, title: CharSequence, customAction: String, extras: Bundle): NotificationCompat.Action =
        NotificationCompat.Action(icon, title, buttons.get(mediaSession, OncePerButton.key(customAction, extras.asMap())) {
            checkNotNull(actions.createCustomAction(mediaSession, icon, title, customAction, extras).actionIntent)
        })

    override fun createCustomActionFromCustomCommandButton(mediaSession: MediaSession, customCommandButton: CommandButton): NotificationCompat.Action {
        // media3 refuses a button that is no custom command: asked as before, it says so as before.
        val command = customCommandButton.sessionCommand?.takeIf { it.commandCode == SessionCommand.COMMAND_CODE_CUSTOM }
            ?: return actions.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)
        val intent = buttons.get(mediaSession, OncePerButton.key(command.customAction, command.customExtras.asMap())) {
            checkNotNull(actions.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton).actionIntent)
        }
        // As media3 makes it (DefaultActionFactory), with the button's icon and name of this moment: a like's heart fills.
        return NotificationCompat.Action(IconCompat.createWithResource(service, customCommandButton.iconResId), customCommandButton.displayName, intent)
    }

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

/**
 * One PendingIntent per custom button of the player's notification and per session, made once and given again on every
 * update (#271, 0.12.11 on a Xiaomi with Android 16: "Too many PendingIntent created", and the player's service died
 * mid-song).
 *
 * media3 gives each custom button a new request code every time it builds the notification (1.8.0 counts one up, its
 * later versions pick one at random), so every update left one more PendingIntent per button with Android, and the
 * notification is built again on every play, pause, skip and cover. Stock Android only warns past 5000 of them; Xiaomi's
 * HyperOS refuses the next one, and the refusal reached the service. The same button keeps its first PendingIntent now:
 * what it carries (its action and extras, and the session's address) is the same each time, and media3 sends
 * every press to the session the address names. A new session (the player's service made again) starts afresh.
 */
internal class OncePerButton<S : Any, V : Any> {
    private var owner: S? = null
    private val made = HashMap<String, V>()

    /** How many are kept (for the tests). */
    val size: Int get() = made.size

    @Synchronized
    fun get(owner: S, key: String, make: () -> V): V {
        if (this.owner !== owner) {
            made.clear()
            this.owner = owner
        }
        made[key]?.let { return it }
        // More than a notification ever shows: a button whose extras change every time would pile up again. Start over.
        if (made.size >= MOST) made.clear()
        return make().also { made[key] = it }
    }

    companion object {
        const val MOST = 32

        /** The action and its extras, whatever their order. */
        fun key(action: String, extras: Map<String, Any?>): String =
            if (extras.isEmpty()) action
            else action + extras.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
    }
}

@Suppress("DEPRECATION") // Bundle.get: any kind of value, read only to tell two buttons apart.
private fun Bundle.asMap(): Map<String, Any?> = keySet().associateWith { get(it) }
