/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The media session hears everything the player says (review of 27 Nov 2026). A wrapper of each listener in
 * SessionPlayer.addListener, `object : Player.Listener by listener`, passed on one method alone: Player.Listener's are
 * all Java default methods, which Kotlin's `by` does not forward. The session then never heard a play, a pause, a song
 * change or a position, and the notification, the lock screen and the watch said "buffering, 0:00" for every song. Run
 * on the JVM against a player of the test's own: what the player tells its listeners reaches the session's.
 */
class SessionPlayerListenersTest {
    /** A player that only keeps the listeners it is given, and answers anything else with nothing. */
    private class Inner {
        val listeners = mutableListOf<Player.Listener>()
        var paused = 0
        var played = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "pause" -> { paused++; null }
                "play" -> { played++; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player
    }

    private class Heard : Player.Listener {
        val said = mutableListOf<String>()
        var eventsPlayer: Player? = null
        override fun onIsPlayingChanged(isPlaying: Boolean) { said += "isPlaying $isPlaying" }
        override fun onPlaybackStateChanged(playbackState: Int) { said += "state $playbackState" }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { said += "playWhenReady $playWhenReady" }
        override fun onEvents(player: Player, events: Player.Events) { said += "events"; eventsPlayer = player }
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { said += "commands" }
    }

    @Test
    fun `a listener added through the session player hears every kind of event, onEvents with the session player`() {
        val inner = Inner()
        val session = SessionPlayer(inner.player)
        val heard = Heard()
        // The session player keeps one of its own on the player (its cover's name, SessionPlayer.init).
        val before = inner.listeners.size
        session.addListener(heard)
        assertEquals(before + 1, inner.listeners.size)
        val given = inner.listeners.last()
        given.onIsPlayingChanged(true)
        given.onPlaybackStateChanged(Player.STATE_READY)
        given.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        given.onEvents(inner.player, Player.Events(FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build()))
        given.onAvailableCommandsChanged(Player.Commands.EMPTY)
        assertEquals(listOf("isPlaying true", "state ${Player.STATE_READY}", "playWhenReady true", "events", "commands"), heard.said)
        // As media3's own ForwardingPlayer gives it: the session asks the player it knows, this one.
        assertSame(session, heard.eventsPlayer)
        // And it lets go of it again.
        session.removeListener(heard)
        assertEquals(before, inner.listeners.size)
    }

    @Test
    fun `a pause through the session goes on unless a listening room takes it, a play always`() {
        val inner = Inner()
        val session = SessionPlayer(inner.player)
        session.pause()
        assertEquals(1, inner.paused)
        var taken = true
        session.onAskedPause = { taken }
        session.pause()
        assertEquals("a guest's mute in its place", 1, inner.paused)
        taken = false
        session.pause()
        assertEquals(2, inner.paused)
        var asked = 0
        session.onAskedPlay = { asked++ }
        session.play()
        assertEquals(1, asked)
        assertEquals(1, inner.played)
    }
}
