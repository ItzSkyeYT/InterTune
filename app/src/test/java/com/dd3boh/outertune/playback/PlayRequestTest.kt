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
}
