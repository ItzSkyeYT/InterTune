/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rooms on Spotify Jam's model (his word of 26 Nov 2026: "Guests listen, host can control and can give a toggle to let
 * all guests control"). A guest who listens has its transport shown disabled and its play button a mute, in step with the
 * room; nobody goes their own way. With control for everyone, a guest's moves go to the one in control (Control), who
 * makes them for everyone. This reads the source where a service, a player and relays would be needed.
 */
class RoomListenTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val connection = File(src, "playback/PlayerConnection.kt").readText()
    private val service = File(src, "playback/MusicService.kt").readText()

    private fun body(text: String, signature: String): String {
        val from = text.indexOf(signature)
        assertTrue("no $signature", from >= 0)
        val open = text.indexOf('{', from)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("no end to $signature")
    }

    @Test
    fun `nobody goes their own way in a room any more`() {
        val chose = body(session, "private fun listenerChose(")
        assertFalse(chose, "ownWay = true" in chose)
        assertTrue(chose, "sayHeld()" in chose)
        for (gone in listOf("fun skipOnOwn()", "fun listenerStarts()", "fun addedHere()", "private fun goAlone()", "private fun backFromAlone(")) {
            assertFalse(gone, gone in session)
        }
        assertFalse(File(src, "together/RoomSkip.kt").exists())
    }

    @Test
    fun `a guest's pause by hand is a mute, its player in step with the room`() {
        val played = body(session, "private fun listenerPlayed(")
        val mute = played.indexOf("toggleMute()")
        assertTrue(played, mute > played.indexOf("service.exoPlayer.playWhenReady = true"))
        assertTrue(played, "reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST" in played)
        // A pause by the phone (a call, headphones out) is still paused here: Play takes it to where the room is.
        assertTrue(played, "pausedHere = PauseHere.after(pausedHere, playWhenReady, room.playing) ?: return" in played)
        // The mute is the player's volume at 0: the service's pipeline takes it, and leaving the room ends it.
        assertTrue("private val heardVolume: Float get() = if (roomMuted.value) 0f else" in service)
        assertTrue("combine(playerVolume, batteryModeNow, roomMuted)" in service)
        assertTrue(body(service, "fun stopTogether()").contains("roomMuted.value = false"))
        // The screens' play button mutes while it is a mute.
        val toggle = body(connection, "fun togglePlayPause()")
        assertTrue(toggle, toggle.indexOf("room.toggleMute()") in 0 until toggle.indexOf("loadQueueIfEmpty().togglePlayPause()"))
    }

    @Test
    fun `no mute where there is no mute button`() {
        assertTrue("if (muted && !listensOnly(_status.value)) {" in body(session, "private fun lookMute()"))
        assertTrue("lookMute()" in body(session, "private fun lookAlone()"))
    }

    @Test
    fun `a guest who listens has previous and next shown disabled`() {
        val update = body(connection, "private fun updateCanSkipPreviousAndNext()")
        assertTrue(update, "if (roomListens.value) {\n                canSkipPrevious.value = false\n                canSkipNext.value = false" in update)
    }

    @Test
    fun `with control for everyone a guest's moves go to the one in control`() {
        val chose = body(session, "private fun listenerChose(")
        assertTrue(chose, chose.indexOf("askRoom(TogetherMessage.Control(to, songId = song") in 0 until chose.indexOf("sayHeld()"))
        assertTrue("is TogetherMessage.Control -> if (leading && message.to == me && _status.value.settings?.everyoneControls == true) controlled(message, from)" in session)
        // Held as the guest left it until the room's word says it is made, for a while at most.
        val follow = body(session, "private fun follow()")
        assertTrue(follow, "if (System.currentTimeMillis() < askedUntil && !answered(state)) return" in follow)
    }

    @Test
    fun `a guest's move goes in its word and comes out the same`() {
        val move = TogetherMessage.Control(
            "ab12", playing = false, songId = "s1", positionMs = 61_000L, shuffle = true, repeat = 2,
            songs = listOf(TogetherMessage.Song("s2", "Song", "Artist", "")),
        )
        assertEquals(move, TogetherMessage.decodeFrom(TogetherMessage.encode(move, sender = "cd34"), "cd34"))
        val play = TogetherMessage.Control("ab12", playing = true)
        assertEquals(play, TogetherMessage.decodeFrom(TogetherMessage.encode(play, sender = "cd34"), "cd34"))
    }

    @Test
    fun `outside the app a guest who listens is offered no skip or seek, as far as media3 lets the session say`() {
        val session = File(src, "playback/SessionPlayer.kt").readText()
        assertTrue("override fun getAvailableCommands(): Player.Commands = roomFiltered(super.getAvailableCommands())" in session)
        assertTrue("listener.onAvailableCommandsChanged(roomFiltered(availableCommands))" in session)
        assertTrue("super.removeListener(given.remove(listener) ?: listener)" in session)
        assertTrue("room?.status?.map(::listensOnly) ?: flowOf(false) }\n                .distinctUntilChanged()\n                .collect { sessionPlayer.roomLocked = it }" in service)
    }

    @Test
    fun `while nobody plays for the room the sheet says so, and nothing goes to the room`() {
        val alone = body(session, "private fun lookAlone()")
        assertTrue(alone, "_status.value = _status.value.copy(nobodyPlays = alone)" in alone)
        val toRoom = body(connection, "private fun toRoom(")
        assertTrue(toRoom, "if (room.status.value.nobodyPlays) {" in toRoom)
    }
}
