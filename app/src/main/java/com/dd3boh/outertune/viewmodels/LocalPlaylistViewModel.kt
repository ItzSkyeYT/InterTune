package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.PlaylistSongSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSongSortType
import com.dd3boh.outertune.constants.PlaylistSongSortTypeKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlistWithSongs = combine(
        database.playlist(playlistId),
        database.playlistSongs(playlistId),
        context.dataStore.data
            .map {
                it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM) to
                        (it[PlaylistSongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged()
    ) { playlist, songs, (sortType, sortDescending) ->
        val sortedSongs = when (sortType) {
            PlaylistSongSortType.CUSTOM -> songs
            PlaylistSongSortType.NAME -> songs.sortedBy { it.song.song.title.lowercase() }
            PlaylistSongSortType.ARTIST -> songs.sortedBy { song ->
                song.song.artists.joinToString { it.name }.lowercase()
            }
            PlaylistSongSortType.ADDED_DATE -> songs.sortedBy { it.song.song.inLibrary }
            PlaylistSongSortType.MODIFIED_DATE -> songs.sortedBy { it.song.song.dateModified }
            PlaylistSongSortType.RELEASE_DATE -> songs.sortedBy { it.song.song.getDateLong() }
        }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)

        Pair(playlist, sortedSongs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, Pair(null, emptyList()))

    /**
     * Finding songs to put in a playlist that has none.
     *
     * An empty playlist used to be a dead end: one line of text and no way out of it but the back
     * button, with the header not even drawn, so it did not say which playlist you were in. This is
     * the search half of the fix, kept in the view model so the query survives rotation and so the
     * screen does not run network calls from a composable.
     */
    val addQuery = MutableStateFlow("")
    private val _addResults = MutableStateFlow<List<SongItem>>(emptyList())
    val addResults = _addResults.asStateFlow()
    private val _addSearching = MutableStateFlow(false)
    val addSearching = _addSearching.asStateFlow()

    /** Ids added during this visit, so a row can show it is done without waiting on the query. */
    private val _justAdded = MutableStateFlow<Set<String>>(emptySet())
    val justAdded = _justAdded.asStateFlow()

    @OptIn(FlowPreview::class)
    private fun watchAddQuery() {
        viewModelScope.launch {
            // Debounced, because this fires per keystroke and each one is a search request.
            addQuery.debounce(350).collectLatest { query ->
                if (query.isBlank()) {
                    _addResults.value = emptyList()
                    _addSearching.value = false
                    return@collectLatest
                }
                _addSearching.value = true
                val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                _addResults.value = result?.items?.filterIsInstance<SongItem>().orEmpty()
                _addSearching.value = false
            }
        }
    }

    /**
     * Puts one search result into this playlist.
     *
     * The song has to exist locally before the map row can point at it, which is the same order
     * YouTubeSongMenu uses. A synced playlist also gets told, and that is deliberately not awaited
     * against the local write: a network failure should not cost the local add.
     */
    fun addSong(playlist: Playlist, song: SongItem) {
        _justAdded.value += song.id
        viewModelScope.launch(Dispatchers.IO) {
            database.transaction {
                insert(song.toMediaMetadata())
                addSongToPlaylist(playlist, listOf(song.id))
            }
            playlist.playlist.browseId?.let { browseId ->
                runCatching { YouTube.addToPlaylist(browseId, song.id) }
            }
        }
    }

    init {
        watchAddQuery()

        // Fix playlist song order
        viewModelScope.launch(Dispatchers.IO) {
            val sortedSongs = playlistWithSongs.first().second.sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, song ->
                    if (song.map.position != index) {
                        update(song.map.copy(position = index))
                    }
                }
            }
        }
    }
}