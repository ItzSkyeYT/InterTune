/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The next song is loaded ahead while one plays, so Next starts it at once, and a song loaded
 * ahead acts like no play: it counts nowhere, owes recoverSong nothing until it plays, and a
 * failed load of it strikes no client in StreamOrder.
 *
 * Preloading has been off by default since media3 1.5, so a Next pressed mid-song waited for
 * /player, the n step, the check and the first bytes. What counts as a play is closed in
 * onPlaybackStatsReady, where a session with no time played writes nothing; recoverSong waits
 * for the song to play (AfterTheSongStartsTest). The walk for a song loaded ahead notes what it
 * hears as any walk does: a client refused for it is refused for the next songs too.
 */
class PreloadNextSongTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private fun body(after: String, before: String) = service.substringAfter(after).substringBefore(before)

    @Test
    fun theNextSongIsLoadedAheadFiveSeconds() {
        assertTrue(service.contains("preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_AHEAD_US)"))
        assertTrue(service.contains("private const val PRELOAD_AHEAD_US = 5_000_000L"))
    }

    @Test
    fun aFailedLoadOfASongLoadedAheadStrikesNoClient() {
        val listener = body("override fun onLoadError(", "override fun onLoadCompleted(")
        assertTrue(listener.contains("val playing = id == exoPlayer.currentMediaItem?.mediaId"))
        assertTrue(listener.contains("strike = playing)"))
        val change = body("private fun changeRoute(", "private fun noteGivenUp(")
        assertTrue(change.contains("if (strike && refused && streamAddresses.refusalIsTheRoutes("))
    }

    @Test
    fun aSessionWithNoTimePlayedWritesNoListen() {
        val stats = body("override fun onPlaybackStatsReady(", "override fun onRepeatModeChanged(")
        val nothing = stats.indexOf("if (playbackStats.totalPlayTimeMs <= 0L) {")
        assertTrue("a session media3 opened for a song it only loaded ahead", nothing > 0 && nothing < stats.indexOf("logListen("))
    }
}
