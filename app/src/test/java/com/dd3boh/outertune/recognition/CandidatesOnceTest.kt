/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What's playing? offers the first songs YouTube's search found when it cannot tell which is the
 * song, and draws them in a list keyed by their id. That search sometimes lists one song twice:
 * of 273 song searches kept from 18 Nov 2026, "Explosions In The Sky Time Stops" had the same video
 * first and third, and "Liv Kristine Stronghold of Angels" two videos twice each in its first four.
 * The same key twice in one list closes the app, as it did on the playlist's quick search (#105).
 */
class CandidatesOnceTest {

    private fun youtube(id: String, title: String) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(name = "Explosions In The Sky", id = null)),
        thumbnail = "",
    )

    @Test
    fun `a song the search lists twice is offered once, and four are still offered`() {
        val found = listOf(
            youtube("biF5ibB1Ks4", "Time Stops"),
            youtube("b", "The Only Moment We Were Alone"),
            youtube("biF5ibB1Ks4", "Time Stops"),
            youtube("c", "Your Hand in Mine"),
            youtube("d", "First Breath After Coma"),
            youtube("e", "Memorial"),
        )
        assertEquals(listOf("biF5ibB1Ks4", "b", "c", "d"), firstSongs(found).map { it.id })
    }

    @Test
    fun `only songs are offered, and nothing found is nothing offered`() {
        val album = AlbumItem(browseId = "MPREb_x", playlistId = "OLAK5uy_x", title = "Take Care, Take Care, Take Care", artists = null, thumbnail = "")
        assertEquals(listOf("a"), firstSongs(listOf(album, youtube("a", "Trembling Hands"))).map { it.id })
        assertTrue(firstSongs(null).isEmpty())
    }

    @Test
    fun `the engine takes its candidates once each, and the screen draws each once`() {
        val engine = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionEngine.kt").readText()
        assertTrue("the engine takes the search's songs as they came", "val firstFound = firstSongs(items)" in engine)
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/RecognitionScreen.kt").readText()
        assertTrue(
            "the screen draws the candidates as they come, a repeat included",
            "items(found.candidates.distinctBy { it.id }, key = { \"candidate/\${it.id}\" })" in screen,
        )
    }
}
