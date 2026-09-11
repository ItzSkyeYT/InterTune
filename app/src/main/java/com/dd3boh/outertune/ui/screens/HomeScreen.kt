package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.constants.ContextChipKey
import com.dd3boh.outertune.constants.AdventurousnessKey
import com.dd3boh.outertune.engine.Lane
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.dd3boh.outertune.viewmodels.CardReason
import com.dd3boh.outertune.constants.ShowReasonsKey
import com.dd3boh.outertune.utils.seenSlots
import com.dd3boh.outertune.utils.CardBox
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.ListItemHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeAlbumRadio
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.PollBanner
import com.dd3boh.outertune.ui.component.ThrottleBanner
import com.dd3boh.outertune.ui.component.PollDialog
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.HideOnScrollFAB
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTile
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.ArtistGridItem
import com.dd3boh.outertune.ui.component.items.SongGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.items.YouTubeGridItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.component.shimmer.GridItemPlaceHolder
import com.valentinilk.shimmer.shimmer
import com.dd3boh.outertune.ui.component.shimmer.ListItemPlaceHolder
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.component.shimmer.TextPlaceholder
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.ArtistMenu
import com.dd3boh.outertune.ui.menu.SongMenu
import com.dd3boh.outertune.ui.menu.YouTubeAlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeArtistMenu
import com.dd3boh.outertune.ui.menu.YouTubePlaylistMenu
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.ui.utils.SnapLayoutInfoProvider
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.HomeViewModel
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val quickPicks by viewModel.quickPicks.collectAsState()

    val pollChecker = LocalPollChecker.current
    val pendingPoll by pollChecker.current.collectAsState()
    var showPoll by rememberSaveable { mutableStateOf(false) }

    // Opened from its own notification. Waits for the question to have loaded, since the tap can
    // arrive before the cached document has been read back on a cold start.
    val openPollRequested by pollChecker.openRequested.collectAsState()
    LaunchedEffect(openPollRequested, pendingPoll) {
        if (openPollRequested && pendingPoll != null) {
            showPoll = true
            pollChecker.consumeOpenRequest()
        }
    }
    val ytQuickPicks by viewModel.ytQuickPicks.collectAsState()
    val quickPicksLoading by viewModel.quickPicksLoading.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val similarRecommendations by viewModel.similarRecommendations.collectAsState()
    val accountPlaylists by viewModel.accountPlaylists.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val explorePage by viewModel.explorePage.collectAsState()

    val selectedChip by viewModel.selectedChip.collectAsState()

    val allLocalItems by viewModel.allLocalItems.collectAsState()
    val allYtItems by viewModel.allYtItems.collectAsState()

    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val quickPicksLazyGridState = rememberLazyGridState()
    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val quickPicksSource by rememberEnumPreference(
        QuickPicksSourceKey, defaultValue = QuickPicksSource.YOUTUBE
    )

    val scope = rememberCoroutineScope()
    val lazylistState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazylistState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val len = lazylistState.layoutInfo.totalItemsCount
                if (lastVisibleIndex != null && lastVisibleIndex >= len - 3) {
                    viewModel.loadMoreYouTubeItems(homePage?.continuation)
                }
            }
    }

    if (selectedChip != null) {
        BackHandler {
            // if a chip is selected, go back to the normal homepage first
            viewModel.toggleChip(selectedChip)
        }
    }


    val localGridItem: @Composable (LocalItem, String) -> Unit = { it, source ->
        when (it) {
            is Song -> SongGridItem(
                song = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (it.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                val song = it.toMediaMetadata()
                                if (song.isLocal) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = source,
                                            items = listOf(song)
                                        ),
                                        origin = PlayOrigin.HOME_ROW,
                                    )
                                } else {
                                    playerConnection.playQueue(YouTubeQueue.radio(song), isRadio = true)
                                }
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(
                                HapticFeedbackType.LongPress,
                            )
                            menuState.show {
                                SongMenu(
                                    originalSong = it,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    ),
                isActive = it.id == mediaMetadata?.id,
                isPlaying = isPlaying,
            )

            is Album -> AlbumGridItem(
                album = it,
                isActive = it.id == mediaMetadata?.album?.id,
                isPlaying = isPlaying,
                coroutineScope = scope,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            navController.navigate("album/${it.id}")
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                AlbumMenu(
                                    originalAlbum = it,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    )
            )

            is Artist -> ArtistGridItem(
                artist = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            navController.navigate("artist/${it.id}")
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(
                                HapticFeedbackType.LongPress,
                            )
                            menuState.show {
                                ArtistMenu(
                                    originalArtist = it,
                                    coroutineScope = scope,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    ),
            )

            is Playlist -> {}
        }
    }

    val ytGridItem: @Composable (YTItem) -> Unit = { item ->
        YouTubeGridItem(
            item = item,
            isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
            isPlaying = isPlaying,
            coroutineScope = scope,
            thumbnailRatio = 1f,
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> playerConnection.playQueue(
                                YouTubeQueue(
                                    item.endpoint ?: WatchEndpoint(
                                        videoId = item.id
                                    ), item.toMediaMetadata()
                                ),
                                isRadio = true,
                            )

                            is AlbumItem -> navController.navigate("album/${item.id}")
                            is ArtistItem -> navController.navigate("artist/${item.id}")
                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            when (item) {
                                is SongItem -> YouTubeSongMenu(
                                    song = item,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )

                                is AlbumItem -> YouTubeAlbumMenu(
                                    albumItem = item,
                                    navController = navController,
                                    onDismiss = menuState::dismiss
                                )

                                is ArtistItem -> YouTubeArtistMenu(
                                    artist = item,
                                    onDismiss = menuState::dismiss
                                )

                                is PlaylistItem -> YouTubePlaylistMenu(
                                    navController = navController,
                                    playlist = item,
                                    coroutineScope = scope,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    }
                )
        )
    }

    LaunchedEffect(quickPicks) {
        quickPicksLazyGridState.scrollToItem(0)
    }

    // What is actually on screen in Quick picks, in slot order, from whichever source is filling
    // it. Registered as a build whenever it changes, and each card logged once it has been at least
    // half visible for half a second while this screen is resumed: a card under the player sheet
    // or a row scrolled past in a flick is not something the listener passed over.
    val engineFallback by viewModel.engineFallback.collectAsState()
    val engineReasons by viewModel.engineReasons.collectAsState()
    val showReasons by rememberPreference(ShowReasonsKey, defaultValue = true)
    // YouTube's shelf is the row when it is the source, or when it stands in for the engine.
    val ytShelfShown = ytQuickPicks?.isNotEmpty() == true &&
        (quickPicksSource == QuickPicksSource.YOUTUBE || (quickPicksSource != QuickPicksSource.YOUTUBE && quickPicksSource != QuickPicksSource.OFF && engineFallback == 2))
    val shownPicks: List<MediaMetadata> = remember(ytQuickPicks, quickPicks, quickPicksSource, engineFallback) {
        ytQuickPicks?.takeIf { ytShelfShown }?.map { it.toMediaMetadata() }
            ?: quickPicks.orEmpty().map { it.toMediaMetadata() }
    }
    val shownSource = when {
        ytShelfShown -> 1
        quickPicksSource == QuickPicksSource.COMPARE && engineFallback == 0 -> 3
        quickPicksSource == QuickPicksSource.ENGINE && engineFallback == 0 -> 2
        else -> 0
    }
    LaunchedEffect(shownPicks) { if (quickPicksSource != QuickPicksSource.OFF) viewModel.quickPicksShown(shownSource, shownPicks) }
    var showWhyThese by remember { mutableStateOf(false) }
    if (showWhyThese) WhyTheseDialog(viewModel = viewModel, navController = navController, onDismiss = { showWhyThese = false })
    val lifecycleOwner = LocalLifecycleOwner.current
    // What was just played leaves Quick picks when Home comes back into view, not under the finger.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.applyTidy() }
    }
    LaunchedEffect(shownPicks, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                // The grid knows its own viewport; Home's list says how much of the grid is on
                // screen at all. Both have to agree before a card counts as seen.
                val outer = lazylistState.layoutInfo
                val holder = outer.visibleItemsInfo.firstOrNull { it.key == "quick_picks_grid" }
                    ?: return@snapshotFlow emptySet()
                val info = quickPicksLazyGridState.layoutInfo
                seenSlots(
                    cards = info.visibleItemsInfo.map { CardBox(it.index, it.offset.x, it.offset.y, it.size.width, it.size.height) },
                    rowViewportStart = info.viewportStartOffset, rowViewportEnd = info.viewportEndOffset,
                    rowTop = holder.offset, screenTop = outer.viewportStartOffset, screenBottom = outer.viewportEndOffset,
                )
            }.collectLatest { slots ->
                if (slots.isEmpty()) return@collectLatest
                delay(500)   // cancelled by the next change, so only a settled row counts
                slots.forEach(viewModel::quickPickSeen)
            }
        }
    }

    LaunchedEffect(forgottenFavorites) {
        forgottenFavoritesLazyGridState.scrollToItem(0)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.pullToRefresh() }
            ),
        contentAlignment = Alignment.TopStart
    ) {
        val listThumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()

        val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
        val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
        val quickPicksSnapLayoutInfoProvider = remember(quickPicksLazyGridState) {
            SnapLayoutInfoProvider(
                lazyGridState = quickPicksLazyGridState,
                positionInLayout = { layoutSize, itemSize ->
                    (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                }
            )
        }
        val forgottenFavoritesSnapLayoutInfoProvider = remember(forgottenFavoritesLazyGridState) {
            SnapLayoutInfoProvider(
                lazyGridState = forgottenFavoritesLazyGridState,
                positionInLayout = { layoutSize, itemSize ->
                    (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                }
            )
        }

        ScrollToTopManager(navController, lazylistState)
        LazyColumn(
            state = lazylistState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
        ) {
            item {
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .animateItem()
                ) {
                    NavigationTile(
                        title = stringResource(R.string.history),
                        icon = Icons.Rounded.History,
                        onClick = { navController.navigate("history") },
                        modifier = Modifier.weight(1f)
                    )

                    NavigationTile(
                        title = stringResource(R.string.stats),
                        icon = Icons.AutoMirrored.Rounded.TrendingUp,
                        onClick = { navController.navigate("stats") },
                        modifier = Modifier.weight(1f)
                    )

                    if (localLibEnable) {
                        NavigationTile(
                            title = stringResource(R.string.scanner_local_title),
                            icon = Icons.Rounded.SdCard,
                            onClick = {
                                navController.navigate("settings/local")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    NavigationTile(
                        title = stringResource(R.string.account),
                        icon = Icons.Rounded.Person,
                        onClick = {
                            navController.navigate("account")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                ChipsRow(
                    chips = homePage?.chips?.mapNotNull { it to it.title } ?: emptyList(),
                    currentValue = selectedChip,
                    onValueUpdate = {
                        viewModel.toggleChip(it)
                    }
                )
            }



            // Same slot reasoning as the poll banner below: above Quick picks, below the chips.
            // Shown only while YouTube is actually refusing this connection, and it takes itself
            // away when the back-off expires.
            item(key = "throttle_banner") {
                ThrottleBanner(modifier = Modifier.animateItem())
            }

            // Above Quick picks and below the chips: visible without being in the way, and it
            // scrolls off with everything else rather than pinning itself to the top.
            pendingPoll?.let { poll ->
                item(key = "poll_banner") {
                    PollBanner(
                        poll = poll,
                        onOpen = { showPoll = true },
                        onDismiss = { scope.launch { pollChecker.dismiss(poll.id) } },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // Same four-row grid whichever list fills it.
            //
            // The setting is read here as well as in the view model, on purpose. The view model
            // only ever fills ytQuickPicks on the YouTube source, but "only ever filled" is not the
            // same as "empty right now": a stale shelf from before the setting changed used to
            // survive here and draw YouTube's songs under the Quick picks heading while the setting
            // read Your library. On this source the row is the app's own recommendations or
            // nothing, and now no ordering inside the load can change that.
            //
            // The YouTube source does fall back to the local list, because YouTube sends no shelf
            // at all while signed out.
            val ytPicks = ytQuickPicks
                ?.takeIf { ytShelfShown }
            val localPicks = quickPicks.orEmpty()

            // Skeleton while the answer is still being worked out, including during a pull to
            // refresh, so the row visibly reloads rather than sitting on the previous songs. The
            // same grid, filled with placeholders, so nothing shifts size when the songs arrive.
            if (quickPicksSource == QuickPicksSource.OFF) {
                // Nothing: the listener turned the row off.
            } else if (quickPicksLoading && ytPicks == null) {
                item {
                    NavigationTitle(
                        title = stringResource(R.string.quick_picks),
                        modifier = Modifier.animateItem()
                    )
                }
                item {
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(4),
                        userScrollEnabled = false,
                        contentPadding = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4)
                            .shimmer()
                            .animateItem()
                    ) {
                        items(6) {
                            ListItemPlaceHolder(
                                modifier = Modifier.width(horizontalLazyGridItemWidth)
                            )
                        }
                    }
                }
            } else {
                // The heading is unconditional. Both sources put Quick picks at the top of Home;
                // all that differs is where its songs come from. Dropping the whole block when the
                // chosen source has nothing to show made the row look like it had been taken away,
                // when the truth is just that it has not been filled yet.
                item {
                    NavigationTitle(
                        title = stringResource(R.string.quick_picks),
                        onClick = if ((quickPicksSource == QuickPicksSource.ENGINE || quickPicksSource == QuickPicksSource.COMPARE) && engineFallback == 0) ({ showWhyThese = true }) else null,
                        label = when {
                            quickPicksSource == QuickPicksSource.COMPARE && engineFallback == 0 -> stringResource(R.string.quick_picks_try_both_label)
                            quickPicksSource != QuickPicksSource.ENGINE && quickPicksSource != QuickPicksSource.COMPARE -> null
                            engineFallback == 1 -> stringResource(R.string.quick_picks_showing_library)
                            engineFallback == 2 -> stringResource(R.string.quick_picks_showing_youtube)
                            else -> null
                        },
                        modifier = Modifier.animateItem()
                    )
                }

                if ((quickPicksSource == QuickPicksSource.ENGINE || quickPicksSource == QuickPicksSource.COMPARE) && engineFallback == 0) {
                    item(key = "context_chips") { ContextChipRow(viewModel = viewModel, modifier = Modifier.animateItem()) }
                }
                if (ytPicks != null || localPicks.isNotEmpty()) {
                    item(key = "quick_picks_grid") {
                        LazyHorizontalGrid(
                            state = quickPicksLazyGridState,
                            rows = GridCells.Fixed(4),
                            flingBehavior = rememberSnapFlingBehavior(quickPicksSnapLayoutInfoProvider),
                            contentPadding = WindowInsets.systemBars
                                .only(WindowInsetsSides.Horizontal)
                                .asPaddingValues(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight * 4)
                                .animateItem()
                        ) {
                            // Same grid either way. Only which list fills it differs.
                            if (ytPicks != null) {
                                itemsIndexed(
                                    items = ytPicks,
                                    key = { _, it -> it.id }
                                ) { slot, song ->
                                    YouTubeListItem(
                                        item = song,
                                        isActive = song.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        modifier = Modifier
                                            .width(horizontalLazyGridItemWidth)
                                            .combinedClickable(
                                                onClick = {
                                                    if (song.id == mediaMetadata?.id) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        val tappedAt = System.currentTimeMillis()
                                                        viewModel.quickPickTapped(slot, tappedAt)
                                                        playerConnection.playQueue(
                                                            YouTubeQueue.radio(song.toMediaMetadata()),
                                                            isRadio = true,
                                                            origin = PlayOrigin.QUICK_PICKS,
                                                            originSlot = slot,
                                                            tappedAt = tappedAt,
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        YouTubeSongMenu(
                                                            song = song,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss,
                                                            onExclude = { kind, reason -> viewModel.excludeYt(song, kind, reason) }
                                                        )
                                                    }
                                                }
                                            )
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = localPicks,
                                    key = { _, it -> it.id }
                                ) { slot, originalSong ->
                                    SongListItem(
                                        song = originalSong,
                                        navController = navController,

                                        isActive = originalSong.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        inSelectMode = null,
                                        isSelected = false,
                                        onSelectedChange = {},
                                        swipeEnabled = false,

                                        thumbnailSize = listThumbnailSize,
                                        caption = if (showReasons && (shownSource == 2 || shownSource == 3)) engineReasons[originalSong.id]?.firstOrNull()?.let { reasonText(it) } else null,
                                        onExclude = { kind, reason -> viewModel.excludeSong(originalSong, kind, reason) },
                                        onPlay = {
                                            val tappedAt = System.currentTimeMillis()
                                            viewModel.quickPickTapped(slot, tappedAt)
                                            playerConnection.playQueue(
                                                YouTubeQueue.radio(originalSong.toMediaMetadata()),
                                                isRadio = true,
                                                origin = PlayOrigin.QUICK_PICKS,
                                                originSlot = slot,
                                                tappedAt = tappedAt,
                                            )
                                        },
                                        modifier = Modifier.width(horizontalLazyGridItemWidth)
                                    )
                                }
                            }
                        }
                    }
            
                } else {
                    // Nothing to show yet. On Your library that means nothing has been played for
                    // the recommendations to be built from, which is what this string has said
                    // since upstream wrote it, in 43 languages, without ever being rendered.
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight * 2)
                                .animateItem()
                        ) {
                            Text(
                                text = stringResource(R.string.quick_picks_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                }
            }

            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { forgottenFavorites ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.forgotten_favorites),
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    val queueTitle = stringResource(R.string.forgotten_favorites)
                    // take min in case list size is less than 4
                    val rows = min(4, forgottenFavorites.size)
                    LazyHorizontalGrid(
                        state = forgottenFavoritesLazyGridState,
                        rows = GridCells.Fixed(rows),
                        flingBehavior = rememberSnapFlingBehavior(forgottenFavoritesSnapLayoutInfoProvider),
                        contentPadding = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * rows)
                            .animateItem()
                    ) {
                        itemsIndexed(
                            items = forgottenFavorites,
                            key = { _, song -> song.id }
                        ) { index, originalSong ->
                            SongListItem(
                                song = originalSong,
                                navController = navController,

                                isActive = originalSong.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                inSelectMode = null,
                                isSelected = false,
                                onSelectedChange = {},
                                swipeEnabled = false,

                                thumbnailSize = listThumbnailSize,
                                onPlay = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = queueTitle,
                                            items = forgottenFavorites.map { it.toMediaMetadata() },
                                            startIndex = index
                                        ),
                                        origin = PlayOrigin.HOME_ROW,
                                    )
                                },
                                modifier = Modifier.width(horizontalLazyGridItemWidth)
                            )
                        }
                    }
                }
            }

            keepListening?.takeIf { it.isNotEmpty() }?.let { keepListening ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.keep_listening),
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    val rows = if (keepListening.size > 6) 2 else 1
                    LazyHorizontalGrid(
                        state = rememberLazyGridState(),
                        rows = GridCells.Fixed(rows),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((GridThumbnailHeight + 24.dp + with(LocalDensity.current) {
                                MaterialTheme.typography.bodyLarge.lineHeight.toDp() * 2 +
                                        MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2
                            }) * rows)
                            .animateItem()
                    ) {
                        items(keepListening) {
                            localGridItem(it, stringResource(R.string.keep_listening))
                        }
                    }
                }
            }

            accountPlaylists?.takeIf { it.isNotEmpty() }?.let { accountPlaylists ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.your_youtube_playlists),
                        onClick = {
                            navController.navigate("account")
                        },
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    LazyRow(
                        contentPadding = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                        modifier = Modifier.animateItem()
                    ) {
                        items(
                            items = accountPlaylists,
                            key = { it.id },
                        ) { item ->
                            ytGridItem(item)
                        }
                    }
                }
            }

            similarRecommendations?.forEach {
                item {
                    NavigationTitle(
                        label = stringResource(R.string.similar_to),
                        title = it.title.title,
                        thumbnail = it.title.thumbnailUrl?.let { thumbnailUrl ->
                            {
                                val shape =
                                    if (it.title is Artist) CircleShape else RoundedCornerShape(
                                        ThumbnailCornerRadius
                                    )
                                AsyncImage(
                                    model = thumbnailUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(ListThumbnailSize)
                                        .clip(shape)
                                )
                            }
                        },
                        onClick = {
                            when (it.title) {
                                is Song -> navController.navigate("album/${it.title.album!!.id}")
                                is Album -> navController.navigate("album/${it.title.id}")
                                is Artist -> navController.navigate("artist/${it.title.id}")
                                is Playlist -> {}
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    LazyRow(
                        contentPadding = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                        modifier = Modifier.animateItem()
                    ) {
                        items(it.items) { item ->
                            ytGridItem(item)
                        }
                    }
                }
            }

            homePage?.sections?.forEach {
                item {
                    NavigationTitle(
                        title = it.title,
                        label = it.label,
                        thumbnail = it.thumbnail?.let { thumbnailUrl ->
                            {
                                val shape =
                                    if (it.endpoint?.isArtistEndpoint == true) CircleShape else RoundedCornerShape(
                                        ThumbnailCornerRadius
                                    )
                                AsyncImage(
                                    model = thumbnailUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(ListThumbnailSize)
                                        .clip(shape)
                                )
                            }
                        },
                        onClick = it.endpoint?.browseId?.let { browseId ->
                            {
                                if (browseId == "FEmusic_moods_and_genres")
                                    navController.navigate("mood_and_genres")
                                else
                                    navController.navigate("browse/$browseId")
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }

                item {
                    LazyRow(
                        contentPadding = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                        modifier = Modifier.animateItem()
                    ) {
                        items(it.items) { item ->
                            ytGridItem(item)
                        }
                    }
                }
            }

            if (homePage?.continuation != null && homePage?.sections?.isNotEmpty() == true) {
                item {
                    ShimmerHost(
                        modifier = Modifier.animateItem()
                    ) {
                        TextPlaceholder(
                            height = 36.dp,
                            modifier = Modifier
                                .padding(12.dp)
                                .width(250.dp),
                        )
                        LazyRow {
                            items(4) {
                                GridItemPlaceHolder()
                            }
                        }
                    }
                }
            }

            explorePage?.moodAndGenres?.let { moodAndGenres ->
                item {
                    NavigationTitle(
                        title = stringResource(R.string.mood_and_genres),
                        onClick = {
                            navController.navigate("mood_and_genres")
                        },
                        modifier = Modifier.animateItem()
                    )
                }
                item {
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(4),
                        contentPadding = PaddingValues(6.dp),
                        modifier = Modifier
                            .height((MoodAndGenresButtonHeight + 12.dp) * 4 + 12.dp)
                            .animateItem()
                    ) {
                        items(moodAndGenres) {
                            MoodAndGenresButton(
                                title = it.title,
                                onClick = {
                                    navController.navigate("youtube_browse/${it.endpoint.browseId}?params=${it.endpoint.params}")
                                },
                                modifier = Modifier
                                    .padding(6.dp)
                                    .width(180.dp)
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    ShimmerHost(
                        modifier = Modifier.animateItem()
                    ) {
                        TextPlaceholder(
                            height = 36.dp,
                            modifier = Modifier
                                .padding(12.dp)
                                .width(250.dp),
                        )
                        LazyRow {
                            items(4) {
                                GridItemPlaceHolder()
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazylistState,
        )

        HideOnScrollFAB(
            visible = allLocalItems.isNotEmpty() || allYtItems.isNotEmpty(),
            lazyListState = lazylistState,
            icon = Icons.Rounded.Casino,
            onClick = {
                val local = when {
                    allLocalItems.isNotEmpty() && allYtItems.isNotEmpty() -> Random.nextFloat() < 0.5
                    allLocalItems.isNotEmpty() -> true
                    else -> false
                }
                if (local) {
                    when (val luckyItem = allLocalItems.random()) {
                        is Song -> playerConnection.playQueue(
                            YouTubeQueue.radio(luckyItem.toMediaMetadata()),
                            isRadio = true
                        )

                        is Album -> {
                            scope.launch(Dispatchers.IO) {
                                val songs = database.albumSongs(luckyItem.id).first()
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = luckyItem.title,
                                        items = songs.map(Song::toMediaMetadata)
                                    ),
                                    origin = PlayOrigin.HOME_ROW,
                                )
                            }
                        }
                        // not possible, already filtered out
                        is Artist -> {}
                        is Playlist -> {}
                    }
                } else {
                    when (val luckyItem = allYtItems.random()) {
                        is SongItem -> playerConnection.playQueue(
                            YouTubeQueue.radio(luckyItem.toMediaMetadata()),
                            isRadio = true
                        )

                        is AlbumItem -> playerConnection.playQueue(
                            YouTubeAlbumRadio(luckyItem.playlistId),
                            isRadio = true
                        )

                        is ArtistItem -> luckyItem.radioEndpoint?.let {
                            playerConnection.playQueue(YouTubeQueue(it), isRadio = true)
                        }

                        is PlaylistItem -> luckyItem.playEndpoint?.let {
                            playerConnection.playQueue(YouTubeQueue(it), isRadio = true)
                        }
                    }
                }
            }
        )

        Indicator(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }

    // Only ever opened by tapping the banner. Closing without answering leaves the question
    // unanswered rather than marking it dealt with, so the banner stays until it is dismissed.
    pendingPoll?.takeIf { showPoll }?.let { poll ->
        PollDialog(
            poll = poll,
            onSubmit = { chosen ->
                showPoll = false
                scope.launch { pollChecker.answer(poll, chosen) }
            },
            onClose = { showPoll = false },
        )
    }
}

/** The caption's words for a reason the engine gave. */
@Composable
private fun reasonText(reason: CardReason): String = when (reason.key) {
    "x_seed" -> reason.arg?.let { stringResource(R.string.reason_seed, it) } ?: stringResource(R.string.reason_activation)
    "x_art" -> reason.arg?.let { stringResource(R.string.reason_artist, it) } ?: stringResource(R.string.reason_activation)
    "x_dorm" -> stringResource(R.string.reason_dormant)
    "x_like" -> stringResource(R.string.reason_like)
    "x_ctx" -> reason.arg?.toIntOrNull()?.let { chip ->
        val name = when (chip) { ContextChip.FOCUS -> R.string.chip_focus; ContextChip.CHILL -> R.string.chip_chill; else -> R.string.chip_party }
        stringResource(R.string.reason_context_mood, stringResource(name))
    } ?: stringResource(R.string.reason_context)
    "x_co" -> stringResource(R.string.reason_co)
    "new_to_you" -> stringResource(R.string.reason_new)
    "wildcard" -> stringResource(R.string.reason_wildcard)
    else -> stringResource(R.string.reason_activation)
}

/** Why these? The seeds the row was built around (each can be turned down), the lanes and their quotas, and the exclusions in force. */
@Composable
private fun WhyTheseDialog(viewModel: HomeViewModel, navController: NavController, onDismiss: () -> Unit) {
    val seeds by viewModel.engineSeeds.collectAsState()
    val quotas by viewModel.engineQuotas.collectAsState()
    val exclusions by viewModel.activeExclusions.collectAsState(initial = 0)
    val adventurousness by rememberPreference(AdventurousnessKey, defaultValue = 15)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.why_these)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.why_these_seeds), style = MaterialTheme.typography.titleSmall)
                seeds.forEach { (id, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { viewModel.rejectSeed(id) }) { Text(stringResource(R.string.why_these_not_this_one)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.why_these_lanes), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOf(
                        stringResource(R.string.why_these_lane_related, quotas[Lane.RELATED] ?: 0),
                        stringResource(R.string.why_these_lane_again, quotas[Lane.AGAIN] ?: 0),
                        stringResource(R.string.why_these_lane_artist, quotas[Lane.ARTIST] ?: 0),
                        stringResource(R.string.why_these_lane_rediscover, quotas[Lane.REDISCOVER] ?: 0),
                        stringResource(R.string.why_these_lane_explore, quotas[Lane.EXPLORE] ?: 0),
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.why_these_dial, adventurousness), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.why_these_exclusions, exclusions), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onDismiss(); navController.navigate("settings/recommendations/exclusions") }) { Text(stringResource(R.string.why_these_manage)) } },
    )
}

/** The declared context, kept until changed: Auto, Discover, Favourites, and three moods that learn from what is played while they are on. */
@Composable
private fun ContextChipRow(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val (chip, onChipChange) = rememberPreference(ContextChipKey, defaultValue = ContextChip.AUTO)
    val tagged by viewModel.engineChipTagged.collectAsState()
    val names = listOf(
        ContextChip.AUTO to stringResource(R.string.chip_auto), ContextChip.DISCOVER to stringResource(R.string.chip_discover),
        ContextChip.FAVOURITES to stringResource(R.string.chip_favourites), ContextChip.FOCUS to stringResource(R.string.chip_focus),
        ContextChip.CHILL to stringResource(R.string.chip_chill), ContextChip.PARTY to stringResource(R.string.chip_party),
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            names.forEach { (value, name) ->
                FilterChip(
                    selected = chip == value,
                    onClick = { if (chip != value) { onChipChange(value); viewModel.chipChanged() } },
                    label = { Text(name) },
                )
            }
        }
        if (chip in ContextChip.MOODS && tagged in 0 until ContextChip.MIN_TAGGED) {
            Text(
                text = stringResource(R.string.chip_learning, names.first { it.first == chip }.second, tagged, ContextChip.MIN_TAGGED),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
