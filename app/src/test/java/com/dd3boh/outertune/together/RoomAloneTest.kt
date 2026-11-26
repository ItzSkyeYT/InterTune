/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * While nobody plays for the room, a guest plays on its own (26 Nov 2026). When the one in control went quiet (the host
 * swiped InterTune away, no End sent), a guest still followed nobody for up to six hours: its Play next and Add to queue
 * said "Added to the room" and went nowhere, its queue stayed the room's, and a song it tapped was not its own. Now it is
 * on its own way meanwhile, its Play next and Add to queue its own as outside a room; when someone plays for the room
 * again it comes back by itself if that changes nothing it hears, and else stays on its own way with Catch up, never
 * pulled back in the middle of a song of its own. This reads the source where a service and its relays would be needed.
 */
class RoomAloneTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val connection = File(src, "playback/PlayerConnection.kt").readText()

    /** The text of the function that starts with [signature]: from its opening brace to the one that closes it. */
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
    fun `nobody playing for the room takes a guest its own way, so its Play next and Add to queue are its own`() {
        // At each look, once watchHost has said whether the host is away.
        val look = session.substringAfter("castLook()\n").substringBefore("withTimeoutOrNull(every)")
        assertTrue(look, look.indexOf("lookAlone()") > look.indexOf("watchHost()"))
        assertTrue("if (nobodyPlays(System.currentTimeMillis())) goAlone()" in body(session, "private fun lookAlone()"))
        val nobody = body(session, "private fun nobodyPlays(")
        assertTrue(nobody, "if (leading || lastState != null) return false" in nobody)
        // The one in control quiet as long as follow waits for it, or, never heard in this session, the host away.
        assertTrue(nobody, "if (lastStateAt > 0) return now - lastStateAt > HOST_TIMEOUT_MS" in nobody)
        assertTrue(nobody, "return _status.value.hostAway && (!joiningNow || answeredAt > 0)" in nobody)
        val go = body(session, "private fun goAlone()")
        assertTrue(go, "if (leading || ownWay) return" in go)
        assertTrue(go, "aloneOwnWay = true" in go)
        assertTrue(go, "ownWay = true, driftMs = null, locked = false, pausedHere = false" in go)
        // Its own way is what sends Play next and Add to queue to its own queue, as outside a room.
        assertTrue("it.following && !it.status.value.ownWay" in body(connection, "private fun toRoom("))
    }

    @Test
    fun `what the guest does meanwhile is its own, and keeps it on its own way when someone plays again`() {
        // A song it picks, a skip or a seek.
        val chose = body(session, "private fun listenerChose(")
        assertTrue(chose, chose.indexOf("if (aloneOwnWay)") in 0 until chose.indexOf("if (lastState == null) return"))
        // Play next and Add to queue, said once in the room's own words.
        for (name in listOf("fun enqueueNext(items: List<MediaItem>) {", "fun enqueueEnd(items: List<MediaItem>) {")) {
            val put = body(connection, name)
            assertTrue(name, put.indexOf("service.together.value?.addedHere()") > put.indexOf("if (toRoom("))
        }
        val added = body(session, "fun addedHere()")
        assertTrue(added, "aloneOwnWay = false" in added)
        assertTrue(added, "R.string.together_own_way" in added)
        // A change made to its queue, in the queue sheet.
        val timeline = body(session, "override fun onTimelineChanged(")
        assertTrue(timeline, "Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && aloneOwnWay" in timeline)
    }

    @Test
    fun `someone playing again brings back a guest that did nothing, never one in a song of its own`() {
        val state = session.substringAfter("is TogetherMessage.State -> if (!leading) {").substringBefore("is TogetherMessage.Queue ->")
        assertTrue(state, state.indexOf("if (aloneOwnWay) backFromAlone(message)") in state.indexOf("lastStateAt = now") until state.indexOf("waitingForHost = false"))
        val back = body(session, "private fun backFromAlone(")
        assertTrue(back, "NobodyPlays.backWithRoom(player.playWhenReady, player.playbackState, player.currentMediaItem?.mediaId, state.songId)" in back)
        // Paused, it comes back paused here: Play takes it to where the room is, nothing plays aloud by itself.
        assertTrue(back, back.indexOf("catchUp()") in 0 until back.indexOf("PauseHere.after(pausedHere = false"))
        // Catch up and taking control end it.
        assertTrue("aloneOwnWay = false" in session.substringAfter("fun catchUp() {").substringBefore("\n    }\n"))
        assertTrue("aloneOwnWay = false" in body(session, "private fun lead(key: String)").substringAfter("if (key == me) {"))
    }

    @Test
    fun `back with the room by itself only when nothing it hears would change`() {
        // Nothing playing here: paused, stopped, or at the end of what it had.
        assertTrue(NobodyPlays.backWithRoom(playWhenReady = false, playbackState = Player.STATE_READY, songHere = "a", roomSong = "b"))
        assertTrue(NobodyPlays.backWithRoom(playWhenReady = true, playbackState = Player.STATE_ENDED, songHere = "a", roomSong = "b"))
        assertTrue(NobodyPlays.backWithRoom(playWhenReady = true, playbackState = Player.STATE_IDLE, songHere = null, roomSong = "b"))
        // The room's own song: back in step, at most a nudge.
        assertTrue(NobodyPlays.backWithRoom(playWhenReady = true, playbackState = Player.STATE_READY, songHere = "b", roomSong = "b"))
        // Another song playing or on its way here: never pulled out of the middle of it.
        assertFalse(NobodyPlays.backWithRoom(playWhenReady = true, playbackState = Player.STATE_READY, songHere = "a", roomSong = "b"))
        assertFalse(NobodyPlays.backWithRoom(playWhenReady = true, playbackState = Player.STATE_BUFFERING, songHere = "a", roomSong = "b"))
    }
}
