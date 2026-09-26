/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.Artist
import org.junit.Assert.assertEquals
import org.junit.Test

/** "View artist" only ever offers an artist it can open. */
class ArtistLinksTest {
    @Test
    fun `a credit with no id, like Various Artists, is left out`() {
        val credits = listOf(Artist(name = "Various Artists", id = null))
        assertEquals(emptyList<MediaMetadata.Artist>(), credits.withArtistIds())
    }

    @Test
    fun `the others keep their order`() {
        val credits = listOf(
            Artist(name = "Queen", id = "UCiMhD4jzUqG-IgPzUmmytRQ"),
            Artist(name = "Various Artists", id = null),
            Artist(name = "Blank", id = ""),
            Artist(name = "David Bowie", id = "UCyqqFFM3YtGUpNHIwtDeK5A"),
        )
        assertEquals(
            listOf(
                MediaMetadata.Artist(id = "UCiMhD4jzUqG-IgPzUmmytRQ", name = "Queen"),
                MediaMetadata.Artist(id = "UCyqqFFM3YtGUpNHIwtDeK5A", name = "David Bowie"),
            ),
            credits.withArtistIds(),
        )
    }

    @Test
    fun `player artists with no id are left out, local ones stay`() {
        val artists = listOf(
            MediaMetadata.Artist(id = null, name = "Various Artists"),
            MediaMetadata.Artist(id = "LAabcdefgh", name = "Local", isLocal = true),
        )
        assertEquals(listOf(artists[1]), artists.withArtistIds())
    }
}
