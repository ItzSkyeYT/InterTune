/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.AlbumFilterKey
import com.dd3boh.outertune.constants.AlbumSortDescendingKey
import com.dd3boh.outertune.constants.AlbumSortType
import com.dd3boh.outertune.constants.AlbumSortTypeKey
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistFilterKey
import com.dd3boh.outertune.constants.ArtistSongSortDescendingKey
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.ArtistSongSortTypeKey
import com.dd3boh.outertune.constants.ArtistSortDescendingKey
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.ArtistSortTypeKey
import com.dd3boh.outertune.constants.LibrarySortDescendingKey
import com.dd3boh.outertune.constants.LibraryCustomOrderKey
import com.dd3boh.outertune.constants.LibrarySortType
import com.dd3boh.outertune.constants.LibrarySortTypeKey
import com.dd3boh.outertune.constants.PlaylistCustomOrderKey
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistFilterKey
import com.dd3boh.outertune.constants.PlaylistSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.PlaylistSortTypeKey
import com.dd3boh.outertune.constants.SongFilter
import com.dd3boh.outertune.constants.SongFilterKey
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.LibraryOrder
import com.dd3boh.outertune.models.PlaylistOrder
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.models.withoutBlocked
import com.dd3boh.outertune.ui.utils.STORAGE_ROOT
import com.dd3boh.outertune.ui.utils.cacheDirectoryTree
import com.dd3boh.outertune.ui.utils.getDirectoryTree
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.refreshLocal
import com.dd3boh.outertune.utils.scanners.TrackTagReread
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allSongs = getSyncedSongs(context, database)
    val isSyncingRemoteLikedSongs = syncUtils.isSyncingRemoteLikedSongs
    val isSyncingRemoteSongs = syncUtils.isSyncingRemoteSongs

    fun syncLibrarySongs(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteSongs(bypassCd) }
    }

    fun syncLikedSongs(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteLikedSongs(bypassCd) }
    }

    private fun getSyncedSongs(context: Context, database: MusicDatabase): StateFlow<List<Song>?> {

        return context.dataStore.data
            .map {
                Triple(
                    it[SongFilterKey].toEnum(SongFilter.LIKED),
                    it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                    (it[SongSortDescendingKey] != false)
                )
            }
            .distinctUntilChanged()
            .flatMapLatest { (filter, sortType, descending) ->
                when (filter) {
                    SongFilter.LIBRARY -> database.songs(sortType, descending)
                    SongFilter.LIKED -> database.likedSongs(sortType, descending)
                    SongFilter.DOWNLOADED -> database.downloadSongs(sortType, descending)
                }
            }
            // Nor a blocked artist's songs, downloads and local files included (ArtistBlock).
            .withoutBlocked()
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
    }
}

@HiltViewModel
class LibraryFoldersViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val TAG = LibraryFoldersViewModel::class.simpleName.toString()
    // Navigation decodes the argument: FolderScreen encodes the whole path into the route.
    val path = savedStateHandle.get<String>("path") ?: STORAGE_ROOT

    val localSongDirectoryTree: MutableStateFlow<DirectoryTree> = MutableStateFlow(getDirectoryTree(path))
    val localSongDtSongCount = MutableStateFlow(0)
    val filteredSongs = mutableStateListOf<Song>()

    var uiInit = false
    var lastLocalScan = 0L

    init {
        // Numbers the one-time tag pass gives while this folder is open (TrackTagReread). Without
        // this the folder kept the old order until it was left and opened again.
        viewModelScope.launch(Dispatchers.IO) {
            TrackTagReread.changes.drop(1).collect {
                if (uiInit) {
                    getLocalSongs()
                    getSongCount()
                }
            }
        }
    }

    /**
     * Trigger a scan of local directory
     */
    suspend fun getLocalSongs(dir: String? = null) {
        Log.d(TAG, "Loading folders page: ${dir ?: path}")
        val dt = refreshLocal(database, dir ?: path)
        dt.isSkeleton = false
        cacheDirectoryTree(dt)
        localSongDirectoryTree.value = dt
    }

    /**
     * Get total number of songs in directory
     */
    suspend fun getSongCount(dir: String? = null) {
        Log.d(TAG, "Loading folder song count: ${dir ?: path}")
        localSongDtSongCount.value = database.localSongCountInPath(dir ?: path).first()
    }

    private var searchJob: Job? = null

    /**
     * Update filteredSongs with search query. The last query asked wins: the search still running
     * for an earlier one is cancelled, or a short query with many rows could finish after the
     * longer one typed next and put its rows back. An emptied field empties the results, as when
     * the search opens, rather than leaving the last ones up. Written on the main thread, where
     * the list is drawn from.
     */
    fun searchInDir(query: String, dir: String = path) {
        searchJob?.cancel()
        if (query.isBlank()) {
            filteredSongs.clear()
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val dbSongs = ContentFilter.current.blocked.library(database.searchSongsAllLocalInDir(dir, query).first())
            withContext(Dispatchers.Main) {
                filteredSongs.clear()
                filteredSongs.addAll(dbSongs)
            }
        }
    }
}

@HiltViewModel
class LibraryArtistsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemoteArtists = syncUtils.isSyncingRemoteArtists

    private data class Listing(val filter: ArtistFilter, val sortType: ArtistSortType, val descending: Boolean, val order: List<String>)

    val allArtists = context.dataStore.data
        .map {
            Listing(
                it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE).let { sort ->
                    // A stored Custom order reads as Date added where it is not offered.
                    if (sort == ArtistSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) ArtistSortType.CREATE_DATE else sort
                },
                it[ArtistSortDescendingKey] ?: true,
                LibraryOrder.idsOf(LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey]), LibraryOrder.ARTIST),
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending, order) ->
            if (sortType == ArtistSortType.CUSTOM) {
                // The library's one order, which the Library page shows whole; oldest first by when
                // each came into the library before it, as there. No way round.
                database.artists(filter, sortType, false).map { artists ->
                    PlaylistOrder.apply(artists.sortedBy { it.artist.bookmarkedAt.toString() }, order) { it.id }
                }
            } else {
                database.artists(filter, sortType, descending)
            }
        }
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * The artists' order after a drag, every liked artist, as shown: they take the places the
     * library's one order gives artists, and its albums and playlists stay put.
     */
    fun saveCustomOrder(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit {
                val saved = LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey])
                val whole = LibraryOrder.refill(saved, ids.map { id -> LibraryOrder.ARTIST + id }) { key -> key.startsWith(LibraryOrder.ARTIST) }
                it[LibraryCustomOrderKey] = PlaylistOrder.encode(whole)
            }
        }
    }

    fun syncArtists(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteArtists(bypassCd) }
    }

    init {
        // Fetching artist pages for artists with no picture or stale details, once, on the first
        // real list. This used to collect allArtists for as long as the view model lived, and
        // that is a Room query that runs again on every write to the artist table. Playback
        // writes to it in the background: every new related song the service records is inserted
        // with its artists and album, and a related song by an artist already in the list raises
        // that artist's song count, so the list really does change. Each change rescanned the
        // whole list and fetched every artist that still qualified, with the screen off and
        // nobody looking at the library, and an artist whose page kept failing was asked for
        // again every time with no pause at all. An artist whose page has no picture did not
        // even need playback: the fetch stamps lastUpdateTime, which is a change to the list,
        // and the picture is still missing, so it qualified again and was fetched again, round
        // and round. It is a repair job, not a reaction to the list changing, so one pass is
        // enough. Whatever turns up later, or fails this time, is tried again the next time this
        // view model is created.
        //
        // filterNotNull because allArtists starts as the stateIn placeholder, null, and first()
        // on a StateFlow hands back whatever it holds right now. Without it this would read the
        // placeholder and do nothing.
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.filterNotNull().first().let { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemoteAlbums = syncUtils.isSyncingRemoteAlbums

    private data class Listing(val filter: AlbumFilter, val sortType: AlbumSortType, val descending: Boolean, val order: List<String>)

    val allAlbums = context.dataStore.data
        .map {
            Listing(
                it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE).let { sort ->
                    // A stored Custom order reads as Date added where it is not offered.
                    if (sort == AlbumSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) AlbumSortType.CREATE_DATE else sort
                },
                it[AlbumSortDescendingKey] ?: true,
                LibraryOrder.idsOf(LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey]), LibraryOrder.ALBUM),
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending, order) ->
            if (sortType == AlbumSortType.CUSTOM) {
                // The library's one order, which the Library page shows whole; oldest first by when
                // each came into the library before it, as there. No way round.
                database.albums(filter, sortType, false).map { albums ->
                    PlaylistOrder.apply(albums.sortedBy { it.album.bookmarkedAt.toString() }, order) { it.id }
                }
            } else {
                database.albums(filter, sortType, descending)
            }
        }
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * The albums' order after a drag, every liked album, as shown: they take the places the
     * library's one order gives albums, and its artists and playlists stay put.
     */
    fun saveCustomOrder(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit {
                val saved = LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey])
                val whole = LibraryOrder.refill(saved, ids.map { id -> LibraryOrder.ALBUM + id }) { key -> key.startsWith(LibraryOrder.ALBUM) }
                it[LibraryCustomOrderKey] = PlaylistOrder.encode(whole)
            }
        }
    }

    fun syncAlbums(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemoteAlbums(bypassCd) }
    }

    init {
        // Once, on the first real list, for the same reasons as the artist backfill above, and
        // one more. update(album, albumPage) writes the album's songs and their artists, so every
        // album it fixed made this list, and the artist list, run again and rescan. And an album
        // whose page really has no songs is written back with a song count of 0, so it qualified
        // on every one of those passes and was fetched again each time, forever.
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.filterNotNull().first().let { albums ->
                albums
                    .filter {
                        !it.album.isLocal && it.album.songCount == 0
                    }.forEach { album ->
                        YouTube.album(album.id).onSuccess { albumPage ->
                            database.query {
                                update(album.album, albumPage)
                            }
                        }.onFailure {
                            // Kept, whatever the failure said: a saved album arrives from the
                            // account with no songs, so this deleted saved albums on a NOT_FOUND
                            // that could have been passing. See AlbumViewModel.
                            reportException(it)
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val isSyncingRemotePlaylists = syncUtils.isSyncingRemotePlaylists

    private data class Listing(val filter: PlaylistFilter, val sortType: PlaylistSortType, val descending: Boolean, val order: List<String>)

    val allPlaylists = context.dataStore.data
        .map {
            Listing(
                it[PlaylistFilterKey].toEnum(PlaylistFilter.LIBRARY),
                it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE).let { sort ->
                    // A stored Custom order reads as Date added where it is not offered.
                    if (sort == PlaylistSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) PlaylistSortType.CREATE_DATE else sort
                },
                it[PlaylistSortDescendingKey] ?: true,
                LibraryOrder.playlistIds(LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey])),
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending, order) ->
            if (sortType == PlaylistSortType.CUSTOM) {
                // No way round in an order of your own: it is the one dragged.
                database.playlists(filter, sortType, false).map { playlists ->
                    PlaylistOrder.apply(playlists, order) { it.id }
                }
            } else {
                database.playlists(filter, sortType, descending)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * The playlists' order after a drag, every playlist of the library, as shown: they take the
     * places the library's one order gives playlists, and its albums and artists stay put.
     */
    fun saveCustomOrder(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit {
                val saved = LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey])
                val whole = LibraryOrder.refill(saved, ids.map { id -> LibraryOrder.PLAYLIST + id }) { key -> key.startsWith(LibraryOrder.PLAYLIST) }
                it[LibraryCustomOrderKey] = PlaylistOrder.encode(whole)
            }
        }
    }

    fun syncPlaylists(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncRemotePlaylists(bypassCd) }
    }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils
) : ViewModel() {

    val isSyncingRemoteLikedSongs = syncUtils.isSyncingRemoteLikedSongs
    val isSyncingRemoteSongs = syncUtils.isSyncingRemoteSongs
    val isSyncingRemoteAlbums = syncUtils.isSyncingRemoteAlbums
    val isSyncingRemoteArtists = syncUtils.isSyncingRemoteArtists
    val isSyncingRemotePlaylists = syncUtils.isSyncingRemotePlaylists

    var artists = database.artistsBookmarkedAsc().withoutBlocked().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums = database.albumsLikedAsc().withoutBlocked().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists = database.playlistInLibraryAsc().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private data class Listing(val sortType: LibrarySortType, val descending: Boolean, val order: List<String>?)

    val allItems = context.dataStore.data
        .map {
            val sortType = it[LibrarySortTypeKey].toEnum(LibrarySortType.CREATE_DATE).let { sort ->
                // A stored Custom order reads as Date added where it is not offered.
                if (sort == LibrarySortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) LibrarySortType.CREATE_DATE else sort
            }
            Listing(
                sortType,
                it[LibrarySortDescendingKey] != false,
                if (sortType == LibrarySortType.CUSTOM) LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey]) else null,
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending, order) ->
            combine(artists, albums, playlists) { artists, albums, playlists ->
                // Once each, by what it is: the screen used to take out repeats by their hash,
                // which reads every field of every item on each pass, and would drop one of two
                // different items that happened to hash alike.
                val items = (artists + albums + playlists).distinctBy(::libraryKey)
                // Custom order: oldest first, then in the order kept, with no way round.
                if (order != null) return@combine PlaylistOrder.apply(items.sortedBy { addedAt(it) }, order) { libraryKey(it).toString() }
                items.sortedBy { item ->
                    when (sortType) {
                        LibrarySortType.NAME -> when (item) {
                            is Album -> item.album.title.lowercase()
                            is Artist -> item.artist.name.lowercase()
                            is Playlist -> item.playlist.name.lowercase()
                            else -> ""
                        }

                        else -> addedAt(item)
                    }
                }.let { if (descending) it.reversed() else it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** When an item came into the library, as Date added sorts them. */
    private fun addedAt(item: Any): String = when (item) {
        is Album -> item.album.bookmarkedAt
        is Artist -> item.artist.bookmarkedAt
        is Playlist -> item.playlist.bookmarkedAt
        else -> LocalDateTime.now()
    }.toString()

    /** The library's order after a drag on this page: every album, artist and playlist, as shown. */
    fun saveCustomOrder(keys: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.edit { it[LibraryCustomOrderKey] = PlaylistOrder.encode(keys) }
        }
    }

    fun syncAll(bypassCd: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.tryAutoSync(bypassCd) }
    }
}

@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs = context.dataStore.data
        .map {
            it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey]
                ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.artistSongs(artistId, sortType, descending)
        }
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

/**
 * What an item of Library's list of everything is known by, the same for as long as it is the
 * same album, artist or playlist, whatever else about it changes: its key in the list, and what
 * repeats are taken out by.
 */
internal fun libraryKey(item: Any): Any = when (item) {
    is Album -> "album:${item.id}"
    is Artist -> "artist:${item.id}"
    is Playlist -> "playlist:${item.id}"
    else -> item
}
