/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His reel of 30 Oct 2026 (instagram.com/reel/De9I_VLo6x8, an FL Studio flip of Lush Life), heard
 * as "Lush Life (Riot Remix)" by Zara Larsson. YouTube Music lists no such song, and its search for
 * that whole name gave other people's versions only: Listen once offered four of them and not the
 * song. The song itself is now searched for without its version and put in front.
 */
class SongItselfTest {

    private fun shazam(title: String, artist: String?) =
        Recognised(title = title, artist = artist, artworkUrl = null, isrc = null, shazamKey = null)

    private fun youtube(id: String, title: String, vararg artists: String) = SongItem(
        id = id,
        title = title,
        artists = artists.map { Artist(name = it, id = null) },
        thumbnail = "",
    )

    private val reel = shazam("Lush Life (Riot Remix)", "Zara Larsson")

    /** "Lush Life (Riot Remix) Zara Larsson" under the song filter, 30 Oct 2026, its first four. */
    private val first = listOf(
        youtube("a", "Lush Life", "Diamond String Orchestra"),
        youtube("b", "LUSH LIFE (SLOWED)", "MEZZIA"),
        youtube("c", "Lush Life x Unforgettable", "studiojenna"),
        youtube("d", "Lush Life (Tropical Cardio Workout Mix)", "Crossfit Junkies"),
    )

    /** "Lush Life Zara Larsson" under the song filter, the same day. */
    private val second = listOf(
        youtube("z", "Lush Life", "Zara Larsson"),
        youtube("e", "Crush", "Zara Larsson"),
        youtube("f", "All the Time", "Zara Larsson"),
        youtube("g", "LUSH LIFE - HYPERTECHNO", "HYPERAVE", "BASSTON"),
        youtube("a", "Lush Life", "Diamond String Orchestra"),
    )

    @Test
    fun `nothing the whole name found is the song, so nothing was certain`() {
        assertTrue(first.none { corresponds(reel, it) })
    }

    @Test
    fun `the song itself is asked for without its version`() {
        assertEquals("Lush Life Zara Larsson", songItselfQuery(reel))
        assertEquals("Lush Life Zara Larsson", songItselfQuery(shazam("Lush Life - Riot Remix", "Zara Larsson")))
        assertEquals("Children Robert Miles", songItselfQuery(shazam("Children [Dream Version]", "Robert Miles")))
    }

    @Test
    fun `a name with no version, or no artist, asks nothing more`() {
        assertNull(songItselfQuery(shazam("Lush Life", "Zara Larsson")))
        assertNull(songItselfQuery(shazam("Lush Life (Riot Remix)", null)))
        assertNull(songItselfQuery(shazam("Lush Life (Riot Remix)", " ")))
        assertNull(songItselfQuery(shazam("(Riot Remix)", "Zara Larsson")))
    }

    @Test
    fun `the song goes in front of what the whole name found, which stays`() {
        val candidates = withSongItself(reel, first, second)
        assertEquals(listOf("z", "a", "b", "c"), candidates?.map { it.id })
        // The same gate as ever says it is the song: the screen lists it, a playlist's run adds it.
        assertTrue(corresponds(reel, candidates!!.first()))
    }

    @Test
    fun `a second search with nothing that is the song leaves the first as it was`() {
        assertNull(withSongItself(reel, first, listOf(youtube("e", "Crush", "Zara Larsson"), youtube("a", "Lush Life", "Diamond String Orchestra"))))
        assertNull(withSongItself(reel, first, emptyList()))
    }

    @Test
    fun `the engine asks again only in Listen once, and only when nothing found is the song`() {
        val engine = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionEngine.kt").readText()
        val resolve = engine.substring(engine.indexOf("val firstFound ="), engine.indexOf("val best = candidates.firstOrNull()"))
        assertTrue("if (keepGoing || firstFound.any { corresponds(outcome.track, it) }) firstFound" in resolve)
        assertTrue("songItselfQuery(outcome.track)" in resolve)
        assertTrue("withSongItself(outcome.track, firstFound," in resolve)
        assertTrue("} ?: firstFound" in resolve)
        // A stop during the second search ends there, as during the first.
        assertTrue(resolve.indexOf("currentCoroutineContext().ensureActive()") in resolve.indexOf("YouTube.search(bare,")..resolve.indexOf("withSongItself("))
        // What found the song goes with the answer.
        assertTrue("foundBy = bare" in resolve)
        assertTrue("_state.value = State.Found(outcome.track, candidates, certain, foundBy)" in engine)
    }

    @Test
    fun `the found notification searches for the words that found the song`() {
        // The answer's words are Shazam's whole name unless only the song's own found it.
        val found = RecognitionEngine.State.Found(reel, first, certain = false)
        assertEquals("Lush Life (Riot Remix) Zara Larsson", found.searchWords)
        val service = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionService.kt").readText()
        assertTrue("notifyFound(state.track, state.searchWords)" in service)
        val notify = service.substring(service.indexOf("private fun notifyFound(track: Recognised, words: String)"))
            .substringBefore("// Away from the main thread")
        assertTrue("SystemSearch.searchLink(words)" in notify)
        assertTrue("the words are made again from Shazam's name", "track.title, track.artist" !in notify)
    }
}
