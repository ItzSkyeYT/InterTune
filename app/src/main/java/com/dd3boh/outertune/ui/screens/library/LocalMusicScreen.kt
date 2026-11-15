/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.LibrarySortDescendingKey
import com.dd3boh.outertune.constants.LibrarySortType
import com.dd3boh.outertune.constants.LibrarySortTypeKey
import com.dd3boh.outertune.constants.LibraryViewType
import com.dd3boh.outertune.constants.LibraryViewTypeKey
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.ChipsLazyRow
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.HideOnScrollFAB
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.LazyVerticalGridScrollbar
import com.dd3boh.outertune.ui.component.LibraryAlbumGridItem
import com.dd3boh.outertune.ui.component.LibraryAlbumListItem
import com.dd3boh.outertune.ui.component.LibraryArtistGridItem
import com.dd3boh.outertune.ui.component.LibraryArtistListItem
import com.dd3boh.outertune.ui.component.LibraryPlaylistGridItem
import com.dd3boh.outertune.ui.component.LibraryPlaylistListItem
import com.dd3boh.outertune.ui.component.ScreenBackHandler
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.StoragePermissionBanner
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.dialog.CreatePlaylistDialog
import com.dd3boh.outertune.ui.menu.GenreMenu
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.FsItem
import com.dd3boh.outertune.viewmodels.LocalCategory
import com.dd3boh.outertune.viewmodels.LocalGenre
import com.dd3boh.outertune.viewmodels.LocalMusicViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val activeCategory by viewModel.activeCategory.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()

    val songs by viewModel.songs.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val localGenres by viewModel.localGenres.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val songsByYear by viewModel.songsByYear.collectAsState()
    val fsCurrentPath by viewModel.fsCurrentPath.collectAsState()
    val fsItems by viewModel.fsItems.collectAsState()

    var viewType by rememberEnumPreference(LibraryViewTypeKey, LibraryViewType.GRID)
    val localLibEnable by rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val (songSortType, onSongSortTypeChange) = rememberEnumPreference(SongSortTypeKey, SongSortType.CREATE_DATE)
    val (songSortDescending, onSongSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)
    val (albumSortType, onAlbumSortTypeChange) = rememberEnumPreference(LibrarySortTypeKey, LibrarySortType.CREATE_DATE)
    val (albumSortDescending, onAlbumSortDescendingChange) = rememberPreference(LibrarySortDescendingKey, true)

    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(onDismiss = { showCreatePlaylistDialog = false })
    }

    // multiselect for songs
    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    if (inSelectMode) {
        ScreenBackHandler(onBack = onExitSelectionMode)
    } else if (selectedGenre != null) {
        ScreenBackHandler(onBack = { viewModel.selectedGenre.value = null })
    }

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()

    var showStoragePerm by remember {
        mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
    }

    val categories = remember {
        LocalCategory.entries.map { it to context.getString(it.titleId) }
    }

    // Header content with category chips and optional tools (view toggle, scanner)
    // Placed as the first item inside each LazyColumn/LazyVerticalGrid so it scrolls together
    // with the content below the floating search bar.
    val categoryFilterContent = @Composable {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (localLibEnable && showStoragePerm) {
                StoragePermissionBanner(
                    onClick = {
                        showStoragePerm = false
                        (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                ChipsLazyRow(
                    chips = categories,
                    currentValue = activeCategory,
                    onValueUpdate = {
                        viewModel.activeCategory.value = it
                        if (it != LocalCategory.GENRES) {
                            viewModel.selectedGenre.value = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                // View type toggle (for albums/artists/playlists)
                if (activeCategory == LocalCategory.ALBUMS || activeCategory == LocalCategory.ARTISTS || activeCategory == LocalCategory.PLAYLISTS) {
                    IconButton(
                        onClick = { viewType = viewType.toggle() },
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Icon(
                            imageVector = when (viewType) {
                                LibraryViewType.LIST -> Icons.AutoMirrored.Rounded.List
                                LibraryViewType.GRID -> Icons.Rounded.GridView
                            },
                            contentDescription = stringResource(
                                if (viewType == LibraryViewType.LIST) R.string.view_as_list else R.string.view_as_grid
                            )
                        )
                    }
                }

                // Scanner shortcut icon
                IconButton(
                    onClick = { navController.navigate("settings/local") },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(
                        Icons.Rounded.SdCard,
                        contentDescription = stringResource(R.string.scanner_local_title)
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (activeCategory) {
            LocalCategory.SONGS -> {
                ScrollToTopManager(navController, lazyListState)
                LocalSongsContent(
                    filterContent = categoryFilterContent,
                    songs = songs,
                    navController = navController,
                    isPlaying = isPlaying,
                    mediaMetadata = mediaMetadata,
                    playerConnection = playerConnection,
                    inSelectMode = inSelectMode,
                    selection = selection,
                    onSelectSong = { songId, selected ->
                        inSelectMode = true
                        if (selected) selection.add(songId) else selection.remove(songId)
                    },
                    swipeEnabled = swipeEnabled,
                    thumbnailSize = thumbnailSize,
                    sortType = songSortType,
                    sortDescending = songSortDescending,
                    onSortTypeChange = onSongSortTypeChange,
                    onSortDescendingChange = onSongSortDescendingChange,
                    lazyListState = lazyListState
                )
            }

            LocalCategory.ARTISTS -> {
                when (viewType) {
                    LibraryViewType.LIST -> ScrollToTopManager(navController, lazyListState)
                    LibraryViewType.GRID -> ScrollToTopManager(navController, lazyGridState)
                }
                LocalArtistsContent(
                    filterContent = categoryFilterContent,
                    artists = artists,
                    viewType = viewType,
                    navController = navController,
                    coroutineScope = coroutineScope,
                    lazyListState = lazyListState,
                    lazyGridState = lazyGridState
                )
            }

            LocalCategory.ALBUMS -> {
                when (viewType) {
                    LibraryViewType.LIST -> ScrollToTopManager(navController, lazyListState)
                    LibraryViewType.GRID -> ScrollToTopManager(navController, lazyGridState)
                }
                LocalAlbumsContent(
                    filterContent = categoryFilterContent,
                    albums = albums,
                    viewType = viewType,
                    sortType = albumSortType,
                    sortDescending = albumSortDescending,
                    onSortTypeChange = onAlbumSortTypeChange,
                    onSortDescendingChange = onAlbumSortDescendingChange,
                    navController = navController,
                    coroutineScope = coroutineScope,
                    isPlaying = isPlaying,
                    mediaMetadata = mediaMetadata,
                    lazyListState = lazyListState,
                    lazyGridState = lazyGridState
                )
            }

            LocalCategory.GENRES -> {
                if (selectedGenre == null) {
                    ScrollToTopManager(navController, lazyListState)
                }
                LocalGenresContent(
                    filterContent = categoryFilterContent,
                    genres = localGenres,
                    selectedGenre = selectedGenre,
                    onSelectGenre = { viewModel.selectedGenre.value = it },
                    onBackFromGenre = { viewModel.selectedGenre.value = null },
                    navController = navController,
                    isPlaying = isPlaying,
                    mediaMetadata = mediaMetadata,
                    playerConnection = playerConnection,
                    coroutineScope = coroutineScope,
                    swipeEnabled = swipeEnabled,
                    thumbnailSize = thumbnailSize,
                    lazyListState = lazyListState
                )
            }

            LocalCategory.DATES -> {
                ScrollToTopManager(navController, lazyListState)
                LocalDatesContent(
                    filterContent = categoryFilterContent,
                    songsByYear = songsByYear,
                    navController = navController,
                    isPlaying = isPlaying,
                    mediaMetadata = mediaMetadata,
                    playerConnection = playerConnection,
                    swipeEnabled = swipeEnabled,
                    thumbnailSize = thumbnailSize,
                    lazyListState = lazyListState
                )
            }

            LocalCategory.FOLDERS -> {
                // Directly embed the established folder browser with the category chips header
                FolderScreen(navController, scrollBehavior, isRoot = true, libraryFilterContent = categoryFilterContent)
            }

            LocalCategory.FILESYSTEM -> {
                ScrollToTopManager(navController, lazyListState)
                LocalFilesystemContent(
                    filterContent = categoryFilterContent,
                    currentPath = fsCurrentPath,
                    items = fsItems,
                    canNavigateUp = viewModel.canNavigateUpFs(),
                    onNavigateUp = { viewModel.navigateUpFs() },
                    onNavigateToDir = { viewModel.navigateToFs(it) },
                    lazyListState = lazyListState,
                    onPlayFile = { fsItem ->
                        val targetMetadata = fsItem.matchedSong?.toMediaMetadata() ?: MediaMetadata(
                            id = fsItem.file.absolutePath,
                            title = fsItem.file.nameWithoutExtension,
                            artists = listOf(MediaMetadata.Artist(id = null, name = fsItem.file.parentFile?.name ?: "Local", isLocal = true)),
                            duration = 0,
                            isLocal = true,
                            localPath = fsItem.file.absolutePath,
                            genre = null
                        )
                        playerConnection.playQueue(
                            ListQueue(
                                title = fsCurrentPath.name,
                                items = listOf(targetMetadata),
                                startIndex = 0,
                                songPicked = true
                            ),
                            origin = PlayOrigin.LIBRARY
                        )
                    }
                )
            }

            LocalCategory.PLAYLISTS -> {
                when (viewType) {
                    LibraryViewType.LIST -> ScrollToTopManager(navController, lazyListState)
                    LibraryViewType.GRID -> ScrollToTopManager(navController, lazyGridState)
                }
                LocalPlaylistsContent(
                    filterContent = categoryFilterContent,
                    playlists = playlists,
                    viewType = viewType,
                    navController = navController,
                    coroutineScope = coroutineScope,
                    lazyListState = lazyListState,
                    lazyGridState = lazyGridState
                )
            }
        }

        // Multiselect Floating Footer for songs
        FloatingFooter(inSelectMode) {
            SelectHeader(
                navController = navController,
                selectedItems = selection.mapNotNull { id -> songs.find { it.id == id }?.toMediaMetadata() },
                totalItemCount = songs.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(songs.map { it.id })
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode
            )
        }

        // FAB to add playlist when in Playlists category
        if (activeCategory == LocalCategory.PLAYLISTS) {
            when (viewType) {
                LibraryViewType.LIST -> HideOnScrollFAB(
                    lazyListState = lazyListState,
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.create_playlist),
                    onClick = { showCreatePlaylistDialog = true }
                )

                LibraryViewType.GRID -> HideOnScrollFAB(
                    lazyListState = lazyGridState,
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.create_playlist),
                    onClick = { showCreatePlaylistDialog = true }
                )
            }
        }
    }
}

@Composable
private fun LocalSongsContent(
    filterContent: @Composable () -> Unit,
    songs: List<Song>,
    navController: NavController,
    isPlaying: Boolean,
    mediaMetadata: MediaMetadata?,
    playerConnection: com.dd3boh.outertune.playback.PlayerConnection,
    inSelectMode: Boolean,
    selection: List<String>,
    onSelectSong: (String, Boolean) -> Unit,
    swipeEnabled: Boolean,
    thumbnailSize: Int,
    sortType: SongSortType,
    sortDescending: Boolean,
    onSortTypeChange: (SongSortType) -> Unit,
    onSortDescendingChange: (Boolean) -> Unit,
    lazyListState: LazyListState
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                filterContent()
            }

            if (songs.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.MusicNote,
                        text = stringResource(R.string.no_local_songs)
                    )
                }
            } else {
                item(key = "header_actions", contentType = CONTENT_TYPE_HEADER) {
                    Column {
                        if (!inSelectMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = "Local Songs",
                                                items = songs.map { it.toMediaMetadata() },
                                                startIndex = 0
                                            ),
                                            origin = PlayOrigin.LIBRARY
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.play_all))
                                }

                                FilledTonalButton(
                                    onClick = {
                                        val shuffled = songs.shuffled()
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = "Local Songs",
                                                items = shuffled.map { it.toMediaMetadata() },
                                                startIndex = 0
                                            ),
                                            origin = PlayOrigin.LIBRARY
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.shuffle_all))
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            SortHeader(
                                sortType = sortType,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortTypeChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { type ->
                                    when (type) {
                                        SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                        SongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                                        SongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                                        SongSortType.NAME -> R.string.sort_by_name
                                        SongSortType.ARTIST -> R.string.sort_by_artist
                                        SongSortType.PLAY_COUNT -> R.string.sort_by_play_count
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    SongListItem(
                        song = song,
                        navController = navController,
                        isActive = song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        inSelectMode = inSelectMode,
                        isSelected = selection.contains(song.id),
                        onSelectedChange = { onSelectSong(song.id, it) },
                        swipeEnabled = swipeEnabled,
                        thumbnailSize = thumbnailSize,
                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = "Local Songs",
                                    items = songs.map { it.toMediaMetadata() },
                                    startIndex = index,
                                    songPicked = true
                                ),
                                origin = PlayOrigin.LIBRARY
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        LazyColumnScrollbar(state = lazyListState)
    }
}

@Composable
private fun LocalArtistsContent(
    filterContent: @Composable () -> Unit,
    artists: List<com.dd3boh.outertune.db.entities.Artist>,
    viewType: LibraryViewType,
    navController: NavController,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    lazyListState: LazyListState,
    lazyGridState: LazyGridState
) {
    val menuState = LocalMenuState.current

    Box(modifier = Modifier.fillMaxSize()) {
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (artists.isEmpty()) {
                        item(key = "empty") {
                            EmptyPlaceholder(
                                icon = R.drawable.artist,
                                text = stringResource(R.string.no_local_artists)
                            )
                        }
                    } else {
                        item(key = "header", contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.n_artist, artists.size, artists.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = artists, key = { it.id }) { artist ->
                            LibraryArtistListItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                artist = artist,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyColumnScrollbar(state = lazyListState)
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (artists.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyPlaceholder(
                                icon = R.drawable.artist,
                                text = stringResource(R.string.no_local_artists)
                            )
                        }
                    } else {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.n_artist, artists.size, artists.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = artists, key = { it.id }) { artist ->
                            LibraryArtistGridItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                artist = artist,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyVerticalGridScrollbar(state = lazyGridState)
            }
        }
    }
}

@Composable
private fun LocalAlbumsContent(
    filterContent: @Composable () -> Unit,
    albums: List<com.dd3boh.outertune.db.entities.Album>,
    viewType: LibraryViewType,
    sortType: LibrarySortType,
    sortDescending: Boolean,
    onSortTypeChange: (LibrarySortType) -> Unit,
    onSortDescendingChange: (Boolean) -> Unit,
    navController: NavController,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    isPlaying: Boolean,
    mediaMetadata: MediaMetadata?,
    lazyListState: LazyListState,
    lazyGridState: LazyGridState
) {
    val menuState = LocalMenuState.current

    Box(modifier = Modifier.fillMaxSize()) {
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (albums.isEmpty()) {
                        item(key = "empty") {
                            EmptyPlaceholder(
                                icon = Icons.Rounded.Album,
                                text = stringResource(R.string.no_local_albums)
                            )
                        }
                    } else {
                        item(key = "header", contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { type ->
                                        when (type) {
                                            LibrarySortType.CREATE_DATE -> R.string.sort_by_create_date
                                            LibrarySortType.NAME -> R.string.sort_by_name
                                            LibrarySortType.CUSTOM -> R.string.sort_by_custom
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = pluralStringResource(R.plurals.n_album, albums.size, albums.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = albums, key = { it.id }) { album ->
                            LibraryAlbumListItem(
                                navController = navController,
                                menuState = menuState,
                                album = album,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyColumnScrollbar(state = lazyListState)
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (albums.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyPlaceholder(
                                icon = Icons.Rounded.Album,
                                text = stringResource(R.string.no_local_albums)
                            )
                        }
                    } else {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { type ->
                                        when (type) {
                                            LibrarySortType.CREATE_DATE -> R.string.sort_by_create_date
                                            LibrarySortType.NAME -> R.string.sort_by_name
                                            LibrarySortType.CUSTOM -> R.string.sort_by_custom
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = pluralStringResource(R.plurals.n_album, albums.size, albums.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = albums, key = { it.id }) { album ->
                            LibraryAlbumGridItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                album = album,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyVerticalGridScrollbar(state = lazyGridState)
            }
        }
    }
}

@Composable
private fun LocalGenresContent(
    filterContent: @Composable () -> Unit,
    genres: List<LocalGenre>,
    selectedGenre: LocalGenre?,
    onSelectGenre: (LocalGenre) -> Unit,
    onBackFromGenre: () -> Unit,
    navController: NavController,
    isPlaying: Boolean,
    mediaMetadata: MediaMetadata?,
    playerConnection: com.dd3boh.outertune.playback.PlayerConnection,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    swipeEnabled: Boolean,
    thumbnailSize: Int,
    lazyListState: LazyListState,
) {
    val menuState = LocalMenuState.current

    if (selectedGenre != null) {
        // Show songs inside the selected genre
        Box(modifier = Modifier.fillMaxSize()) {
            val genreSongsListState = rememberLazyListState()
            LazyColumn(
                state = genreSongsListState,
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                    filterContent()
                }

                item(key = "genre_header", contentType = CONTENT_TYPE_HEADER) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        IconButton(onClick = onBackFromGenre) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = selectedGenre.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pluralStringResource(R.plurals.n_song, selectedGenre.songCount, selectedGenre.songCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (selectedGenre.songs.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = selectedGenre.name,
                                            items = selectedGenre.songs.map { it.toMediaMetadata() },
                                            startIndex = 0
                                        ),
                                        origin = PlayOrigin.LIBRARY
                                    )
                                }
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.play_all))
                            }
                            IconButton(
                                onClick = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = selectedGenre.name,
                                            items = selectedGenre.songs.shuffled().map { it.toMediaMetadata() },
                                            startIndex = 0
                                        ),
                                        origin = PlayOrigin.LIBRARY
                                    )
                                }
                            ) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.shuffle))
                            }
                        }
                    }
                }

                if (selectedGenre.songs.isEmpty()) {
                    item(key = "empty") {
                        EmptyPlaceholder(icon = Icons.Rounded.MusicNote, text = stringResource(R.string.no_local_songs))
                    }
                } else {
                    itemsIndexed(selectedGenre.songs, key = { _, s -> s.id }) { index, song ->
                        SongListItem(
                            song = song,
                            navController = navController,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            inSelectMode = false,
                            isSelected = false,
                            onSelectedChange = {},
                            swipeEnabled = swipeEnabled,
                            thumbnailSize = thumbnailSize,
                            onPlay = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = selectedGenre.name,
                                        items = selectedGenre.songs.map { it.toMediaMetadata() },
                                        startIndex = index,
                                        songPicked = true
                                    ),
                                    origin = PlayOrigin.LIBRARY
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            LazyColumnScrollbar(state = genreSongsListState)
        }
    } else {
        // Gramophone-style vertical list of genres
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                    filterContent()
                }

                if (genres.isEmpty()) {
                    item(key = "empty") {
                        EmptyPlaceholder(icon = Icons.Rounded.Category, text = stringResource(R.string.no_local_genres))
                    }
                } else {
                    items(genres, key = { it.name }) { genre ->
                        val density = LocalDensity.current
                        val px = (ListThumbnailSize.value * density.density).roundToInt()
                        ListItem(
                            title = genre.name,
                            subtitle = pluralStringResource(R.plurals.n_song, genre.songCount, genre.songCount),
                            thumbnailContent = {
                                if (genre.coverPath != null) {
                                    AsyncImage(
                                        model = if (genre.coverPath.startsWith("/storage")) {
                                            LocalArtworkPath(genre.coverPath, px, px)
                                        } else {
                                            genre.coverPath
                                        },
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(ListThumbnailSize)
                                            .clip(RoundedCornerShape(ThumbnailCornerRadius))
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(ThumbnailCornerRadius),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(ListThumbnailSize)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Category,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                val haptic = LocalHapticFeedback.current
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            GenreMenu(
                                                genre = genre,
                                                coroutineScope = coroutineScope,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.options)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectGenre(genre) }
                        )
                    }
                }
            }
            LazyColumnScrollbar(state = lazyListState)
        }
    }
}

@Composable
private fun LocalDatesContent(
    filterContent: @Composable () -> Unit,
    songsByYear: Map<String, List<Song>>,
    navController: NavController,
    isPlaying: Boolean,
    mediaMetadata: MediaMetadata?,
    playerConnection: com.dd3boh.outertune.playback.PlayerConnection,
    swipeEnabled: Boolean,
    thumbnailSize: Int,
    lazyListState: LazyListState
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
        ) {
            item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                filterContent()
            }

            if (songsByYear.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(icon = Icons.Rounded.DateRange, text = stringResource(R.string.no_local_dates))
                }
            } else {
                songsByYear.forEach { (year, yearSongs) ->
                    item(key = "year_$year", contentType = CONTENT_TYPE_HEADER) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Text(
                                    text = year,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.n_song, yearSongs.size, yearSongs.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            Row {
                                IconButton(
                                    onClick = {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = year,
                                                items = yearSongs.map { it.toMediaMetadata() },
                                                startIndex = 0
                                            ),
                                            origin = PlayOrigin.LIBRARY
                                        )
                                    }
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.play_all))
                                }
                            }
                        }
                    }

                    itemsIndexed(yearSongs, key = { _, s -> "date_${year}_${s.id}" }) { index, song ->
                        SongListItem(
                            song = song,
                            navController = navController,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            inSelectMode = false,
                            isSelected = false,
                            onSelectedChange = {},
                            swipeEnabled = swipeEnabled,
                            thumbnailSize = thumbnailSize,
                            onPlay = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = year,
                                        items = yearSongs.map { it.toMediaMetadata() },
                                        startIndex = index,
                                        songPicked = true
                                    ),
                                    origin = PlayOrigin.LIBRARY
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        LazyColumnScrollbar(state = lazyListState)
    }
}

@Composable
private fun LocalFilesystemContent(
    filterContent: @Composable () -> Unit,
    currentPath: java.io.File,
    items: List<FsItem>,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onNavigateToDir: (java.io.File) -> Unit,
    onPlayFile: (FsItem) -> Unit,
    lazyListState: LazyListState
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
        ) {
            item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                filterContent()
            }

            // Path breadcrumbs / Current directory pill
            item(key = "fs_breadcrumb", contentType = CONTENT_TYPE_HEADER) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentPath.absolutePath,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(icon = Icons.Rounded.Folder, text = stringResource(R.string.empty_directory))
                }
            } else {
                // Parent folder item
                if (canNavigateUp) {
                    item(key = "parent_dir") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateUp() }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = ".. (${stringResource(R.string.parent_folder)})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                items(items, key = { it.file.absolutePath }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.isDirectory) {
                                    onNavigateToDir(item.file)
                                } else if (item.isAudio) {
                                    onPlayFile(item)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                item.isDirectory -> Icons.Rounded.Folder
                                item.isAudio -> Icons.Rounded.MusicNote
                                else -> Icons.Rounded.Storage
                            },
                            contentDescription = null,
                            tint = when {
                                item.isDirectory -> MaterialTheme.colorScheme.primary
                                item.isAudio -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when {
                                item.isDirectory -> ""
                                item.size > 0 -> Formatter.formatFileSize(context, item.size)
                                else -> ""
                            }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(state = lazyListState)
    }
}

@Composable
private fun LocalPlaylistsContent(
    filterContent: @Composable () -> Unit,
    playlists: List<com.dd3boh.outertune.db.entities.Playlist>,
    viewType: LibraryViewType,
    navController: NavController,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    lazyListState: LazyListState,
    lazyGridState: LazyGridState
) {
    val menuState = LocalMenuState.current

    Box(modifier = Modifier.fillMaxSize()) {
        when (viewType) {
            LibraryViewType.LIST -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (playlists.isEmpty()) {
                        item(key = "empty") {
                            EmptyPlaceholder(
                                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                text = stringResource(R.string.no_local_playlists)
                            )
                        }
                    } else {
                        item(key = "header", contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.n_playlist, playlists.size, playlists.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = playlists, key = { it.id }) { playlist ->
                            LibraryPlaylistListItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                playlist = playlist,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyColumnScrollbar(state = lazyListState)
            }

            LibraryViewType.GRID -> {
                LazyVerticalGrid(
                    state = lazyGridState,
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                ) {
                    item(key = "filter", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                        filterContent()
                    }

                    if (playlists.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyPlaceholder(
                                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                text = stringResource(R.string.no_local_playlists)
                            )
                        }
                    } else {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.n_playlist, playlists.size, playlists.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        }

                        items(items = playlists, key = { it.id }) { playlist ->
                            LibraryPlaylistGridItem(
                                navController = navController,
                                menuState = menuState,
                                coroutineScope = coroutineScope,
                                playlist = playlist,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                LazyVerticalGridScrollbar(state = lazyGridState)
            }
        }
    }
}
