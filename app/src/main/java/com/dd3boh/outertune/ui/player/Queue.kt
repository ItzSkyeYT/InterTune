/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.focus.focusProperties
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.utils.ActivityLog
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.InsetsSafeE
import com.dd3boh.outertune.constants.InsetsSafeS
import com.dd3boh.outertune.constants.InsetsSafeSE
import com.dd3boh.outertune.constants.InsetsSafeSTE
import com.dd3boh.outertune.constants.InsetsSafeT
import com.dd3boh.outertune.constants.ListItemHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.LockQueueKey
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.move
import com.dd3boh.outertune.extensions.supportsWideScreen
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.ui.component.BottomSheet
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.BufferedSheetScrollConnection
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.playback.AdaptiveNote
import com.dd3boh.outertune.playback.AdaptiveQueue
import androidx.compose.material.icons.rounded.AutoAwesome
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.component.items.MediaMetadataListItem
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.menu.PlayerMenu
import com.dd3boh.outertune.ui.menu.QueueMenu
import com.dd3boh.outertune.utils.QueueToPlaylist
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import com.dd3boh.outertune.ui.utils.GlassSpec
import com.dd3boh.outertune.ui.utils.LocalGlassStill
import com.dd3boh.outertune.ui.utils.STILL_ALPHA
import com.dd3boh.outertune.ui.utils.stillGlass
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** The adaptive queue's note in words: see AdaptiveNote. */
@Composable
private fun adaptiveNoteText(note: AdaptiveNote.Note): String = when (note.kind) {
    AdaptiveNote.Kind.LIKE -> pluralStringResource(R.plurals.adaptive_note_like, note.count, note.count, note.title.orEmpty())
    AdaptiveNote.Kind.AFTER_SKIP -> pluralStringResource(R.plurals.adaptive_note_after_skip, note.count, note.count)
    AdaptiveNote.Kind.HABITS -> pluralStringResource(R.plurals.adaptive_note_habits, note.count, note.count)
    AdaptiveNote.Kind.FINISH -> pluralStringResource(R.plurals.adaptive_note_finish, note.count, note.count)
    AdaptiveNote.Kind.DROPPED -> pluralStringResource(R.plurals.adaptive_note_dropped, note.count, note.count)
}

/**
 * Songs shown beyond the one playing while the far end is being chosen.
 *
 * Four, matching what the planner leaves untouched, so everything on screen is genuinely settled
 * and nothing listed can be taken away while somebody is looking at it.
 */
private const val ADAPTIVE_VISIBLE_AHEAD = 4

/**
 * How far the finger can go past the top of the queue, in a scroll that moved the list first,
 * before the queue's sheet comes down with it (BufferedSheetScrollConnection): all the way. A
 * quick scroll up through a long queue runs on past the top, and with no room the rest of that
 * movement dragged the queue shut, or a flick threw it shut; one song's row of room (64 dp) was
 * not enough. So a scroll that started in the list never closes the queue, however far or fast it
 * runs on: closing it takes a pull that starts with the list at its top, which moves the queue at
 * once, as before.
 */
internal val QueueOvershootBuffer = Dp.Infinity

/**
 * A pull down that starts with the queue at its top, as every pull does on a queue too short to
 * scroll (Auto queue shows only a few songs ahead): the queue stays put for the first 48 dp of it,
 * so a small movement of the finger never closes it.
 */
internal val QueuePullDeadZone = 48.dp

/**
 * How far the queue itself has to have come down before a flick throws it shut, a song's row.
 * Short of that, letting go puts it back open; a slow drag past halfway still closes it.
 */
internal val QueueFlingAfter = 64.dp

/**
 * Puts the list at [index] once its rows reach that far, and says whether it did. Rows just put
 * into the list are laid out a frame later, and a scroll asked for before then is cut to the top.
 * Done once per opening, that was never put right, so the queue opened at its first song instead
 * of the one playing (his report, 26 Oct 2026).
 */
internal suspend fun LazyListState.scrollToWhenLaidOut(index: Int): Boolean {
    if (index < 0) return false
    withTimeoutOrNull(2_000) { snapshotFlow { layoutInfo.totalItemsCount }.first { it > index } } ?: return false
    scrollToItem(index)
    return true
}

/**
 * The queue's own link from a list to its sheet, with [QueueOvershootBuffer]. Only the queue has
 * it: the player's sheet keeps preUpPostDownNestedScrollConnection over its artwork and lyrics, and
 * the cast picker its own pull, as they were. The queue is the long list that gets scrolled fast,
 * where running into its top closed the sheet by accident. One for each list, since it holds the
 * state of the gesture it is in. Null without a sheet (the tablet's side pane).
 */
@Composable
private fun rememberQueueSheetScroll(queueState: BottomSheetState?): NestedScrollConnection? {
    val density = LocalDensity.current
    return remember(queueState, density) {
        queueState?.let {
            with(density) {
                BufferedSheetScrollConnection(it, QueueOvershootBuffer.toPx(), QueuePullDeadZone.toPx(), QueueFlingAfter.toPx())
            }
        }
    }
}

@Composable
fun QueueSheet(
    state: BottomSheetState,
    onTerminate: () -> Unit,
    playerBottomSheetState: BottomSheetState,
    onBackgroundColor: Color,
    navController: NavController,
    modifier: Modifier = Modifier,
    /**
     * Whether the collapsed sheet shows its handle, the arrow that pulls it up. Off when the queue
     * opens from a button instead, where the sheet collapses to nothing and only ever comes up
     * from that button.
     */
    showHandle: Boolean = true,
    /**
     * Whether the open sheet is laid out for a small window: the bar at its bottom has no
     * transport controls, which the player has a back press away, and keeps the queue's title to
     * one line. On a keypad phone those two took all the height and left none for the songs.
     */
    compact: Boolean = false,
    /**
     * What the player draws behind itself, the artwork and its gradient, for the bar at the foot
     * of the queue to be a pane of glass over (Liquid glass). The sheet itself stays one colour.
     * Null for the bar as it was.
     */
    backdrop: LayerBackdrop? = null,
    glassIntensity: Float = 1f,
    /**
     * Whether Back from the open queue closes the whole player and leaves the queue open in it, so
     * that the player opens on the queue next time (queueBack). Off, Back puts the queue down. A
     * selection or a search in it is left first either way, by its own handlers further in.
     */
    backClosesPlayer: Boolean = false,
) {
    Log.v("QueueSheet", "Q-1")
    val haptic = LocalHapticFeedback.current
    BottomSheet(
        state = state,
        backMoves = if (backClosesPlayer) playerBottomSheetState else state,
        background = {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                    .fillMaxSize()
            )
        },
        modifier = modifier,
        collapsedContent = {
            Log.v("QueueSheet", "Q-2")
            if (showHandle) Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
            ) {
                IconButton(onClick = {
                    state.expandSoft()
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandLess,
                        tint = onBackgroundColor,
                        // The arrow under the player that opens the queue had no name either.
                        contentDescription = stringResource(R.string.queue),
                    )
                }
            }
        },
    ) {
        QueueContent(
            queueState = state,
            onTerminate = onTerminate,
            playerState = playerBottomSheetState,
            navController = navController,
            compact = compact,
            backdrop = backdrop,
            glassIntensity = glassIntensity,
        )
    }
}

@Composable
fun QueueScreen(
    onTerminate: () -> Unit,
    playerBottomSheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
                    .add(WindowInsets(bottom = MiniPlayerHeight))
            )
    ) {
        QueueContent(
            onTerminate = onTerminate,
            playerState = playerBottomSheetState,
            navController = navController
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
fun BoxScope.QueueContent(
    queueState: BottomSheetState? = null,
    playerState: BottomSheetState,
    onTerminate: () -> Unit,
    navController: NavController,
    /**
     * Show only the up-next song list, without the saved-queues panel beside it.
     *
     * For the tablet player's side pane. That pane is already half the screen, so the normal
     * landscape split would squeeze the up-next list into a quarter of the display, and picking a
     * different saved queue belongs with the rest of the queue management in the slide-up sheet
     * rather than permanently occupying the player.
     */
    songsOnly: Boolean = false,
    /** Whether to lay the bar at the bottom out for a small window, see [QueueSheet]. */
    compact: Boolean = false,
    /** For the bar's controls as glass, see [QueueSheet]. */
    backdrop: LayerBackdrop? = null,
    glassIntensity: Float = 1f,
) {
    Log.v("QueueContent", "QC-1")
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val qb = playerConnection.service.queueBoard

    // preferences
    var lockQueue by rememberPreference(LockQueueKey, defaultValue = false)

    // player
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()

    // player controls
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val seekIncrement by rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )

    // ui
    val wideScreen = context.supportsWideScreen()
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && wideScreen

    val queueWindows by playerConnection.queueWindows.collectAsState()
    // Songs put into the restored queue before play, which the fallback list below shows.
    val unloadedQueueChanged by playerConnection.service.unloadedQueueChanged.collectAsState()

    // multi queue vars
    val fallBackQueue = if (queueWindows.isEmpty()) qb.getCurrentQueue() else null
    // The list of queues opens from its button only. It used to open by itself whenever the player
    // held nothing yet, after an update or a restart and on the first frame or two of an opening,
    // and then put a list of queues where the songs should be.
    var mqExpand by remember { mutableStateOf(false) }
    var detachedHead by remember { mutableStateOf(fallBackQueue != null) }
    var detachedQueue by remember { mutableStateOf<MultiQueueObject?>(fallBackQueue) }

    /**
     * The queue the fallback seeded us with, captured once alongside the remembers above.
     *
     * Used to tell "we are detached because nothing had loaded yet" apart from "the user picked a
     * queue to preview". Only the first is safe to clear automatically.
     */
    val fallbackSeed = remember { fallBackQueue }
    val mutableQueues = remember { mutableStateListOf<MultiQueueObject>() }
    var playingQueue by remember { mutableIntStateOf(-1) }


    /**
     * SONG LIST
     */
    val mutableSongs = remember { mutableStateListOf<MediaMetadata>() }
    // Which queue the list was last put at its playing song for. Null until this opening's first.
    var scrolledFor by remember { mutableStateOf<String?>(null) }
    val lazySongsListState = rememberLazyListState()

    // multiselect
    var inSelectMode by remember {
        mutableStateOf(false)
    }
    val selectedItems = remember { mutableStateListOf<Int>() }
    val onExitSelectionMode = {
        inSelectMode = false
        selectedItems.clear()
    }

    // search
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    // Derived rather than remembered against mutableSongs. That is one list object whatever it
    // holds, so a remembered filter only ever ran again when the query changed, and results
    // outlived the queue they came from.
    val filteredSongs by remember {
        derivedStateOf {
            val text = searchQuery.text
            if (text.isEmpty()) mutableSongs.toList()
            else mutableSongs.filter { song ->
                song.title.contains(text, ignoreCase = true)
                        || song.artists.fastAny { it.name.contains(text, ignoreCase = true) }
            }
        }
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching && (queueState == null || queueState.isExpanded)) {
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


    // reorder
    var dragInfo by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazySongsListState,
        scrollThresholdPadding = WindowInsets.systemBars.add(
            WindowInsets(top = ListItemHeight, bottom = ListItemHeight)
        ).asPaddingValues()
    ) { from, to ->
        // Positions in the queue, found by the rows' keys. The indices handed over are the list's,
        // which need not be the queue's: when the adaptive queue's note sat above the songs as a
        // row of its own, every move landed one song off, and dragging the last song up asked for
        // a position past the end of the queue and crashed. A row added to the list later must not
        // bring that back.
        val fromIndex = mutableSongs.indexOfFirst { QueueRows.key(it) == from.key }
        val toIndex = mutableSongs.indexOfFirst { QueueRows.key(it) == to.key }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        val currentDragInfo = dragInfo
        dragInfo = if (currentDragInfo == null) {
            fromIndex to toIndex
        } else {
            currentDragInfo.first to toIndex
        }
        mutableSongs.move(fromIndex, toIndex)
    }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                if (from != to) {
                    qb.moveSong(from, to)
                    playerConnection.player.moveMediaItem(from, to)
                    // Where the listener put it, the adaptive queue leaves it.
                    mutableSongs.getOrNull(to)?.id?.let { playerConnection.service.noteMovedByHand(it) }
                }
                // Cleared either way. A drag dropped where it started used to leave it set, and
                // the next drag then moved from where the last one had begun.
                dragInfo = null
            }
        }
    }

    val lazyQueuesListState = rememberLazyListState()
    var dragInfoEx by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val reorderableStateEx = rememberReorderableLazyListState(
        lazyListState = lazyQueuesListState,
        scrollThresholdPadding = WindowInsets.systemBars.add(
            WindowInsets(top = ListItemHeight, bottom = ListItemHeight)
        ).asPaddingValues()
    ) { from, to ->
        // Found by the rows' keys, as the songs are above, rather than trusting the list's indices.
        val fromIndex = mutableQueues.indexOfFirst { it.id == from.key }
        val toIndex = mutableQueues.indexOfFirst { it.id == to.key }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        val currentDragInfo = dragInfoEx
        dragInfoEx = if (currentDragInfo == null) {
            fromIndex to toIndex
        } else {
            currentDragInfo.first to toIndex
        }
        mutableQueues.move(fromIndex, toIndex)
    }
    LaunchedEffect(reorderableStateEx.isAnyItemDragging) {
        if (!reorderableStateEx.isAnyItemDragging) {
            dragInfoEx?.let { (from, to) ->
                qb.move(from, to)
                dragInfoEx = null
            }
        }
    }

    // Helpers
    fun exitDetachHead() {
        detachedHead = false
        detachedQueue = null // detachedQueue should only exist in detached mode
        onExitSelectionMode()
    }

    /**
     * Undo the detached state that the fallback seeds on first composition.
     *
     * queueWindows is a flow, so it is empty for the first composition or two even when something
     * is already playing. That makes fallBackQueue non-null, which seeds detachedHead true. The
     * remember holding it has no key, so when the real queue arrives a moment later the value is
     * never reconsidered: the song list stays in detached mode, and detached mode disables
     * drag-to-reorder. The handles were therefore missing on every open, and came back only after
     * switching to another queue and back, because that path calls exitDetachHead.
     *
     * Only clears a detach the user did not ask for, hence the identity check against the seed.
     */
    LaunchedEffect(queueWindows.isEmpty()) {
        if (queueWindows.isNotEmpty() && detachedHead && detachedQueue === fallbackSeed) {
            exitDetachHead()
        }
    }

    /**
     * How much of the queue is worth showing when the far end is being chosen as you listen.
     *
     * A few songs, not a few dozen. The point of showing any is that what comes next is settled
     * and can be relied on; past that the list would be describing decisions that have not been
     * made yet.
     */
    val adaptiveTailActive by playerConnection.service.adaptiveTailActive.collectAsState()

    // Read from the live list on every composition, not remembered. A remembered copy keyed on the
    // list's size went stale on a drag, which reorders without changing the count: the rows kept
    // the old order while the queue had the new one, so swiping a row away removed the song that
    // was now at that position rather than the one on screen, and tapping one played it. A change
    // to the hidden tail needs no key either: dropping songs changes the player's timeline, which
    // refills mutableSongs.
    //
    // Counted from the furthest song this queue has reached, not from the one playing. Counted from
    // the one playing, going back to the first song folded away every song played since, and they
    // seemed to vanish. See AdaptiveQueue.settledThrough.
    val reachedInQueue by playerConnection.service.reachedInQueue.collectAsState()
    // What the adaptive queue last changed, said under the songs on show, and the songs it put in
    // that have not played, marked in their rows. Only for the live queue of a radio.
    val adaptivePicks by playerConnection.service.adaptivePicks.collectAsState()
    val adaptiveNote by playerConnection.service.adaptiveNote.collectAsState()
    val adaptiveSaying = adaptiveTailActive && !isSearching && detachedQueue == null
    // The preview the sheet shows while the player holds nothing yet (after an update or a
    // restart, until something plays) is of the queue that is playing: its far end is folded
    // away like the live queue's, from the song it is at, not shown in full. Another queue looked
    // at from the list of queues still shows all of it.
    val previewOfPlaying = detachedQueue != null && detachedQueue?.id == qb.getCurrentQueue()?.id
    val foldTail = adaptiveTailActive && !isSearching && (detachedQueue == null || previewOfPlaying)
    val visibleSongs: List<MediaMetadata> =
        if (!foldTail) mutableSongs
        else {
            val settled = detachedQueue?.let { AdaptiveQueue.settledThrough(it.getQueuePosShuffled(), mutableSongs, { it.id }, reachedInQueue) }
                ?: AdaptiveQueue.settledThrough(currentWindowIndex, mutableSongs, { it.id }, reachedInQueue)
            mutableSongs.take((settled + 1 + ADAPTIVE_VISIBLE_AHEAD).coerceIn(0, mutableSongs.size))
        }
    val hiddenTailCount = if (isSearching) 0 else mutableSongs.size - visibleSongs.size

    // Refreshed while searching as well, and only the scroll waits for the search to close. It
    // used to stop here during a search, so Start radio from a result replaced the queue while the
    // results still showed the old one, and a tap or a swipe on them then acted on the new queue at
    // the old positions. Not keyed on the search itself, since opening one must not clear a
    // selection made before it.
    //
    // The list is put at the song playing once: when the sheet opens, and again when it comes to
    // show another queue. It used to be put there on every run of this, and this runs whenever
    // the player's list changes in any way, which it does each time a song starts (its length
    // becomes known). So a list somebody had scrolled jumped back with every new song, and with
    // every song removed or moved.
    // Also when the place of the song playing becomes known: on opening it can arrive after the list.
    LaunchedEffect(queueWindows, detachedQueue, unloadedQueueChanged, currentWindowIndex == -1) { // add to songs list & scroll
        if (detachedQueue != null) {
            val shown = QueueRows.of(detachedQueue!!.getCurrentQueueShuffled())
            if (!QueueRows.same(shown, mutableSongs)) {
                mutableSongs.apply {
                    clear()
                    addAll(shown)
                }
            }
            val showing = "detached " + detachedQueue!!.id
            if (!isSearching && scrolledFor != showing) {
                // Marked done only once the list was really put there: see scrollToWhenLaidOut.
                val at = detachedQueue!!.getQueuePosShuffled()
                if (mutableSongs.size > at && lazySongsListState.scrollToWhenLaidOut(at)) scrolledFor = showing
            }
            return@LaunchedEffect
        }
        // fallback queue, for before user plays any song
        if (fallBackQueue != null) {
            return@LaunchedEffect
        }

        // Each row under a name of its own that it keeps: see QueueRows. A list that comes back
        // the same, as it does when only a song's length was learned, is left alone, and so is a
        // selection made in it.
        val shown = QueueRows.of(queueWindows.mapNotNull { it.mediaItem.metadata })
        if (!QueueRows.same(shown, mutableSongs)) {
            mutableSongs.apply {
                clear()
                addAll(shown)
            }
            // A selection is of the list as it was: cleared with it, as it always was.
            selectedItems.clear()
        }

        val showing = "playing " + qb.getCurrentQueue()?.id
        // Not before the song playing is in the list: queueWindows can still be empty on the
        // first run after opening, and a scroll then went to the top and counted as done.
        if (currentWindowIndex != -1 && !isSearching && scrolledFor != showing && mutableSongs.size > currentWindowIndex) {
            if (lazySongsListState.scrollToWhenLaidOut(currentWindowIndex)) scrolledFor = showing
        }
    }


    LaunchedEffect(Unit) {
        combine(snapshotFlow { qb.masterQueues.toList() }, playerConnection.service.qbInit) { updatedList, init ->
            updatedList to init
        }.collect { (updatedList, init) ->
            Log.d("Queue.kt", "Trigger loading queue. init = $init")
            if (init) {
                mutableQueues.clear()
                // Never two with the same id: the list keys queues by it (#46).
                mutableQueues.addAll(qb.getAllQueues().distinctBy { it.id })
                playingQueue = updatedList.indexOf(qb.getCurrentQueue())
            }
        }
    }

    /**
     * SAVE THE SHOWN QUEUE AS A PLAYLIST
     *
     * Non-null while the naming dialog is open, holding the name it was opened with. The default name
     * needs the library's playlist names, which live in the database, so it is worked out in the
     * coroutine that opens the dialog rather than by watching a flow the sheet has no other use for.
     */
    var saveQueueName by remember { mutableStateOf<String?>(null) }

    saveQueueName?.let { proposedName ->
        TextFieldDialog(
            icon = { Icon(imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
            title = { Text(text = stringResource(R.string.save_queue_as_playlist)) },
            initialTextFieldValue = TextFieldValue(proposedName, TextRange(proposedName.length)),
            onDismiss = { saveQueueName = null },
            onDone = { name ->
                // The list the sheet renders is the order the user is looking at, shuffled or not, so
                // it is the list that is saved. The queue itself is left alone.
                val songs = mutableSongs.toList()
                coroutineScope.launch(Dispatchers.IO) {
                    val playlist = PlaylistEntity(
                        name = name,
                        browseId = null,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                        isLocal = true
                    )
                    val saved = runCatching {
                        database.transactionNow {
                            // A radio queue holds songs the song table has never seen, and the map's
                            // foreign key needs them there first.
                            songs.forEach { if (!songExists(it.id)) insert(it) }
                            insert(playlist)
                            QueueToPlaylist.positions(songs.map { it.id }).forEach { (songId, position) ->
                                insert(PlaylistSongMap(playlistId = playlist.id, songId = songId, position = position))
                            }
                        }
                    }.onFailure { Log.w("Queue.kt", "Could not save the queue as a playlist", it) }.isSuccess
                    if (saved) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.saved_as_playlist, name), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    val queueHeader: @Composable ColumnScope.(Modifier) -> Unit = { modifier ->
        Log.v("QueueContent", "QC-mq_a")

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.queues_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ResizableIconButton(
                    icon = if (lockQueue) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    onClick = {
                        lockQueue = !lockQueue
                    },
                )

                if (!landscape) {
                    ResizableIconButton(
                        icon = Icons.Rounded.Close,
                        onClick = {
                            mqExpand = false
                            exitDetachHead()
                        },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }

    val queueList: @Composable ColumnScope.(PaddingValues) -> Unit = { contentPadding ->
        Log.v("QueueContent", "QC-mq_b")
        LaunchedEffect(mqExpand) { // scroll to queue
            if (mqExpand && playingQueue >= 0) {
                lazyQueuesListState.animateScrollToItem(playingQueue)
                if (currentWindowIndex != -1) {
                    lazySongsListState.scrollToItem(currentWindowIndex)
                }
            }
        }

        if (mutableQueues.isEmpty()) {
            Text(text = stringResource(R.string.queues_empty))
        }

        val queuesScroll = rememberQueueSheetScroll(queueState)
        LazyColumn(
            state = lazyQueuesListState,
            contentPadding = contentPadding,
            modifier = if (queuesScroll != null) Modifier.nestedScroll(queuesScroll) else Modifier
        ) {
            itemsIndexed(
                items = mutableQueues,
                key = { _, item -> item.id }
            ) { index, mq ->
                ReorderableItem(
                    state = reorderableStateEx,
                    // The same key as the list's. It was the queue's hash, which changes whenever
                    // the queue does and never matched a row, so a saved queue could not be dragged.
                    key = mq.id
                ) {
                    Row( // wrapper
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (playingQueue == index) {
                                    MaterialTheme.colorScheme.tertiary.copy(0.3f)
                                } else if (detachedHead && detachedQueue == mq) {
                                    MaterialTheme.colorScheme.tertiary.copy(0.1f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    // Tapping a queue switches to it and plays it. It used to only
                                    // preview the queue in detached mode and wait for a second tap
                                    // on the play button in the Songs header, which is not what a
                                    // tap on a queue reads as, and detached mode also disables
                                    // drag-to-reorder while you sit in it.
                                    if (playingQueue == index) {
                                        // Already the playing queue, so there is nothing to switch
                                        // to: close the list of queues and show its songs. Before
                                        // anything was played since the app started, the player holds
                                        // none of them yet, so the songs stay on the preview of this
                                        // queue, with the play button in the Songs header.
                                        mqExpand = false
                                        if (queueWindows.isEmpty()) {
                                            detachedQueue = mq
                                            detachedHead = true
                                        } else {
                                            exitDetachHead()
                                        }
                                    } else {
                                        coroutineScope.launch(Dispatchers.Main) {
                                            isSearching = false
                                            qb.setCurrQueue(mq, origin = PlayOrigin.QUEUE)
                                            // prepare() first, else it cannot start after playback
                                            // was stopped by an auto-skip on error.
                                            playerConnection.player.prepare()
                                            playerConnection.player.playWhenReady = true
                                            exitDetachHead()
                                        }
                                    }
                                },
                                onLongClick = {
                                    menuState.show {
                                        QueueMenu(
                                            navController = navController,
                                            mq = mq,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                    ) {
                        Row( // row contents (wrapper is needed for margin)
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(horizontal = 40.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, false)
                            ) {
                                if (!lockQueue) {
                                    ResizableIconButton(
                                        icon = Icons.Rounded.Close,
                                        onClick = {
                                            val remainingQueues =
                                                qb.deleteQueue(mq)
                                            if (playingQueue == index) {
                                                qb.setCurrQueue()
                                            }
                                            exitDetachHead()
                                            if (remainingQueues < 1) {
                                                onTerminate.invoke()
                                            }
                                        },
                                    )
                                }
                                Text(
                                    text = "${index + 1}. ${mq.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp)
                                )
                            }

                            if (!lockQueue) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier.draggableHandle()
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyQueuesListState,
        )
    }

    val songHeader: @Composable ColumnScope.(Modifier) -> Unit = { modifier ->
        Log.v("QueueContent", "QC-s_a")
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.songs),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // The lock normally lives in the saved-queues header, which songsOnly hides. Locking
            // is what gates drag-to-reorder, so without it here the tablet pane can be stuck
            // locked with no way to unlock and reordering just stops working, with nothing on
            // screen explaining why. Sits outside the detached/search choice below because both
            // of those branches occupy the same slot and would otherwise hide it.
            if (songsOnly) {
                ResizableIconButton(
                    icon = if (lockQueue) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    onClick = {
                        lockQueue = !lockQueue
                    },
                )
            }

            // play the detached queue
            if (detachedHead) {
                ResizableIconButton(
                    icon = Icons.Rounded.PlayArrow,
                    onClick = {
                        coroutineScope.launch(Dispatchers.Main) {
                            // change to this queue, seek to the item clicked on
                            qb.setCurrQueue(detachedQueue, origin = PlayOrigin.QUEUE)
                            playerConnection.player.prepare() // else cannot click to play after auto-skip onError stop
                            playerConnection.player.playWhenReady = true
                            exitDetachHead()
                        }
                    }
                )
            } else if (!isSearching && !(songsOnly && landscape)) {
                // The landscape layout with songsOnly (the tablet's queue pane) never draws
                // searchBar(), so there the button only hid the drag handles, with no field to
                // type in. The portrait layout draws searchBar() whatever songsOnly says, so a
                // songsOnly pane that falls back to it (tablet UI forced on in a narrow window)
                // keeps the button.
                ResizableIconButton(
                    icon = Icons.Rounded.Search,
                    onClick = {
                        isSearching = true
                    }
                )
            }
        }
    }

    val songList: @Composable ColumnScope.(PaddingValues) -> Unit = { contentPadding ->
        Log.v("QueueContent", "QC-s_b")
        val songsScroll = rememberQueueSheetScroll(queueState)
        LazyColumn(
            state = lazySongsListState,
            contentPadding = contentPadding,
            modifier = if (songsScroll != null) Modifier.nestedScroll(songsScroll) else Modifier
        ) {
            if ((if (isSearching) filteredSongs else mutableSongs).isEmpty()) {
                item {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.MusicNote,
                        text = stringResource(if (isSearching) R.string.no_results_found else R.string.queues_empty),
                        modifier = Modifier.animateItem()
                    )
                }
            }

            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = if (isSearching) filteredSongs else visibleSongs,
                key = { _, item -> QueueRows.key(item) },
                contentType = { _, _ -> CONTENT_TYPE_SONG }
            ) { displayIndex, window ->
                // The lambda hands back a position in the list being displayed, and every use of
                // it below means a position in the queue. Those are the same list until a search
                // narrows it, and then they are not: filteredSongs is a filter, so row 0 of two
                // results is whatever row 0 of the whole queue was. Tapping a result played the
                // wrong song and swiping one away removed the wrong song, which is the worse half
                // because it is not obvious and not undoable.
                //
                // visibleSongs is safe by construction, being a prefix of the same list, so this
                // only resolves while searching. Resolved once here rather than at nine call sites.
                val index = if (isSearching) {
                    mutableSongs.indexOf(window).takeIf { it >= 0 } ?: displayIndex
                } else {
                    displayIndex
                }
                // The swipe state keeps the confirmValueChange from the row's first composition
                // (Material3 remembers it with no inputs), so an index captured there goes stale.
                // Removing a song shifts every later one down without changing its key, and a second
                // swipe then removed the song after the one swiped. The lambda reads this instead.
                val currentIndex by rememberUpdatedState(index)
                ReorderableItem(
                    state = reorderableState,
                    key = QueueRows.key(window)
                ) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        positionalThreshold = { totalDistance ->
                            totalDistance
                        },
                        confirmValueChange = { dismissValue ->
                            // Read once, so the removal and the log below agree on the row.
                            val swipedIndex = currentIndex
                            // A row whose song has already left the queue (a search result stays on
                            // screen until the results refresh) has no place of its own any more,
                            // and a swipe on it must not take another song with it.
                            if (dismissValue != SwipeToDismissBoxValue.Settled && mutableSongs.getOrNull(swipedIndex) != window) {
                                return@rememberSwipeToDismissBoxState false
                            }
                            when (dismissValue) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    if (qb.removeCurrentQueueSong(swipedIndex)) {
                                        mutableSongs.getOrNull(swipedIndex)?.let { playerConnection.service.noteRemovedByHand(it) }
                                        mutableSongs.getOrNull(swipedIndex)?.id?.let { ActivityLog.note(context, database, it, SignalKind.REMOVED_FROM_QUEUE) }
                                        playerConnection.player.removeMediaItem(swipedIndex)
                                        mutableSongs.removeAt(swipedIndex)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    return@rememberSwipeToDismissBoxState true
                                }

                                SwipeToDismissBoxValue.EndToStart -> {
                                    if (qb.removeCurrentQueueSong(swipedIndex)) {
                                        mutableSongs.getOrNull(swipedIndex)?.let { playerConnection.service.noteRemovedByHand(it) }
                                        mutableSongs.getOrNull(swipedIndex)?.id?.let { ActivityLog.note(context, database, it, SignalKind.REMOVED_FROM_QUEUE) }
                                        playerConnection.player.removeMediaItem(swipedIndex)
                                        mutableSongs.removeAt(swipedIndex)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    return@rememberSwipeToDismissBoxState true
                                }

                                SwipeToDismissBoxValue.Settled -> {
                                    return@rememberSwipeToDismissBoxState false
                                }
                            }
                        }
                    )

                    val onCheckedChange: (Boolean) -> Unit = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        if (it) {
                            selectedItems.add(window.hashCode())
                        } else {
                            selectedItems.remove(window.hashCode())
                        }
                    }

                    val content = @Composable {
                        MediaMetadataListItem(
                            mediaMetadata = window,
                            isActive = (index == currentWindowIndex && !detachedHead) || index == detachedQueue?.getQueuePosShuffled(),
                            isPlaying = isPlaying && !detachedHead,
                            isNew = adaptiveSaying && window.id in adaptivePicks,
                            trailingContent = {
                                if (inSelectMode) {
                                    Checkbox(
                                        checked = window.hashCode() in selectedItems,
                                        onCheckedChange = onCheckedChange
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                PlayerMenu(
                                                    mediaMetadata = window,
                                                    navController = navController,
                                                    playerBottomSheetState = playerState,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                    },
                                                )
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = stringResource(R.string.options)
                                        )
                                    }
                                    // Not over search results, as in a playlist: the results are not redrawn
                                    // after a move, so a second drag started from rows that were no longer
                                    // where they showed.
                                    if (!lockQueue && !detachedHead && !isSearching) {
                                        Icon(
                                            imageVector = Icons.Rounded.DragHandle,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 16.dp)
                                                .draggableHandle()
                                        )
                                    }
                                }
                            },
                            isSelected = inSelectMode && window.hashCode() in selectedItems,
                            preferredSize = thumbnailSize,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (inSelectMode) {
                                            onCheckedChange(window.hashCode() !in selectedItems)
                                        } else {
                                            coroutineScope.launch(Dispatchers.Main) {
                                                if (index == currentWindowIndex && !detachedHead) {
                                                    playerConnection.togglePlayPause()
                                                } else {
                                                    val index = index // race condition...?
                                                    if (detachedHead) {
                                                        detachedQueue?.setCurrentQueuePos(index)
                                                        qb.setCurrQueue(detachedQueue, false, origin = PlayOrigin.QUEUE)
                                                    } else {
                                                        playerConnection.markUserChoice(); playerConnection.player.seekToDefaultPosition(index)
                                                    }
                                                    playerConnection.player.prepare() // else cannot click to play after auto-skip onError stop
                                                    playerConnection.player.playWhenReady = true
                                                    exitDetachHead()
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!inSelectMode) {
                                            inSelectMode = true
                                            selectedItems.add(window.hashCode())
                                        }
                                    }
                                )
                        )
                    }

                    if (!lockQueue && !inSelectMode && !detachedHead) {
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {},
                            content = { content() }
                        )
                    } else {
                        content()
                    }
                }
            }

            // What is past the end of the list, and why it is not shown.
            //
            // The planning has been working all along: it replaced eighteen of forty six upcoming
            // songs in a single pass. Nobody could tell, because the queue still presented forty
            // six songs as though they were settled. Showing a fixed list that is quietly being
            // rewritten is worse than not rewriting it, so the far end is not shown at all, and
            // this says what is happening there instead.
            //
            // And what the last change was, in its first line, while that change stands: "3 new
            // picks after your skip". It used to be a row of its own above the songs, which said
            // "Tuned to this session" over playlists and albums too, put the list's scroll one row
            // above the song playing, and was off screen whenever the sheet opened at that song.
            val note = adaptiveNote.takeIf { adaptiveSaying }
            if (hiddenTailCount > 0 || note != null) {
                item(key = "adaptive-tail", contentType = "adaptive-tail") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            // Two lines: the reason is the end of the note, and on one line it
                            // was what got cut, in French and at a larger font in English.
                            Text(
                                text = note?.let { adaptiveNoteText(it) } ?: stringResource(R.string.adaptive_tail_title),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (hiddenTailCount > 0) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.adaptive_tail_hidden,
                                        hiddenTailCount,
                                        hiddenTailCount,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazySongsListState,
        )
    }

    val searchBar: @Composable ColumnScope.() -> Unit = {
        Log.v("QueueContent", "QC-searchbar")
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (isSearching && !detachedHead) {
                        isSearching = false
                        query = TextFieldValue()
                    } else {
                        navController.navigateUp()
                    }
                },
                modifier = Modifier.padding(start = 16.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
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

    // With glass, the list runs on under the bar: it is the picture the bar's pane looks through,
    // and the bar's height is the room left under its last row. Upright that list is the songs, on
    // its side the queues, which is what the bar stands under there.
    val listBackdrop = if (backdrop != null) rememberLayerBackdrop() else null
    // Battery use on Saver: the pane keeps its shape and place, frosted still, and the list is not
    // recorded for it (LocalGlassStill).
    val glassStill = LocalGlassStill.current
    val listUnderBar = listBackdrop != null
    var barHeight by remember { mutableStateOf(0.dp) }

// queue info + player controls
    val bottomNav: @Composable ColumnScope.() -> Unit = {
        Log.v("QueueContent", "QC-nav")

        val barInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
        val barShape = RoundedCornerShape(28.dp)
        // The list runs on under the pane, and it is glass over that, as the dock is over a page.
        val paneBackdrop = if (listUnderBar) listBackdrop else backdrop
        val barTint = when {
            paneBackdrop == null -> Color.Transparent
            // The dock's own tint: there are words behind this pane.
            listUnderBar -> GlassSpec(paneBackdrop, glassIntensity.coerceIn(0f, 1f)).tint()
            // Thinner: nothing behind it but the cover out of focus.
            else -> GlassSpec(paneBackdrop, glassIntensity.coerceIn(0f, 1f)).tint(min = 0.3f)
        }
        Column(
            modifier = Modifier
                .then(
                    if (paneBackdrop != null) {
                        // One pane of glass standing clear of the edges, as the dock does.
                        Modifier
                            .windowInsetsPadding(barInsets)
                            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 8.dp)
                            .glassPane(paneBackdrop, glassIntensity, barShape, barTint, glassStill, MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp).copy(alpha = STILL_ALPHA))
                            .clip(barShape)
                            .fillMaxWidth()
                    } else {
                        Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .fillMaxWidth()
                            .windowInsetsPadding(barInsets)
                    }
                )
                // Not a stop for the arrow keys, or the buttons inside could never be reached:
                // focus only moves between siblings. Back closes the queue from the keys.
                .focusProperties { canFocus = false }
                .clickable {
                    queueState?.collapseSoft()
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
        ) {
            // queue info
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(24.dp, 12.dp)
            ) {
                // handle selection mode
                if (inSelectMode && !isSearching) {
                    SelectHeader(
                        navController = navController,
                        selectedItems = selectedItems.mapNotNull { uidHash ->
                            (detachedQueue?.getCurrentQueueShuffled() ?: mutableSongs).find { it.hashCode() == uidHash }
                        },
                        totalItemCount = (detachedQueue?.getCurrentQueueShuffled() ?: mutableSongs).size,
                        onSelectAll = {
                            selectedItems.clear()
                            selectedItems.addAll(mutableSongs.map { it.hashCode() })
                        },
                        onDeselectAll = { selectedItems.clear() },
                        menuState = menuState,
                        onDismiss = onExitSelectionMode
                    )
                } else {
                    // queue title and show multiqueue button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
                            .padding(2.dp)
                            .weight(1f)
                            .clickable(enabled = !landscape && !queueWindows.isEmpty()) {
                                mqExpand = !mqExpand
                                if (mqExpand) {
                                    exitDetachHead()
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                    ) {
                        Text(
                            text = detachedQueue?.title ?: mutableQueues.getOrNull(playingQueue)?.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = if (compact) 1 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        val saveQueueDescription = stringResource(R.string.save_queue_as_playlist)
                        val queueFallbackName = stringResource(R.string.queue_default_name)
                        ResizableIconButton(
                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            enabled = mutableSongs.isNotEmpty(),
                            onClick = {
                                val title = detachedQueue?.title ?: mutableQueues.getOrNull(playingQueue)?.title
                                coroutineScope.launch(Dispatchers.IO) {
                                    val names = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
                                        .first().map { it.playlist.name }
                                    val proposed = QueueToPlaylist.defaultName(title, names, queueFallbackName)
                                    withContext(Dispatchers.Main) { saveQueueName = proposed }
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            modifier = Modifier
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .semantics { contentDescription = saveQueueDescription }
                        )
                        ResizableIconButton(
                            icon = if (mqExpand) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            enabled = !landscape && !queueWindows.isEmpty(),
                            onClick = {
                                mqExpand = !mqExpand
                                if (mqExpand) {
                                    exitDetachHead()
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        fun getQueueLength(): Int {
                            return if (!detachedHead) {
                                queueWindows.sumOf { it.mediaItem.metadata!!.duration }
                            } else detachedQueue?.queue?.sumOf { it.duration } ?: 0
                        }

                        fun getQueuePositionStr(): String {
                            return if (!detachedHead) {
                                "${currentWindowIndex + 1} / ${queueWindows.size}"
                            } else {
                                detachedQueue?.let {
                                    "${it.getQueuePosShuffled() + 1} / ${it.getSize()}"
                                } ?: "–/–"
                            }
                        }
                        Text(
                            text = getQueuePositionStr(),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = makeTimeString(getQueueLength() * 1000L),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // player controls
            if (queueState != null && !compact) {
                val iconButtonColor = MaterialTheme.colorScheme.onSecondaryContainer
                PlaybackOrder {
                    Row(
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PlayerHorizontalPadding)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off,
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(4.dp)
                                    .align(Alignment.Center)
                                    .named(shuffleName(shuffleModeEnabled)),
                                color = iconButtonColor,
                                enabled = !detachedHead,
                                onClick = {
                                    playerConnection.triggerShuffle()
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.SkipPrevious,
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.Center)
                                    .named(stringResource(R.string.widget_previous)),
                                color = iconButtonColor,
                                enabled = !detachedHead && canSkipPrevious,
                                onClick = {
                                    // Cold start: the saved queue is not in the player yet, and a
                                    // skip in an empty player goes nowhere. As the full player does.
                                    playerConnection.loadQueueIfEmpty().seekToPrevious()
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                            )
                        }

                        if (seekIncrement != SeekIncrement.OFF) {
                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = Icons.Rounded.FastRewind,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center)
                                        .named(seekBackName()),
                                    enabled = !detachedHead,
                                    onClick = {
                                        playerConnection.player.seekTo(playerConnection.player.currentPosition - seekIncrement.millisec)
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = if (playbackState == STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.Center)
                                    .named(playPauseName(isPlaying && playbackState != STATE_ENDED)),
                                color = iconButtonColor,
                                enabled = !detachedHead,
                                onClick = {
                                    if (playbackState == STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.togglePlayPause()
                                    }
                                    // play/pause is slightly harder haptic
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                }
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        if (seekIncrement != SeekIncrement.OFF) {
                            Box(modifier = Modifier.weight(1f)) {
                                ResizableIconButton(
                                    icon = Icons.Rounded.FastForward,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.Center)
                                        .named(seekForwardName()),
                                    enabled = !detachedHead,
                                    onClick = {
                                        playerConnection.player.seekTo(playerConnection.player.currentPosition + seekIncrement.millisec)
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.SkipNext,
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.Center)
                                    .named(stringResource(R.string.widget_next)),
                                color = iconButtonColor,
                                enabled = !detachedHead && canSkipNext,
                                onClick = {
                                    playerConnection.loadQueueIfEmpty().seekToNext()
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = when (repeatMode) {
                                    REPEAT_MODE_OFF -> R.drawable.repeat_off
                                    REPEAT_MODE_ALL -> R.drawable.repeat_on
                                    REPEAT_MODE_ONE -> R.drawable.repeat_one
                                    else -> throw IllegalStateException()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(4.dp)
                                    .align(Alignment.Center)
                                    .named(repeatName(repeatMode)),
                                color = iconButtonColor,
                                enabled = !detachedHead,
                                onClick = {
                                    playerConnection.player.toggleRepeatMode()
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }


// finally render ui
    if (landscape) {
        Row {
            // song header & song list
            Column(
                modifier = if (songsOnly) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.5f)
            ) {
                songHeader(Modifier.windowInsetsPadding(InsetsSafeSTE))
                songList(InsetsSafeS.asPaddingValues())
            }

            if (!songsOnly) {

            Spacer(Modifier.width(8.dp))

            // multiqueue list & navbar
            Box(modifier = Modifier.fillMaxHeight()) {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    // As upright: what the pane looks through, the fill in the picture, the pane outside it.
                    .then(
                        if (listBackdrop != null) Modifier
                            // Not recorded while the glass is still: the pane does not read it then.
                            .then(if (glassStill) Modifier else Modifier.layerBackdrop(listBackdrop))
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                        else Modifier
                    )
            ) {
                // Its own, not the one queueList gives the list inside this column: both are in
                // the way of that list's scroll, and one connection in both places would see every
                // movement twice.
                val paneScroll = rememberQueueSheetScroll(queueState)
                Column(
                    modifier = (if (paneScroll != null) Modifier.nestedScroll(paneScroll) else Modifier)
                        .fillMaxWidth()
                        .weight(1f, false)
                ) {
                    if (isSearching) {
                        Spacer(Modifier.windowInsetsPadding(InsetsSafeT))
                        searchBar()
                        if (inSelectMode) {
                            Row {
                                SelectHeader(
                                    navController = navController,
                                    // This header only shows while searching, so it counts and
                                    // selects the results, as the portrait one below does.
                                    selectedItems = selectedItems.mapNotNull { uidHash ->
                                        filteredSongs.find { it.hashCode() == uidHash }
                                    },
                                    totalItemCount = filteredSongs.size,
                                    onSelectAll = {
                                        selectedItems.clear()
                                        selectedItems.addAll(filteredSongs.map { it.hashCode() })
                                    },
                                    onDeselectAll = { selectedItems.clear() },
                                    menuState = menuState,
                                    onDismiss = onExitSelectionMode
                                )
                            }
                        }
                    } else {
                        queueHeader(Modifier.windowInsetsPadding(InsetsSafeSTE))
                        queueList(
                            if (listUnderBar) InsetsSafeE.asPaddingValues().plusBottom(barHeight, LocalLayoutDirection.current)
                            else InsetsSafeE.asPaddingValues()
                        )
                    }
                }

                // nav bar
                if (!isSearching && !listUnderBar) {
                    bottomNav()
                }
            }
            if (!isSearching && listUnderBar) {
                val density = LocalDensity.current
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                ) {
                    bottomNav()
                }
            }
            } // the queues' half
            } // songsOnly
        }
    } else {
        Log.v("QueueContent", "QC-2.1")
        // queue contents
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                // What the pane of glass over the foot of the list looks through. The fill comes
                // after, so that it is in the picture: a lens over nothing draws nothing. The
                // pane is not in this column, which a layer's own reader must never be.
                .then(
                    if (listBackdrop != null) Modifier
                        .then(if (glassStill) Modifier else Modifier.layerBackdrop(listBackdrop))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                    else Modifier
                )
        ) {
            Log.v("QueueContent", "QC-2.2")
            Column(
                modifier = Modifier.weight(1f, false)
            ) {
                Log.v("QueueContent", "QC-2.3")
                // multiqueue list
                AnimatedVisibility(
                    visible = isSearching,
                    modifier = Modifier
                        .windowInsetsPadding(InsetsSafeT)
                ) {
                    Log.v("QueueContent", "QC-2.4a")
                    // In a Column: AnimatedVisibility lays its children on top of each other, so
                    // the selection header was drawn over the search field and its back arrow.
                    Column {
                        Spacer(Modifier.windowInsetsPadding(InsetsSafeT))
                        searchBar()
                        if (inSelectMode) {
                            Row {
                                SelectHeader(
                                    navController = navController,
                                    selectedItems = selectedItems.mapNotNull { uidHash ->
                                        filteredSongs.find { it.hashCode() == uidHash }
                                    },
                                    totalItemCount = filteredSongs.size,
                                    onSelectAll = {
                                        selectedItems.clear()
                                        selectedItems.addAll(filteredSongs.map { it.hashCode() })
                                    },
                                    onDeselectAll = { selectedItems.clear() },
                                    menuState = menuState,
                                    onDismiss = onExitSelectionMode
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(mqExpand && !isSearching) {
                    Log.v("QueueContent", "QC-2.4b")
                    // why cant i just put everything in one column???
                    Column {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight(0.4f)
                        ) {
                            queueHeader(Modifier.windowInsetsPadding(InsetsSafeSTE))
                            queueList(InsetsSafeSE.asPaddingValues())
                        }
                        Spacer(Modifier.height(12.dp))
                        songHeader(Modifier.windowInsetsPadding(InsetsSafeSE)) // song header
                    }
                }

                val songListInsets = if (mqExpand) {
                    InsetsSafeSE
                } else {
                    InsetsSafeSTE
                }
                val songListPadding = songListInsets.asPaddingValues()
                val direction = LocalLayoutDirection.current
                // Room for the last song to come up clear of the pane.
                songList(if (listUnderBar && !isSearching) songListPadding.plusBottom(barHeight, direction) else songListPadding) // song list
            }

            // nav bar
            if (!isSearching && !listUnderBar) {
                bottomNav()
            }
        }
        if (!isSearching && listUnderBar) {
            val density = LocalDensity.current
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
            ) {
                bottomNav()
            }
        }
    }
}

/**
 * The bar at the foot of the queue as one pane of glass: the dock's recipe, over [backdrop], under
 * a wash of [tint] that keeps what is written on it readable.
 */
private fun Modifier.glassPane(backdrop: LayerBackdrop, intensity: Float, shape: Shape, tint: Color, still: Boolean, stillFill: Color): Modifier {
    // Still on Saver (LocalGlassStill): the same pane, frosted, nothing behind it read.
    if (still) return this.stillGlass { shape }.background(stillFill, shape).background(tint, shape)
    val glass = GlassSpec(backdrop, intensity.coerceIn(0f, 1f))
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(glass.blur.toPx())
                lens(
                    refractionHeight = 20f.dp.toPx() * glass.lensT,
                    refractionAmount = 28f.dp.toPx() * glass.lensT,
                    depthEffect = true,
                )
            },
        )
        // After the backdrop, not in its onDrawSurface, which would paint a square patch (MainActivity's dock says why).
        .background(tint, shape)
}

/** These paddings with [more] added at the foot: the room a list leaves for a bar that floats over its last rows. */
private fun PaddingValues.plusBottom(more: Dp, direction: LayoutDirection) = PaddingValues(
    start = calculateStartPadding(direction),
    top = calculateTopPadding(),
    end = calculateEndPadding(direction),
    bottom = calculateBottomPadding() + more,
)
