/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.models.withoutBlocked
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class LocalCategory(
    @StringRes val titleId: Int,
    val icon: ImageVector,
) {
    SONGS(R.string.local_category_songs, Icons.Rounded.MusicNote),
    ARTISTS(R.string.local_category_artists, Icons.Rounded.Person),
    ALBUMS(R.string.local_category_albums, Icons.Rounded.Album),
    GENRES(R.string.local_category_genres, Icons.Rounded.Category),
    DATES(R.string.local_category_dates, Icons.Rounded.DateRange),
    FOLDERS(R.string.local_category_folders, Icons.Rounded.Folder),
    FILESYSTEM(R.string.local_category_filesystem, Icons.Rounded.Storage),
    PLAYLISTS(R.string.local_category_playlists, Icons.AutoMirrored.Rounded.QueueMusic),
}

data class LocalGenre(
    val name: String,
    val songCount: Int,
    val songs: List<Song>,
    val coverPath: String? = null,
)

data class FsItem(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long = 0L,
    val isAudio: Boolean = false,
    val matchedSong: Song? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalMusicViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "ogg", "wav", "m4a", "opus", "aac", "wma", "ape", "wv", "dsf", "dff"
        )
    }

    val activeCategory = MutableStateFlow(LocalCategory.SONGS)
    val selectedGenre = MutableStateFlow<LocalGenre?>(null)

    // Local Songs
    val songs: StateFlow<List<Song>> = context.dataStore.data
        .map {
            it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] != false)
        }
        .distinctUntilChanged()
        .combine(database.allLocalSongsFlow()) { (sortType, descending), list ->
            val sorted = when (sortType) {
                SongSortType.CREATE_DATE -> list.sortedBy { it.song.inLibrary ?: java.time.LocalDateTime.MIN }
                SongSortType.MODIFIED_DATE -> list.sortedBy { it.song.dateModified ?: java.time.LocalDateTime.MIN }
                SongSortType.RELEASE_DATE -> list.sortedBy { it.song.year ?: 0 }
                SongSortType.NAME -> list.sortedBy { it.song.title.lowercase() }
                SongSortType.ARTIST -> list.sortedBy { it.artists.firstOrNull()?.name?.lowercase() ?: "" }
                SongSortType.PLAY_COUNT -> list.sortedBy { it.playCount?.sumOf { pc -> pc.count } ?: 0 }
            }
            if (descending) sorted.reversed() else sorted
        }
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Local Artists (deduplicated by name, using album cover of the album the artist appears on the most)
    val artists: StateFlow<List<Artist>> = combine(
        database.allLocalArtistsFlow(),
        songs
    ) { rawArtists, localSongs ->
        val groupedByName = rawArtists.groupBy { it.artist.name.trim().lowercase() }
        groupedByName.mapNotNull { (_, artistGroup) ->
            if (artistGroup.isEmpty()) return@mapNotNull null
            val primary = artistGroup.maxByOrNull { it.songCount } ?: artistGroup.first()
            val artistIds = artistGroup.map { it.id }.toSet()
            val artistNameLower = primary.artist.name.trim().lowercase()

            val matchedSongs = localSongs.filter { s ->
                s.artists.any { it.id in artistIds || it.name.trim().lowercase() == artistNameLower }
            }

            val totalSongs = if (matchedSongs.isNotEmpty()) matchedSongs.size else artistGroup.sumOf { it.songCount }
            val totalDownloads = if (matchedSongs.isNotEmpty()) {
                matchedSongs.count { it.song.dateDownload != null }
            } else {
                artistGroup.sumOf { it.downloadCount }
            }

            val bestCover: String? = if (matchedSongs.isNotEmpty()) {
                val albumGroups = matchedSongs
                    .groupBy { it.song.albumName?.takeIf { n -> n.isNotBlank() } ?: it.song.albumId ?: it.song.id }
                val bestAlbum = albumGroups.maxByOrNull { it.value.size }
                bestAlbum?.value?.firstNotNullOfOrNull { it.song.thumbnailUrl ?: it.song.localPath }
            } else null

            val finalThumb = bestCover ?: primary.artist.thumbnailUrl

            primary.copy(
                artist = primary.artist.copy(thumbnailUrl = finalThumb),
                songCount = totalSongs,
                downloadCount = totalDownloads
            )
        }.sortedBy { it.artist.name.lowercase() }
    }
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Local Albums
    val albums: StateFlow<List<Album>> = database.allLocalAlbumsFlow()
        .withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Local Genres (Gramophone style: single genre per song, "Unknown genre" at top, with album artwork)
    val localGenres: StateFlow<List<LocalGenre>> = songs
        .map { songList ->
            val unknownGenreStr = context.getString(R.string.genre_unknown)

            val songsWithGenre = songList.map { song ->
                val genreList = song.genre?.map { it.title.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val singleGenre = when {
                    genreList.isEmpty() -> unknownGenreStr
                    "Alternative R" in genreList && "B" in genreList -> "Alternative R&B"
                    "Contemporary R" in genreList && "B" in genreList -> "Contemporary R&B"
                    "R" in genreList && "B" in genreList -> "R&B"
                    else -> genreList.first()
                }
                singleGenre to song
            }

            val grouped = songsWithGenre.groupBy({ it.first }, { it.second })

            grouped.map { (genreName, gSongs) ->
                val albumGroups = gSongs
                    .groupBy { it.song.albumName?.takeIf { n -> n.isNotBlank() } ?: it.song.albumId ?: it.song.id }
                val bestAlbum = albumGroups.maxByOrNull { it.value.size }
                val coverPath = bestAlbum?.value?.firstNotNullOfOrNull { it.song.thumbnailUrl ?: it.song.localPath }

                LocalGenre(
                    name = genreName,
                    songCount = gSongs.size,
                    songs = gSongs,
                    coverPath = coverPath
                )
            }.sortedWith { a, b ->
                when {
                    a.name == unknownGenreStr && b.name == unknownGenreStr -> 0
                    a.name == unknownGenreStr -> -1
                    b.name == unknownGenreStr -> 1
                    else -> a.name.compareTo(b.name, ignoreCase = true)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Local Playlists
    val playlists: StateFlow<List<Playlist>> = database.playlists(
        PlaylistFilter.LIBRARY,
        PlaylistSortType.CREATE_DATE,
        descending = true
    )
        .map { list -> list.filter { it.playlist.isLocal } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Songs grouped by Year / Date
    val songsByYear: StateFlow<Map<String, List<Song>>> = songs
        .map { songList ->
            songList
                .groupBy { song ->
                    val y = song.song.year
                    if (y != null && y > 0) y.toString() else context.getString(R.string.year_unknown)
                }
                .toSortedMap(compareByDescending { if (it == context.getString(R.string.year_unknown)) "0000" else it })
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // Filesystem explorer
    private val initialFsDir: File = Environment.getExternalStorageDirectory()
        ?: File("/storage/emulated/0")

    val fsCurrentPath = MutableStateFlow(initialFsDir)
    val fsItems = MutableStateFlow<List<FsItem>>(emptyList())

    init {
        viewModelScope.launch {
            fsCurrentPath.collect { dir ->
                loadFsDirectory(dir)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            deduplicateDatabaseArtists()
        }
    }

    private suspend fun deduplicateDatabaseArtists() = withContext(Dispatchers.IO) {
        try {
            val allLocalArtists = database.allLocalArtists()
            val grouped = allLocalArtists.groupBy { it.name.trim().lowercase() }
            val allLocalSongs = database.allLocalSongs()

            for ((_, group) in grouped) {
                if (group.size <= 1) {
                    val single = group.firstOrNull() ?: continue
                    if (single.thumbnailUrl.isNullOrBlank()) {
                        val artistSongs = allLocalSongs.filter { song ->
                            song.artists.any { it.id == single.id || it.name.trim().equals(single.name.trim(), ignoreCase = true) }
                        }
                        val bestAlbum = artistSongs
                            .groupBy { it.song.albumName?.takeIf { n -> n.isNotBlank() } ?: it.song.albumId ?: it.song.id }
                            .maxByOrNull { it.value.size }
                        val bestCover = bestAlbum?.value?.firstNotNullOfOrNull { it.song.thumbnailUrl ?: it.song.localPath }
                        if (bestCover != null) {
                            database.update(single.copy(thumbnailUrl = bestCover))
                        }
                    }
                    continue
                }

                val primary = group.first()
                val duplicates = group.drop(1)

                for (dup in duplicates) {
                    database.updateSongArtistMapIgnore(dup.id, primary.id)
                    database.deleteSongArtistMap(dup.id)
                    database.delete(dup)
                }

                val artistSongs = allLocalSongs.filter { song ->
                    song.artists.any { it.name.trim().equals(primary.name.trim(), ignoreCase = true) }
                }

                val bestAlbum = artistSongs
                    .groupBy { it.song.albumName?.takeIf { n -> n.isNotBlank() } ?: it.song.albumId ?: it.song.id }
                    .maxByOrNull { it.value.size }
                val bestCover = bestAlbum?.value?.firstNotNullOfOrNull { it.song.thumbnailUrl ?: it.song.localPath }
                val finalThumb = bestCover ?: primary.thumbnailUrl
                if (finalThumb != null && finalThumb != primary.thumbnailUrl) {
                    database.update(primary.copy(thumbnailUrl = finalThumb))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadFsDirectory(dir: File) = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.canRead()) {
            fsItems.value = emptyList()
            return@withContext
        }

        val allFiles = dir.listFiles()?.toList() ?: emptyList()
        val currentLocalSongs = songs.value

        val items = allFiles
            .filterNot { it.name.startsWith(".") }
            .map { file ->
                val isDir = file.isDirectory
                val ext = file.extension.lowercase()
                val isAudio = !isDir && ext in AUDIO_EXTENSIONS
                val matchedSong = if (isAudio) {
                    currentLocalSongs.find { it.song.localPath == file.absolutePath }
                } else null

                FsItem(
                    file = file,
                    isDirectory = isDir,
                    name = file.name,
                    size = if (!isDir) file.length() else 0L,
                    isAudio = isAudio,
                    matchedSong = matchedSong
                )
            }
            .filter { it.isDirectory || it.isAudio }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        fsItems.value = items
    }

    fun navigateToFs(dir: File) {
        if (dir.isDirectory && dir.canRead()) {
            fsCurrentPath.value = dir
        }
    }

    fun navigateUpFs(): Boolean {
        val parent = fsCurrentPath.value.parentFile
        if (parent != null && parent.canRead() && parent.absolutePath.startsWith("/storage")) {
            fsCurrentPath.value = parent
            return true
        }
        return false
    }

    fun canNavigateUpFs(): Boolean {
        val parent = fsCurrentPath.value.parentFile
        return parent != null && parent.canRead() && parent.absolutePath.startsWith("/storage")
    }
}
