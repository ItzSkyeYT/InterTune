package com.dd3boh.outertune.ui.screens

import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEachReversed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.HistorySource
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.history.DatabaseHistoryIo
import com.dd3boh.outertune.history.HistoryEntry
import com.dd3boh.outertune.history.HistoryFormat
import com.dd3boh.outertune.history.HistoryRemoval
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.TopBarActions
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.HistoryViewModel
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val snackbarHostState = LocalSnackbarHostState.current

    val historySource by viewModel.historySource.collectAsState()
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
    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    }

    LaunchedEffect(query) {
        snapshotFlow { searchQuery }.debounce { 300L }.collectLatest {
            searchQuery = query
        }
    }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<Long>, Long>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    // no multiselect for remote hisory (yet)
    val historyPage by viewModel.historyPage

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    // Day headings and play times in the phone's own language and 12 or 24 hour setting.
    val configuration = LocalConfiguration.current
    val todayText = stringResource(R.string.today)
    val yesterdayText = stringResource(R.string.yesterday)
    val format = remember(context, configuration, todayText, yesterdayText) {
        HistoryFormat(
            locale = configuration.locales[0],
            is24Hour = DateFormat.is24HourFormat(context),
            today = todayText,
            yesterday = yesterdayText,
        )
    }

    val days by viewModel.days.collectAsState()
    val filteredDays = remember(days, searchQuery) {
        if (searchQuery.text.isEmpty()) days
        else days.mapNotNull { day ->
            day.plays.filter { entry ->
                entry.song.song.title.contains(searchQuery.text, ignoreCase = true) ||
                        entry.song.artists.fastAny { it.name.contains(searchQuery.text, ignoreCase = true) }
            }.takeIf { it.isNotEmpty() }?.let { day.copy(plays = it) }
        }
    }
    val filteredIndex: Map<Long, HistoryEntry> by remember(filteredDays) {
        derivedStateOf {
            filteredDays.flatMap { it.plays }.associateBy { it.key }
        }
    }
    LaunchedEffect(filteredDays) {
        selection.fastForEachReversed { key ->
            if (filteredIndex[key] == null) {
                selection.remove(key)
            }
        }
    }

    val lazyListState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .union(WindowInsets.ime)
                .asPaddingValues(),
            modifier = Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Top)
                )
                .padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            stickyHeader(
                key = "searchbar"
            ) {
                if (isSearching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.background(MaterialTheme.colorScheme.background)
                    ) {
                        IconButton(
                            onClick = { isSearching = true }
                        ) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null
                            )
                        }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                }
                Spacer(Modifier.height(1.dp)) // for Compose ui 1.8
            }

            item {
                ChipsRow(
                    chips = if (isLoggedIn) listOf(
                        HistorySource.LOCAL to stringResource(R.string.local_history),
                        HistorySource.REMOTE to stringResource(R.string.remote_history),
                    ) else {
                        listOf(HistorySource.LOCAL to stringResource(R.string.local_history))
                    },
                    currentValue = historySource,
                    onValueUpdate = {
                        viewModel.historySource.value = it
                        if (it == HistorySource.REMOTE) {
                            viewModel.fetchRemoteHistory()
                        }
                    }
                )
            }

            if (historySource == HistorySource.REMOTE && isLoggedIn) {
                historyPage?.sections?.forEach { section ->
                    stickyHeader {
                        NavigationTitle(
                            title = section.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }

                    itemsIndexed(
                        items = section.songs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        val content: @Composable () -> Unit = {
                            YouTubeListItem(
                                item = song,
                                isActive = song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.MoreVert,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = context.getString(R.string.queue_remote_history),
                                                        items = section.songs.map { it.toMediaMetadata() },
                                                        startIndex = index
                                                    ),
                                                    origin = PlayOrigin.HISTORY,
                                                )
                                            }
                                        },
                                        onLongClick = {

                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                            }

                                        }
                                    )
                                    .animateItem()
                            )
                        }



                        SwipeToQueueBox(
                            item = song.toMediaItem(),
                            swipeEnabled = swipeEnabled,
                            snackbarHostState = snackbarHostState,
                            content = { content() },
                        )

                    }
                }
            } else {
                filteredDays.forEach { group ->
                    stickyHeader {
                        NavigationTitle(
                            title = format.heading(group.day),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    }

                    val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                    itemsIndexed(
                        items = group.plays,
                        // By play, not by place: a new play lands at the top and shifts every row,
                        // and without keys the rows under a finger or a swipe moved with it.
                        key = { _, entry -> entry.key },
                    ) { index, entry ->
                        SongListItem(
                            song = entry.song,
                            navController = navController,
                            snackbarHostState = snackbarHostState,

                            isActive = entry.song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            inSelectMode = inSelectMode,
                            isSelected = selection.contains(entry.key),
                            onSelectedChange = {
                                inSelectMode = true
                                if (it) {
                                    selection.add(entry.key)
                                } else {
                                    selection.remove(entry.key)
                                }
                            },
                            swipeEnabled = swipeEnabled,

                            thumbnailSize = thumbnailSize,
                            onPlay = {
                                if (entry.song.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "${context.getString(R.string.queue_local_history)}: ${format.day(group.day)}",
                                            items = group.plays.map { it.song.toMediaMetadata() },
                                            startIndex = index
                                        ),
                                        origin = PlayOrigin.HISTORY,
                                    )
                                }
                            },
                            // When it started, on the clock where it was played. The song's length
                            // leaves the subtitle, so the row has one time on it and that is this one;
                            // without the caption line the length is back, as on every other list.
                            caption = "",
                            trailingText = format.time(entry.start),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                        )
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        FloatingFooter(
            visible = inSelectMode
        ) {
            SelectHeader(
                navController = navController,
                selectedItems = days.flatMap { group ->
                    group.plays.filter { it.key in selection }
                }.map { it.song.toMediaMetadata() },
                // The rows on screen, not the whole history: while searching, Select all must not
                // reach plays the search is hiding.
                totalItemCount = filteredIndex.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(filteredIndex.keys)
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode,
                onRemoveFromHistory = {
                    val sel = selection.mapNotNull { key -> filteredIndex[key]?.play }
                    val at = System.currentTimeMillis()
                    // Short transactions, each play whole in one, so a play is never half removed
                    // and the song playing can still write its listen; caught outside them, so a
                    // failure rolls back rather than ending the app from Room's executor.
                    database.query {
                        runCatching { HistoryRemoval.remove(DatabaseHistoryIo(this), sel, at, transaction = { block -> transactionNow { block() } }) }
                            .onFailure { Log.w("HistoryScreen", "Could not remove from history", it) }
                    }
                },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }

    FloatingTopBar(
        title = stringResource(R.string.history),
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
                        onClick = { isSearching = true }
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null
                        )
                    }
                }
            }
        },
    )
}
