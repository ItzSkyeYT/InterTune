/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.playback.PlayerConnection
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import com.dd3boh.outertune.ui.utils.LocalAppBackdrop
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.constants.PlayerButtonsStyleKey
import com.dd3boh.outertune.constants.QueueButtonKey
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.foundation.shape.CircleShape
import android.content.Intent
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.ui.component.rememberSleepTimerState
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.dd3boh.outertune.ui.component.SleepTimerDialog
import androidx.compose.material.icons.rounded.Timer
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.utils.ActivityLog
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.TransformOrigin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.QueuePeekHeight
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.SwipeToSkipKey
import com.dd3boh.outertune.constants.SwipeToDismissPlayerKey
import com.dd3boh.outertune.constants.GroupedPlayerControlsKey
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.constants.PlayerLiquidGlassKey
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.supportsWideScreen
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.BottomSheet
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.component.rememberBottomSheetState
import com.dd3boh.outertune.ui.menu.PlayerMenu
import com.dd3boh.outertune.ui.theme.extractGradientColors
import com.dd3boh.outertune.ui.utils.SnapLayoutInfoProvider
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val TAG = "BottomSheetPlayer"
    Log.v(TAG, "PLR-1")

    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val database = LocalDatabase.current

    val playbackState by playerConnection.playbackState.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)

    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val qbInit by playerConnection.service.qbInit.collectAsState()
    val swipeToSkip by rememberPreference(SwipeToSkipKey, defaultValue = false)
    val queueWindows by playerConnection.queueWindows.collectAsState()
    val shuffleOn by playerConnection.shuffleModeEnabled.collectAsState()
    // Songs put into the restored queue before play, which only the queue board knows about.
    val unloadedQueueChanged by playerConnection.service.unloadedQueueChanged.collectAsState()
    // The songs either side of this one, for the artwork strip, worked out again whenever the queue,
    // the song, shuffle or repeat changes. They used to be read straight off the player while the
    // screen drew. With the music paused nothing redraws the screen once the queue has loaded after
    // a launch, so the strip held the current song alone and a swipe had nowhere to go.
    val (previousMediaMetadata, nextMediaMetadata) = remember(swipeToSkip, qbInit, queueWindows, mediaMetadata, shuffleOn, repeatMode, unloadedQueueChanged) {
        val none = Pair<MediaMetadata?, MediaMetadata?>(null, null)
        if (!swipeToSkip) return@remember none
        val player = playerConnection.player
        val timeline = player.currentTimeline
        // Repeat one would make the song its own neighbour; the player treats it as off when
        // skipping, and so does the strip.
        val mode = if (repeatMode == REPEAT_MODE_ONE) REPEAT_MODE_OFF else repeatMode
        if (!timeline.isEmpty) {
            val index = player.currentMediaItemIndex
            fun at(i: Int): MediaMetadata? =
                i.takeIf { it != C.INDEX_UNSET && it != index }?.let { player.getMediaItemAt(it).metadata }
            return@remember Pair(at(timeline.getPreviousWindowIndex(index, mode, shuffleOn)), at(timeline.getNextWindowIndex(index, mode, shuffleOn)))
        }
        // After a cold start the player is empty until something is played: the restored queue
        // sits in the queue board and only goes into the player on play. The neighbours come
        // from there meanwhile.
        val queue = playerConnection.service.queueBoard.getCurrentQueue() ?: return@remember none
        val songs = queue.getCurrentQueueShuffled()
        val position = queue.getQueuePosShuffled()
        if (position !in songs.indices) return@remember none
        fun around(step: Int): MediaMetadata? {
            val i = position + step
            return when {
                i in songs.indices -> songs[i]
                mode == REPEAT_MODE_ALL && songs.size > 1 -> songs[(i + songs.size) % songs.size]
                else -> null
            }
        }
        Pair(around(-1), around(1))
    }
    // Keyed by song in the strip, so a neighbour that is the same song as this one (a queue with a
    // song twice in a row, or either side of this one) is left out rather than crashing it. When
    // both neighbours are one song, a two-song queue on repeat, next is the one kept: forward is
    // the swipe people make, and dropping it left that queue with no working swipe at all.
    val nextInStrip = nextMediaMetadata?.takeIf { it.id != mediaMetadata?.id }
    val previousInStrip = previousMediaMetadata?.takeIf { it.id != mediaMetadata?.id && it.id != nextInStrip?.id }
    val mediaItems = listOfNotNull(previousInStrip, mediaMetadata, nextInStrip)
    val currentMediaIndex = if (previousInStrip != null) 1 else 0


    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )

    val glassIntensity by rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)

    val liquidGlass by rememberPreference(PlayerLiquidGlassKey, defaultValue = false)
    val swipeToDismissPlayer by rememberPreference(SwipeToDismissPlayerKey, defaultValue = true)
    val groupedControls by rememberPreference(GroupedPlayerControlsKey, defaultValue = true)
    val playerBackdrop = rememberLayerBackdrop()

    val seekIncrement by rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )

    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    val onBackgroundColor = when (playerBackground) {
        PlayerBackgroundStyle.FOLLOW_THEME -> MaterialTheme.colorScheme.secondary
        else ->
            if (useDarkTheme)
                MaterialTheme.colorScheme.onSurface
            else {
                val c = MaterialTheme.colorScheme.secondary
                c.copy(alpha = 1f, red = c.red - 0.2f, green = c.green - 0.2f, blue = c.blue - 0.2f)
            }
    }

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)
    val buttonsStyle by rememberEnumPreference(PlayerButtonsStyleKey, defaultValue = PlayerButtonsStyle.CLASSIC)
    val queueAsButton by rememberPreference(QueueButtonKey, defaultValue = false)

    var position by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.currentPosition)
    }
    var duration by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.duration)
    }
    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }

    // A restored song shows where it stopped until it is loaded into the player, which on a cold
    // start is not until play (see PlayerConnection.restoredPosition).
    val restoredPosition by playerConnection.restoredPosition.collectAsState()
    val shownPosition = restoredPosition ?: position
    // The song's own length while the player has none: before a restored song is loaded, and while
    // any song is still loading, when the bar used to drop to the start and the length went blank.
    val shownDuration = if (duration == C.TIME_UNSET) {
        mediaMetadata?.duration?.takeIf { it > 0 }?.let { it * 1000L } ?: duration
    } else {
        duration
    }

    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }


    /**
     * Background style actually used, which is not always the one the user picked.
     *
     * BLUR draws the cover art stretched across the whole window with ContentScale.FillBounds,
     * from a 100px source. On a phone that is about a 10x upscale and the blur hides it. On a
     * 2560px wide tablet it is a 25x upscale, and FillBounds also squashes a square cover into a
     * 16:10 box, so it reads as a blocky stretched mess rather than a background.
     *
     * On big screens the gradient is simply the better picture: it is built from the artwork's own
     * colours, so it still feels like the album, and it cannot pixelate because there are no pixels
     * to stretch. The preference is left alone, so a phone keeps whatever was chosen.
     */
    val effectivePlayerBackground = if (playerBackground == PlayerBackgroundStyle.BLUR && context.supportsWideScreen()) {
        PlayerBackgroundStyle.GRADIENT
    } else {
        playerBackground
    }

    // gradient colours
    LaunchedEffect(mediaMetadata, effectivePlayerBackground) {
        // Keyed on the EFFECTIVE style. Keying on the raw preference would mean a tablet that has
        // BLUR selected never extracts any colours, and then renders a gradient of nothing.
        if (effectivePlayerBackground != PlayerBackgroundStyle.GRADIENT || context.isPowerSaver()) {
            return@LaunchedEffect
        }

        withContext(coilCoroutine) {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(mediaMetadata?.getThumbnailModel(100, 100))
                    .allowHardware(false)
                    .build()
            )

            val bitmap = result.image?.toBitmap()?.extractGradientColors()
            bitmap?.let {
                gradientColors = it
            }
        }
    }

    // Twice a second, and only while somebody can see it. Composition is not disposed when the
    // screen goes off, so a bare LaunchedEffect here polled the player all night on any device
    // left playing: 7200 reads an hour to move a progress bar nobody was looking at. STARTED
    // rather than RESUMED because the mini player is still visible behind a dialog or a partially
    // covering screen, and the bar should keep moving there.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playbackState, lifecycleOwner) {
        if (playbackState != STATE_READY) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(500)
                position = playerConnection.player.currentPosition
                duration = playerConnection.player.duration
            }
        }
    }

    LaunchedEffect(qbInit, playerConnection.service.queueBoard.masterQueues.toList()) {
        Log.d(TAG, "Queues changed. qbInit = $qbInit")
        if (qbInit && !playerConnection.service.queueBoard.masterQueues.isEmpty() && state.isDismissed) {
            Log.d(TAG, "Triggering sheet collapseSoft")
            state.collapseSoft()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabMode = context.tabMode()
    val wideScreen = context.supportsWideScreen()

    /**
     * The two-pane landscape layout, i.e. the only configuration that actually has the artwork
     * competing with the controls for height. tabMode and narrow (<600dp) landscape both fall
     * through to the stacked portrait layout, which already reserved the queue peek correctly and
     * must not be perturbed.
     */
    val landscapeTwoPane = isLandscape && !tabMode && wideScreen

    /**
     * A tablet in landscape, which is the case with width to spare for a permanent queue pane.
     *
     * tabMode is already "tablet sized AND landscape", so this is just tabMode with the intent
     * spelled out at the point of use.
     */
    val tabletTwoPane = tabMode


    // ignoringVisibility so hiding the bars in immersive landscape does not change this bound and
    // rebuild the sheet state mid-gesture. See the matching note in MainActivity.
    val dismissedBound =
        QueuePeekHeight + WindowInsets.systemBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

    /**
     * The collapsed queue sheet is [QueuePeekHeight] taller than the peek it actually needs, and
     * [QueueSheet] pins its expand arrow to the *top* of that strip. In portrait, spending 96dp on
     * it costs nothing. In landscape the whole player is 384dp tall and the artwork is
     * height-constrained, so those extra 48dp come straight out of the artwork while leaving the
     * arrow floating in the middle of the transport controls.
     *
     * Landscape therefore collapses to exactly the peek. Safe: [rememberBottomSheetState] already
     * defaults collapsedBound to dismissedBound, and a slow release below the collapsed midpoint
     * can only collapse this sheet, because QueueSheet passes no onDismiss. With the two bounds
     * equal, that range is the single point where the sheet already rests. Velocity-based dismiss
     * is unaffected.
     *
     * No queue peek on a tablet either: the queue is permanently in the side pane, so reserving a
     * strip for a preview of it wastes the bottom of the screen, squashes the artwork (which is
     * sized by the height left over) and pushes the handle up into the middle of nowhere.
     *
     * The player keeps the same space clear at the bottom whether the queue opens from the sheet
     * or from its button. Giving the strip back when the button took over let everything above it
     * drop, 96dp in portrait, and the controls are pressed from memory, so turning the button on
     * must not move them.
     */
    val queueReserve = if (landscapeTwoPane || tabletTwoPane) dismissedBound else dismissedBound + QueuePeekHeight

    /**
     * Whether the queue opens from its button rather than from the sheet's handle.
     *
     * Only while a song is loaded. The button sits with the controls, which are not drawn without
     * one, so a queue emptied by swiping its last song away left the player with no controls and,
     * with the handle gone as well, no way to the saved queues. The handle comes back until
     * something is loaded again, and since the player keeps queueReserve clear either way, nothing
     * moves when it does.
     */
    val queueOnButton = queueAsButton && mediaMetadata != null

    /**
     * With the queue on a button the sheet collapses to nothing at all: it sits wholly below the
     * screen, so the player has no handle and no pull-up gesture at the bottom, and the button is
     * the only way up. Opened, it is the same sheet with the same queue in it, and back or a drag
     * down puts it away again.
     */
    val queueSheetState = rememberBottomSheetState(
        dismissedBound = if (queueOnButton) 0.dp else dismissedBound,
        expandedBound = state.expandedBound,
        collapsedBound = if (queueOnButton) 0.dp else queueReserve,
        initialAnchor = 1
    )

    // Nothing may move while the queue is on screen. The service does the re-planning on a
    // background thread and has no idea what is visible, so the screen tells it.
    //
    // queueSheetState stays expanded when the player sheet collapses or is dismissed over it, so
    // the player's own state is part of the answer. The tablet's queue pane shows the same rows
    // without ever expanding this sheet, so tabletTwoPane counts as the queue being on screen.
    LaunchedEffect(queueSheetState.isExpanded, tabletTwoPane, state.isCollapsed, state.isDismissed) {
        playerConnection.service.queueSheetOpen = computeQueueSheetOpen(
            queueSheetExpanded = queueSheetState.isExpanded,
            tabletTwoPane = tabletTwoPane,
            playerCollapsed = state.isCollapsed,
            playerDismissed = state.isDismissed,
        )
    }

    DisposableEffect(Unit) {
        onDispose { playerConnection.service.queueSheetOpen = false }
    }

    /**
     * Whether landscape is in lean-back mode, with the system bars hidden.
     *
     * Latched rather than derived straight from `state.isExpanded`, which is true only at the exact
     * expanded bound and therefore flips false on the very first pixel of a drag. That put the
     * system bars back mid-gesture, and since they carry window insets the entire player relaid out
     * underneath the finger: the artwork visibly disappeared the moment you started dragging and
     * came back smaller. The same thing happened in reverse while opening.
     *
     * So: enter when fully expanded, leave only once the sheet has actually settled at collapsed or
     * dismissed, and hold the current value for everything in between. Insets then change once, at
     * a moment when the layout is already changing anyway, instead of twice per gesture.
     */
    var immersiveLandscape by remember { mutableStateOf(false) }
    LaunchedEffect(isLandscape, state.isExpanded, state.isCollapsed, state.isDismissed) {
        immersiveLandscape = when {
            !isLandscape -> false
            state.isExpanded -> true
            state.isCollapsed || state.isDismissed -> false
            else -> immersiveLandscape // mid-drag, hold
        }
    }

    /**
     * Landscape with the player open is a lean-back "now playing" mode: hide the system bars, since
     * the user is looking at artwork rather than reading. An edge swipe brings them back
     * transiently, and onDispose restores them so collapsing or rotating cannot strand the user
     * without a status bar.
     *
     * Gated on [state].isExpanded so the mini player does not take over the screen.
     */
    val currentView = LocalView.current
    DisposableEffect(immersiveLandscape) {
        val controller = (currentView.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, currentView)
        }

        if (immersiveLandscape) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    /**
     * This composable's two reasons to keep the screen on, contributed to [KeepScreenOnController]
     * rather than written to View.keepScreenOn directly. It is one boolean on one View, so two
     * independent writers race: whichever disposes last wins and silently clears the other's
     * request, which is exactly how a recognition listen elsewhere used to get its keep-awake
     * cleared by an unrelated play/pause here. The holder ORs every owner's reasons together
     * instead, and each owner only ever adds or removes its own.
     *
     * The lyrics reason counts only while this player sheet is actually open (not collapsed or
     * dismissed): showLyrics is a persisted preference that outlives the sheet, and without this
     * gate it kept the screen on everywhere in the app, forever, once lyrics had ever been shown.
     *
     * [isPlaying] is part of the landscape condition on purpose. "Landscape holds the screen awake"
     * is about watching playback, and a player left paused overnight in landscape would otherwise
     * hold the screen on until the battery died. Lyrics keep it awake whether or not anything is
     * playing.
     */
    val lyricsKeepAwake = showLyrics && !state.isCollapsed && !state.isDismissed
    val landscapeKeepAwake = immersiveLandscape && isPlaying
    DisposableEffect(currentView, lyricsKeepAwake, landscapeKeepAwake) {
        KeepScreenOnController.set(currentView, KeepScreenOnReason.LYRICS, lyricsKeepAwake)
        KeepScreenOnController.set(currentView, KeepScreenOnReason.IMMERSIVE_LANDSCAPE, landscapeKeepAwake)
        onDispose {
            KeepScreenOnController.set(currentView, KeepScreenOnReason.LYRICS, false)
            KeepScreenOnController.set(currentView, KeepScreenOnReason.IMMERSIVE_LANDSCAPE, false)
        }
    }


    // Chromatic shock ripple, adapted from notK50BML/OuterTune. Wraps the whole sheet so the
    // refraction crosses the background and the controls together, which is what makes the
    // rainbow fringing show up along element edges. Gated on the player being expanded, so the
    // mini player never pays for it, and a no-op below API 33.
    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            Log.v(TAG, "PLR-2.1")
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                    .fillMaxSize()
                    // Publishes the artwork and gradient we already draw as a backdrop so the glass
                    // panel can refract it. Capturing here keeps this self-contained: no MainActivity
                    // change, and the lens only ever sees pixels the player itself drew.
                    .layerBackdrop(playerBackdrop)
            ) {
                // "Glass" look, requested in upstream #1282. The one-off
                // pre_rel-0.10.1-b1-glass build differed from stock only in this background: it
                // dropped the flat overlay wash, halved the gradient, put the blurred artwork at
                // half alpha and used a single blur radius. Rather than ship a second app, all four
                // are interpolated by `glassIntensity`, so 0f reproduces stock 0.10.1 exactly and
                // 1f reproduces that build exactly.
                // Rides the single Liquid glass switch. This half needs no RuntimeShader, so it
                // still works below API 33 where the refraction cannot.
                val glassT = if (liquidGlass) glassIntensity.coerceIn(0f, 1f) else 0f

                val stockOverlayAlpha = if (useDarkTheme) 0.4f else 0.55f
                val overlayColor = (if (useDarkTheme) Color.Black else Color.White)
                    .copy(alpha = lerp(stockOverlayAlpha, 0f, glassT))
                val artworkAlpha = lerp(1f, 0.5f, glassT)
                val gradientAlpha = lerp(0.8f, 0.4f, glassT)
                val blurRadius = lerp(if (useDarkTheme) 150f else 100f, 100f, glassT).dp
                AnimatedContent(
                    targetState = mediaMetadata,
                    transitionSpec = {
                        fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
                    }
                ) { metadata ->
                    if (effectivePlayerBackground == PlayerBackgroundStyle.BLUR) {
                        Log.v(TAG, "PLR-2.2a")
                        AsyncImage(
                            model = metadata?.getThumbnailModel(100, 100),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(blurRadius)
                                .alpha(artworkAlpha)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(overlayColor)
                        )
                    }
                }

                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
                    }
                ) { colors ->
                    if (effectivePlayerBackground == PlayerBackgroundStyle.GRADIENT && colors.size >= 2) {
                        Log.v(TAG, "PLR-2.2b")
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(colors), alpha = gradientAlpha)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(overlayColor)
                        )
                    }
                }

                if (playerBackground != PlayerBackgroundStyle.FOLLOW_THEME && showLyrics) {
                    Log.v(TAG, "PLR-2.2c")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (useDarkTheme) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f))
                    )
                }
            }
        },
        // Transparent under glass: the collapsed sheet otherwise paints a solid fill over
        // exactly the region the dock refracts, so ~70% of what the dock would show is flat colour.
        // Only when the app backdrop exists, though, not whenever the setting is on: the rail
        // layout and anything below API 33 have none, the mini player then draws no panel of its
        // own, and a transparent sheet left its title lying over the list underneath.
        collapsedBackgroundColor = if (liquidGlass && LocalAppBackdrop.current != null) Color.Transparent
        else MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        // performFling only dismisses when this is non-null and falls back to collapse() when it
        // is not, so withholding the callback is what actually prevents the dismiss. BottomSheet
        // reads it through rememberUpdatedState, without which toggling this at runtime would not
        // reach the already-running gesture coroutine.
        onDismiss = if (swipeToDismissPlayer) {
            { playerConnection.softKillPlayer() }
        } else null,
        // Belt and braces on top of that: with the dismiss off, the mini player should not budge
        // downwards either, rather than sliding away and springing back as if it were about to go.
        pinAtCollapsed = !swipeToDismissPlayer,
        collapsedContent = {
            MiniPlayer(
                position = shownPosition,
                duration = shownDuration
            )
        }
    ) {
        Log.v(TAG, "PLR-3.1")

        val lol: @Composable BoxScope.() -> Unit = {
            /**
             * Landscape gets a tighter gutter and a larger type scale. In landscape the controls
             * share the width with the artwork, so the column is narrow and the default 32dp
             * gutter wastes space the progress bar and title want; the screen also sits further
             * from the eye than a held phone, so the text runs a size up.
             *
             * Hoisted to this scope because both [controlsContent] and the two-pane landscape
             * column need them.
             */
            val landscapePlayer = isLandscape && !tabMode
            val hPadding = if (landscapePlayer) 24.dp else PlayerHorizontalPadding
            val titleSize = if (landscapePlayer) 25.sp else TextUnit.Unspecified
            val artistSize = if (landscapePlayer) 19.sp else TextUnit.Unspecified

            /**
             * Transport controls run larger in landscape, where there is room for them.
             *
             * Lyrics do not change them. The play button used to drop to 56dp whenever lyrics were
             * showing, to give the lyrics a little more height, and the controls jumping to a smaller
             * size as lyrics came up read as the player shrinking its buttons. The lyrics take the
             * artwork's place, which is plenty of room without the controls giving any up.
             */
            val transportIconSize = if (landscapePlayer) 42.dp else 32.dp
            val playButtonSize = if (landscapePlayer) 84.dp else 72.dp

            // Sleep timer, lyrics and the menu one tap away on the player itself, not only inside
            // the menu (yuuichi-s #54). The timer's fields are Compose state, so the buttons follow it.
            val (sleepTimerOn, sleepTimerLeft) = rememberSleepTimerState(playerConnection)
            var showSleepTimerDialog by remember { mutableStateOf(false) }
            if (showSleepTimerDialog) {
                SleepTimerDialog(playerConnection) { showSleepTimerDialog = false }
            }

            // Glass for the connected buttons whenever the player has it, grouped controls or not:
            // they are separate buttons either way.
            val buttonBackdrop = if (liquidGlass) playerBackdrop else null

            val shareSong: () -> Unit = {
                mediaMetadata?.let { song ->
                    ActivityLog.note(context, database, song.id, SignalKind.SHARE)
                    val intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.id}")
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }
            }

            val showPlayerMenu: () -> Unit = {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onDismiss = menuState::dismiss
                    )
                }
            }

            // With the queue on a button, this is the only way the queue sheet comes up.
            val openQueue: () -> Unit = {
                queueSheetState.expandSoft()
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }

            // Classic: circles beside the title. Tertiary on the timer while it runs, and a
            // tap then cancels it, as the menu's entry does. With the queue on a button it comes
            // first, so like and the menu stay where they always were at the end of the row.
            val classicButtons: @Composable RowScope.() -> Unit = {
                Log.v(TAG, "PLR-3.xa")
                Spacer(modifier = Modifier.width(10.dp))

                if (queueAsButton) {
                    PlayerCircleButton(
                        painter = rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic),
                        contentDescription = stringResource(R.string.queue),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        onClick = openQueue
                    )

                    Spacer(modifier = Modifier.width(7.dp))
                }

                PlayerCircleButton(
                    painter = rememberVectorPainter(Icons.Rounded.Timer),
                    contentDescription = stringResource(R.string.sleep_timer),
                    container = if (sleepTimerOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    content = if (sleepTimerOn) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        if (sleepTimerOn) playerConnection.service.sleepTimer.clear()
                        else showSleepTimerDialog = true
                    }
                )

                Spacer(modifier = Modifier.width(7.dp))

                PlayerCircleButton(
                    painter = painterResource(if (currentSong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = null,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = playerConnection::toggleLike
                )

                Spacer(modifier = Modifier.width(7.dp))

                PlayerCircleButton(
                    painter = rememberVectorPainter(Icons.Rounded.MoreVert),
                    contentDescription = stringResource(R.string.options),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = showPlayerMenu
                )
            }

            // Connected: share and like beside the title as one pair, round on the outside and
            // nearly square where they meet. A local file has no link to share, so it gets like alone.
            val connectedButtons: @Composable RowScope.() -> Unit = {
                Log.v(TAG, "PLR-3.xa")
                Spacer(modifier = Modifier.width(10.dp))

                val canShare = mediaMetadata?.isLocal == false
                val liked = currentSong?.song?.liked == true
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ConnectedButtonGap),
                    modifier = Modifier.offset(y = 4.dp)
                ) {
                    if (canShare) {
                        PlayerActionSegment(
                            painter = rememberVectorPainter(Icons.Rounded.Share),
                            contentDescription = stringResource(R.string.share),
                            shape = connectedShape(first = true, last = false),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = shareSong,
                            backdrop = buttonBackdrop,
                            glassIntensity = glassIntensity,
                        )
                    }
                    PlayerActionSegment(
                        painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                        contentDescription = null,
                        shape = connectedShape(first = !canShare, last = true),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        onClick = playerConnection::toggleLike,
                        backdrop = buttonBackdrop,
                        glassIntensity = glassIntensity,
                    )
                }
            }

            val actionButtons = if (buttonsStyle == PlayerButtonsStyle.CONNECTED) connectedButtons else classicButtons

            /**
             * Whether the buttons by the title sit beside it, sharing its line.
             *
             * Always in portrait. Phone landscape used to put them in a row above the title, and a
             * long title then ran on right underneath share and like, touching them wherever the
             * short landscape screen left no gap. Connected keeps its pair beside the title there
             * too now, which also hands that row's height back to the controls. Classic keeps its
             * row at the top of the two-pane column, lined up with the artwork, and narrow
             * landscape keeps the buttons above the title.
             *
             * A tablet in landscape shows them nowhere, and keeps it that way unless the queue is
             * on a button in Classic. Classic's queue button lives in its row of circles, and
             * without that row the saved queues would be out of reach, so the row sits beside the
             * title there too. Connected's queue button is in the row under the controls, which a
             * tablet already shows.
             */
            val buttonsBesideTitle = !isLandscape ||
                (landscapeTwoPane && buttonsStyle == PlayerButtonsStyle.CONNECTED) ||
                (tabMode && queueAsButton && buttonsStyle == PlayerButtonsStyle.CLASSIC)

            // Lyrics, the sleep timer and the menu under the transport controls, filled while the
            // thing they control is on. The timer shows what is left, so a running timer is visible
            // without opening anything, and a tap on it cancels, as the menu's entry does. With the
            // queue on a button it joins the group in front of lyrics, the other button that opens
            // a view, and the three stay exactly where they sit with it off.
            val quickActions: @Composable () -> Unit = {
                val inactive = onBackgroundColor.copy(alpha = 0.12f)
                CentredRowWithLeading(
                    gap = ConnectedButtonGap,
                    gutter = hPadding,
                    leading = if (queueAsButton) {
                        {
                            PlayerActionSegment(
                                painter = rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic),
                                contentDescription = stringResource(R.string.queue),
                                shape = connectedShape(first = true, last = false),
                                container = inactive,
                                filled = false,
                                content = onBackgroundColor,
                                width = QuickActionWidth,
                                backdrop = buttonBackdrop,
                                glassIntensity = glassIntensity,
                                onClick = openQueue,
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPadding)
                        .padding(top = 16.dp)
                ) {
                    PlayerActionSegment(
                        painter = rememberVectorPainter(Icons.Rounded.Lyrics),
                        contentDescription = stringResource(R.string.toggle_lyrics),
                        shape = connectedShape(first = !queueAsButton, last = false),
                        container = if (showLyrics) MaterialTheme.colorScheme.primary else inactive,
                        filled = showLyrics,
                        content = if (showLyrics) MaterialTheme.colorScheme.onPrimary else onBackgroundColor,
                        width = QuickActionWidth,
                        backdrop = buttonBackdrop,
                        glassIntensity = glassIntensity,
                        onClick = {
                            if (!showLyrics) mediaMetadata?.id?.let { ActivityLog.note(context, database, it, SignalKind.LYRICS) }
                            showLyrics = !showLyrics
                        },
                    )
                    PlayerActionSegment(
                        painter = rememberVectorPainter(Icons.Rounded.Timer),
                        contentDescription = stringResource(R.string.sleep_timer),
                        shape = connectedShape(first = false, last = false),
                        container = if (sleepTimerOn) MaterialTheme.colorScheme.tertiary else inactive,
                        filled = sleepTimerOn,
                        content = if (sleepTimerOn) MaterialTheme.colorScheme.onTertiary else onBackgroundColor,
                        width = QuickActionWidth,
                        label = if (sleepTimerOn && sleepTimerLeft > 0) makeTimeString(sleepTimerLeft) else null,
                        backdrop = buttonBackdrop,
                        glassIntensity = glassIntensity,
                        onClick = {
                            if (sleepTimerOn) playerConnection.service.sleepTimer.clear()
                            else showSleepTimerDialog = true
                        },
                    )
                    PlayerActionSegment(
                        painter = rememberVectorPainter(Icons.Rounded.MoreVert),
                        contentDescription = stringResource(R.string.options),
                        shape = connectedShape(first = false, last = true),
                        container = inactive,
                        filled = false,
                        content = onBackgroundColor,
                        width = QuickActionWidth,
                        backdrop = buttonBackdrop,
                        glassIntensity = glassIntensity,
                        onClick = showPlayerMenu,
                    )
                }
            }

            val controlsContent: @Composable ColumnScope.(MediaMetadata) -> Unit = { mediaMetadata ->
                Log.v(TAG, "PLR-3.xb")
                val playPauseRoundness by animateDpAsState(
                    targetValue = if (isPlaying) 24.dp else 36.dp,
                    animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                    label = "playPauseRoundness"
                )

                // Action buttons for landscape, above the title. The two-pane layout hoists these
                // to the top of its controls column instead, so this only covers narrow landscape,
                // which still uses the stacked layout.
                if (landscapePlayer && !wideScreen) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = hPadding, end = hPadding, bottom = 16.dp)
                    ) {
                        actionButtons()
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPadding)
                ) {
                    // The title and artists only ever get the width left of the buttons. They
                    // scroll when they do not fit, and fade out at the end rather than stopping
                    // mid-letter against the first button.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaMetadata.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = titleSize,
                            color = onBackgroundColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fadeWhenClipped()
                                .basicMarquee(
                                    iterations = 1,
                                    initialDelayMillis = 3000
                                )
                                .clickable(enabled = mediaMetadata.album != null) {
                                    navController.navigate("album/${mediaMetadata.album!!.id}")
                                    state.collapseSoft()
                                }
                        )

                        Row(modifier = Modifier.fadeWhenClipped()) {
                            mediaMetadata.artists.fastForEachIndexed { index, artist ->
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = artistSize,
                                    color = onBackgroundColor,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .basicMarquee(
                                            iterations = 1,
                                            initialDelayMillis = 5000
                                        )
                                        .clickable(enabled = artist.id != null) {
                                            mediaMetadata?.id?.let { ActivityLog.note(context, database, it, SignalKind.ARTIST_PAGE) }
                                            navController.navigate("artist/${artist.id}")
                                            state.collapseSoft()
                                        }
                                )

                                if (index != mediaMetadata.artists.lastIndex) {
                                    // One line even when squeezed to nothing by a long name before
                                    // it, or it wraps and makes the artist line taller.
                                    Text(
                                        text = ", ",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = artistSize,
                                        color = onBackgroundColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (buttonsBesideTitle) {
                        actionButtons()
                    }
                }

                val seekInteraction = remember { MutableInteractionSource() }
                Slider(
                    value = (sliderPosition ?: shownPosition).toFloat(),
                    valueRange = 0f..(if (shownDuration == C.TIME_UNSET) 0f else shownDuration.toFloat()),
                    onValueChange = {
                        sliderPosition = it.toLong()
                        // slider too granular for this haptic to feel right
//                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    },
                    onValueChangeFinished = {
                        sliderPosition?.let {
                            if (playerConnection.player.currentMediaItem == null) {
                                // Nothing is loaded yet, so there is nothing to seek: move where
                                // the restored song will start instead, whether or not it already
                                // had a saved point (restoredPosition is null when the process died
                                // before ever pausing, since ResumePoint.afterTransition leaves the
                                // saved position at TIME_UNSET for an ordinary song change).
                                playerConnection.service.queueBoard.getCurrentQueue()?.lastSongPos = it
                                playerConnection.restoredPosition.value = it
                            } else {
                                playerConnection.player.seekTo(it)
                                position = it
                            }
                        }
                        sliderPosition = null
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    // Material 3 Expressive: a bar thumb standing clear of a thick track, with the
                    // stop dot at the end, the same slider the backup settings already use.
                    interactionSource = seekInteraction,
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = seekInteraction,
                            thumbSize = DpSize(4.dp, 36.dp),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(14.dp),
                            thumbTrackGapSize = 5.dp,
                            trackInsideCornerSize = 4.dp,
                        )
                    },
                    modifier = Modifier.padding(horizontal = hPadding)
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPadding + 4.dp)
                ) {
                    Text(
                        text = makeTimeString(sliderPosition ?: shownPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = onBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = if (shownDuration != C.TIME_UNSET) makeTimeString(shownDuration) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = onBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hPadding)
                    .then(
                        if (liquidGlass && groupedControls) {
                            Modifier.drawBackdrop(
                                backdrop = playerBackdrop,
                                shape = { RoundedCornerShape(32.dp) },
                                effects = {
                                    blur(4f.dp.toPx())
                                    lens(
                                        refractionHeight = 24f.dp.toPx() * glassIntensity,
                                        refractionAmount = 32f.dp.toPx() * glassIntensity,
                                        depthEffect = true,
                                        chromaticAberration = true
                                    )
                                }
                            )
                        } else Modifier
                    )
                ) {
                    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off,
                            modifier = Modifier
                                .size(transportIconSize)
                                .padding(4.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            enabled = playerConnection.player.currentMediaItem != null,
                            onClick = {
                                playerConnection.triggerShuffle()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = Icons.Rounded.SkipPrevious,
                            enabled = canSkipPrevious,
                            modifier = Modifier
                                .size(transportIconSize)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            onClick = {
                                if (playerConnection.player.currentMediaItem == null) {
                                    playerConnection.service.queueBoard.setCurrQueue()
                                }
                                playerConnection.player.seekToPrevious()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }

                    if (seekIncrement != SeekIncrement.OFF) {
                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.FastRewind,
                                modifier = Modifier
                                    .size(transportIconSize)
                                    .align(Alignment.Center),
                                color = onBackgroundColor,
                                enabled = playerConnection.player.currentMediaItem != null,
                                onClick = {
                                    playerConnection.player.seekTo(playerConnection.player.currentPosition - seekIncrement.millisec)
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(PlayButtonGap))

                    Box(
                        modifier = Modifier
                            .size(playButtonSize)
                            .animateContentSize()
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                // One branch. setCurrQueue loads the queue but never prepares,
                                // and togglePlayPause used to flip playWhenReady instead of
                                // starting, so this took up to three taps to make a sound.
                                if (playerConnection.player.currentMediaItem == null) {
                                    playerConnection.service.queueBoard.setCurrQueue()
                                }
                                playerConnection.player.togglePlayPause()
                                // play/pause is slightly harder haptic
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                    ) {
                        Image(
                            imageVector = if (playbackState == STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp)
                        )
                    }

                    Spacer(Modifier.width(PlayButtonGap))

                    if (seekIncrement != SeekIncrement.OFF) {
                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.FastForward,
                                modifier = Modifier
                                    .size(transportIconSize)
                                    .align(Alignment.Center),
                                color = onBackgroundColor,
                                enabled = playerConnection.player.currentMediaItem != null,
                                onClick = {
                                    //ExoPlayer seek increment can only be set in builder
                                    //playerConnection.player.seekForward()
                                    playerConnection.player.seekTo(playerConnection.player.currentPosition + seekIncrement.millisec)
                                }
                            )
                        }
                    }



                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = Icons.Rounded.SkipNext,
                            enabled = canSkipNext,
                            modifier = Modifier
                                .size(transportIconSize)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            onClick = {
                                // Cold start: the restored queue is only in the queue board until
                                // something loads it into the player, same as Previous and Play above.
                                if (playerConnection.player.currentMediaItem == null) {
                                    playerConnection.service.queueBoard.setCurrQueue()
                                }
                                playerConnection.player.seekToNext()
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
                                .size(transportIconSize)
                                .padding(4.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            enabled = playerConnection.player.currentMediaItem != null,
                            onClick = {
                                playerConnection.player.toggleRepeatMode()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }
                }

                if (buttonsStyle == PlayerButtonsStyle.CONNECTED) quickActions()
            }


            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && !tabMode && wideScreen) {
                val vPadding = max(
                    WindowInsets.safeDrawing.getTop(LocalDensity.current),
                    WindowInsets.safeDrawing.getBottom(LocalDensity.current)
                )
                // Floor this. It is derived from safeDrawing, which collapses to zero once the
                // system bars are hidden, and with no floor the artwork expands flush to the top
                // edge and its rounded corners get clipped by the display.
                val vPaddingDp = with(LocalDensity.current) { vPadding.toDp() }.coerceAtLeast(16.dp)
                val verticalInsets = WindowInsets(left = 0.dp, top = vPaddingDp, right = 0.dp, bottom = vPaddingDp)
                BoxWithConstraints(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).add(verticalInsets)
                        )
                        .fillMaxSize()
                ) {
                val controlsWidth = landscapeControlsWidth(
                    available = maxWidth,
                    playButton = playButtonSize,
                    slots = if (seekIncrement != SeekIncrement.OFF) 6 else 4,
                    gutter = hPadding,
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    // The queue sheet's peek is reserved on the controls column alone, not on this
                    // Row. The arrow is horizontally centred on the window, well clear of the
                    // artwork's half, so making the artwork dodge it vertically only wasted height.
                    // CenterStart rather than Center: the artwork hugs the inner edge instead of
                    // floating in the middle of its half.
                    BoxWithConstraints(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(state.preUpPostDownNestedScrollConnection)
                    ) {
                        Log.v(TAG, "PLR-3.2a")
                        if (!swipeToSkip) {
                            Thumbnail(
                                sliderPositionProvider = { sliderPosition },
                                modifier = Modifier
//                                .width(horizontalLazyGridItemWidth)
                                    .animateContentSize(),
                                showLyricsOnClick = true,
                                customMediaMetadata = mediaMetadata
                            )
                        } else {
                            SwipeableArtwork(
                                mediaItems = mediaItems,
                                currentIndex = currentMediaIndex,
                                scrollable = state.isExpanded,
                                expanded = state.isExpanded,
                                contentPadding = PaddingValues(vertical = 16.dp),
                                onSkip = { forward -> skipFromArtwork(playerConnection, forward) },
                            ) {
                                Thumbnail(
                                    sliderPositionProvider = { sliderPosition },
                                    modifier = Modifier
                                        .width(maxWidth)
                                        .animateContentSize(),
                                    showLyricsOnClick = true,
                                    customMediaMetadata = it
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            // The same width, lyrics or not. Lyrics used to narrow this column to
                            // make their pane wider, which squeezed the glass bar and packed the
                            // transport buttons closer together, so the controls looked smaller
                            // with lyrics on. The lyrics now simply take the artwork's place.
                            .width(controlsWidth)
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                            // Only this column dodges the queue sheet's peek; the artwork does not
                            // need to, since the arrow is centred on the window and never reaches
                            // the artwork's half. The collapsed sheet covers queueReserve up from
                            // the bottom of the screen, and the Row already keeps vPaddingDp of that
                            // clear, so only the difference is taken here. Taking all of it again
                            // cost 16dp of a height that is only 384dp on a 1440p phone, and that
                            // was enough to crush the row of buttons under the controls.
                            .padding(bottom = (queueReserve - vPaddingDp).coerceAtLeast(0.dp))
                    ) {
                        // Classic's buttons sit at the very top of the column rather than riding
                        // the centred block, so they line up with the top of the artwork.
                        // Connected's pair sits beside the title instead, see buttonsBesideTitle.
                        if (!buttonsBesideTitle) {
                            Row(
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = hPadding)
                            ) {
                                actionButtons()
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.shrinkToFitHeight()
                            ) {
                                controlsContent(it)
                            }
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
                }
            } else {
                /**
                 * Two panes on a tablet: player on the left, the up-next queue on the right.
                 *
                 * A tablet in landscape is excluded from the phone's landscape layout by tabMode,
                 * so it falls through to this stacked one and the artwork, title and controls end
                 * up spread across a 2560px width with the entire right half empty. Splitting the
                 * width and putting the queue in the space it was wasting is the whole change; the
                 * player column below is untouched apart from being handed half the room.
                 *
                 * Not a Row on phones. This branch is also what portrait and small landscape use,
                 * and they have no width to spare.
                 */
                Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .then(if (tabletTwoPane) Modifier.weight(1f) else Modifier)
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                        // On a tablet the collapsed sheet is just a handle, not a peek at the queue,
                        // because the queue is already in the side pane. Reserving the full peek
                        // height under the controls leaves a dead band at the bottom, holds the
                        // controls up, and costs the artwork the same height twice over, since the
                        // artwork is sized from whatever the column has left. Reserve only the
                        // handle.
                        .padding(
                            bottom = if (tabletTwoPane) TabletQueueHandleReserve
                            else queueReserve
                        )
                ) {
                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(state.preUpPostDownNestedScrollConnection)
                    ) {
                        Log.v(TAG, "PLR-3.2b")
                        if (!swipeToSkip) {
                            Thumbnail(
                                modifier = Modifier
//                                .width(horizontalLazyGridItemWidth)
                                    .animateContentSize(),
                                sliderPositionProvider = { sliderPosition },
                                showLyricsOnClick = true,
                                customMediaMetadata = mediaMetadata
                            )
                        } else {
                            SwipeableArtwork(
                                mediaItems = mediaItems,
                                currentIndex = currentMediaIndex,
                                scrollable = state.isExpanded,
                                expanded = state.isExpanded,
                                modifier = Modifier.padding(vertical = QueuePeekHeight / 2),
                                onSkip = { forward -> skipFromArtwork(playerConnection, forward) },
                            ) {
                                Thumbnail(
                                    modifier = Modifier
                                        .width(maxWidth)
                                        .animateContentSize(),
                                    sliderPositionProvider = { sliderPosition },
                                    showLyricsOnClick = true,
                                    customMediaMetadata = it
                                )
                            }
                        }
                    }

                    // Shrunk as a block when the window is too short for them, as the landscape
                    // column already was. A pop-up window was short enough for the Column to hand
                    // the whole shortfall to whatever came last: the lyrics, timer and menu row
                    // came out a few pixels tall, or, a few dp shorter, the play button squashed
                    // into a pill and that row gone. The cover above had already given up all its
                    // height by then, so the top of the block also keeps clear of the status bar,
                    // which the cover had been covering for.
                    val controlsFor = mediaMetadata
                    if (controlsFor != null) {
                        val statusBar = WindowInsets.systemBars.getTop(LocalDensity.current)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.shrinkToFitHeight(keepClearAtTop = statusBar)
                        ) {
                            controlsContent(controlsFor)
                            Spacer(Modifier.height(24.dp))
                        }
                    } else {
                        Spacer(Modifier.height(24.dp))
                    }
                }

                    // The right pane. Same list the queue sheet shows, so there is one queue
                    // implementation rather than two that drift apart.
                    if (tabletTwoPane) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                                )
                        ) {
                            QueueContent(
                                playerState = state,
                                onTerminate = {
                                    state.dismiss()
                                    playerConnection.service.queueBoard.detachedHead = false
                                },
                                navController = navController,
                                // Up next only. Choosing a different saved queue stays in the
                                // slide-up sheet, where the rest of queue management lives.
                                songsOnly = true
                            )
                        }
                    }
                }
            }

        }
        lol()

        QueueSheet(
            state = queueSheetState,
            playerBottomSheetState = state,
            onTerminate = {
                state.dismiss()
                playerConnection.service.queueBoard.detachedHead = false
            },
            onBackgroundColor = onBackgroundColor,
            navController = navController,
            showHandle = !queueOnButton
        )
    }
}

/**
 * Space kept under the tablet player's controls for the queue handle.
 *
 * Only the arrow needs to fit. The full collapsed-sheet height is for a peek at the queue, which a
 * tablet does not need because the queue is permanently beside the player.
 */
private val TabletQueueHandleReserve = 48.dp

/** The space either side of the play button, between it and its neighbours' slots. */
private val PlayButtonGap = 8.dp

/**
 * The width the landscape player gives each transport button's slot wherever the screen has room
 * for it: the 42dp icon and 7dp either side, so neighbours never touch and the outer buttons stay
 * clear of the glass bar's rounded ends.
 */
private val LandscapeTransportSlot = 56.dp

/**
 * How wide the landscape player's controls column is, out of the [available] width it shares with
 * the artwork.
 *
 * Half, or more where half cannot give each of the transport bar's [slots] buttons a full
 * [LandscapeTransportSlot] beside the play button. Half is not always enough: a phone that only
 * just counts as wide gives the bar under 300dp, and the buttons then crowd together until the
 * outer ones run into the bar's rounded ends. Never more than 62%, though, and that limit wins, so
 * the artwork, or the lyrics in its place, always keeps a proper share of the screen.
 *
 * With the usual four buttons that keeps every icon at full size on the narrowest two-pane row,
 * about 520dp, and gives full slots from 600dp. With the seek buttons on, six buttons get full
 * slots only from about 780dp, which a 1440p phone has, and below about 645dp their icons shrink
 * to fit. That is still never less than the even split gave them, and letting the buttons win
 * there would leave the lyrics a quarter of the width or less.
 *
 * Lyrics play no part in it, so the controls are the same with them on or off.
 */
internal fun landscapeControlsWidth(available: Dp, playButton: Dp, slots: Int, gutter: Dp): Dp {
    val needed = LandscapeTransportSlot * slots + playButton + PlayButtonGap * 2 + gutter * 2
    return (available / 2).coerceAtLeast(needed).coerceAtMost(available * 0.62f)
}

/**
 * Whether some queue list is genuinely on screen right now, i.e. whether the adaptive queue must
 * hold what it has shown rather than replan it out from under somebody looking at it.
 *
 * True while the queue sheet is expanded, or while the tablet's permanent queue pane is showing
 * (it has no sheet to expand), but never while the player itself is collapsed or dismissed: the
 * queue sheet's own expanded state does not reset when the player sheet collapses under it, so
 * without this the flag could get stuck true after the player closes.
 */
internal fun computeQueueSheetOpen(
    queueSheetExpanded: Boolean,
    tabletTwoPane: Boolean,
    playerCollapsed: Boolean,
    playerDismissed: Boolean,
): Boolean = (queueSheetExpanded || tabletTwoPane) && !playerCollapsed && !playerDismissed

/**
 * Lays the content out at its natural height, and when that is more than there is room for,
 * shrinks all of it evenly to fit rather than letting the last rows be squashed.
 *
 * The landscape controls are a column of fixed heights, and a Column hands out height in order,
 * so whatever comes last took the whole shortfall: on a 1440p phone at a larger screen zoom,
 * about 340dp tall, the buttons under the controls came out less than half their height. Scaled
 * as a block they lose a few percent each instead, and where everything fits nothing is touched.
 *
 * [keepClearAtTop] is height in px, the status bar's, that a block which has to shrink stays under.
 */
private fun Modifier.shrinkToFitHeight(keepClearAtTop: Int = 0): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val room = constraints.maxHeight - keepClearAtTop
    if (!constraints.hasBoundedHeight || placeable.height <= room) {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val scale = room.coerceAtLeast(1).toFloat() / placeable.height
        layout(placeable.width, constraints.maxHeight) {
            placeable.placeWithLayer(0, keepClearAtTop) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}

// Wide enough apart to read as separate buttons that belong together, not one slab with a seam.
private val ConnectedButtonGap = 6.dp
private val QuickActionWidth = 56.dp

/** Material 3 Expressive connected buttons: fully round on the group's outer ends, 8dp between. */
private fun connectedShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val round = CornerSize(50)
    val inner = CornerSize(8.dp)
    return RoundedCornerShape(
        topStart = if (first) round else inner,
        bottomStart = if (first) round else inner,
        topEnd = if (last) round else inner,
        bottomEnd = if (last) round else inner,
    )
}

/** How close to the edge of the gutter a leading button may come before the row moves over. */
private val LeadingEdgeMargin = 8.dp

/**
 * A row of [buttons] centred in its width, placed exactly as a Row centred and spaced [gap] apart
 * would place them, with [leading], when there is one, just before the first of them at the same
 * gap.
 *
 * The leading button is left out of the centring on purpose. It is how the queue button joins the
 * row under the controls without moving lyrics, the sleep timer or the menu, which people press
 * from memory: those three sit exactly where they do with the queue button off, and it takes the
 * space in front of lyrics. It may reach into the [gutter] beside the row, up to
 * [LeadingEdgeMargin] from its edge. Only a phone too narrow even for that, with the timer's time
 * showing, moves the whole row over, and then by no more than it needs.
 */
@Composable
private fun CentredRowWithLeading(
    gap: Dp,
    gutter: Dp,
    leading: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    buttons: @Composable () -> Unit,
) {
    Layout(
        contents = listOf<@Composable () -> Unit>(leading ?: {}, buttons),
        modifier = modifier,
    ) { (leadingMeasurables, buttonMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = buttonMeasurables.map { it.measure(loose) }
        val lead = leadingMeasurables.firstOrNull()?.measure(loose)
        val gapPx = gap.roundToPx()
        val sizes = IntArray(placeables.size) { placeables[it].width }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else sizes.sum() + gapPx * (sizes.size - 1).coerceAtLeast(0)
        val height = constraints.constrainHeight((placeables + listOfNotNull(lead)).maxOfOrNull { it.height } ?: 0)

        // The very arrangement the Row used, so the buttons land on the same pixels as before.
        val positions = IntArray(placeables.size)
        with(Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            arrange(width, sizes, layoutDirection, positions)
        }

        // Start is on the left in a left-to-right layout and on the right otherwise, and the
        // arrangement has already mirrored the buttons, so the leading one goes on whichever side
        // of the first button reads as before it.
        val reach = (gutter - LeadingEdgeMargin).coerceAtLeast(0.dp).roundToPx()
        var shift = 0
        var leadX = 0
        if (lead != null && placeables.isNotEmpty()) {
            if (layoutDirection == LayoutDirection.Ltr) {
                leadX = positions[0] - gapPx - lead.width
                if (leadX < -reach) shift = -reach - leadX
            } else {
                leadX = positions[0] + sizes[0] + gapPx
                val over = leadX + lead.width - (width + reach)
                if (over > 0) shift = -over
            }
        }

        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(positions[index] + shift, 0) }
            lead?.place(leadX + shift, 0)
        }
    }
}

/**
 * Fades out the end of a line that is too long for its space, and leaves a line that fits alone.
 *
 * The title and artists scroll as a marquee, which clips them hard at the edge of their space. With
 * buttons beside the title that edge is only a few dp from the first button, so a long title
 * stopped mid-letter right against it and looked as if it carried on underneath. Fading its last
 * stretch shows the line ending short of the buttons instead.
 */
private fun Modifier.fadeWhenClipped(length: Dp = 24.dp): Modifier = this then FadeWhenClippedElement(length)

private data class FadeWhenClippedElement(val length: Dp) : ModifierNodeElement<FadeWhenClippedNode>() {
    override fun create() = FadeWhenClippedNode(length)

    override fun update(node: FadeWhenClippedNode) {
        node.length = length
        node.invalidateDraw()
    }
}

private class FadeWhenClippedNode(var length: Dp) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var clipped = false

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        // A marquee gives the whole width of its text as its widest size while laying itself out
        // no wider than it is allowed, so the two differ exactly when the line is cut short. A row
        // of them adds theirs up, which covers the artists as well as the title.
        val clippedNow = measurable.maxIntrinsicWidth(placeable.height) > placeable.width
        if (clippedNow != clipped) {
            clipped = clippedNow
            invalidateDraw()
        }
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        if (!clipped || size.width <= 0f) {
            drawContent()
            return
        }
        val fade = length.toPx().coerceAtMost(size.width)
        val mask = if (layoutDirection == LayoutDirection.Ltr) {
            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width)
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade)
        }
        // The mask has to act on this line alone, so the line is drawn into a layer of its own
        // and the mask keeps only as much of it as the gradient allows.
        drawContext.canvas.saveLayer(size.toRect(), Paint())
        drawContent()
        drawRect(brush = mask, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}

/** The classic player button: a 36dp circle, sitting a little low to line up with the title. */
@Composable
private fun PlayerCircleButton(
    painter: Painter,
    contentDescription: String?,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(y = 5.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** One segment of a connected group: an icon, and a short label beside it when there is one. */
@Composable
private fun PlayerActionSegment(
    painter: Painter,
    contentDescription: String?,
    shape: Shape,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    width: Dp = 44.dp,
    label: String? = null,
    backdrop: LayerBackdrop? = null,
    glassIntensity: Float = 1f,
    /** Whether [container] is a real fill (on, or a primary action) rather than the idle wash. */
    filled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = width)
            .clip(shape)
            .then(
                // Frosted, with no lens, highlight or shadow. Those draw a bright rim, and at this
                // size the rim made the buttons read as empty outlines. So this is only the blur and
                // vibrancy, clipped to the shape, under the button's colour: a filled button keeps
                // most of its fill, an idle one gets a wash.
                if (backdrop != null) Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(lerp(16f, 6f, glassIntensity.coerceIn(0f, 1f)).dp.toPx())
                        },
                        // The library's default highlight and shadow are the rim.
                        highlight = { null },
                        shadow = { null },
                    )
                    .background(
                        if (filled) container.copy(alpha = lerp(0.95f, 0.8f, glassIntensity.coerceIn(0f, 1f)))
                        else content.copy(alpha = 0.16f)
                    )
                else Modifier.background(container)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(22.dp)
        )
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1
            )
        }
    }
}

/**
 * The artwork as a strip of this song and its neighbours, swiped sideways to change song.
 *
 * A drag that comes to rest on another song plays it. That is decided when the scroll stops after a
 * drag, not from a frame-late look at the scroll offset, which could miss the moment the snap
 * finished; and programmatic scrolls, the strip following a song change, never count.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableArtwork(
    mediaItems: List<MediaMetadata>,
    currentIndex: Int,
    scrollable: Boolean,
    expanded: Boolean,
    onSkip: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    item: @Composable (MediaMetadata) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestOnSkip by rememberUpdatedState(onSkip)

    // Whether the strip is on its way to the song after a change. Cleared only when it gets there,
    // so a touch that stops it partway can be told from a swipe.
    val following = remember { booleanArrayOf(false) }

    // Follows the song: when it changes, the strip moves to it. Animated only while the player is
    // open, where the animation can run.
    LaunchedEffect(mediaItems.getOrNull(currentIndex)?.id, currentIndex) {
        following[0] = true
        if (expanded) gridState.animateScrollToItem(currentIndex) else gridState.scrollToItem(currentIndex)
        following[0] = false
    }

    LaunchedEffect(gridState) {
        var dragged = false
        launch {
            gridState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true }
        }
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val wasDrag = dragged
            dragged = false
            val landed = gridState.firstVisibleItemIndex
            when {
                // A touch landing while the strip followed a song change, a tap on the cover just
                // after next was pressed, stopped it partway. Counted as a swipe, it went back.
                wasDrag && following[0] -> {
                    following[0] = false
                    launch { gridState.animateScrollToItem(latestIndex) }
                }
                wasDrag && landed != latestIndex -> latestOnSkip(landed > latestIndex)
                // Moved by something other than a finger, TalkBack's scroll or a mouse wheel: back
                // to the song that is playing, rather than another's cover left on screen. In a
                // child job, so a drag cancelling it cannot end this collector.
                landed != latestIndex || gridState.firstVisibleItemScrollOffset != 0 ->
                    launch { gridState.animateScrollToItem(latestIndex) }
            }
        }
    }

    val snap = remember(gridState) {
        SnapLayoutInfoProvider(
            lazyGridState = gridState,
            positionInLayout = { layoutSize, itemSize -> layoutSize / 2f - itemSize / 2f }
        )
    }
    LazyHorizontalGrid(
        state = gridState,
        rows = GridCells.Fixed(1),
        contentPadding = contentPadding,
        flingBehavior = rememberSnapFlingBehavior(snap),
        userScrollEnabled = scrollable,
        modifier = modifier
    ) {
        items(items = mediaItems, key = { it.id }) { item(it) }
    }
}

/**
 * What a swipe on the artwork does: the next or the previous song. On a cold start the player is
 * still empty, so the restored queue goes into it first, as the previous button does.
 */
private fun skipFromArtwork(playerConnection: PlayerConnection, forward: Boolean) {
    val player = playerConnection.player
    if (player.currentMediaItem == null) playerConnection.service.queueBoard.setCurrQueue()
    if (forward) player.seekToNext() else player.seekToPreviousMediaItem()
}
