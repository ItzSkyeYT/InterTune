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

    /** Review of 27 Nov 2026: every pause the app makes itself said "user request", and each one toggled the mute. */
    @Test
    fun `only the listener's own pause is a mute, through the screens' button or the media session`() {
        // The session's pause (the notification, a headset, a watch, a car) is asked before it reaches the player.
        val sessionPlayer = File(src, "playback/SessionPlayer.kt").readText()
        assertTrue("override fun pause() {\n        if (onAskedPause()) return\n        super.pause()" in sessionPlayer)
        assertTrue("sessionPlayer.onAskedPause = { together.value?.askedPause() == true }" in service)
        val asked = body(session, "fun askedPause()")
        assertTrue(asked, asked.indexOf("toggleMute()") in 0 until asked.indexOf("byHand()"))
        // A pause on the player itself, the app's or the phone's, is never a mute: paused here, the room plays on.
        val played = body(session, "private fun listenerPlayed(")
        assertFalse(played, "toggleMute()" in played)
        assertFalse(played, "PLAY_WHEN_READY_CHANGE_REASON" in played)
        assertTrue(played, "pausedHere = PauseHere.after(pausedHere, playWhenReady, room.playing) ?: return" in played)
        // The mute is the player's volume at 0: the service's pipeline takes it, and leaving the room ends it.
        assertTrue("private val heardVolume: Float get() = if (roomMuted.value) 0f else" in service)
        assertTrue("combine(playerVolume, batteryModeNow, roomMuted)" in service)
        // The screens' play button mutes while it is a mute.
        val toggle = body(connection, "fun togglePlayPause()")
        assertTrue(toggle, toggle.indexOf("room.toggleMute()") in 0 until toggle.indexOf("loadQueueIfEmpty().togglePlayPause()"))
    }

    @Test
    fun `a pause through the session mutes a guest who listens while the room plays, and nobody else`() {
        assertTrue(RoomListen.pauseMutes(listens = true, roomPlaying = true, pausedHere = false))
        assertFalse("the room paused: nothing to mute", RoomListen.pauseMutes(listens = true, roomPlaying = false, pausedHere = false))
        assertFalse("before the room's word", RoomListen.pauseMutes(listens = true, roomPlaying = null, pausedHere = false))
        assertFalse("paused here already", RoomListen.pauseMutes(listens = true, roomPlaying = true, pausedHere = true))
        assertFalse("in control, or with control for everyone", RoomListen.pauseMutes(listens = false, roomPlaying = true, pausedHere = false))
    }

    @Test
    fun `a play of the listener's own after a pause here takes the mute with it, the app's own play never does`() {
        assertTrue(RoomListen.playUnmutes(wasPausedHere = true, pausedHere = false, byHand = true, muted = true))
        // Recognise plays again after its listening: the mute stays.
        assertFalse(RoomListen.playUnmutes(wasPausedHere = true, pausedHere = false, byHand = false, muted = true))
        assertFalse(RoomListen.playUnmutes(wasPausedHere = false, pausedHere = false, byHand = true, muted = true))
        assertFalse(RoomListen.playUnmutes(wasPausedHere = true, pausedHere = true, byHand = true, muted = true))
        assertFalse(RoomListen.playUnmutes(wasPausedHere = true, pausedHere = false, byHand = true, muted = false))
        assertTrue("RoomListen.playUnmutes(was, pausedHere, byHand = handTurn, muted = muted)" in body(session, "private fun listenerPlayed("))
    }

    @Test
    fun `a room that ends while this phone is muted pauses it rather than playing aloud`() {
        val stop = body(service, "fun stopTogether()")
        val paused = stop.indexOf("if (wasMuted) player.pause()")
        assertTrue(stop, paused > stop.indexOf("together.value = null"))
        assertTrue(stop, paused in 0 until stop.indexOf("roomMuted.value = false"))
        assertTrue(stop, stop.indexOf("val wasMuted = roomMuted.value") in 0 until paused)
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
        val play = TogetherMessage.Control("ab12", playing = true, at = 1_764_200_000_000L)
        assertEquals(play, TogetherMessage.decodeFrom(TogetherMessage.encode(play, sender = "cd34"), "cd34"))
    }

    @Test
    fun `outside the app a guest who listens is offered no skip or seek, per controller, the session hearing everything`() {
        val sessionPlayer = File(src, "playback/SessionPlayer.kt").readText()
        // No wrapper of the session's listeners any more (SessionPlayerListenersTest).
        assertFalse("by listener" in sessionPlayer)
        assertTrue("override fun addListener(listener: Player.Listener) {\n        listeners += listener\n        super.addListener(listener)" in sessionPlayer)
        val lock = body(service, "private fun lockSessionForRoom(locked: Boolean)")
        assertTrue(lock, "session.setAvailableCommands(controller, commands, playerCommands)" in lock)
        assertTrue(lock, "updateNotification()" in lock)
        val callback = File(src, "playback/MediaLibrarySessionCallback.kt").readText()
        assertTrue("RoomSessionCommands.playerCommands(connectionResult.availablePlayerCommands, locked = service.roomLocksSession)," in callback)
        assertTrue("room?.status?.map(::listensOnly) ?: flowOf(false) }\n                .distinctUntilChanged()\n                .collect { lockSessionForRoom(it) }" in service)
    }

    @Test
    fun `the notification's own shuffle and repeat obey the room`() {
        val callback = File(src, "playback/MediaLibrarySessionCallback.kt").readText()
        assertTrue("MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> if (!service.roomShuffleAsked()) session.player.toggleShuffleMode()" in callback)
        assertTrue("MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> if (!service.roomRepeatAsked()) session.player.toggleRepeatMode()" in callback)
        assertTrue("val buttons = RoomSessionCommands.buttons(" in body(service, "fun updateNotification()"))
        val shuffle = body(service, "fun roomShuffleAsked()")
        assertTrue(shuffle, "it.following && !it.status.value.ownWay" in shuffle)
        assertTrue(shuffle, "if (room.controls) room.askOrder(shuffle = !RoomListen.shownShuffle(" in shuffle)
        assertTrue(shuffle, "else Toaster.show(this, orderHeldLine(this, status))" in shuffle)
        // The player's and the queue's buttons take the same way.
        assertTrue("if (service.roomShuffleAsked()) return" in body(connection, "fun triggerShuffle()"))
        assertTrue("if (service.roomRepeatAsked()) return" in body(connection, "fun toggleRepeat()"))
    }

    @Test
    fun `a guest's shuffle shows the room's, and asks the opposite of it`() {
        assertTrue(RoomListen.shownShuffle(follows = true, roomShuffle = true, playerShuffle = false))
        assertFalse(RoomListen.shownShuffle(follows = true, roomShuffle = false, playerShuffle = true))
        assertTrue("before the room's queue, the player's own", RoomListen.shownShuffle(follows = true, roomShuffle = null, playerShuffle = true))
        assertTrue("outside a room, the player's own", RoomListen.shownShuffle(follows = false, roomShuffle = false, playerShuffle = true))
        val onQueue = session.substringAfter("is TogetherMessage.Queue -> if (!leading) {").substringBefore("\n            }\n")
        assertTrue(onQueue, "_status.value = _status.value.copy(roomShuffle = message.shuffle)" in onQueue)
        assertFalse("shuffleModeEnabled.value = enabled" in connection)
    }

    @Test
    fun `only the listener's own moves go to the room, never the app's, and not while it casts`() {
        val chose = body(session, "private fun listenerChose(")
        assertTrue(chose, "if (pressed && guestControls(_status.value)) {" in chose)
        assertTrue("if (!handTurn || ownWay || lastState == null || !guestControls(_status.value)) return" in body(session, "private fun movedInSong("))
        // The content filter passes over nothing on a phone that follows a room.
        assertTrue("val inRoom = together.value?.following == true" in service)
        assertTrue("if (Unreleased.ARTIST_BLOCK && !inRoom && " in service)
        assertTrue("if (Unreleased.AI_FILTER && !inRoom && " in service)
        // A guest that casts plays its own music on the cast, as before 26 Nov 2026: no leave asked, its adds its own.
        assertTrue("if (room != null && room.following && !room.status.value.ownWay) {" in body(connection, "fun playQueue("))
        assertTrue("takeIf { it.following && !it.status.value.ownWay } ?: return false" in body(connection, "private fun toRoom("))
    }

    @Test
    fun `the one in control takes a guest's moves in the order the guest made them`() {
        val order = ControlOrder()
        assertTrue(order.take("a", 1_000L))
        assertTrue(order.take("a", 2_000L))
        assertFalse("an older move that came late", order.take("a", 1_500L))
        assertTrue("another guest's own order", order.take("b", 1_200L))
        assertTrue("a move without a time, from an older build of this one", order.take("a", 0L))
        assertTrue("if (!controlOrder.take(from, message.at)) {" in body(session, "private fun controlled("))
        assertTrue("send(message.copy(at = System.currentTimeMillis()))" in body(session, "private fun askRoom("))
    }

    @Test
    fun `while nobody plays for the room the sheet says so, and nothing goes to the room`() {
        val alone = body(session, "private fun lookAlone()")
        assertTrue(alone, "_status.value = _status.value.copy(nobodyPlays = alone)" in alone)
        val toRoom = body(connection, "private fun toRoom(")
        assertTrue(toRoom, "if (room.status.value.nobodyPlays) {" in toRoom)
    }
}
