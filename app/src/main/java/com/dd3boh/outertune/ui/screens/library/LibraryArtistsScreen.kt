package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistFilterKey
import com.dd3boh.outertune.constants.ArtistSortDescendingKey
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.ArtistSortTypeKey
import com.dd3boh.outertune.constants.ArtistsOrderLockKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.Artist
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.dd3boh.outertune.constants.ArtistViewTypeKey
import com.dd3boh.outertune.constants.CONTENT_TYPE_ARTIST
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.LibraryViewType
import com.dd3boh.outertune.constants.LibraryViewTypeKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.LazyVerticalGridScrollbar
import com.dd3boh.outertune.ui.component.LibraryArtistGridItem
import com.dd3boh.outertune.ui.component.LibraryArtistListItem
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.StoragePermissionBanner
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.menu.ActionDropdown
import com.dd3boh.outertune.ui.menu.DropdownItem
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.LibraryArtistsViewModel
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.ui.utils.SideBySide
import com.dd3boh.outertune.ui.utils.itemsInColumns
import com.dd3boh.outertune.ui.utils.rememberListColumns

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryArtistsScreen(
    navController: NavController,
    viewModel: LibraryArtistsViewModel = hiltViewModel(),
    libraryFilterContent: @Composable() (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    var filter by rememberEnumPreference(ArtistFilterKey, ArtistFilter.LIKED)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)

    var artistViewType by rememberEnumPreference(ArtistViewTypeKey, LibraryViewType.GRID)
    val libraryViewType by rememberEnumPreference(LibraryViewTypeKey, LibraryViewType.GRID)
    val viewType = if (libraryFilterContent != null) libraryViewType else artistViewType

    val (storedSortType, onSortTypeChange) = rememberEnumPreference(ArtistSortTypeKey, ArtistSortType.CREATE_DATE)
    // A stored Custom order reads as Date added where it is not offered (Unreleased.PLAYLIST_ORDER).
    val sortType = if (storedSortType == ArtistSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) ArtistSortType.CREATE_DATE else storedSortType
    val (sortDescending, onSortDescendingChange) = rememberPreference(ArtistSortDescendingKey, true)
    // Custom order: the artists are dragged by their handles as the playlists are, with a lock of their
    // own; only the liked ones, which the Library page shows in the same order, and one row to a line.
    val customOrder = Unreleased.PLAYLIST_ORDER && sortType == ArtistSortType.CUSTOM
    var orderLocked by rememberPreference(ArtistsOrderLockKey, defaultValue = false)
    val canDrag = customOrder && !orderLocked && filter == ArtistFilter.LIKED

    val artists by viewModel.allArtists.collectAsState()
    val isSyncingRemoteArtists by viewModel.isSyncingRemoteArtists.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()

    // The artists as they are dragged, and the database's between drags, as the playlists.
    val dragged = remember { mutableStateListOf<Artist>() }
    // The order just dropped, until the list read back from the preferences has it.
    var droppedOrder by remember { mutableStateOf<List<String>?>(null) }
    var wasDragging by remember { mutableStateOf(false) }
    val moveRow: (Any?, Any?) -> Unit = { fromKey, toKey ->
        // By key, not place: the headers sit above the rows.
        val from = dragged.indexOfFirst { it.id == fromKey }
        val to = dragged.indexOfFirst { it.id == toKey }
        if (from >= 0 && to >= 0) dragged.add(to, dragged.removeAt(from))
    }
    val reorderableListState = rememberReorderableLazyListState(lazyListState) { from, to -> moveRow(from.key, to.key) }
    val reorderableGridState = rememberReorderableLazyGridState(lazyGridState) { from, to -> moveRow(from.key, to.key) }
    val dragging = reorderableListState.isAnyItemDragging || reorderableGridState.isAnyItemDragging
    LaunchedEffect(artists, dragging) {
        if (dragging) {
            wasDragging = true
            return@LaunchedEffect
        }
        val listed = artists ?: return@LaunchedEffect
        val ids = listed.map { it.id }
        if (wasDragging) {
            wasDragging = false
            val shown = dragged.map { it.id }
            if (customOrder && shown != ids && shown.toSet() == ids.toSet()) {
                droppedOrder = shown
                viewModel.saveCustomOrder(shown)
                return@LaunchedEffect
            }
        }
        // The rows stay where they were dropped until the order kept comes back, not the old one.
        val waiting = droppedOrder
        if (waiting != null && ids != waiting && ids.toSet() == waiting.toSet()) return@LaunchedEffect
        droppedOrder = null
        dragged.clear()
        dragged.addAll(listed)
    }
    val shownArtists: List<Artist>? = if (customOrder && dragged.isNotEmpty()) dragged else artists

    LaunchedEffect(Unit) { viewModel.syncArtists() }

    // On a phone on its side the chips and the sort row share a line, a half each (Landscape.kt).
    val oneHeaderLine = LocalLandscape.current.active
    // The list view runs two rows abreast there, as Songs does.
    val columns = rememberListColumns()
    val filterContent = @Composable {
        var showStoragePerm by remember {
            mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
        }
        Column {
            if (localLibEnable && showStoragePerm) {
                StoragePermissionBanner(
                    onClick = {
                        showStoragePerm =
                            false // allow user to hide error when clicked. This also makes the code a lot nicer too...
                        (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                    }
                )
            }

            Row {
                ChipsRow(
                    chips = listOf(
                        ArtistFilter.LIKED to stringResource(R.string.filter_liked),
                        ArtistFilter.LIBRARY to stringResource(R.string.filter_library),
                        ArtistFilter.DOWNLOADED to stringResource(R.string.filter_downloaded)
                    ),
                    currentValue = filter,
                    onValueUpdate = {
                        filter = it
                        if ((it == ArtistFilter.LIBRARY || it == ArtistFilter.LIKED)
                            && !isSyncingRemoteArtists
                        ) viewModel.syncArtists()
                    },
                    modifier = Modifier.weight(1f),
                    isLoading = {
                        (it == ArtistFilter.LIBRARY || it == ArtistFilter.LIKED)
                                && isSyncingRemoteArtists
                    }
                )

                IconButton(
                    onClick = {
                        artistViewType = artistViewType.toggle()
                    },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Icon(
                        imageVector =
                            when (artistViewType) {
                                LibraryViewType.LIST -> Icons.AutoMirrored.Rounded.List
                                LibraryViewType.GRID -> Icons.Rounded.GridView
                            },
                        contentDescription = stringResource(if (artistViewType == LibraryViewType.LIST) R.string.view_as_list else R.string.view_as_grid)
                    )
                }
            }
        }
    }

    val headerContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            // The sort and, in Custom order, the lock right after it, as on the Playlists page.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                SortHeader(
                    sortType = sortType,
                    sortDescending = sortDescending,
                    onSortTypeChange = onSortTypeChange,
                    onSortDescendingChange = onSortDescendingChange,
                    sortTypeText = { sortType ->
                        when (sortType) {
                            ArtistSortType.CREATE_DATE -> R.string.sort_by_create_date
                            ArtistSortType.NAME -> R.string.sort_by_name
                            ArtistSortType.SONG_COUNT -> R.string.sort_by_song_count
                            ArtistSortType.CUSTOM -> R.string.sort_by_custom
                        }
                    },
                    offered = { it != ArtistSortType.CUSTOM || Unreleased.PLAYLIST_ORDER },
                )
                // The page's own lock, beside the sort as the playlists' is: whether the rows can be dragged.
                if (customOrder) {
                    IconButton(
                        onClick = { orderLocked = !orderLocked },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (orderLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = stringResource(if (orderLocked) R.string.playlist_order_locked else R.string.playlist_order_unlocked)
                        )
                    }
                }
            }

            shownArtists?.let { artists ->
                Text(
                    text = pluralStringResource(R.plurals.n_artist, artists.size, artists.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                ActionDropdown(
                    actions = listOf(
                        DropdownItem(
                            title = stringResource(R.string.library_filter),
                            leadingIcon = { Icon(Icons.Rounded.FilterAlt, null) },
                            action = {},
                            secondaryDropdown =
                                listOf(
                                    DropdownItem(
                                        title = stringResource(R.string.filter_liked),
                                        leadingIcon = null,
                                        action = { filter = ArtistFilter.LIKED }
                                    ),
                                    DropdownItem(
                                        title = stringResource(R.string.filter_library),
                                        leadingIcon = null,
                                        action = { filter = ArtistFilter.LIBRARY }
                                    ),
                                    DropdownItem(
                                        title = stringResource(R.string.filter_downloaded),
                                        leadingIcon = null,
                                        action = { filter = ArtistFilter.DOWNLOADED }
                                    ),
                                )
                        ),
                    ),
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = pullRefreshState,
                isRefreshing = isSyncingRemoteArtists,
                onRefresh = {
                    viewModel.syncArtists(true)
                }
            ),
    ) {
        when (viewType) {
            LibraryViewType.LIST -> ScrollToTopManager(navController, lazyListState)
            LibraryViewType.GRID -> ScrollToTopManager(navController, lazyGridState)
        }
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(
                        key = "filter",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        if (oneHeaderLine) {
                            SideBySide({ libraryFilterContent?.let { it() } ?: filterContent() }, { headerContent() })
                        } else {
                            libraryFilterContent?.let { it() } ?: filterContent()
                        }
                    }

                    if (!oneHeaderLine) item(
                        key = "header",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        headerContent()
                    }

                    shownArtists?.let { artists ->
                        if (artists.isEmpty()) {
                            item {
                                EmptyPlaceholder(
                                    icon = R.drawable.artist,
                                    text = stringResource(R.string.library_artist_empty),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        itemsInColumns(
                            items = artists,
                            columns = columns,
                            key = { _, artist -> artist.id },
                            contentType = { _, _ -> CONTENT_TYPE_ARTIST }
                        ) { _, artist ->
                            if (canDrag && columns == 1) {
                                ReorderableItem(state = reorderableListState, key = artist.id) {
                                    LibraryArtistListItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        artist = artist,
                                        dragHandle = Modifier.draggableHandle(),
                                    )
                                }
                            } else {
                                LibraryArtistListItem(
                                    navController = navController,
                                    menuState = menuState,
                                    coroutineScope = coroutineScope,
                                    modifier = Modifier.animateItem(),
                                    artist = artist
                                )
                            }
                        }
                    }
                }
                LazyColumnScrollbar(
                    state = lazyListState,
                )
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(
                        key = "filter",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        if (oneHeaderLine) {
                            SideBySide({ libraryFilterContent?.let { it() } ?: filterContent() }, { headerContent() })
                        } else {
                            libraryFilterContent?.let { it() } ?: filterContent()
                        }
                    }

                    if (!oneHeaderLine) item(
                        key = "header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        headerContent()
                    }

                    shownArtists?.let { artists ->
                        if (artists.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyPlaceholder(
                                    icon = R.drawable.artist,
                                    text = stringResource(R.string.library_artist_empty),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                        items(
                            items = artists,
                            key = { it.id },
                            contentType = { CONTENT_TYPE_ARTIST }
                        ) { artist ->
                            if (canDrag) {
                                ReorderableItem(state = reorderableGridState, key = artist.id) {
                                    LibraryArtistGridItem(
                                        navController = navController,
                                        menuState = menuState,
                                        coroutineScope = coroutineScope,
                                        artist = artist,
                                        dragHandle = Modifier.draggableHandle(),
                                    )
                                }
                            } else {
                                LibraryArtistGridItem(
                                    navController = navController,
                                    menuState = menuState,
                                    coroutineScope = coroutineScope,
                                    modifier = Modifier.animateItem(),
                                    artist = artist
                                )
                            }
                        }
                    }
                }
                LazyVerticalGridScrollbar(
                    state = lazyGridState,
                )
            }
        }

        Indicator(
            isRefreshing = isSyncingRemoteArtists,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }
}
