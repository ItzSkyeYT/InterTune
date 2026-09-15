/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.innertube

import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.zionhuang.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.zionhuang.innertube.models.NavigationEndpoint
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.pages.PageHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who a byline credits, and who it does not.
 *
 * The cases here are the shapes that put "431K plays" and "Aug 25, 2016" into a real library as
 * artist names, beside the shapes that must keep working: linked artists, unlinked artists, and
 * real acts whose names look like metadata.
 */
class PageHelperArtistsTest {

    private fun sep(text: String = " • ") = Run(text, null)

    private fun plain(text: String) = Run(text, null)

    private fun linked(text: String, id: String, pageType: String = BrowseEndpointContextMusicConfig.MUSIC_PAGE_TYPE_ARTIST) =
        Run(
            text,
            NavigationEndpoint(
                browseEndpoint = BrowseEndpoint(
                    browseId = id,
                    browseEndpointContextSupportedConfigs = BrowseEndpointContextSupportedConfigs(
                        BrowseEndpointContextMusicConfig(pageType)
                    )
                )
            )
        )

    private fun names(byline: List<Run>) = PageHelper.artists(byline).map { it.name }

    @Test
    fun `a video row that credits nobody gets nobody`() {
        // "Video • 16M views", the shape that stored a play count as an artist.
        assertEquals(emptyList<String>(), names(listOf(plain("Video"), sep(), plain("16M views"))))
        assertEquals(emptyList<String>(), names(listOf(plain("Song"), sep(), plain("431K plays"))))
    }

    @Test
    fun `an upload date is not an artist either`() {
        assertEquals(emptyList<String>(), names(listOf(plain("Video"), sep(), plain("Aug 25, 2016"))))
        assertEquals(emptyList<String>(), names(listOf(plain("Video"), sep(), plain("Dec 16, 2025"))))
    }

    @Test
    fun `a linked artist is taken, with an unlinked co-artist beside it`() {
        assertEquals(
            listOf("Linkin Park"),
            names(listOf(plain("Song"), sep(), linked("Linkin Park", "UC1"), sep(), plain("Hybrid Theory")))
        )
        assertEquals(
            listOf("Anyma", "Baset"),
            names(listOf(linked("Anyma", "UC2"), Run(" & ", null), plain("Baset"), sep(), plain("Genesys")))
        )
    }

    @Test
    fun `an unlinked artist survives, because whole albums are served that way`() {
        // Every track on "Forza Horizon 6 (Original Soundtrack)" is plain text everywhere YouTube
        // serves it. Demanding a link would store that album with no artist at all.
        assertEquals(
            listOf("Milk Talk"),
            names(listOf(plain("Song"), sep(), plain("Milk Talk")))
        )
    }

    @Test
    fun `a known limit, with nothing linked at all the last group wins`() {
        // "Song . Milk Talk . Forza Horizon 6" with no links anywhere reads the album as the
        // credit, because the rule takes the last group that is not a stated fact and an unlinked
        // album name is not one. A linked album is caught (a group of links that are not artist
        // links is a fact), which is the normal case, so this only bites when YouTube links
        // nothing in the row at all. Recorded rather than fixed: bending the rule to guess which
        // of two unlinked names is the album would be guessing, and inventing the wrong artist is
        // what this whole change is trying to stop.
        assertEquals(
            listOf("Forza Horizon 6"),
            names(listOf(plain("Song"), sep(), plain("Milk Talk"), sep(), plain("Forza Horizon 6")))
        )
    }

    @Test
    fun `a real act whose name looks like metadata is kept when YouTube links it`() {
        // These are all in the maintainer's own library: the Bronx drill collective, and acts
        // called 1991, 4K and 19:26. A rule that read the name alone would delete them.
        assertEquals(listOf("41"), names(listOf(plain("Song"), sep(), linked("41", "UC30ejtOxiK0Auw5QK_3XLjw"))))
        assertEquals(listOf("1991"), names(listOf(plain("Song"), sep(), linked("1991", "UChbAx3XyUGwac_pklBMW1PA"))))
        assertEquals(listOf("19:26"), names(listOf(plain("Song"), sep(), linked("19:26", "UCAox6cl_Y-_TThf01XJg_6g"))))
    }

    @Test
    fun `a duration or a bare year in the artist's place is refused`() {
        assertEquals(emptyList<String>(), names(listOf(plain("Song"), sep(), plain("3:21"))))
        assertEquals(emptyList<String>(), names(listOf(plain("Album"), sep(), plain("2016"))))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<String>(), names(emptyList()))
        assertEquals(emptyList<String>(), PageHelper.artists(null).map { it.name })
    }
}
