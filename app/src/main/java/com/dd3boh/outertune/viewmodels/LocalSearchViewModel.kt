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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
            // Several words are looked for apart (LibrarySearch): each asked of the database, the best
            // few hundred rows of each, and only what has every word kept, so the overview cuts them
            // short here. Lone letters are asked together, as typed, like one word.
            val apart = LibrarySearch.apart(query)
            val asked = LibrarySearch.asked(query)
            val preview = LibrarySearch.limit(query, PREVIEW_SIZE)
            val listed = LibrarySearch.limit(query, Int.MAX_VALUE)
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
                    found { database.searchSongs(it, listed) },
                    found { database.searchArtistSongs(it, listed) },
                ) { songs, artistSongs ->
                    val list = songs + artistSongs
                    list.distinctBy { it.id }
                }
                LocalFilter.ALBUM -> found { database.searchAlbums(it, listed) }.map { list -> list.distinctBy { it.id } }
                LocalFilter.ARTIST -> found { database.searchArtists(it, listed) }.map { list -> list.distinctBy { it.id } }
                LocalFilter.PLAYLIST -> found { database.searchPlaylists(it, listed) }.map { list -> list.distinctBy { it.id } }
            }.withoutBlocked().map { list ->
                LocalSearchResult(
                    query = query,
                    filter = filter,
                    // Each kind under its heading in the order it always had; inside one, what
                    // matches best first (LibrarySearch.rank).
                    map = list.groupBy { kindOf(it) }.mapValues { (kind, items) ->
                        val ordered = LibrarySearch.order(query, items, { it.title }, { othersOf(it) })
                        // With words apart the database hands back up to a few hundred of each: the
                        // overview shows as many of each kind as one word does (three, and up to six
                        // songs between their titles and their artists).
                        if (filter == LocalFilter.ALL && apart) ordered.take(if (kind == LocalFilter.SONG) PREVIEW_SIZE * 2 else PREVIEW_SIZE)
                        else ordered
                    }.filterValues { it.isNotEmpty() })
            }
        }
    }
        // What comes back, put together, filtered and ranked off the main thread: on a big library
        // that work held the screen up after each pause in typing, and again at each change to the
        // library while the page was open (a song stored, a like).
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, LocalSearchResult("", filter.value, emptyMap()))

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
