/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.engine.EngineExclusionRow
import com.dd3boh.outertune.engine.EngineInput
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.Weights
import com.dd3boh.outertune.playback.QueueFit
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Don't play this song keeps radios and autoplay from bringing (DontPlay, #78), what it
 * leaves on the pages so that the song can still be picked, and the queues that read it.
 */
class DontPlayTest {

    private fun song(id: String, title: String, artist: String = "Toto", explicit: Boolean = false, type: String = MUSIC_VIDEO_TYPE_ATV) =
        SongItem(id = id, title = title, artists = listOf(Artist(artist, "UC$artist")), thumbnail = "", explicit = explicit, musicVideoType = type)

    private val dontPlay = DontPlay(listOf(DontPlaySong("af1", "Africa", "Toto")))
    private val filter = ContentFilter(dontPlay = dontPlay)

    private val page = listOf(song("r1", "Rosanna"), song("af1", "Africa"), song("r2", "Hold the Line"))

    @Test
    fun `a radio's page loses the song not to play, and keeps it when it is the song chosen`() {
        val (kept, at) = filter.radio(page, start = 0)
        assertEquals(listOf("r1", "r2"), kept.map { it.id })
        assertEquals(0, at)
        // The radio started from it: the listener picked it, so it stays and the radio starts on it.
        val (chosen, start) = filter.radio(page, start = 1)
        assertEquals(listOf("r1", "af1", "r2"), chosen.map { it.id })
        assertEquals(1, start)
        // Chosen, it still takes nothing else with it: the next one not to play goes.
        val (later, from) = filter.radio(page + song("af9", "Africa (Live)"), start = 1)
        assertEquals(listOf("r1", "af1", "r2"), later.map { it.id })
        assertEquals(1, from)
    }

    @Test
    fun `the next pages and the radio's top-up lose it too, and its other uploads with it`() {
        val next = listOf(
            song("r1", "Rosanna"),
            song("af2", "Africa (Official HD Video)", type = MUSIC_VIDEO_TYPE_OMV),
            song("af1", "Africa"),
            song("w1", "Africa", artist = "Weezer"),
        )
        assertEquals(listOf("r1", "w1"), filter.queue(next).map { it.id })
    }

    @Test
    fun `search, Home and an artist's page still show it, so it can be picked`() {
        val items = listOf(song("af1", "Africa"), song("r1", "Rosanna"))
        assertFalse(filter.hides(items[0]))
        assertTrue("nothing hidden from the pages", filter.isOff)
        assertSame(items, filter.apply(items))
        val home = HomePage(chips = null, sections = listOf(HomePage.Section(title = "Toto", label = null, thumbnail = null, endpoint = null, items = items)))
        assertSame(home, filter.home(home))
        val search = SearchSummaryPage(listOf(SearchSummary("Songs", items)))
        assertSame(search, filter.search(search))
    }

    @Test
    fun `with nothing set every list goes through as it came, and the switches still apply beside it`() {
        assertSame(page, ContentFilter().queue(page))
        assertEquals(page to 1, ContentFilter().radio(page, start = 1))
        val both = ContentFilter(explicit = true, dontPlay = dontPlay)
        val mixed = listOf(song("e1", "Explicit", explicit = true), song("af1", "Africa"), song("ok", "Fine"))
        assertEquals(listOf("ok"), both.queue(mixed).map { it.id })
        // On a page only the switch hides anything.
        assertEquals(listOf("af1", "ok"), both.apply(mixed).map { it.id })
    }

    @Test
    fun `a song is known by its id, and by its title and first artist where the library had the artist`() {
        assertTrue(dontPlay.covers("af1", "Something else", "Someone"))
        assertTrue(dontPlay.covers("x", "Africa (Live)", "Toto"))
        assertTrue(dontPlay.covers("x", "Africa - Remastered 2011", "Toto"))
        assertFalse("the same name by another artist is another song", dontPlay.covers("x", "Africa", "Weezer"))
        assertFalse(dontPlay.covers("x", "Rosanna", "Toto"))
        assertFalse(dontPlay.covers("x", "Africa", null))
        // A song the library had no artist for is known by its id alone.
        val idOnly = DontPlay(listOf(DontPlaySong("u1", "Africa", null)))
        assertTrue(idOnly.covers("u1", "Africa", "Toto"))
        assertFalse(idOnly.covers("u2", "Africa", "Toto"))
        // A title that is nothing but brackets has no song to match others by.
        val brackets = DontPlay(listOf(DontPlaySong("b1", "[Intro]", "Toto")))
        assertFalse(brackets.covers("b2", "(Outro)", "Toto"))
        assertTrue(DontPlay.NONE.isEmpty)
        assertFalse(DontPlay.NONE.covers("af1", "Africa", "Toto"))
        // Only this very upload is one its menu can lift.
        assertTrue(dontPlay.has("af1"))
        assertFalse(dontPlay.has("af2"))
    }

    @Test
    fun `the player's songs are known the same way`() {
        val video = MediaMetadata(id = "v9", title = "Africa (Official Video)", artists = listOf(MediaMetadata.Artist("UCToto", "Toto")), duration = 200, genre = null)
        val other = MediaMetadata(id = "v8", title = "Rosanna", artists = listOf(MediaMetadata.Artist("UCToto", "Toto")), duration = 200, genre = null)
        assertTrue(dontPlay.covers(video))
        assertFalse(dontPlay.covers(other))
    }

    @Test
    fun `the adaptive queue never picks it nor keeps it ahead, as a ban`() {
        val input = EngineInput(1_000L, emptyMap(), emptyList(), emptyList())
        val row = EngineExclusionRow(ExclusionsViewModel.KIND_SONG, "af1", "Africa", ExclusionsViewModel.REASON_DONT_PLAY)
        val taste = QueueFit.Taste.of(EngineLoader.HeardRows(input, listOf(row), Weights.PRIORS))
        val africa = MediaMetadata(id = "af1", title = "Africa", artists = listOf(MediaMetadata.Artist(null, "Toto")), duration = 200, genre = null)
        assertTrue(taste.banned(africa))
        assertFalse(taste.banned(africa.copy(id = "r1", title = "Rosanna")))
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    @Test
    fun `the radios read it, the first page, every next page, an album's radio and the radio ladder's lists`() {
        val queue = code("playback/queues/YouTubeQueue.kt")
        assertTrue("ContentFilter.current.radio(nextResult.items, first)" in queue)
        assertTrue("nextResult?.items?.let(ContentFilter.current::queue)" in queue)
        assertFalse("a next page passes over only what the pages hide", "ContentFilter.current::apply" in queue)
        val album = code("playback/queues/YouTubeAlbumRadio.kt")
        assertEquals(2, Regex("""ContentFilter\.current\.queue\(""").findAll(album).count())
        assertFalse("ContentFilter.current.apply(" in album)
        val service = code("playback/MusicService.kt")
        assertTrue("AiFilter.forQueue().recommendations(ContentFilter.current.queue(page.songs))" in service)
        assertTrue(".filterNot(filter.dontPlay::covers)" in service)
    }

    @Test
    fun `App follows the songs from the database only in a build with the feature`() {
        val app = code("App.kt")
        assertTrue("val dontPlay = if (!Unreleased.ARTIST_BLOCK) flowOf(DontPlay.NONE) else {" in app)
        assertTrue(".dontPlaySongs()" in app)
        assertTrue("filter.copy(dontPlay = songs)" in app)
        // The reason the database keeps it under, and nothing else: Not this song stays on Home.
        val dao = code("db/daos/ListenDao.kt")
        assertTrue("WHERE x.kind = ${ExclusionsViewModel.KIND_SONG} AND x.reason = ${ExclusionsViewModel.REASON_DONT_PLAY}\"" in dao)
        assertTrue("WHERE kind = ${ExclusionsViewModel.KIND_SONG} AND reason = ${ExclusionsViewModel.REASON_DONT_PLAY} AND targetId = :songId\"" in dao)
    }
}
