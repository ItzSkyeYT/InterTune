/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A new song's own requests go out alone: what the app asks about a song in the background waits
 * until the song plays.
 *
 * recoverSong (its next, related and Last.fm requests) was launched by the resolve, before the
 * stream had opened, and for a song the player only loads ahead before it played at all. The
 * history report of the song before (for an account: a signature timestamp under the lock the new
 * song's n step waits on, a po token, a /player, then the ping) went out as the new song started.
 */
class AfterTheSongStartsTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private fun body(start: String, end: String): String {
        val from = service.indexOf(start)
        assertTrue("not found: $start", from >= 0)
        return service.substring(from, service.indexOf(end, from))
    }

    @Test
    fun theResolveOwesRecoverSongAndNeverLaunchesIt() {
        val resolver = body("inner class SongResolver", "fun startCastServer(")
        assertFalse("recoverSong( is called from the resolve", "recoverSong(" in resolver)
        assertEquals("every branch that used to launch it", 3, Regex("""recoverWhenPlaying\(mediaId, """).findAll(resolver).count())
    }

    @Test
    fun whatIsOwedIsPaidToTheSongPlayingOnceItIsReady() {
        val pay = body("private fun payOwedRecovery()", "private fun forgetOwedRecoveryBehind()")
        assertTrue("player.playbackState != Player.STATE_READY) return" in pay)
        assertTrue("player.currentMediaItem?.mediaId ?: return" in pay)
        assertTrue("offloadScope.launch { recoverSong(id, owed.playbackData) }" in pay)
        val events = body("override fun onEvents(player: Player, events: Player.Events)", "private fun lastCompletedId()")
        assertTrue(Regex("""containsAny\(Player\.EVENT_PLAYBACK_STATE_CHANGED, Player\.EVENT_MEDIA_ITEM_TRANSITION\)\) payOwedRecovery\(\)""").containsMatchIn(events))
        assertTrue("forgotten for songs left behind", "forgetOwedRecoveryBehind()" in events)
    }

    @Test
    fun theHistoryReportWaitsForTheNextSongToStart() {
        val report = body("Trying to register remote history", "override fun onRepeatModeChanged")
        val waits = report.indexOf("afterSongStarts()")
        assertTrue("before both requests for the address", waits in 0 until report.indexOf("YTPlayerUtils.playerResponseAsAccount("))
        val helper = body("private suspend fun afterSongStarts()", "private suspend fun recoverSong(")
        assertTrue("bounded", "withTimeoutOrNull(NEW_SONG_WAIT_MS)" in helper)
        assertTrue("activePlayer.playbackState == Player.STATE_BUFFERING" in helper)
    }
}
