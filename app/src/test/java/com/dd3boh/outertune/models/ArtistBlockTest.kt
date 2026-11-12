/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zionhuang.innertube.models.Artist as Credit

/** What a blocked artist takes out of InterTune (ArtistBlock), and what it leaves. */
class ArtistBlockTest {

    private val subFocus = BlockedArtist(id = "UCsubfocus", name = "Sub Focus", at = 1L)
    private val block = ArtistBlock(listOf(subFocus))

    private fun song(id: String, vararg artists: Credit, explicit: Boolean = false, type: String? = MUSIC_VIDEO_TYPE_ATV) =
        SongItem(id = id, title = id, artists = artists.toList(), thumbnail = "", explicit = explicit, musicVideoType = type)

    private fun album(id: String, vararg artists: Credit, explicit: Boolean = false) =
        AlbumItem(browseId = id, playlistId = "OLAK$id", title = id, artists = artists.toList(), thumbnail = "", explicit = explicit)

    private fun artistItem(id: String, name: String) = ArtistItem(id = id, title = name, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)

    private fun playlist(id: String, author: Credit?) = PlaylistItem(
        id = id, title = id, author = author, songCountText = null, thumbnail = "",
        playEndpoint = WatchEndpoint(playlistId = id), shuffleEndpoint = WatchEndpoint(playlistId = id), radioEndpoint = null,
    )

    private fun stored(id: String, vararg artists: ArtistEntity) =
        Song(SongEntity(id = id, title = id, localPath = null), artists = artists.toList())

    private val him = Credit("Sub Focus", "UCsubfocus")
    private val other = Credit("Wilkinson", "UCwilkinson")

    @Test
    fun `nothing blocked hides nothing, and a list goes through as it came`() {
        val items = listOf(song("a", him), album("b", him))
        assertTrue(ArtistBlock.NONE.isEmpty)
        assertFalse(ArtistBlock.NONE.hides(song("a", him)))
        assertSame(items, ArtistBlock.NONE.apply(items))
        val songs = listOf(stored("s", ArtistEntity("UCsubfocus", "Sub Focus")))
        assertSame(songs, ArtistBlock.NONE.library(songs))
    }

    @Test
    fun `a song, album or video of theirs is hidden by YouTube's id, and the artist too`() {
        assertTrue(block.hides(song("own", him)))
        assertTrue(block.hides(song("video", him, type = MUSIC_VIDEO_TYPE_OMV)))
        assertTrue(block.hides(album("album", him)))
        assertTrue(block.hides(artistItem("UCsubfocus", "Sub Focus")))
        // Their own playlist, by its author.
        assertTrue(block.hides(playlist("PLmine", him)))
    }

    @Test
    fun `a feature counts, whoever comes first`() {
        assertTrue(block.hides(song("feat", other, him)))
        assertTrue(block.hides(album("together", other, him)))
    }

    @Test
    fun `everyone else stays, other people's playlists and a namesake on YouTube included`() {
        assertFalse(block.hides(song("w", other)))
        assertFalse(block.hides(album("w", other)))
        assertFalse(block.hides(artistItem("UCwilkinson", "Wilkinson")))
        assertFalse(block.hides(playlist("PLyours", Credit("YouTube Music", null))))
        assertFalse(block.hides(playlist("PLnobody", null)))
        // Another artist of the same name has an id of its own on YouTube, and it is not blocked.
        assertFalse(block.hides(song("namesake", Credit("Sub Focus", "UCsomeoneelse"))))
    }

    @Test
    fun `a name with no id is blocked by its name, whatever its case and spaces`() {
        assertTrue(block.hides(song("credit", other, Credit("sub  focus ", null))))
        assertTrue(block.blocks(null, "SUB FOCUS"))
        assertFalse(block.blocks(null, "Sub Focused"))
        assertFalse(block.blocks(null, null))
        assertFalse(block.blocks(null, "   "))
    }

    @Test
    fun `a local file's artist, with an id of its own, is blocked by its name`() {
        val local = ArtistEntity("LAabcdefgh", "Sub Focus", isLocal = true)
        assertTrue(block.hides(stored("file", local)))
        assertTrue(block.hides(Artist(local, songCount = 3, downloadCount = 0)))
        assertFalse(block.hides(stored("file2", ArtistEntity("LAzzzzzzzz", "Netsky", isLocal = true))))
    }

    @Test
    fun `an artist blocked from a local file is known by name alone, so the name blocks it on YouTube too`() {
        val byName = ArtistBlock(listOf(BlockedArtist(id = "LAabcdefgh", name = "Netsky")))
        assertTrue(byName.hides(song("yt", Credit("Netsky", "UCnetsky"))))
        assertTrue(byName.blocks("LAabcdefgh", "renamed since"))
        val noId = ArtistBlock(listOf(BlockedArtist(id = null, name = "Netsky")))
        assertTrue(noId.hides(artistItem("UCnetsky", "Netsky")))
    }

    @Test
    fun `the library leaves out their songs, albums and artists, and keeps the rest in order`() {
        val his = ArtistEntity("UCsubfocus", "Sub Focus")
        val theirs = ArtistEntity("UCwilkinson", "Wilkinson")
        val songs = listOf(stored("1", theirs), stored("2", his), stored("3", theirs, his), stored("4", theirs))
        assertEquals(listOf("1", "4"), block.library(songs).map { it.id })
        val albums = listOf(
            Album(AlbumEntity(id = "MPa", title = "A", songCount = 1, duration = 1), downloadCount = 0, artists = listOf(his)),
            Album(AlbumEntity(id = "MPb", title = "B", songCount = 1, duration = 1), downloadCount = 0, artists = listOf(theirs)),
        )
        assertEquals(listOf("MPb"), block.library(albums).map { it.id })
        val artists = listOf(Artist(his, 1, 0), Artist(theirs, 1, 0))
        assertEquals(listOf("UCwilkinson"), block.library(artists).map { it.id })
    }

    @Test
    fun `a song in the queue is told by its artists as well`() {
        val queued = MediaMetadata(id = "q", title = "q", artists = listOf(MediaMetadata.Artist("UCsubfocus", "Sub Focus")), duration = 1, genre = null)
        val fine = queued.copy(artists = listOf(MediaMetadata.Artist(null, "Wilkinson")))
        assertTrue(block.hides(queued))
        assertFalse(block.hides(fine))
        assertEquals(listOf(fine), block.songs(listOf(queued, fine)))
    }

    @Test
    fun `a search is for a blocked artist only when it is their name`() {
        assertEquals(subFocus, block.named("Sub Focus"))
        assertEquals(subFocus, block.named("  sub   FOCUS "))
        assertNull(block.named("Sub Focus Go Back"))
        assertNull(block.named("Sub"))
        assertNull(block.named("Wilkinson"))
        assertNull(block.named(""))
        assertNull(ArtistBlock.NONE.named("Sub Focus"))
    }

    @Test
    fun `the entry that blocks an artist is the one blocks finds`() {
        assertEquals(subFocus, block.entry("UCsubfocus", "Sub Focus"))
        assertEquals(subFocus, block.entry("LAabcdefgh", "Sub Focus"))
        assertNull(block.entry("UCsomeoneelse", "Sub Focus"))
        assertNull(block.entry("UCwilkinson", "Wilkinson"))
    }

    @Test
    fun `it is kept as one setting, and read back as it was written`() {
        val list = listOf(subFocus.copy(thumbnail = "https://lh3.googleusercontent.com/x=w544-h544"), BlockedArtist(id = null, name = "Netsky", at = 5L))
        assertEquals(list, ArtistBlock.decode(ArtistBlock.encode(list)))
        assertEquals(emptyList<BlockedArtist>(), ArtistBlock.decode(null))
        assertEquals(emptyList<BlockedArtist>(), ArtistBlock.decode("not json"))
        // An entry it cannot read is left out, not the whole list.
        assertEquals(listOf("Netsky"), ArtistBlock.decode("""[{"id":"UC1"},{"name":"Netsky","at":2}]""").map { it.name })
        assertEquals(ArtistBlock(list), ArtistBlock.read(ArtistBlock.encode(list)))
    }

    @Test
    fun `blocking again moves the artist to the top without a second entry, and unblocking takes it out`() {
        val netsky = BlockedArtist(id = null, name = "Netsky", at = 2L)
        val list = ArtistBlock.add(ArtistBlock.add(emptyList(), subFocus), netsky)
        assertEquals(listOf("Netsky", "Sub Focus"), list.map { it.name })
        assertEquals(listOf("Sub Focus", "Netsky"), ArtistBlock.add(list, subFocus.copy(at = 9L)).map { it.name })
        assertEquals(listOf("Sub Focus"), ArtistBlock.remove(list, BlockedArtist(id = null, name = "NETSKY")).map { it.name })
        assertEquals(listOf("Netsky"), ArtistBlock.remove(list, subFocus).map { it.name })
    }

    @Test
    fun `the content filter hides them with the switches, and the switches work as before`() {
        val filter = ContentFilter(blocked = block)
        assertFalse(filter.isOff)
        assertTrue(filter.hides(song("own", him)))
        assertFalse(filter.hides(song("explicit", other, explicit = true)))
        val both = ContentFilter(explicit = true, blocked = block)
        assertTrue(both.hides(song("explicit", other, explicit = true)))
        assertTrue(both.hides(song("own", him)))
        assertFalse(both.hides(song("clean", other)))
        // Without a block the switches are what they were.
        assertTrue(ContentFilter(explicit = true).hides(song("e", other, explicit = true)))
        assertFalse(ContentFilter(explicit = true).hides(song("own", him)))
    }

    @Test
    fun `Home's rows, search and artist pages lose them, and a row or part left empty goes`() {
        val filter = ContentFilter(blocked = block)
        val home = HomePage(
            chips = null,
            sections = listOf(
                HomePage.Section(title = "His", label = null, thumbnail = null, endpoint = null, items = listOf(song("1", him), album("2", him))),
                HomePage.Section(title = "Mixed", label = null, thumbnail = null, endpoint = null, items = listOf(song("3", other, him), song("4", other))),
            ),
        )
        val shown = filter.home(home)
        assertEquals(listOf("Mixed"), shown.sections.map { it.title })
        assertEquals(listOf("4"), shown.sections.single().items.map { it.id })
        val search = SearchSummaryPage(listOf(SearchSummary("Artists", listOf(artistItem("UCsubfocus", "Sub Focus"))), SearchSummary("Songs", listOf(song("5", him), song("6", other)))))
        val found = filter.search(search)
        assertEquals(listOf("Songs"), found.summaries.map { it.title })
        assertEquals(listOf("6"), found.summaries.single().items.map { it.id })
    }

    @Test
    fun `a radio keeps the song it started from, which playback then passes over, and loses the rest of theirs`() {
        val (kept, at) = ContentFilter(blocked = block).radio(listOf(song("seed", him), song("a", him), song("b", other), song("c", other, him)), start = 0)
        assertEquals(listOf("seed", "b"), kept.map { it.id })
        assertEquals(0, at)
    }
}
