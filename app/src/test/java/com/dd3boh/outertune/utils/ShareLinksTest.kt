/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The links Share can send. What has gone out to somebody cannot be taken back, so the YouTube
 * Music link stays exactly what it always was, and the share page's link has to carry the song
 * whole: the page reads the name, the artists and the length out of it and has nothing else to go by.
 */
class ShareLinksTest {

    private val id = "dQw4w9WgXcQ"
    private val albumList = "OLAK5uy_kNhM2yaBTOVwrcZJepB1C9P3-n5_Sfy5c"
    private val albumBrowseLink = "https://music.youtube.com/browse/MPREb_K8qWMWVqXGi"

    private fun rick(seconds: Int = 213, albumTrack: Boolean = true) =
        ShareLinks.song(id, "Never Gonna Give You Up", listOf("Rick Astley"), seconds, albumTrack)

    /** What the page reads back out of a link, the way a browser's URLSearchParams does. */
    private fun read(link: String): Map<String, String> =
        link.substringAfter('#').split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    @Test
    fun `a song keeps the link it always had`() {
        assertEquals("https://music.youtube.com/watch?v=$id", rick().youTubeMusic)
    }

    @Test
    fun `the page's link carries the song`() {
        assertEquals("${ShareLinks.PAGE}#v=$id&t=Never%20Gonna%20Give%20You%20Up&a=Rick%20Astley&d=213&k=t", rick().page)
    }

    @Test
    fun `a video is not marked as an album track, and an unknown length is left out`() {
        assertEquals("${ShareLinks.PAGE}#v=$id&t=Never%20Gonna%20Give%20You%20Up&a=Rick%20Astley", rick(seconds = -1, albumTrack = false).page)
        assertFalse(rick(seconds = 0).page!!.contains("&d="))
    }

    @Test
    fun `whatever is in a name comes back out of the link as it went in`() {
        val names = listOf(
            "Where Are Ü Now (with Justin Bieber)", "夜に駆ける", "A&B=C#D?E+F%G/H", "Don't \"Stop\" 'Now'", "Line one\nline two",
            "100% 🔥 + more", "  spaces  kept inside  ", "<img src=x onerror=alert(1)>", "t=1&v=zzzzzzzzzzz",
        )
        for (name in names) {
            val got = read(ShareLinks.song(id, name, listOf(name, "Second, With Comma"), 100, false).page!!)
            assertEquals(name, name.trim(), got["t"])
            assertEquals("${name.trim()}, Second, With Comma", got["a"])
            assertEquals(id, got["v"])
            assertEquals("100", got["d"])
        }
    }

    @Test
    fun `nothing but letters, digits and percent signs after the names begin`() {
        val link = ShareLinks.song(id, "A&B=C#D?E+F%G/H \"x\" 夜", listOf("Earth, Wind & Fire"), 100, true).page!!
        val carried = link.substringAfter("&t=")
        assertTrue(carried, Regex("[A-Za-z0-9%._*-]+&a=[A-Za-z0-9%._*-]+&d=100&k=t").matches(carried))
    }

    @Test
    fun `several artists are told apart by a comma and a space, eight at most`() {
        val link = ShareLinks.song(id, "Song", (1..12).map { "Artist $it" }, 100, false)
        assertEquals((1..8).joinToString(", ") { "Artist $it" }, read(link.page!!)["a"])
        assertEquals("Song · Artist 1, Artist 2, Artist 3", ShareLinks.song(id, "Song", listOf("Artist 1", "Artist 2", "Artist 3"), 100, false).caption)
    }

    @Test
    fun `a very long name is cut where the page would cut it`() {
        assertEquals(200, read(ShareLinks.song(id, "x".repeat(500), listOf("Somebody"), 100, false).page!!)["t"]!!.length)
    }

    @Test
    fun `what is not a YouTube song has no page`() {
        for (other in listOf("", "dQw4w9WgXc", "dQw4w9WgXcQQ", "LA1f0c9d2e7b", "dQw4w9 gXcQ", "dQw4w/WgXcQ")) {
            assertNull(other, ShareLinks.song(other, "Song", listOf("Somebody"), 100, true).page)
        }
        assertNull("a song with no name", ShareLinks.song(id, "  ", listOf("Somebody"), 100, true).page)
        assertEquals("https://music.youtube.com/watch?v=LA1f0c9d2e7b", ShareLinks.song("LA1f0c9d2e7b", "Song", emptyList(), 100, true).youTubeMusic)
    }

    @Test
    fun `an album keeps the link it was given and gets a page from its playlist id`() {
        val album = ShareLinks.album(albumBrowseLink, albumList, "Random Access Memories", listOf("Daft Punk"))
        assertEquals(albumBrowseLink, album.youTubeMusic)
        assertEquals("${ShareLinks.PAGE}#l=$albumList&t=Random%20Access%20Memories&a=Daft%20Punk", album.page)
        assertEquals("Random Access Memories · Daft Punk", album.caption)
    }

    @Test
    fun `an album without its own playlist id has no page`() {
        for (other in listOf(null, "", "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", "MPREb_K8qWMWVqXGi", "OLAK5uy_short", " $albumList", "$albumList&x=1")) {
            assertNull(other, ShareLinks.album(albumBrowseLink, other, "Album", listOf("Somebody")).page)
        }
    }

    @Test
    fun `the YouTube Music choice sends the link alone, as before`() {
        assertEquals(ShareAction.Send("https://music.youtube.com/watch?v=$id"), ShareLinks.decide(rick(), ShareLinkKind.YOUTUBE_MUSIC))
    }

    @Test
    fun `the page choice sends the name on one line and the link on the next`() {
        val link = rick()
        assertEquals(ShareAction.Send("Never Gonna Give You Up · Rick Astley\n${link.page}"), ShareLinks.decide(link, ShareLinkKind.PAGE))
    }

    @Test
    fun `where there is no page the YouTube Music link is sent and nobody is asked`() {
        val local = ShareLinks.song("LA1f0c9d2e7b", "Song", listOf("Somebody"), 100, true)
        assertEquals(ShareAction.Send(local.youTubeMusic), ShareLinks.decide(local, ShareLinkKind.PAGE))
        assertEquals(ShareAction.Send(local.youTubeMusic), ShareLinks.decide(local, ShareLinkKind.ASK))
        assertEquals(ShareAction.Ask, ShareLinks.decide(rick(), ShareLinkKind.ASK))
    }

    @Test
    fun `a song with no artist is named by its name alone`() {
        val link = ShareLinks.song(id, "Song", emptyList(), 100, false)
        assertEquals("Song", link.caption)
        assertFalse(link.page!!.contains("&a="))
    }
}
