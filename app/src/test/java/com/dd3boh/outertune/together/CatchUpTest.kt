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
 * Catch up brought a guest that went its own way back to the room, and three things left it where it was (dvd in
 * #testers, 22 Nov 2026: "i can catch up but its not catching up"): a queue swap landing after it put back the pause it
 * had seen, a player stopped on an error that the room's look never starts, and a song change asked for before and never
 * got, waited for at every look. See CatchUp.
 */
class CatchUpTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val catchUp = session.substringAfter("fun catchUp() {").substringBefore("\n    }\n")
    private val follow = session.substringAfter("private fun follow()").substringBefore("\n    private fun ")
    private val matchQueue = session.substringAfter("private fun matchQueue()").substringBefore("\n    private fun ")

    /**
     * A guest's player through Catch up, as the session has it: Catch up, then the look (follow) asks for the room's
     * queue under the song when its own differs, then plays as the room does, and the queue lands a moment later with
     * the play state it was asked with (QueueStart's start). Each change of play state is taken as listenerPlayed takes
     * it (PauseHere).
     */
    private class Guest(var playWhenReady: Boolean, val roomPlaying: Boolean) {
        var pausedHere = false

        fun catchUp(swapPlays: (pausedHere: Boolean, roomPlaying: Boolean?, playingHere: Boolean) -> Boolean) {
            pausedHere = false
            val asked = swapPlays(pausedHere, roomPlaying, playWhenReady)
            set(roomPlaying)
            set(asked)
        }

        private fun set(value: Boolean) {
            if (value == playWhenReady) return
            playWhenReady = value
            PauseHere.after(pausedHere, value, roomPlaying)?.let { pausedHere = it }
        }
    }

    @Test
    fun `paused on its own, Catch up plays with the room and the queue swap keeps it playing`() {
        // As it was: the swap put back the pause it saw when it was asked for, and the guest was paused here.
        val before = Guest(playWhenReady = false, roomPlaying = true)
        before.catchUp { _, _, playingHere -> playingHere }
        assertTrue("the bug: paused here after Catch up", before.pausedHere)
        assertFalse(before.playWhenReady)
        // Now the swap lands as the room wants it.
        val now = Guest(playWhenReady = false, roomPlaying = true)
        now.catchUp(CatchUp::swapPlays)
        assertFalse(now.pausedHere)
        assertTrue(now.playWhenReady)
        // Playing on its own, it plays on; the room paused, it stands paused with it.
        val playing = Guest(playWhenReady = true, roomPlaying = true).apply { catchUp(CatchUp::swapPlays) }
        assertTrue(playing.playWhenReady && !playing.pausedHere)
        val roomPaused = Guest(playWhenReady = true, roomPlaying = false).apply { catchUp(CatchUp::swapPlays) }
        assertFalse(roomPaused.playWhenReady || roomPaused.pausedHere)
    }

    @Test
    fun `the swap plays as the room wants it`() {
        assertTrue(CatchUp.swapPlays(pausedHere = false, roomPlaying = true, playingHere = false))
        assertFalse(CatchUp.swapPlays(pausedHere = false, roomPlaying = false, playingHere = true))
        // Paused here by the listener: the room's queue comes in and the pause stands.
        assertFalse(CatchUp.swapPlays(pausedHere = true, roomPlaying = true, playingHere = false))
        // Before the room's first word: as it is.
        assertTrue(CatchUp.swapPlays(pausedHere = false, roomPlaying = null, playingHere = true))
        assertFalse(CatchUp.swapPlays(pausedHere = false, roomPlaying = null, playingHere = false))
    }

    @Test
    fun `the session's swap is asked with the room's play state, before the look plays as the room does`() {
        assertTrue("playWhenReady = CatchUp.swapPlays(pausedHere, lastState?.playing, player.playWhenReady)" in matchQueue)
        assertFalse("playWhenReady = player.playWhenReady," in matchQueue)
        // The order the model above follows: the swap first, then the play state.
        val swap = follow.indexOf("if (roomQueue !== matchedQueue) matchQueue()")
        val plays = follow.indexOf("mine()\n            player.playWhenReady = state.playing")
        assertTrue(swap in 0 until plays)
        // And a queue with no song named ahead is started, as it lands, with the play state it was asked with.
        val start = File(src, "playback/QueueStart.kt").readText()
        assertTrue("service.start(if (preloadItem != null) playWhenReady && service.wantsToPlay else playWhenReady)" in start)
    }

    @Test
    fun `Catch up starts a player stopped on the room's song`() {
        assertTrue(CatchUp.restarts(Player.STATE_IDLE, "abc", "abc"))
        // On another song, the change to the room's song starts it (the queue's start, or the service on a seek).
        assertFalse(CatchUp.restarts(Player.STATE_IDLE, "own", "abc"))
        assertFalse(CatchUp.restarts(Player.STATE_IDLE, null, "abc"))
        assertFalse(CatchUp.restarts(Player.STATE_IDLE, "abc", null))
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            assertFalse(CatchUp.restarts(state, "abc", "abc"))
        }
        // The look leaves a player neither ready nor buffering alone, after waking an ended one: a stopped one stayed
        // silent under "Following the host" until the room's next song.
        val gaveUp = follow.indexOf("player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return")
        assertTrue(gaveUp > follow.indexOf("player.playbackState == Player.STATE_ENDED"))
        assertFalse("the look starts no stopped player", "STATE_IDLE" in follow.substringAfter("player.playbackState == Player.STATE_ENDED"))
        // So Catch up does it, once a tap.
        val restart = catchUp.indexOf("CatchUp.restarts(player.playbackState, player.currentMediaItem?.mediaId, lastState?.songId)")
        assertTrue(restart >= 0)
        assertTrue(catchUp.indexOf("player.prepare()") > restart)
        // A song that fails here again is kept and said (holdsFailed), now that the guest follows: not while on its own.
        val holds = session.substringAfter("fun holdsFailed(").substringBefore("\n    }\n")
        assertTrue("if (leading || ownWay || pausedHere || songId == null || songId != state.songId) return false" in holds)
        assertTrue(catchUp.indexOf("ownWay = false") in 0 until restart)
    }

    @Test
    fun `Catch up asks for the leader's song afresh`() {
        // A song change asked for is waited for until the player is on it: one that never came (the player taken
        // elsewhere meanwhile) passed the leader's song by at every look, Catch up included.
        val toSong = session.substringAfter("private fun toLeadersSong(").substringBefore("\n    private fun ")
        assertTrue("if (loadingSong == state.songId) return" in toSong)
        assertTrue("loadingSong = null" in catchUp)
        // Before the look Catch up wakes.
        assertTrue(catchUp.indexOf("loadingSong = null") in 0 until catchUp.indexOf("wake.trySend(Unit)"))
    }
}
