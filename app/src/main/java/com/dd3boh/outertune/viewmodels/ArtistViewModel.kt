package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.withoutBlocked
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.ArtistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!

    /** The page as YouTube sent it, kept so that a block or an unblock while it is open shows at once. */
    private val pageAsSent = MutableStateFlow<ArtistPage?>(null)

    /** The page without what the switches and the blocked artists keep out (ContentFilter). */
    var artistPage by mutableStateOf<ArtistPage?>(null)
        private set
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = database.artistSongsPreview(artistId).withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId).withoutBlocked()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isLoading = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            combine(pageAsSent, ContentFilter.changes) { page, filter -> page?.let(filter::artist) }
                .collect { artistPage = it }
        }
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            isLoading.value = true
            YouTube.artist(artistId)
                .onSuccess {
                    // Shown at once, not a turn later through the collector above, so that the
                    // loading shimmer never gives way to a page with nothing on it.
                    artistPage = ContentFilter.current.artist(it)
                    pageAsSent.value = it
                }.onFailure {
                    reportException(it)
                }

            isLoading.value = false
        }
    }
}
