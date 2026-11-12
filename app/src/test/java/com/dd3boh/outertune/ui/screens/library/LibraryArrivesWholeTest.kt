/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.LibrarySortType
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.viewmodels.libraryKey
import com.dd3boh.outertune.viewmodels.libraryListing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * #144 (Ti-Die, 11 Nov 2026): on the Library page the playlists were drawn first and then pushed
 * down and sideways by the albums and the artists, which are read from the database a few frames
 * later. The page's list of everything is now handed over in one piece, once its three lists have
 * all been read, so no tile is drawn in a place it will not keep.
 */
class LibraryArrivesWholeTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val day = LocalDateTime.of(2026, 11, 1, 12, 0)
    private fun artist(id: String, name: String, added: Long) = Artist(ArtistEntity(id = id, name = name, bookmarkedAt = day.plusDays(added)), songCount = 0, downloadCount = 0)
    private fun album(id: String, title: String, added: Long) =
        Album(AlbumEntity(id = id, title = title, songCount = 10, duration = 2_000, bookmarkedAt = day.plusDays(added)), downloadCount = 0, artists = emptyList())
    private fun playlist(id: String, name: String, added: Long) =
        Playlist(PlaylistEntity(id = id, name = name, bookmarkedAt = day.plusDays(added)), songCount = 0, downloadCount = 0, songThumbnails = emptyList())

    // Added in this order, oldest first: Bravo (album), Delta (playlist), Alpha (artist), Echo (album), Charlie (playlist).
    private val artists = listOf(artist("UCa", "Alpha", 3))
    private val albums = listOf(album("MPREb_b", "Bravo", 1), album("MPREb_e", "Echo", 4))
    private val playlists = listOf(playlist("LPd", "Delta", 2), playlist("LPc", "Charlie", 5))

    private fun keys(items: List<Any>?) = items?.map { libraryKey(it).toString() }
    private fun listing(artists: List<Artist>?, albums: List<Album>?, playlists: List<Playlist>?) =
        libraryListing(artists, albums, playlists, LibrarySortType.CREATE_DATE, descending = true, order = null)

    @Test
    fun `there is no list until the artists, the albums and the playlists have all been read`() {
        assertNull(listing(null, null, null))
        assertNull("the playlists alone, as the page used to draw them first", listing(null, null, playlists))
        assertNull(listing(artists, null, playlists))
        assertNull(listing(null, albums, playlists))
        assertNull(listing(artists, albums, null))
        assertEquals(
            listOf("playlist:LPc", "album:MPREb_e", "artist:UCa", "playlist:LPd", "album:MPREb_b"),
            keys(listing(artists, albums, playlists)),
        )
    }

    @Test
    fun `a list that was read and is empty counts, and an empty library is a list`() {
        assertEquals(listOf("playlist:LPc", "playlist:LPd"), keys(listing(emptyList(), emptyList(), playlists)))
        assertEquals(emptyList<String>(), keys(listing(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `three reads that end one after the other reach the page as one list`() = runBlocking {
        val readArtists = MutableStateFlow<List<Artist>?>(null)
        val readAlbums = MutableStateFlow<List<Album>?>(null)
        val readPlaylists = MutableStateFlow<List<Playlist>?>(null)
        // As LibraryViewModel.allItems hands it to the page: a state, so a value is told once.
        val handed = mutableListOf<List<String>?>()
        val page = launch {
            combine(readArtists, readAlbums, readPlaylists) { a, b, p -> listing(a, b, p) }
                .distinctUntilChanged()
                .collect { handed += keys(it) }
        }
        delay(20)
        readPlaylists.value = playlists      // the quickest of the three on a phone
        delay(20)
        readArtists.value = artists
        delay(20)
        assertEquals("nothing is drawn from the first two lists", listOf<List<String>?>(null), handed.toList())
        readAlbums.value = albums
        delay(20)
        page.cancel()
        assertEquals(2, handed.size)
        assertNull(handed[0])
        // Delta used to be drawn second, under the playlists alone, then moved to fourth.
        assertEquals(listOf("playlist:LPc", "album:MPREb_e", "artist:UCa", "playlist:LPd", "album:MPREb_b"), handed[1])
    }

    @Test
    fun `the order is the one it was, by date, by name and in an order of your own`() {
        fun sorted(sort: LibrarySortType, descending: Boolean, order: List<String>? = null) =
            keys(libraryListing(artists, albums, playlists, sort, descending, order))
        assertEquals(listOf("album:MPREb_b", "playlist:LPd", "artist:UCa", "album:MPREb_e", "playlist:LPc"), sorted(LibrarySortType.CREATE_DATE, false))
        assertEquals(listOf("artist:UCa", "album:MPREb_b", "playlist:LPc", "playlist:LPd", "album:MPREb_e"), sorted(LibrarySortType.NAME, false))
        assertEquals(listOf("album:MPREb_e", "playlist:LPd", "playlist:LPc", "album:MPREb_b", "artist:UCa"), sorted(LibrarySortType.NAME, true))
        // The order kept wins, whatever way round is asked for.
        val own = listOf("playlist:LPd", "artist:UCa", "album:MPREb_e", "playlist:LPc", "album:MPREb_b")
        assertEquals(own, sorted(LibrarySortType.CUSTOM, true, own))
        assertEquals(own, sorted(LibrarySortType.CUSTOM, false, own))
        // The same item from two lists is listed once.
        assertEquals(5, libraryListing(artists + artists, albums, playlists, LibrarySortType.NAME, false, null)?.size)
    }

    @Test
    fun `the view model's three lists start as not read, and its list of everything with them`() {
        val model = code("viewmodels/LibraryViewModels.kt").substringAfter("class LibraryViewModel @Inject constructor(").substringBefore("\n}\n")
        for (read in listOf("database.artistsBookmarkedAsc().withoutBlocked()", "database.albumsLikedAsc().withoutBlocked()", "database.playlistInLibraryAsc()")) {
            assertTrue(read, "$read.stateIn(viewModelScope, SharingStarted.Lazily, null)" in model)
        }
        assertFalse("a list that starts empty is drawn as if it had been read", "emptyList()" in model)
        assertTrue("libraryListing(artists, albums, playlists, sortType, descending, order)" in model)
        assertTrue(Regex("""combine\(artists, albums, playlists\)[^}]*\}\s*\}\s*\.stateIn\(viewModelScope, SharingStarted\.Lazily, null\)""").containsMatchIn(model))
    }

    @Test
    fun `the page lists nothing, and does not say the library is empty, before the list is read`() {
        val screen = code("ui/screens/library/LibraryScreen.kt")
        assertTrue("val readItems by viewModel.allItems.collectAsState()" in screen)
        assertTrue("val allItems = readItems.orEmpty()" in screen)
        // In the list and in the grid.
        assertEquals(2, Regex(Regex.escape("if (readItems != null && allItems.isEmpty() && !showLikedAndDownloadedPlaylist) {")).findAll(screen).count())
        assertEquals(2, Regex(Regex.escape("R.string.library_empty")).findAll(screen).count())
    }
}
