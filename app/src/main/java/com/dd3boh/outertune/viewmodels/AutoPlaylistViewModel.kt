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
import com.dd3boh.outertune.constants.FavouritesStrictKey
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.favouritesMix
import com.dd3boh.outertune.utils.guestQueue
import com.dd3boh.outertune.utils.holdOrderAppendingNewcomers
import com.dd3boh.outertune.utils.interleaveBy
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AutoPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
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

    /**
     * The favourites order established so far, by song id, held for this view model's life so a
     * newly stored song (any played song, or one recorded as related to whatever is playing) is
     * appended rather than triggering a full reshuffle of the mix already on screen.
     */
    private var favouritesOrder: List<String>? = null

    /**
     * The mix with its guests as it was last dealt, by song id, held for the same reason: a song
     * stored, or an artist bookmarked, while the page is open adds to the end and moves nothing
     * that is already there. Forgotten when the switch goes back to favourites only, so that
     * turning it off again deals the guests in afresh and does not append them all.
     */
    private var guestMixOrder: List<String>? = null

    /**
     * How many guest songs this visit has to deal in, for the line under the switch: null until
     * they have been read, which is not before the switch is first found off.
     */
    private val _guestCount = MutableStateFlow<Int?>(null)
    val guestCount = _guestCount.asStateFlow()

    /**
     * The guests for this visit, by song id, in the order they are dealt in. Chosen once, the
     * first time the mix is wanted with the switch off, and kept: related lists are written as
     * songs play, so choosing again would change the guests while the mix is being listened to.
     *
     * A failed read is no guests, not a crash: the favourites still play.
     */
    private val guestIds by lazy {
        viewModelScope.async(Dispatchers.IO) {
            val queue = try {
                val favouriteArtists = database.songsByBookmarkedArtists().first()
                    .flatMap { it.artists }
                    .filter { it.bookmarkedAt != null }
                    .distinctBy { it.id }
                    .size
                guestQueue(
                    candidates = database.songsSimilarToBookmarkedArtists(System.currentTimeMillis(), GUEST_CANDIDATES),
                    favouriteArtists = favouriteArtists,
                    random = Random(mixSeed + 1),
                    id = { it.song.id },
                    strength = { it.refs },
                    // The first billed, as the favourites are grouped by the first bookmarked.
                    artist = { it.song.artists.firstOrNull()?.id },
                ).take(GUESTS_KEPT).map { it.song.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                emptyList()
            }
            _guestCount.value = queue.size
            queue
        }
    }

    /**
     * Those songs as they are now. Who the guests are and the order they come in never changes,
     * but the rows are observed, so a like or a download shows on a guest as it does on a
     * favourite, and a guest that is deleted leaves.
     */
    private fun guests(): Flow<List<Song>> = flow {
        val ids = guestIds.await()
        emitAll(database.songsByIds(ids).map { rows ->
            val byId = rows.associateBy { it.id }
            ids.mapNotNull(byId::get)
        })
    }

    /** The favourites in their running order, as the mix was before there were guests. */
    private fun favourites(): Flow<List<Song>> =
        database.songsByBookmarkedArtists().map { songs ->
            fun interleaved(list: List<Song>) = interleaveBy(list, Random(mixSeed)) { song ->
                song.artists.firstOrNull { it.bookmarkedAt != null }?.id
            }
            val order = favouritesOrder
            val result = if (order == null) {
                interleaved(songs)
            } else {
                holdOrderAppendingNewcomers(order, songs, { it.id }, ::interleaved)
            }
            favouritesOrder = result.map { it.id }
            result
        }

    /**
     * [favourites] with similar artists dealt in, unless the switch on the page keeps it to the
     * favourites. Strict is that flow itself, untouched, and the guests are not even read.
     */
    private fun withGuests(strict: Flow<Boolean>, favourites: Flow<List<Song>>): Flow<List<Song>> =
        strict.flatMapLatest { strictNow ->
            if (strictNow) {
                guestMixOrder = null
                favourites
            } else combine(favourites, guests()) { songs, guests ->
                val bookmarked = songs.flatMap { it.artists }.filter { it.bookmarkedAt != null }.mapTo(HashSet()) { it.id }
                val dealt = favouritesMix(songs, guests, strict = false, bookmarked, Random(mixSeed + 2), { it.id }) { song ->
                    song.artists.map { it.id }
                }
                val order = guestMixOrder
                val result = if (order == null) dealt else holdOrderAppendingNewcomers(order, dealt, { it.id }) { it }
                guestMixOrder = result.map { it.id }
                result
            }
        }

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
                "favourites" ->
                    if (!Unreleased.FAVOURITES_GUESTS) favourites()
                    else withGuests(
                        strict = context.dataStore.data.map { it[FavouritesStrictKey] ?: true }.distinctUntilChanged(),
                        favourites = favourites(),
                    )
                else -> MutableStateFlow(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    companion object {
        /**
         * How many candidate songs are read to choose the guests from, the most listed first. A
         * guest is dealt for every three favourites, so even a mix of a thousand favourites uses
         * a third of this, and the read stays the same size however much has been played.
         */
        private const val GUEST_CANDIDATES = 1000

        /**
         * The most guests kept for one visit: enough for a mix of nine hundred favourites, and few
         * enough to be read back in one query on any Android (see songEntitiesByIds).
         */
        private const val GUESTS_KEPT = 300
    }
}
