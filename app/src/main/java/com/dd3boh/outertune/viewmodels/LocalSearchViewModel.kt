package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.models.withoutBlocked
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.search.LibrarySearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel @Inject constructor(
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)

    val result = combine(query, filter) { query, filter ->
        query to filter
    }.flatMapLatest { (query, filter) ->
        if (query.isEmpty()) {
            flowOf(LocalSearchResult("", filter, emptyMap()))
        } else {
            // Several words are looked for apart (LibrarySearch): each asked of the database, all of
            // what comes back, and only what has every word kept, so none can be cut short here.
            val apart = LibrarySearch.apart(query)
            val preview = if (apart) Int.MAX_VALUE else PREVIEW_SIZE
            val asked = LibrarySearch.asked(query)
            fun found(search: (String) -> Flow<List<LocalItem>>): Flow<List<LocalItem>> =
                if (asked.size == 1) search(asked.single())
                else combine(asked.map(search)) { lists -> lists.flatMap { it } }
            when (filter) {
                LocalFilter.ALL -> combine(
                    found { database.searchSongs(it, preview) },
                    found { database.searchAlbums(it, preview) },
                    found { database.searchArtists(it, preview) },
                    found { database.searchArtistSongs(it, preview) },
                    found { database.searchPlaylists(it, preview) },
                ) { songs, albums, artists, artistSongs, playlists ->
                    val list = songs + albums + artists + artistSongs + playlists
                    list.distinctBy { it.id }
                }
                LocalFilter.SONG -> combine(
                    found { database.searchSongs(it) },
                    found { database.searchArtistSongs(it) },
                ) { songs, artistSongs ->
                    val list = songs + artistSongs
                    list.distinctBy { it.id }
                }
                LocalFilter.ALBUM -> found { database.searchAlbums(it) }.map { list -> list.distinctBy { it.id } }
                LocalFilter.ARTIST -> found { database.searchArtists(it) }.map { list -> list.distinctBy { it.id } }
                LocalFilter.PLAYLIST -> found { database.searchPlaylists(it) }.map { list -> list.distinctBy { it.id } }
            }.withoutBlocked().map { list ->
                LocalSearchResult(
                    query = query,
                    filter = filter,
                    // Each kind under its heading in the order it always had; inside one, what
                    // matches best first (LibrarySearch.rank).
                    map = list.groupBy { kindOf(it) }.mapValues { (kind, items) ->
                        val ordered = LibrarySearch.order(query, items, { it.title }, { othersOf(it) })
                        // With words apart nothing was cut short by the database: the overview
                        // shows as many of each kind as one word does (three, and up to six songs
                        // between their titles and their artists).
                        if (filter == LocalFilter.ALL && apart) ordered.take(if (kind == LocalFilter.SONG) PREVIEW_SIZE * 2 else PREVIEW_SIZE)
                        else ordered
                    }.filterValues { it.isNotEmpty() })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, LocalSearchResult("", filter.value, emptyMap()))

    companion object {
        const val PREVIEW_SIZE = 3

        private fun kindOf(item: LocalItem) = when (item) {
            is Song -> LocalFilter.SONG
            is Album -> LocalFilter.ALBUM
            is Artist -> LocalFilter.ARTIST
            is Playlist -> LocalFilter.PLAYLIST
        }

        /** Where else a result's words may be: a song's artists and album, an album's artists. */
        private fun othersOf(item: LocalItem): List<String> = when (item) {
            is Song -> item.artists.map { it.name } + listOfNotNull(item.album?.title)
            is Album -> item.artists.map { it.name }
            is Artist, is Playlist -> emptyList()
        }
    }
}

enum class LocalFilter {
    ALL, SONG, ALBUM, ARTIST, PLAYLIST
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
