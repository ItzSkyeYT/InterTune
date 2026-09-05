package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.SimilarRecommendation
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    val quickPicks = MutableStateFlow<List<Song>?>(null)

    /**
     * YouTube Music's own Quick picks for this account, when it sends them.
     *
     * Preferred over [quickPicks] when present. The local one is a query over songs related to
     * things already played, which can only ever recirculate the library; this is YouTube's model
     * of the user. Null when signed out, offline, or when the shelf is simply absent.
     */
    val ytQuickPicks = MutableStateFlow<List<SongItem>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val recentActivity = database.recentActivity()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private suspend fun load(force: Boolean) {
        isLoading.value = true

        // The query already ranks by how many of your seed songs point at each result, strongest
        // first. Shuffling all 100 of them threw that away and gave the 100th the same odds as the
        // 1st. Shuffle inside the strongest 40 instead: still different on each refresh, but drawn
        // from the good end.
        quickPicks.value = database.quickPicks()
            .first().take(40).shuffled().take(20)

        forgottenFavorites.value = database.forgottenFavorites()
            .first().shuffled().take(20)

        val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
        val keepListeningSongs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
            .first().shuffled().take(10)
        val keepListeningAlbums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
            .first().filter { it.album.thumbnailUrl != null }.shuffled().take(5)
        val keepListeningArtists = database.mostPlayedArtists(0, 1)
            .first().filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
        keepListening.value = (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()

        allLocalItems.value =
            (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                .filter { it is Song || it is Album }

        // Everything above is local and always runs. Everything below is remote, and opening the app
        // fires all of it: an artist lookup per recommendation seed, a related lookup per seed, home,
        // explore and a recent-activity sync. While YouTube is refusing this network that is a pile
        // of requests that cannot succeed and that make the refusal last longer. Pulling to refresh
        // passes force and still tries, because the user asked for it and one request doubles as the
        // probe that clears the block.
        if (!force && Throttle.isBlocked) {
            Log.d("HomeViewModel", "Skipping remote home load, backing off")
            isLoading.value = false
            return
        }

        if (YouTube.cookie != null) { // if logged in
            // InnerTune way is YouTube.likedPlaylists().onSuccess { ... }
            // OuterTune uses YouTube.library("FEmusic_liked_playlists").completedL().onSuccess { ... }
            YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
            }.onFailure {
                reportException(it)
            }
        }

        // Similar to artists
        val artistRecommendations =
            database.mostPlayedArtists(0, 1, limit = 10).first()
                .filter { it.artist.isYouTubeArtist }
                .shuffled().take(3)
                .mapNotNull {
                    val items = mutableListOf<YTItem>()
                    YouTube.artist(it.id).onSuccess { page ->
                        items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                        items += page.sections.lastOrNull()?.items.orEmpty()
                    }
                    SimilarRecommendation(
                        title = it,
                        items = items
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        // Similar to songs
        val songRecommendations =
            database.mostPlayedSongs(fromTimeStamp, limit = 10).first()
                .filter { it.album != null }
                .shuffled().take(2)
                .mapNotNull { song ->
                    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                        ?: return@mapNotNull null
                    val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                    SimilarRecommendation(
                        title = song,
                        items = (page.songs.shuffled().take(8) +
                                page.albums.shuffled().take(4) +
                                page.artists.shuffled().take(4) +
                                page.playlists.shuffled().take(4))
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        similarRecommendations.value = (artistRecommendations + songRecommendations).shuffled()

        ytQuickPicks.value = null
        YouTube.home().onSuccess { page ->
            var merged = takeQuickPicks(page)

            // YouTube does not put Quick picks in the first response. Measured against the live API
            // on 5 Sep: the first response is three card carousels, and Quick picks is index 0 of
            // the FIRST continuation, numItemsPerColumn 4. So fetch that one batch now, or the row
            // would only appear once the user happened to scroll far enough to trigger it.
            //
            // One extra request per home load, which is affordable now that the sync cooldown no
            // longer runs a full library sync on every app open.
            if (ytQuickPicks.value == null) {
                merged.continuation?.let { next ->
                    YouTube.home(next).getOrNull()?.let { page2 ->
                        val cleaned = takeQuickPicks(page2)
                        merged = merged.copy(
                            sections = merged.sections + cleaned.sections,
                            continuation = cleaned.continuation,
                        )
                    }
                }
            }
            homePage.value = merged
        }.onFailure {
            reportException(it)
        }

        YouTube.explore().onSuccess { page ->
            explorePage.value = page
        }.onFailure {
            reportException(it)
        }

        syncUtils.syncRecentActivity()

        allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                homePage.value?.sections?.flatMap { it.items }.orEmpty()

        isLoading.value = false
    }

    /**
     * Lifts YouTube's own Quick picks out of one batch of the home feed, and returns the batch
     * without it so it does not also render as a carousel further down the page.
     *
     * Found by shape rather than by name: the one carousel that is a list of songs. Its title is
     * localised, so matching the words "Quick picks" would find nothing outside English.
     */
    private fun takeQuickPicks(page: HomePage): HomePage {
        // Set to Your library and YouTube's shelf is left where it is, rendering as an ordinary
        // section of the feed rather than being lifted into the row.
        if (context.dataStore.get(QuickPicksSourceKey, QuickPicksSource.YOUTUBE.name)
                .toEnum(QuickPicksSource.YOUTUBE) != QuickPicksSource.YOUTUBE
        ) return page

        val shelf = page.sections.firstOrNull { section ->
            section.itemsPerColumn != null &&
                    section.items.isNotEmpty() &&
                    section.items.all { it is SongItem }
        } ?: return page
        ytQuickPicks.value = shelf.items.filterIsInstance<SongItem>()
        return page.copy(sections = page.sections - shelf)
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }
            val cleaned = takeQuickPicks(nextSections)
            homePage.value = cleaned.copy(
                chips = homePage.value?.chips,
                sections = homePage.value?.sections.orEmpty() + cleaned.sections
            )
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            homePage.value = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        if (selectedChip.value == null) {
            // store the actual homepage for deselecting chips
            previousHomePage.value = homePage.value
        }
        viewModelScope.launch(Dispatchers.IO) {
            val nextSections = YouTube.home(params = chip?.endpoint?.params).getOrNull() ?: return@launch
            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = nextSections.sections,
                continuation = nextSections.continuation
            )
            selectedChip.value = chip
        }
    }

    fun refresh(force: Boolean = false) {
        if (isRefreshing.value) return
        viewModelScope.launch(syncCoroutine) {
            isRefreshing.value = true
            load(force)
            isRefreshing.value = false
        }
    }

    init {
        refresh()
        viewModelScope.launch(syncCoroutine) {
            syncUtils.tryAutoSync()
        }

        // Signing in changes what this whole page should show, and nothing recreated this view
        // model when it happened, so the feed sat there showing the signed-out page until the app
        // was restarted. Signing out has the same problem in reverse. Changing where Quick picks
        // come from is the same situation: the setting is read while building the page, so without
        // this it would appear to do nothing until something else happened to reload.
        //
        // force, because this is a real change of state rather than a routine open, so it should go
        // out even while backing off. drop(1) because init above has already loaded once, and the
        // flow replays its current value the moment it is collected.
        viewModelScope.launch {
            context.dataStore.data
                .map { it[InnerTubeCookieKey].orEmpty() to it[QuickPicksSourceKey].orEmpty() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(force = true) }
        }
    }
}
