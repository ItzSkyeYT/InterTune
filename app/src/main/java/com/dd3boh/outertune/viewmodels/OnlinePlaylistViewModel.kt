package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.flow.combine
import com.dd3boh.outertune.models.blockedArtists
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.WhyStopped
import com.dd3boh.outertune.utils.YouTubeTrouble
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Follows continuations one page at a time, starting from [start], handing each page's items to
 * [onPage] and asking [fetch] for the next. Returns the continuation to resume from later: null
 * once the list is exhausted, the same token a failed page was fetched with, or the next token once
 * [maxPages] pages have been fetched.
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
 * can be asked to stop after [maxPages] of them and pick up later from the token it returns.
 */
suspend fun <T> followContinuations(
    start: String?,
    fetch: suspend (String) -> Result<Pair<List<T>, String?>>,
    maxPages: Int = Int.MAX_VALUE,
    onPage: (List<T>) -> Unit,
): String? {
    var next = start
    var fetched = 0
    val followed = HashSet<String>()
    while (next != null) {
        if (fetched >= maxPages) return next
        val token = next
        if (!followed.add(token)) return null
        val page = fetch(token).getOrElse { e ->
            if (e is CancellationException) throw e
            reportException(e)
            return token
        }
        fetched++
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

    /** The playlist's songs as YouTube sends them. */
    private val songsAsSent = MutableStateFlow<List<SongItem>>(emptyList())

    /** Its songs without a blocked artist's (ArtistBlock), followed. */
    val playlistSongs = combine(songsAsSent, blockedArtists) { songs, block -> block.apply(songs) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var continuation: String? = null
    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val isLoading = MutableStateFlow(false)

    /**
     * What the page's first request came to when it failed, null otherwise. The page showed its
     * loading shimmer for good after a failure; now it says why when that is clear (WhyStopped),
     * with Retry.
     */
    val failedKind = MutableStateFlow<WhyStopped.Kind?>(null)

    // The one in-flight continuation fetch, shared by loadMoreSongs and loadRemainingSongs: they
    // both read and write `continuation` and `playlistSongs`, so two running together raced to
    // append the same page twice. A second call while one is active is a no-op rather than a
    // second fetch.
    private var loadJob: Job? = null

    init {
        load()
    }

    /** Asks again after the first request failed: the page's Retry. */
    fun retry() {
        if (!isLoading.value && playlist.value == null) load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            failedKind.value = null
            YouTube.playlist(playlistId)
                .onSuccess { playlistPage ->
                    YouTubeTrouble.answered(asAccount = YouTube.useLoginForBrowse)
                    playlist.value = playlistPage.playlist
                    songsAsSent.value = playlistPage.songs
                    continuation = playlistPage.songsContinuation
                }.onFailure {
                    reportException(it)
                    YouTubeTrouble.failed(it)
                    failedKind.value = WhyStopped.kindOf(it)
                }
            isLoading.value = false
        }
    }

    fun loadMoreSongs() {
        if (loadJob?.isActive == true) return
        val token = continuation ?: return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                // One page only: loading the rest is loadRemainingSongs, the user's own choice
                // through the header's button (see its comment in OnlinePlaylistScreen); a scroll
                // reaching the bottom should not do that fetch on its own.
                continuation = followContinuations(token, ::fetchContinuation, maxPages = 1) { songs ->
                    songsAsSent.value = songsAsSent.value + songs
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    fun loadRemainingSongs() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                continuation = followContinuations(continuation, ::fetchContinuation) { songs ->
                    songsAsSent.value = songsAsSent.value + songs
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    private suspend fun fetchContinuation(continuation: String): Result<Pair<List<SongItem>, String?>> =
        YouTube.playlistContinuation(continuation).map { it.songs to it.continuation }
}
