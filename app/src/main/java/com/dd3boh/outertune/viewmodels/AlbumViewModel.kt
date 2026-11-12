package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.AlbumRows
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.AlbumAnswerPretend
import com.dd3boh.outertune.utils.WhyStopped
import com.dd3boh.outertune.utils.YouTubeTrouble
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.albumWithOrderedSongs
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.pages.AlbumPage
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
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!

    /** The song a link opened the album on: brought into view and lit once (AlbumScreen). */
    val shownSong: String? = savedStateHandle.get<String>("song")
    /**
     * The album with its songs in the album's order. The relation in AlbumWithSongs goes through
     * a view sorted by position, but SQLite folds the view into the join and keeps no order, so
     * the songs came out in the order they were first stored: a song heard before the album was
     * opened headed the tracklist as number one. albumSongs sorts in the query itself.
     */
    val albumWithSongs = database.albumWithOrderedSongs(albumId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    val isLoading = MutableStateFlow(true)

    /** What the last load's failure came to, for the page to say why (WhyStopped); null when it did not fail. */
    val failedKind = MutableStateFlow<WhyStopped.Kind?>(null)

    init {
        load()
    }

    /** Asks again after a load that failed or came back with no songs: the page's Retry. */
    fun retry() {
        if (!isLoading.value) load()
    }

    private fun load() {
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
            // A debug build can be told to answer as YouTube does for an album it has nothing under.
            val answer = AlbumAnswerPretend.failureFor(albumId)?.let { Result.failure<AlbumPage>(it) } ?: YouTube.album(albumId)
            answer.onSuccess {
                failedKind.value = null
                YouTubeTrouble.answered(asAccount = YouTube.useLoginForBrowse)
                // Set before the wait below, so the other versions are ready when the songs appear.
                otherVersions.value = it.otherVersions
                val mapped = database.albumWithSongs(albumId).first()?.songs.orEmpty().map { song -> song.id }
                if (AlbumRows.pageAddsSongs(album?.album, mapped, it.songs.map { song -> song.id }) ||
                    AlbumRows.pageAddsArtist(album?.artists.orEmpty().map { a -> a.id }, it.album.artists.orEmpty().map { a -> a.name })
                ) {
                    database.transaction {
                        // The row as it is now, not as read before the fetch: update writes the
                        // whole row it is given, so a heart tapped meanwhile would be undone.
                        val current = albumById(albumId)
                        if (current == null) insert(it)
                        else update(current, it)
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
                YouTubeTrouble.failed(it)
                // The album stays as it is stored, whatever the failure said. NOT_FOUND in its
                // words deleted it here, saved or not, and YouTube's answers cannot tell an album
                // it removed from one it holds back for now. Asked on 2 Nov 2026: an album id that
                // does not exist came back as an empty page with HTTP 200, as every album did in
                // South Korea (#68); a sign-in that no longer works is a 401, a blocked connection
                // no answer at all, and none of the ids asked was answered NOT_FOUND. The page says
                // it did not load instead, with Retry (keptAlbumNoteShown).
                failedKind.value = WhyStopped.kindOf(it)
            }
            isLoading.value = false
        }
    }
}

/**
 * Whether the album page says over the songs it has that the album did not load: after a failure,
 * once the load is over, and only with a network. Offline the stored songs are all there is to
 * show, and a note on every downloaded album opened there would only be in the way.
 */
internal fun keptAlbumNoteShown(failedKind: WhyStopped.Kind?, isLoading: Boolean, online: Boolean): Boolean =
    failedKind != null && !isLoading && online
