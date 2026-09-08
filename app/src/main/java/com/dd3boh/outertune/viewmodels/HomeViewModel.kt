package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
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

    /**
     * True while the Quick picks row has no settled answer yet.
     *
     * Separate from [isLoading], which covers the whole page. This one goes false the moment Quick
     * picks are decided, which for the library source is as soon as the query returns and for the
     * YouTube source is after its shelf has been looked for. The row shows a skeleton while it is
     * true, so a refresh replaces the songs rather than leaving the old ones sitting there.
     */
    val quickPicksLoading = MutableStateFlow(false)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)

    // A reload asked for while one was already running, and whether it asked to bypass the back-off.
    /**
     * The Quick picks source the last load ran under, and whether that load found YouTube's shelf.
     *
     * Both exist so a refresh can leave the row alone. See [load].
     */
    private var lastQuickPicksSource: QuickPicksSource? = null
    private var foundQuickPicksThisLoad = false

    private var pendingRefresh = false
    private var pendingRefreshForce = false
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
        val source = quickPicksSource()
        foundQuickPicksThisLoad = false

        // Blanked only when the source changed, not on every load.
        //
        // Clearing unconditionally is what made a refresh feel slow: the row emptied to a shimmer
        // the instant you pulled, and stayed empty for the second or so the home request takes,
        // even though the songs that came back were usually the same ones. YouTube returns the
        // same Quick picks shelf for the whole session, so that was a second of blank screen to
        // arrive back where it started.
        //
        // It does still have to blank when the source changes. Switching from YouTube to Your
        // library used to leave YouTube's shelf sitting under the heading for the whole load,
        // because the screen draws whatever this flow holds and has no way to know better.
        if (source != lastQuickPicksSource) {
            ytQuickPicks.value = null
        }
        lastQuickPicksSource = source

        // Only the YouTube source has anything to wait for, and only when there is nothing to
        // show. A shimmer over a row that already has songs in it is the same lie as blanking it.
        quickPicksLoading.value = source == QuickPicksSource.YOUTUBE && ytQuickPicks.value == null

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

        // Your own playlists come first, and are exempt from both gates below.
        //
        // Asking YouTube for the playlists you made is not a recommendation, so the source does not
        // govern it. It is also not the kind of request the back-off is protecting you from: the
        // back-off exists to stop a pile of recommendation lookups hammering a network that is
        // already refusing us, and this is one request for your own data. It used to sit below the
        // back-off guard, which meant a back-off silently cost you your own playlists too.
        //
        // Assigned on every path, including failure and signed out, because leaving the previous
        // value in place shows a signed-out user the last account's playlists.
        if (YouTube.cookie != null) {
            YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
            }.onFailure {
                // Left as it was. A transient failure blanking your playlists is worse than
                // briefly showing the previous list, and this runs on every home load.
                reportException(it)
            }
        } else {
            accountPlaylists.value = null
        }

        // The Quick picks source governs the Quick picks row and nothing else. It briefly governed
        // the whole page, hiding YouTube's carousels, its "Similar to" rows and its mood tiles when
        // the library was chosen, and that was wrong: choosing where one row's songs come from is
        // not a request to empty the home screen. See takeQuickPicks, which is the whole of it.

        // Everything below here is remote recommendation work, and opening the app fires all of it:
        // an artist lookup per seed, a related lookup per seed, home, and explore. While YouTube is
        // refusing this network that is a pile of requests that cannot succeed and that make the
        // refusal last longer. Pulling to refresh passes force and still tries, because the user
        // asked for it and one request doubles as the probe that clears the block.
        if (!force && Throttle.isBlocked) {
            Log.d("HomeViewModel", "Skipping remote home load, backing off")
            quickPicksLoading.value = false
            isLoading.value = false
            return
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

        YouTube.home().onSuccess { page ->
            var merged = takeQuickPicks(page)

            // Read past the first response, because on its own it is not a home feed.
            //
            // Measured against the live API on 8 Sep, signed out: the first response is 3 sections
            // and 25 items, and that is the whole of what a refresh used to show. Two refreshes back
            // to back share about half their items, so pulling to refresh mostly reprinted what was
            // already there, in a different order, and the rest of the feed was only reachable by
            // scrolling far enough to trigger loadMoreYouTubeItems. Walking the continuations
            // instead: batch 1 adds 22 items that were not in batch 0, batch 2 adds 24 more, and
            // then the continuation goes null. 71 unique items rather than 25, so a refresh draws
            // from a pool nearly three times the size and genuinely lands on new songs.
            //
            // Capped rather than exhaustive. That feed ended on its own after two continuations,
            // but a signed-in feed is longer and there is no reason to let one refresh walk it to
            // the end; HOME_REFRESH_BATCHES is the ceiling and scrolling picks up from wherever
            // this left off, since the surviving continuation is carried into merged.
            //
            // Quick picks used to be the only reason to fetch a continuation at all, since YouTube
            // puts that shelf at index 0 of the first one rather than in the first response. It is
            // still lifted out on the way past, by takeQuickPicks on each batch.
            var batches = 0
            while (batches < HOME_REFRESH_BATCHES) {
                val next = merged.continuation ?: break
                val page2 = YouTube.home(next).getOrNull() ?: break
                val cleaned = takeQuickPicks(page2)
                merged = merged.copy(
                    sections = merged.sections + cleaned.sections,
                    continuation = cleaned.continuation,
                )
                batches++
            }
            Log.d(
                "HomeViewModel",
                "Home loaded: ${merged.sections.size} sections, " +
                        "${merged.sections.sumOf { it.items.size }} items, $batches continuations"
            )
            homePage.value = merged
        }.onFailure {
            reportException(it)
        }
        // The row is kept across loads now, so a shelf that has actually disappeared has to be
        // cleared here or last load's songs would sit there indefinitely.
        if (source == QuickPicksSource.YOUTUBE && !foundQuickPicksThisLoad) {
            ytQuickPicks.value = null
        }

        // Settled either way: found, or looked for and not there. Leaving it true on failure would
        // leave a skeleton shimmering over a row that is never going to fill.
        quickPicksLoading.value = false

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
    private fun quickPicksSource(): QuickPicksSource =
        context.dataStore.get(QuickPicksSourceKey, QuickPicksSource.YOUTUBE.name)
            .toEnum(QuickPicksSource.YOUTUBE)

    private fun takeQuickPicks(page: HomePage): HomePage {
        // Set to Your library and YouTube's shelf is left where it is, rendering as an ordinary
        // section of the feed rather than being lifted into the row. The row on that setting is the
        // app's own recommendations, so there is nothing to lift it for, and removing it would just
        // lose a section of the feed.
        //
        // It does get renamed. YouTube calls that shelf "Quick picks" too, so leaving its title
        // alone put two rows with the same heading on the same screen, one of them the library row
        // and one of them the thing that row exists instead of. That reads as a bug even though
        // both rows are correct.
        if (quickPicksSource() != QuickPicksSource.YOUTUBE) {
            val theirs = page.sections.firstOrNull { section ->
                section.itemsPerColumn != null &&
                        section.items.isNotEmpty() &&
                        section.items.all { it is SongItem }
            } ?: return page
            return page.copy(
                sections = page.sections.map {
                    if (it === theirs) it.copy(title = context.getString(R.string.youtube_picks)) else it
                }
            )
        }

        val shelf = page.sections.firstOrNull { section ->
            section.itemsPerColumn != null &&
                    section.items.isNotEmpty() &&
                    section.items.all { it is SongItem }
        } ?: return page
        foundQuickPicksThisLoad = true
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

    /**
     * Reloads the page.
     *
     * The old guard was `if (isRefreshing.value) return`, which threw the request away whenever a
     * load happened to be in flight. That is fine for a second pull on the refresh indicator and
     * wrong for everything else: the sign-in watcher below is the only thing that reloads when a
     * cookie appears, its upstream is distinctUntilChanged, so a dropped emission never comes back
     * and the page stays signed out for the life of the view model.
     *
     * So a dropped request is remembered instead, and the running load repeats when it finishes.
     * The try/finally matters too: without it a thrown load latched isRefreshing true and disabled
     * both this watcher and pull to refresh permanently.
     */
    fun refresh(force: Boolean = false) {
        if (isRefreshing.value) {
            pendingRefresh = true
            pendingRefreshForce = pendingRefreshForce || force
            return
        }
        viewModelScope.launch(syncCoroutine) {
            isRefreshing.value = true
            try {
                var nextForce = force
                do {
                    pendingRefresh = false
                    load(nextForce)
                    nextForce = pendingRefreshForce
                    pendingRefreshForce = false
                } while (pendingRefresh)
            } finally {
                isRefreshing.value = false
            }
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

/**
 * How many home continuations one load is allowed to read past the first response.
 *
 * Three is the measured shape of the signed-out feed plus headroom: batches 0 to 2 hold the whole
 * of it, and the fourth request is the one that discovers the continuation has gone null. A
 * signed-in feed runs longer, and this is the point at which a refresh stops walking it and leaves
 * the rest to scrolling.
 */
private const val HOME_REFRESH_BATCHES = 3
