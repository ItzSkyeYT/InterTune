package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val albumWithSongs = database.albumWithSongs(albumId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    val isLoading = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            isLoading.value = true
            val album = database.album(albumId).first()
            // AlbumScreen shows its shimmer whenever the album has no songs and isLoading is still
            // true, so every way out of this coroutine has to clear it. It used to be cleared only
            // on a network failure, which left a local album with no songs, and a YouTube album that
            // came back with none, sitting on the shimmer for as long as the screen stayed open.
            if (album?.album?.isLocal == true) {
                // albumWithSongs is a separate and heavier query than the read above, so it can
                // still be at its initial null when we get here. Clearing isLoading then would show
                // an empty screen for a frame between the shimmer and the songs, so wait for its
                // first real result. The bound is there so an album deleted in the meantime cannot
                // leave the shimmer up for good.
                withTimeoutOrNull(5_000) {
                    albumWithSongs.first { current -> current != null }
                }
                isLoading.value = false
                return@launch
            }
            YouTube.album(albumId).onSuccess {
                // Set before the wait below, so the other versions are ready when the songs appear.
                otherVersions.value = it.otherVersions
                if (album == null || album.album.songCount == 0) {
                    database.transaction {
                        if (album == null) insert(it)
                        else update(album.album, it)
                    }
                    // database.transaction only queues the write on Room's executor and returns at
                    // once, and albumWithSongs picks the songs up a little later, after the commit
                    // and Room's re-query. Clearing isLoading straight away would drop the shimmer
                    // for a frame or two of empty screen before the songs appear, on exactly the
                    // albums opened most often: ones not yet in the database. So hold it until the
                    // songs are showing. The bound is only there so a write that never lands, say
                    // another writer inserting the same album between the read above and this
                    // insert, cannot bring back the endless shimmer this is fixing.
                    if (it.songs.isNotEmpty()) {
                        withTimeoutOrNull(5_000) {
                            albumWithSongs.first { current -> !current?.songs.isNullOrEmpty() }
                        }
                    }
                }
            }.onFailure {
                reportException(it)
                if (it.message?.contains("NOT_FOUND") == true) {
                    // This album no longer exists in YouTube Music
                    database.query {
                        album?.album?.let(::delete)
                    }
                }
            }
            isLoading.value = false
        }
    }
}
