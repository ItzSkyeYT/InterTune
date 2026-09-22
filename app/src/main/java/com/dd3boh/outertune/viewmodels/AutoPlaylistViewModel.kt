package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.interleaveBy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AutoPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!

    /**
     * One seed for the life of this screen, so the mix holds still while it is being looked at.
     *
     * The songs flow re-emits on every database change, and an unseeded shuffle would deal a new
     * order each time: rows would reshuffle under the finger whenever anything downloaded or a play
     * count ticked. Re-seeding from this constant gives the same order for the same songs, while a
     * new visit to the screen builds a new view model and so a new mix.
     */
    private val mixSeed = Random.nextLong()

    val thumbnail: StateFlow<ImageVector> = MutableStateFlow(
        when (playlistId) {
            "liked" -> Icons.Rounded.Favorite
            "downloaded" -> Icons.Rounded.CloudDownload
            "favourites" -> Icons.Rounded.Shuffle
            else -> Icons.AutoMirrored.Rounded.QueueMusic
        }
    ).asStateFlow()

    val songs = context.dataStore.data
        .map {
            it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to (it[SongSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            when (playlistId) {
                "liked" -> database.likedSongs(sortType, descending)
                "downloaded" -> database.downloadSongs(sortType, descending)
                // Sorting is deliberately ignored here. The point of this one is the running
                // order, and any sort at all undoes it: see interleaveByArtist.
                "favourites" -> database.songsByBookmarkedArtists().map { songs ->
                    interleaveBy(songs, Random(mixSeed)) { song ->
                        song.artists.firstOrNull { it.bookmarkedAt != null }?.id
                    }
                }
                else -> MutableStateFlow(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
