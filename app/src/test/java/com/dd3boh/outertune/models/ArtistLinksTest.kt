/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "View artist" offers every credited artist, and never opens "artist/null" (which crashed the app). One YouTube
 * gave no page opens a search for its name: So What, of "Thread The Needle", was left out, and the choice
 * offered Hoax and Dynamite MC alone (27 Nov 2026).
 */
class ArtistLinksTest {

    private fun route(artist: MediaMetadata.Artist) = artist.viewRoute { it.replace(" ", "%20") }

    @Test
    fun `Thread The Needle offers its three artists, in their order`() {
        val credits = listOf(
            Artist(name = "Hoax", id = "UC0-C_jjGdhQ4x9pmMSEZLcg"),
            Artist(name = "So What", id = null),
            Artist(name = "Dynamite MC", id = "UCEMEp1vS0srzZBre3-J9JbQ"),
        )
        val offered = credits.viewable()
        assertEquals(listOf("Hoax", "So What", "Dynamite MC"), offered.map { it.name })
        assertEquals(
            listOf("artist/UC0-C_jjGdhQ4x9pmMSEZLcg", "search/So%20What", "artist/UCEMEp1vS0srzZBre3-J9JbQ"),
            offered.map(::route),
        )
    }

    @Test
    fun `a credit with no id is a search for its name, never artist null`() {
        val various = listOf(Artist(name = "Various Artists", id = null), Artist(name = "Blank id", id = ""))
        val offered = various.viewable()
        assertEquals(listOf(null, null), offered.map { it.id })
        assertEquals(listOf("search/Various%20Artists", "search/Blank%20id"), offered.map(::route))
        offered.forEach { assertFalse(route(it)!!.startsWith("artist/")) }
    }

    @Test
    fun `an artist with neither an id nor a name is not offered`() {
        val artists = listOf(MediaMetadata.Artist(id = null, name = " "), MediaMetadata.Artist(id = "", name = ""))
        assertEquals(emptyList<MediaMetadata.Artist>(), artists.viewable())
        assertNull(route(artists[0]))
    }

    @Test
    fun `the player's artists, local and made up here, open their pages`() {
        val artists = listOf(
            MediaMetadata.Artist(id = "LAabcdefgh", name = "Local", isLocal = true),
            MediaMetadata.Artist(id = "LAqWgslcqk", name = "So What"),
            MediaMetadata.Artist(id = null, name = "So What"),
        )
        assertEquals(artists, artists.viewable())
        assertEquals(listOf("artist/LAabcdefgh", "artist/LAqWgslcqk", "search/So%20What"), artists.map(::route))
    }
}
