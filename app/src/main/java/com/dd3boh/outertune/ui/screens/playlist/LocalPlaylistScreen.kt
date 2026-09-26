package com.dd3boh.outertune.ui.screens.playlist

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastSumBy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.LocalSyncUtils
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AlbumCornerRadius
import com.dd3boh.outertune.constants.AlbumThumbnailSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.component.RecognitionSheet
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlaylistEditLockKey
import com.dd3boh.outertune.constants.PlaylistSongSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSongSortType
import com.dd3boh.outertune.constants.PlaylistSongSortTypeKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.extensions.move
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.AutoResizeText
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.component.FontSizeRange
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.TopBarActions
import com.dd3boh.outertune.ui.component.TopBarSearchField
import com.dd3boh.outertune.ui.component.TopBarTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.PlaylistThumbnail
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.getNSongsString
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.syncCoroutine
import com.dd3boh.outertune.viewmodels.LocalPlaylistViewModel
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LocalPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalPlaylistViewModel = hiltViewModel(),
) {
    Log.v("LocalPlaylistScreen", "P_RC-1")
    val context = LocalContext.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    val playlistWithSongs by viewModel.playlistWithSongs.collectAsState()
    val addQuery by viewModel.addQuery.collectAsState()
    val currentPlaylist = playlistWithSongs.first
    val addResults by viewModel.addResults.collectAsState()
    val addSearching by viewModel.addSearching.collectAsState()
    val justAdded by viewModel.justAdded.collectAsState()

    /**
     * Whether the screen is currently the add-songs screen rather than the playlist.
     *
     * Latched on rather than derived from the song count. Deriving it meant the search closed the
     * instant the first song landed, which is the one moment somebody is most likely to want a
     * second one. It stays until Done, or until the screen is left.
     */
    var addMode by rememberSaveable { mutableStateOf(false) }
    var showRecognition by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playlistWithSongs.first?.songCount) {
        if (playlistWithSongs.first?.songCount == 0) addMode = true
    }

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val mutableSongs = remember { mutableStateListOf<PlaylistSong>() }

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSongSortTypeKey, PlaylistSongSortType.CUSTOM)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSongSortDescendingKey, true)
    var locked by rememberPreference(PlaylistEditLockKey, defaultValue = false)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

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

    // search
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(query) {
        snapshotFlow { searchQuery }.debounce { 300L }.collectLatest {
            if (searchQuery.text != query.text) {
                searchQuery = query
            }
        }
    }

    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    } else if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    }

    val editable: Boolean =
        playlistWithSongs.first?.playlist?.isLocal == true || (playlistWithSongs.first?.playlist?.isEditable == true && syncMode == SyncMode.RW)

    // Rebuilt from the playlist whenever it changes, during a search as well. A search used to be
    // filtered only when its text changed, so its rows kept the positions they were drawn with: a
    // song removed from the results stayed listed, and the next removal from them moved whichever
    // song had since shifted into the old position, usually one the search was hiding.
    LaunchedEffect(playlistWithSongs.second, isSearching, searchQuery.text) {
        mutableSongs.apply {
            clear()
            addAll(
                if (isSearching) playlistSearchResults(playlistWithSongs.second, searchQuery.text)
                else playlistWithSongs.second
            )
        }
    }

    var showEditDialog by remember {
        mutableStateOf(false)
    }

    if (showRecognition && currentPlaylist != null) {
        RecognitionSheet(
            playlist = currentPlaylist,
            onDismiss = { showRecognition = false },
        )
    }

    if (showEditDialog) {
        playlistWithSongs.first?.playlist?.let { playlistEntity ->
            TextFieldDialog(
                icon = { Icon(imageVector = Icons.Rounded.Edit, contentDescription = null) },
                title = { Text(text = stringResource(R.string.edit_playlist)) },
                onDismiss = { showEditDialog = false },
                initialTextFieldValue = TextFieldValue(playlistEntity.name, TextRange(playlistEntity.name.length)),
                onDone = { name ->
                    database.query {
                        update(playlistEntity.copy(name = name))
                    }

                    viewModel.viewModelScope.launch(syncCoroutine) {
                        if (context.mayPushToYouTube()) {
                            playlistEntity.browseId?.let { YouTube.renamePlaylist(it, name) }
                        }
                    }
                }
            )
        }
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlistWithSongs.first?.playlist!!.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showRemoveDownloadDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        // Downloads only, as the dialog says. For a playlist that cannot be edited
                        // here this also emptied the playlist.
                        playlistWithSongs.second.forEach { song ->
                            downloadUtil.removeDownload(song.song.id)
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlistWithSongs.first?.playlist!!.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        database.query {
                            playlistWithSongs.first?.let { delete(it.playlist) }
                        }

                        // Signed out or in "Read only" this removes the copy here and nothing
                        // else. It used to delete the playlist from the YouTube Music account
                        // whatever the setting said, which cannot be undone.
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            if (context.mayPushToYouTube()) {
                                playlistWithSongs.first?.playlist?.let { playlist ->
                                    val browseId = playlist.browseId ?: return@let
                                    // Someone else's playlist cannot be deleted, only taken out
                                    // of the library, as the heart does.
                                    if (playlist.isEditable) YouTube.deletePlaylist(browseId)
                                    else YouTube.likePlaylist(browseId, false)
                                }
                            }
                        }

                        navController.popBackStack()
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    val lazyListState = rememberLazyListState()
    var dragInfo by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
//        scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    ) { from, to ->
        // The library hands back positions in the whole LazyColumn, not in the song list. They
        // used to be turned into song positions by taking off a fixed 2, for the playlist header
        // and the sort row, but that only holds on the plain playlist screen. With the add-songs
        // panel open the header, the search field and every search result all sit above the
        // songs, so a drag there moved the wrong rows and wrote that to the database, or ran off
        // the end of the list and crashed. Looking each song up by its key gives its real place
        // whatever is drawn above it. Only ReorderableItems can be drop targets, so both keys are
        // songs; the check is for the list being refilled from the database mid-drag.
        val fromSongIndex = mutableSongs.indexOfFirst { it.map.id == from.key }
        val toSongIndex = mutableSongs.indexOfFirst { it.map.id == to.key }
        if (fromSongIndex >= 0 && toSongIndex >= 0) {
            val currentDragInfo = dragInfo
            dragInfo = if (currentDragInfo == null) {
                fromSongIndex to toSongIndex
            } else {
                currentDragInfo.first to toSongIndex
            }

            mutableSongs.move(fromSongIndex, toSongIndex)
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                // The moves for YouTube Music are worked out here, from the order on screen, before
                // the local move is even queued. They used to come from reading the database back
                // afterwards on the assumption that it still held the old order, but the move runs
                // on Room's own thread and nothing waited for it, so the read could land on either
                // side of it. Landing after, it picked out whichever song had shifted into the
                // dragged one's old place and moved that on YouTube, in front of the wrong song.
                val youTubeMoves = if (playlistWithSongs.first?.playlist?.isLocal == false) {
                    youTubeMovesAfterDrag(mutableSongs.map { it.map.setVideoId }, from, to)
                } else {
                    emptyList()
                }
                database.transaction {
                    move(viewModel.playlistId, from, to)
                }
                if (youTubeMoves.isNotEmpty()) {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        viewModel.youTubeMoveLock.withLock {
                            playlistWithSongs.first?.playlist?.browseId?.let { browseId ->
                                youTubeMoves.forEach { (setVideoId, successorSetVideoId) ->
                                    YouTube.moveSongPlaylist(browseId, setVideoId, successorSetVideoId)
                                }
                            }
                        }
                    }
                }
                dragInfo = null
            }
        }
    }

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Log.v("LocalPlaylistScreen", "P_RC-2.1")
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            Log.v("LocalPlaylistScreen", "P_RC-2.2")
            playlistWithSongs.first?.let { playlist ->
                if (playlist.songCount == 0 || addMode) {
                    // An empty playlist used to be one line of text over nothing, with the header
                    // not even drawn, so it did not say which playlist you had just made and
                    // offered no way to put anything in it. The header stays, and the rest of the
                    // screen becomes the search for the first song.
                    item(
                        key = "playlist header",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        LocalPlaylistHeader(
                            onIdentifySong = { showRecognition = true },
                            playlist = playlist,
                            songs = playlistWithSongs.second,
                            onShowEditDialog = { showEditDialog = true },
                            onShowRemoveDownloadDialog = { showRemoveDownloadDialog = true },
                            snackbarHostState = snackbarHostState,
                            modifier = Modifier,
                        )
                    }

                    item(key = "add songs field", contentType = CONTENT_TYPE_HEADER) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.playlist_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (playlist.songCount > 0) {
                                    TextButton(onClick = {
                                        addMode = false
                                        viewModel.addQuery.value = ""
                                    }) {
                                        Text(stringResource(R.string.playlist_empty_done))
                                    }
                                }
                            }
                            TextField(
                                value = addQuery,
                                onValueChange = { viewModel.addQuery.value = it },
                                placeholder = { Text(stringResource(R.string.playlist_empty_search_hint)) },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // The other way to fill a playlist: hold the phone up. Sits beside the
                            // search rather than behind a menu, because it is the whole point of
                            // the feature and nobody hunts for a microphone in an overflow.
                            OutlinedButton(
                                onClick = { showRecognition = true },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(R.string.recognition_identify_song),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    if (addSearching) {
                        item(key = "add searching") {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp)
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    items(items = addResults, key = { "add_" + it.id }) { song ->
                        val added = song.id in justAdded
                        YouTubeListItem(
                            item = song,
                            isActive = false,
                            isPlaying = false,
                            trailingContent = {
                                // Stays as a tick rather than disappearing. A row that vanishes
                                // under the finger loses your place in the results.
                                IconButton(
                                    enabled = !added,
                                    onClick = { viewModel.addSong(playlist, song) }
                                ) {
                                    Icon(
                                        imageVector = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { if (!added) viewModel.addSong(playlist, song) }
                        )
                    }

                    // The prompt to add a first song only belongs above an empty playlist. The
                    // songs already in it are listed below this panel, so showing "add your first
                    // song" over two of them read as the app having lost them.
                    if (!addSearching && addResults.isEmpty()) {
                        if (addQuery.isNotBlank()) {
                            item(key = "add no results") {
                                EmptyPlaceholder(
                                    icon = Icons.Rounded.MusicNote,
                                    text = stringResource(R.string.playlist_empty_no_results),
                                )
                            }
                        } else if (playlist.songCount == 0) {
                            item(key = "add empty hint") {
                                EmptyPlaceholder(
                                    icon = Icons.Rounded.MusicNote,
                                    text = stringResource(R.string.playlist_empty_hint),
                                )
                            }
                        }
                    }
                } else {
                    // playlist header
                    if (!isSearching) {
                        item(
                            key = "playlist header",
                            contentType = CONTENT_TYPE_HEADER
                        ) {
                            LocalPlaylistHeader(
                                onIdentifySong = { showRecognition = true },
                                playlist = playlist,
                                songs =  playlistWithSongs.second,
                                onShowEditDialog = { showEditDialog = true },
                                onShowRemoveDownloadDialog = { showRemoveDownloadDialog = true },
                                snackbarHostState = snackbarHostState,
                                modifier = Modifier // .animateItem()
                            )
                        }
                    }

                    item(
                        key = "action header",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp)
                        ) {
                            SortHeader(
                                sortType = sortType,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortTypeChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { sortType ->
                                    when (sortType) {
                                        PlaylistSongSortType.CUSTOM -> R.string.sort_by_custom
                                        PlaylistSongSortType.NAME -> R.string.sort_by_name
                                        PlaylistSongSortType.ARTIST -> R.string.sort_by_artist
                                        PlaylistSongSortType.ADDED_DATE -> R.string.sort_by_create_date
                                        PlaylistSongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                                        PlaylistSongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            if (editable && !(inSelectMode || isSearching)) {
                                IconButton(
                                    onClick = { locked = !locked },
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // songs
            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = mutableSongs,
                key = { _, song -> song.map.id },
                contentType = { _, song -> CONTENT_TYPE_SONG },
            ) { index, song ->
                ReorderableItem(
                    state = reorderableState,
                    key = song.map.id,
                    enabled = editable
                ) {
                    SongListItem(
                        song = song.song,
                        thumbnailSize = thumbnailSize,
                        playlistSong = song,
                        playlist =  playlistWithSongs.first,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = song.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        swipeEnabled = swipeEnabled,
                        onSelectedChange = {
                            inSelectMode = true
                            if (it) {
                                selection.add(song.song.id)
                            } else {
                                selection.remove(song.song.id)
                            }
                        },
                        inSelectMode = inSelectMode,
                        isSelected = selection.contains(song.song.id),

                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title =  playlistWithSongs.first!!.playlist.name,
                                    items = mutableSongs.map { it.song.toMediaMetadata() },
                                    startIndex = index,
                                    playlistId =  playlistWithSongs.first?.playlist?.browseId
                                ),
                                origin = PlayOrigin.PLAYLIST,
                            )
                        },
                        dragHandleModifier = if (sortType == PlaylistSongSortType.CUSTOM && !locked && !isSearching && editable) Modifier.draggableHandle() else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }

        LazyColumnScrollbar(
            state = lazyListState,
        )

        FloatingTopBar(
            titleContent = {
                if (isSearching) {
                    TopBarSearchField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                } else if (showTopBarTitle) {
                    TopBarTitle(playlistWithSongs.first?.playlist?.name.orEmpty())
                }
            },
            navController = navController,
            // Holding back does nothing while searching, as it always did here.
            onLongBack = { if (!isSearching) navController.backToMain() },
            onBack = {
                if (isSearching) {
                    isSearching = false
                    query = TextFieldValue()
                } else {
                    navController.navigateUp()
                }
            },
            actions = {
                if (!isSearching) {
                    TopBarActions {
                        IconButton(
                            onClick = {
                                isSearching = true
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            windowInsets = TopAppBarDefaults.windowInsets,
        )

        FloatingFooter(inSelectMode) {
            // While searching, mutableSongs holds just the results, and Select all must not reach
            // songs the search is hiding. Outside a search it is the whole playlist, as before.
            val shownSongs = if (isSearching) mutableSongs else playlistWithSongs.second
            SelectHeader(
                navController = navController,
                selectedItems = selection.mapNotNull { id ->
                    playlistWithSongs.second.find { it.song.id == id }?.song
                }.map { it.toMediaMetadata() },
                totalItemCount = shownSongs.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(shownSongs.map { it.song.id })
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode,
                onRemoveFromPlaylist = if (!editable) null else {
                    {
                        // Snapshot the maps before touching the db, since removing a row renumbers
                        // the ones after it. Descending position order means each removal only
                        // shifts rows we have already handled, so the captured positions stay valid.
                        val doomed = playlistWithSongs.second
                            .filter { it.song.id in selection }
                            .sortedByDescending { it.map.position }

                        database.transaction {
                            doomed.forEach { playlistSong ->
                                move(playlistSong.map.playlistId, playlistSong.map.position, Int.MAX_VALUE)
                                delete(playlistSong.map.copy(position = Int.MAX_VALUE))
                            }
                        }

                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            playlistWithSongs.first?.playlist?.browseId?.let { playlistId ->
                                doomed.forEach { playlistSong ->
                                    playlistSong.map.setVideoId?.let { setVideoId ->
                                        YouTube.removeFromPlaylist(
                                            playlistId, playlistSong.map.songId, setVideoId
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
//                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime))
                .align(Alignment.BottomCenter)
        )
    }
}


@Composable
fun LocalPlaylistHeader(
    onIdentifySong: () -> Unit = {},
    playlist: Playlist,
    songs: List<PlaylistSong>,
    onShowEditDialog: () -> Unit,
    onShowRemoveDownloadDialog: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    Log.v("LocalPlaylistScreen", "P_H_RC-1")
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val isNetworkConnected = LocalNetworkConnected.current
    val scope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current

    val playlistLength = remember(songs) {
        songs.fastSumBy { it.song.song.duration }
    }

    val downloadUtil = LocalDownloadUtil.current
    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

//    LaunchedEffect(songs) {
//        val songs = songs.filterNot { it.song.song.isLocal }
//        if (songs.isEmpty()) return@LaunchedEffect
//        downloadUtil.downloads.collect { downloads ->
//            downloadState = getDownloadState(songs.map { downloads[it.song.id] })
//        }
//    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(12.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistThumbnail(
                playlist = playlist.playlist,
                thumbnails = playlist.thumbnails,
                size = AlbumThumbnailSize,
                shape = RoundedCornerShape(AlbumCornerRadius),
                iconPadding = AlbumThumbnailSize / 16,
                iconTint = LocalContentColor.current.copy(alpha = 0.8f),
            )

            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                AutoResizeText(
                    text = playlist.playlist.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSizeRange = FontSizeRange(16.sp, 22.sp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playlist.downloadCount > 0) {
                        Icon(
                            imageVector = Icons.Rounded.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp)
                        )
                    }

                    Text(
                        text = getNSongsString(songs.size, playlist.downloadCount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal
                    )
                }

                Text(
                    text = makeTimeString(playlistLength * 1000L),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal
                )

                Row {
                    IconButton(
                        onClick = onShowEditDialog
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null
                        )
                    }

                    // On every playlist, not only empty ones: recognising something playing near
                    // you is how you fill a playlist you already started, not just a new one.
                    IconButton(onClick = onIdentifySong) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = stringResource(R.string.recognition_identify_song),
                        )
                    }

                    if (playlist.playlist.browseId != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val synced = syncUtils.syncPlaylist(playlist.playlist.browseId, playlist.id)
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(
                                            if (synced) R.string.playlist_synced else R.string.playlist_sync_failed
                                        ),
                                        withDismissAction = true
                                    )
                                }
                            },
                            enabled = isNetworkConnected
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null
                            )
                        }
                    }

                    if (songs.any { !it.song.song.isLocal }) {
                        when (downloadState) {
                            Download.STATE_COMPLETED -> {
                                IconButton(
                                    onClick = onShowRemoveDownloadDialog
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.OfflinePin,
                                        contentDescription = null
                                    )
                                }
                            }

                            Download.STATE_DOWNLOADING -> {
                                IconButton(
                                    onClick = {
                                        songs.forEach { song ->
                                            downloadUtil.removeDownload(song.song.id)
                                        }
                                    }
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            else -> {
                                IconButton(
                                    onClick = {
                                        downloadUtil.download(songs.map { it.song.toMediaMetadata() })
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                playerConnection.enqueueEnd(
                                    items = songs.map { it.song.toMediaItem() }
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() }.toList()
                        ),
                        origin = PlayOrigin.PLAYLIST,
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.play))
            }

            OutlinedButton(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() },
                            startShuffled = true,
                        ),
                        origin = PlayOrigin.PLAYLIST,
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.shuffle))
            }
        }
    }
}

/**
 * The songs a search of a playlist shows: those with [query] in the title or in an artist's name,
 * ignoring case, in the playlist's own order. An empty query shows the whole playlist, as the
 * search does when it first opens.
 *
 * A function of the playlist as it is now, so the screen can rebuild the results every time the
 * playlist changes rather than only when the query does.
 */
fun playlistSearchResults(songs: List<PlaylistSong>, query: String): List<PlaylistSong> =
    if (query.isEmpty()) songs
    else songs.filter { song ->
        song.song.title.contains(query, ignoreCase = true) || song.song.artists.fastAny {
            it.name.contains(query, ignoreCase = true)
        }
    }

/**
 * What to send YouTube Music after a drag: pairs of a song's set-video id and the set-video id of
 * the song it should now sit directly in front of, in the order they are to be sent.
 *
 * [setVideoIds] is the playlist in its order after the drag, the order on screen, with the song
 * dragged from [from] now at [to]. Working from that rather than from the database is the point:
 * the local move is written on another thread, and a read straight after it could see either the
 * old order or the new one.
 *
 * YouTube Music can only put a song in front of another one. A song dragged to the very end has
 * nothing after it, so it first goes in front of the song that is now last but one, and that song
 * is then put back in front of it. A song with no set-video id, such as one added here and not yet
 * synced back, cannot be named to YouTube, so then nothing is sent at all.
 */
fun youTubeMovesAfterDrag(setVideoIds: List<String?>, from: Int, to: Int): List<Pair<String, String>> {
    if (from == to || from !in setVideoIds.indices || to !in setVideoIds.indices) return emptyList()
    val moved = setVideoIds[to] ?: return emptyList()
    if (to < setVideoIds.lastIndex) {
        val successor = setVideoIds[to + 1] ?: return emptyList()
        return listOf(moved to successor)
    }
    val lastButOne = setVideoIds[to - 1] ?: return emptyList()
    return listOf(moved to lastButOne, lastButOne to moved)
}
