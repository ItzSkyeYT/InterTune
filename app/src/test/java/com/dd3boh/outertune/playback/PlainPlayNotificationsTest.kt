/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.session.MediaNotification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * #184, #195, #204, #208, #216 (0.12.10 and 0.12.11, Samsung and Xiaomi): Android closed the app
 * for a foreground start the app itself never made. media3's notification gives a paused player's
 * play button a PendingIntent.getForegroundService, pressed from outside the app, and a song that
 * then did not start left Android waiting. That press is now a plain start of the service.
 */
class PlainPlayNotificationsTest {

    private val source = File("src/main/java/com/dd3boh/outertune/playback/PlainPlayNotifications.kt").readText()

    @Test
    fun `every button but play is made by media3 as before`() {
        // A newer media3 that adds a way to make a button fails here, or that button would go
        // through the interface's default instead of media3's factory.
        val actions = PlainPlayActions::class.java
        val missing = MediaNotification.ActionFactory::class.java.methods
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { m -> runCatching { actions.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure }
            .map { it.name }
        assertTrue("not passed on: $missing", missing.isEmpty())
        assertTrue(source, "else actions.createMediaAction(mediaSession, icon, title, command)" in source)
        assertTrue(source, "else actions.createMediaActionPendingIntent(mediaSession, command)" in source)
        assertTrue(source, "actions.createNotificationDismissalIntent(mediaSession)" in source)
    }

    @Test
    fun `play is a plain start of the player's service with the play-pause key`() {
        assertTrue(source, "if (command == Player.COMMAND_PLAY_PAUSE) NotificationCompat.Action(icon, title, playPause())" in source)
        assertTrue(source, "if (command == Player.COMMAND_PLAY_PAUSE.toLong()) playPause()" in source)
        val playPause = source.substringAfter("private fun playPause(): PendingIntent = ").substringBefore("\n    )\n")
        assertTrue(playPause, playPause.startsWith("PendingIntent.getService("))
        assertFalse("never a foreground start", "getForegroundService" in playPause)
        assertTrue(playPause, "KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE" in playPause && "PendingIntent.FLAG_IMMUTABLE" in playPause)
        val intent = source.substringAfter("fun playPauseIntent(service: Service): Intent = ")
        assertTrue(intent, intent.startsWith("Intent(Intent.ACTION_MEDIA_BUTTON)"))
        assertTrue(intent, ".setComponent(ComponentName(service, service.javaClass))" in intent)
        assertTrue(intent, "KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)" in intent)
    }

    @Test
    fun `media3's notification takes its buttons from here`() {
        assertTrue(source, "super.addNotificationActions(mediaSession, mediaButtons, builder, PlainPlayActions(service, actionFactory))" in source)
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertFalse("media3's own provider is not set bare any more", "DefaultMediaNotificationProvider(\n" in service)
        assertTrue(Regex("""setMediaNotificationProvider\(\s*PlainPlayNotifications\(""").containsMatchIn(service))
    }
}
