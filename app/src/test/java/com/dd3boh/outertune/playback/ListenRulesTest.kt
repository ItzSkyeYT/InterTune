/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.lastfm.LastFm
import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a play has to be to reach Last.fm or ListenBrainz, and what ListenBrainz gets of it. The
 * rule is Last.fm's, kept for both so that a song does not count for one and not the other.
 */
class ListenRulesTest {

    private val client = ListenBrainz(ListenBrainz.App("InterTune", "0.13.1", "https://github.com/ItzSkyeYT/InterTune"))

    private fun song(
        id: String = "dQw4w9WgXcQ",
        title: String = "Never Gonna Give You Up",
        artists: List<String> = listOf("Rick Astley"),
        album: String? = "Whenever You Need Somebody",
        duration: Int = 213,
        trackNumber: Int? = null,
        isLocal: Boolean = false,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = duration,
        trackNumber = trackNumber,
        album = album?.let { MediaMetadata.Album(id = "album", title = it) },
        genre = null,
        isLocal = isLocal,
    )

    @Test
    fun `Last_fm's rule, now for both`() {
        // Under thirty seconds never counts, whatever happens.
        assertFalse(ListenRules.qualifies(playedMs = 29_000, durationSeconds = 29))
        // Half of a short song.
        assertFalse(ListenRules.qualifies(playedMs = 59_000, durationSeconds = 120))
        assertTrue(ListenRules.qualifies(playedMs = 60_000, durationSeconds = 120))
        // Four minutes caps it, so a long song does not need half.
        assertTrue(ListenRules.qualifies(playedMs = 4 * 60_000, durationSeconds = 20 * 60))
        assertFalse(ListenRules.qualifies(playedMs = 4 * 60_000 - 1, durationSeconds = 20 * 60))
    }

    @Test
    fun `a song whose metadata says -1 counts once its recovered length is used`() {
        val played = 4 * 60 * 1000L
        assertFalse(ListenRules.qualifies(played, -1))
        assertTrue(ListenRules.qualifies(played, 300))
    }

    @Test
    fun `the rule is Last_fm's own for as long as Last_fm's client keeps one`() {
        for (duration in listOf(-1, 0, 29, 30, 31, 59, 60, 120, 479, 480, 481, 1200))
            for (played in listOf(0L, 14_999L, 15_000L, 29_999L, 30_000L, 59_999L, 60_000L, 239_999L, 240_000L, 600_000L))
                assertEquals("$played ms of $duration s", LastFm.qualifies(played, duration), ListenRules.qualifies(played, duration))
    }

    @Test
    fun `a listen carries the first artist, the album, the recovered length and the YouTube Music link`() {
        val listen = ListenRules.listenOf(client, song(artists = listOf("Rick Astley", "Someone Else"), duration = -1, trackNumber = 1), 1795000000, durationSeconds = 213)!!
        assertEquals(1795000000L, listen.listenedAt)
        val meta = listen.trackMetadata
        assertEquals("Rick Astley", meta.artistName)
        assertEquals("Never Gonna Give You Up", meta.trackName)
        assertEquals("Whenever You Need Somebody", meta.releaseName)
        val info = meta.additionalInfo!!
        assertEquals(213_000L, info.durationMs)
        assertEquals("1", info.trackNumber)
        assertEquals("InterTune", info.submissionClient)
        assertEquals("music.youtube.com", info.musicService)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", info.originUrl)
    }

    @Test
    fun `a local file goes without a service or a link`() {
        val info = ListenRules.listenOf(client, song(id = "LA1234", isLocal = true), 1795000000, 213)!!.trackMetadata.additionalInfo!!
        assertNull(info.musicService)
        assertNull(info.originUrl)
    }

    @Test
    fun `no length known sends none`() {
        assertNull(ListenRules.listenOf(client, song(), 1795000000, durationSeconds = -1)!!.trackMetadata.additionalInfo!!.durationMs)
    }

    @Test
    fun `a blank artist or title sends nothing`() {
        assertNull(ListenRules.listenOf(client, song(artists = emptyList()), 1795000000, 213))
        assertNull(ListenRules.listenOf(client, song(artists = listOf(" ", "")), 1795000000, 213))
        assertNull(ListenRules.listenOf(client, song(title = "  "), 1795000000, 213))
    }

    @Test
    fun `playing now carries no time`() {
        assertNull(ListenRules.listenOf(client, song(), startedAtSeconds = null, durationSeconds = 213)!!.listenedAt)
    }

    @Test
    fun `paused listen history sends nothing`() {
        // Both services go through Scrobbler, which asks this before playing now; a scrobble is
        // only asked for on a counted play with history not paused (MusicService, ListenBrainzWiringTest).
        assertFalse(ListenReporting.sendsNowPlaying(listenHistoryPaused = true))
    }
}
