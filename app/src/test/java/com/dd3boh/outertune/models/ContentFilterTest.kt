/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_PODCAST_EPISODE
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which items the three switches under Library and content keep out, and what stays. */
class ContentFilterTest {

    private fun song(id: String, explicit: Boolean = false, type: String? = MUSIC_VIDEO_TYPE_ATV) =
        SongItem(id = id, title = id, artists = listOf(Artist("A", null)), thumbnail = "", explicit = explicit, musicVideoType = type)

    private fun album(id: String, explicit: Boolean = false) =
        AlbumItem(browseId = id, playlistId = "OLAK$id", title = id, artists = null, thumbnail = "", explicit = explicit)

    private fun playlist(id: String) = PlaylistItem(
        id = id, title = id, author = null, songCountText = null, thumbnail = "",
        playEndpoint = WatchEndpoint(playlistId = id), shuffleEndpoint = WatchEndpoint(playlistId = id), radioEndpoint = null,
    )

    private val artist = ArtistItem(id = "UC1", title = "A", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)

    private val all = ContentFilter(explicit = true, videoSongs = true, shorts = true)

    @Test
    fun `off by default, and then nothing is hidden and the list is the same list`() {
        val items = listOf(song("e", explicit = true), song("v", type = MUSIC_VIDEO_TYPE_OMV), playlist("SSshorts"))
        assertTrue(ContentFilter().isOff)
        assertSame(items, ContentFilter().apply(items))
    }

    @Test
    fun `explicit hides explicit songs and albums, and nothing else`() {
        val filter = ContentFilter(explicit = true)
        assertTrue(filter.hides(song("e", explicit = true)))
        assertTrue(filter.hides(album("e", explicit = true)))
        assertFalse(filter.hides(song("clean")))
        assertFalse(filter.hides(album("clean")))
        assertFalse(filter.hides(song("v", type = MUSIC_VIDEO_TYPE_OMV)))
        assertFalse(filter.hides(artist))
    }

    @Test
    fun `music videos and uploads are video songs, album tracks, episodes and the unknown are not`() {
        val filter = ContentFilter(videoSongs = true)
        assertTrue(filter.hides(song("omv", type = MUSIC_VIDEO_TYPE_OMV)))
        assertTrue(filter.hides(song("ugc", type = MUSIC_VIDEO_TYPE_UGC)))
        assertFalse(filter.hides(song("atv", type = MUSIC_VIDEO_TYPE_ATV)))
        assertFalse(filter.hides(song("episode", type = MUSIC_VIDEO_TYPE_PODCAST_EPISODE)))
        // A list that does not say what its songs are keeps them all.
        assertFalse(filter.hides(song("unknown", type = null)))
    }

    @Test
    fun `Shorts are the lists whose id starts with SS, and only those`() {
        val filter = ContentFilter(shorts = true)
        assertTrue(filter.hides(playlist("SSabcdef")))
        assertFalse(filter.hides(playlist("PLabcdef")))
        assertFalse(filter.hides(playlist("RDCLAK5uy_abc")))
        assertFalse(filter.hides(song("SSnotalist")))
    }

    @Test
    fun `a radio keeps the song it started from, even a hidden one, and says where it now is`() {
        val items = listOf(song("v1", type = MUSIC_VIDEO_TYPE_OMV), song("e1", explicit = true), song("chosen", explicit = true), song("ok"), song("e2", explicit = true))
        val (kept, at) = all.radio(items, start = 2)
        assertEquals(listOf("chosen", "ok"), kept.map { it.id })
        assertEquals(0, at)

        val (kept2, at2) = all.radio(listOf(song("a"), song("e", explicit = true), song("b")), start = 2)
        assertEquals(listOf("a", "b"), kept2.map { it.id })
        assertEquals(1, at2)
    }

    @Test
    fun `a radio with nothing in it or a start past its end does not throw`() {
        assertEquals(emptyList<SongItem>() to 0, all.radio(emptyList(), start = 0))
        val (kept, at) = all.radio(listOf(song("a")), start = 5)
        assertEquals(listOf("a"), kept.map { it.id })
        assertEquals(0, at)
    }

    @Test
    fun `a row or a part of a search left empty goes`() {
        val home = HomePage(
            chips = null,
            sections = listOf(
                HomePage.Section(title = "Only explicit", label = null, thumbnail = null, endpoint = null, items = listOf(song("e", explicit = true))),
                HomePage.Section(title = "Mixed", label = null, thumbnail = null, endpoint = null, items = listOf(song("e2", explicit = true), song("ok"))),
            ),
        )
        val filtered = ContentFilter(explicit = true).home(home)
        assertEquals(listOf("Mixed"), filtered.sections.map { it.title })
        assertEquals(listOf("ok"), filtered.sections.single().items.map { it.id })

        val search = SearchSummaryPage(listOf(SearchSummary("Videos", listOf(song("v", type = MUSIC_VIDEO_TYPE_UGC))), SearchSummary("Songs", listOf(song("s")))))
        assertEquals(listOf("Songs"), ContentFilter(videoSongs = true).search(search).summaries.map { it.title })
    }

    private fun texts(folder: String) = File("src/main/res/$folder/strings-ot.xml").readText()

    @Test
    fun `each switch has its words in English and in French, and says it in two sentences at most`() {
        for (folder in listOf("values", "values-fr")) {
            val texts = texts(folder)
            for (name in listOf("hide_explicit_description", "hide_video_songs", "hide_video_songs_description", "hide_shorts", "hide_shorts_description")) {
                val text = Regex("<string name=\"$name\">(.*?)</string>").find(texts)?.groupValues?.get(1)
                assertTrue("$folder $name", text != null)
                assertTrue("$folder $name", Regex("[.!?](?=\\s|$)").findAll(text!!).count() <= 2)
                assertFalse("$folder $name", '—' in text || '!' in text)
            }
        }
        // The first switch's title is upstream's, translated already.
        assertTrue("name=\"hide_explicit\"" in File("src/main/res/values-fr/strings.xml").readText())
    }
}
