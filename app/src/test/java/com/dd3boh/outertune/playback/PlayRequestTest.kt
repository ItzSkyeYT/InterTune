/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading back the ids Android Auto plays by. */
class PlayRequestTest {

    @Test
    fun `a song from the song list`() {
        assertEquals(PlayRequest.Song("dQw4w9WgXcQ"), PlayRequest.parse("song/dQw4w9WgXcQ"))
    }

    @Test
    fun `a song from an artist, an album and a playlist`() {
        assertEquals(PlayRequest.Artist("UCabc", "s1"), PlayRequest.parse("artist/UCabc/s1"))
        assertEquals(PlayRequest.Album("MPREb_x", "s2"), PlayRequest.parse("album/MPREb_x/s2"))
        assertEquals(PlayRequest.Playlist("LP123", "s3"), PlayRequest.parse("playlist/LP123/s3"))
    }

    @Test
    fun `a search whose query has a slash keeps the whole query and the right song`() {
        // Split on every slash this was the query "AC" and the song "DC".
        assertEquals(PlayRequest.Search("AC/DC", "v2AC41dglnM"), PlayRequest.parse("search/AC/DC/v2AC41dglnM"))
    }

    @Test
    fun `a plain search`() {
        assertEquals(PlayRequest.Search("daft punk", "s9"), PlayRequest.parse("search/daft punk/s9"))
    }

    @Test
    fun `folders and anything else name no song`() {
        // Browsable ids: a request for one of these used to empty the player.
        assertNull(PlayRequest.parse("artist/UCabc"))
        assertNull(PlayRequest.parse("playlist/LP123"))
        assertNull(PlayRequest.parse("song"))
        assertNull(PlayRequest.parse("root"))
        assertNull(PlayRequest.parse(""))
        assertNull(PlayRequest.parse("song/"))
        assertNull(PlayRequest.parse("search//s9"))
        assertNull(PlayRequest.parse("video/abc/def"))
        // A bare song id, which some controllers send, is not one of ours.
        assertNull(PlayRequest.parse("dQw4w9WgXcQ"))
    }

    // What the car is shown since the rework.

    @Test
    fun `a song of one of the rows of For you`() {
        assertEquals(PlayRequest.Home("quick", "abc"), PlayRequest.parse("home/quick/abc"))
        assertEquals(PlayRequest.Home("keep", "abc"), PlayRequest.parse("home/keep/abc"))
        assertEquals(PlayRequest.Home("forgotten", "abc"), PlayRequest.parse("home/forgotten/abc"))
        // A row there is not, and a row with no song.
        assertNull(PlayRequest.parse("home/other/abc"))
        assertNull(PlayRequest.parse("home/quick/"))
        assertNull(PlayRequest.parse("home/abc"))
    }

    @Test
    fun `a song of what was played lately`() {
        assertEquals(PlayRequest.Recent("abc"), PlayRequest.parse("recent/abc"))
        assertNull(PlayRequest.parse("recent/"))
        assertNull(PlayRequest.parse("recent/a/b"))
    }

    @Test
    fun `a list in a new order names no song`() {
        assertEquals(PlayRequest.Shuffle("liked"), PlayRequest.parse("shuffle/liked"))
        assertEquals(PlayRequest.Shuffle("downloaded"), PlayRequest.parse("shuffle/downloaded"))
        assertEquals(PlayRequest.Shuffle("playlist/PL123"), PlayRequest.parse("shuffle/playlist/PL123"))
        assertEquals("", PlayRequest.parse("shuffle/liked")!!.songId)
        // A playlist's id can hold a slash of its own, and stays whole.
        assertEquals(PlayRequest.Shuffle("playlist/LP/a b"), PlayRequest.parse("shuffle/playlist/LP/a b"))
        assertNull(PlayRequest.parse("shuffle/playlist/"))
        assertNull(PlayRequest.parse("shuffle/everything"))
        assertNull(PlayRequest.parse("shuffle/"))
    }

    @Test
    fun `the queue there is, carried on with`() {
        assertEquals(PlayRequest.Resume, PlayRequest.parse("resume/queue"))
        assertNull(PlayRequest.parse("resume/"))
        assertNull(PlayRequest.parse("resume/other"))
    }

    @Test
    fun `the ids the car always had still mean what they did`() {
        assertEquals(PlayRequest.Song("abc"), PlayRequest.parse("song/abc"))
        assertEquals(PlayRequest.Playlist("LM", "abc"), PlayRequest.parse("playlist/LM/abc"))
        assertEquals(PlayRequest.Search("AC/DC", "abc"), PlayRequest.parse("search/AC/DC/abc"))
    }

    @Test
    fun `quick picks for the car are the ones Home showed, then the library's, none twice`() {
        assertEquals(listOf("a", "b", "c", "d"), AutoBrowse.picks(listOf("a", "b"), listOf("b", "c", "d")))
        assertEquals(listOf("c", "d"), AutoBrowse.picks(emptyList(), listOf("c", "d")))
        assertEquals(12, AutoBrowse.picks((1..9).map { "s$it" }, (5..40).map { "s$it" }).size)
        assertEquals((1..12).map { "s$it" }, AutoBrowse.picks((1..9).map { "s$it" }, (5..40).map { "s$it" }))
    }

    @Test
    fun `a cover's address names something of the library and nothing else`() {
        assertEquals("song" to "abc", AutoArt.parse(listOf("song", "abc")))
        assertEquals("playlist" to "LM", AutoArt.parse(listOf("playlist", "LM")))
        assertNull(AutoArt.parse(listOf("song")))
        assertNull(AutoArt.parse(listOf("song", "")))
        assertNull(AutoArt.parse(listOf("file", "abc")))
        assertNull(AutoArt.parse(listOf("song", "abc", "..")))
        assertNull(AutoArt.parse(emptyList()))
    }
}

