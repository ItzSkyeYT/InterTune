package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Follows continuations one page at a time, starting from [start], handing each page's items to
 * [onPage] and asking [fetch] for the next. Returns the continuation to resume from later: null
 * once the list is exhausted, or the same token a failed page was fetched with.
 *
 * Within one call, a page is fetched at most once: a token already followed, [start] included, is
 * never asked for or appended again, so a continuation that loops back on itself ends the walk
 * instead of repeating pages. Separate calls do not share this guard. The first failure ends the
 * walk too, rather than retrying the same token forever: [fetch] is expected to wrap its own errors
 * in [Result], but a [CancellationException] reaching here (the caller's coroutine was cancelled)
 * is rethrown rather than treated as an ordinary failed page, so cancellation actually stops the
 * walk instead of being swallowed and retried at once.
 *
 * Close to innertube's walkContinuations (see Walk.kt), which this does not use instead: that one
 * collects every page into a single list and hands it back only once the whole walk ends. This one
 * hands pages to [onPage] as they arrive, so a caller showing a list can grow it page by page, and
 * it returns the token to resume from.
 */
suspend fun <T> followContinuations(
    start: String?,
    fetch: suspend (String) -> Result<Pair<List<T>, String?>>,
    onPage: (List<T>) -> Unit,
): String? {
    var next = start
    val followed = HashSet<String>()
    while (next != null) {
        val token = next
        if (!followed.add(token)) return null
        val page = fetch(token).getOrElse { e ->
            if (e is CancellationException) throw e
            reportException(e)
            return token
        }
        onPage(page.first)
        next = page.second
    }
    return null
}

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())
    var continuation: String? = null
    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val isLoading = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            YouTube.playlist(playlistId)
                .onSuccess { playlistPage ->
                    playlist.value = playlistPage.playlist
                    playlistSongs.value = playlistPage.songs
                    continuation = playlistPage.songsContinuation
                }.onFailure {
                    reportException(it)
                }
            isLoading.value = false
        }
    }

    fun loadMoreSongs() {
        continuation?.let {
            isLoading.value = true
            viewModelScope.launch(Dispatchers.IO) {
                getContinuation(it)
            }
            isLoading.value = false
        }
    }

    fun loadRemainingSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                continuation = followContinuations(continuation, ::fetchContinuation) { songs ->
                    playlistSongs.value = playlistSongs.value + songs
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    suspend fun getContinuation(continuation: String) {
        val continuationPage = YouTube.playlistContinuation(continuation).getOrElse { e ->
            reportException(e)
            return
        }
        playlistSongs.value = playlistSongs.value + continuationPage.songs
        this.continuation = continuationPage.continuation
    }

    private suspend fun fetchContinuation(continuation: String): Result<Pair<List<SongItem>, String?>> =
        YouTube.playlistContinuation(continuation).map { it.songs to it.continuation }
}
