/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
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
import com.dd3boh.outertune.ui.component.PlayerSliderTrack
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

    val playbackState by playerConnection.playbackState.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)

    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val swipeToSkip by rememberPreference(SwipeToSkipKey, defaultValue = false)
    val previousMediaMetadata = if (swipeToSkip && playerConnection.player.hasPreviousMediaItem()) {
        val previousIndex = playerConnection.player.previousMediaItemIndex
        playerConnection.player.getMediaItemAt(previousIndex).metadata
    } else null

    val qbInit by playerConnection.service.qbInit.collectAsState()
    val nextMediaMetadata = if (swipeToSkip && playerConnection.player.hasNextMediaItem()) {
        val nextIndex = playerConnection.player.nextMediaItemIndex
        playerConnection.player.getMediaItemAt(nextIndex).metadata
    } else null

    val mediaItems = listOfNotNull(previousMediaMetadata, mediaMetadata, nextMediaMetadata)
    val currentMediaIndex = mediaItems.indexOf(mediaMetadata)


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

    val showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    var position by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.currentPosition)
    }
    var duration by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.duration)
    }
    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }

    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }


    // gradient colours
    LaunchedEffect(mediaMetadata, playerBackground) {
        if (playerBackground != PlayerBackgroundStyle.GRADIENT || context.isPowerSaver()) return@LaunchedEffect

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

    LaunchedEffect(playbackState) {
        if (playbackState == STATE_READY) {
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
     * defaults collapsedBound to dismissedBound, and the slow-drag dismiss branch that compares the
     * two is unreachable with stock values anyway (l0 = 48dp+inset, l1 = 24dp, so `in l0..l1` is an
     * empty range). Velocity-based dismiss is unaffected.
     */
    val queueSheetState = rememberBottomSheetState(
        dismissedBound = dismissedBound,
        expandedBound = state.expandedBound,
        collapsedBound = if (landscapeTwoPane) dismissedBound else dismissedBound + QueuePeekHeight,
        initialAnchor = 1
    )

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
     * Single owner of View.keepScreenOn.
     *
     * It is one boolean on one View, so it cannot be written from two places: whichever effect
     * disposes last wins and silently clears the other's request. [Thumbnail] used to own it for
     * the lyrics view; that ownership moved here so lyrics and immersive landscape can be OR'd
     * together instead of clobbering each other.
     *
     * [isPlaying] is part of the condition on purpose. "Landscape holds the screen awake" is about
     * watching playback, and a player left paused overnight in landscape would otherwise hold the
     * screen on until the battery died. Lyrics keep it awake regardless, matching the old
     * behaviour.
     */
    DisposableEffect(showLyrics, immersiveLandscape, isPlaying) {
        currentView.keepScreenOn = showLyrics || (immersiveLandscape && isPlaying)
        onDispose { currentView.keepScreenOn = false }
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
                    if (playerBackground == PlayerBackgroundStyle.BLUR) {
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
                    if (playerBackground == PlayerBackgroundStyle.GRADIENT && colors.size >= 2) {
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
        collapsedBackgroundColor = if (liquidGlass) Color.Transparent
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
                position = position,
                duration = duration
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

            /** Transport controls run larger in landscape, where there is room for them. */
            val transportIconSize = if (landscapePlayer) 42.dp else 32.dp
            val playButtonSize = when {
                showLyrics -> 56.dp
                landscapePlayer -> 84.dp
                else -> 72.dp
            }

            val actionButtons: @Composable RowScope.() -> Unit = {
                Log.v(TAG, "PLR-3.xa")
                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .offset(y = 5.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    ResizableIconButton(
                        icon = if (currentSong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                        onClick = playerConnection::toggleLike
                    )
                }

                Spacer(modifier = Modifier.width(7.dp))

                Box(
                    modifier = Modifier
                        .offset(y = 5.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    ResizableIconButton(
                        icon = Icons.Rounded.MoreVert,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                        onClick = {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = state,
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
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
                    Row {
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
                                    .basicMarquee(
                                        iterations = 1,
                                        initialDelayMillis = 3000
                                    )
                                    .clickable(enabled = mediaMetadata.album != null) {
                                        navController.navigate("album/${mediaMetadata.album!!.id}")
                                        state.collapseSoft()
                                    }
                            )

                            Row {
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
                                                navController.navigate("artist/${artist.id}")
                                                state.collapseSoft()
                                            }
                                    )

                                    if (index != mediaMetadata.artists.lastIndex) {
                                        Text(
                                            text = ", ",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontSize = artistSize,
                                            color = onBackgroundColor
                                        )
                                    }
                                }
                            }
                        }

                        // action buttons for portrait (inline with title)
                        if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE && !tabMode) {
                            actionButtons()
                        }
                    }
                }

                Slider(
                    value = (sliderPosition ?: position).toFloat(),
                    valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                    onValueChange = {
                        sliderPosition = it.toLong()
                        // slider too granular for this haptic to feel right
//                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    },
                    onValueChangeFinished = {
                        sliderPosition?.let {
                            playerConnection.player.seekTo(it)
                            position = it
                        }
                        sliderPosition = null
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                    track = { sliderState ->
                        PlayerSliderTrack(
                            sliderState = sliderState,
                            colors = SliderDefaults.colors()
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
                        text = makeTimeString(sliderPosition ?: position),
                        style = MaterialTheme.typography.labelMedium,
                        color = onBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
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

                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(playButtonSize)
                            .animateContentSize()
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (playerConnection.player.currentMediaItem == null) {
                                    playerConnection.service.queueBoard.setCurrQueue()
                                    playerConnection.player.togglePlayPause()
                                } else if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
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

                    Spacer(Modifier.width(8.dp))

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
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).add(verticalInsets)
                        )
                        .fillMaxSize()
                ) {
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
                            val thumbnailLazyGridState = rememberLazyGridState()
                            val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
                            val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

                            LaunchedEffect(itemScrollOffset) {
                                if (!thumbnailLazyGridState.isScrollInProgress || itemScrollOffset != 0) return@LaunchedEffect

                                if (currentItem > currentMediaIndex)
                                    playerConnection.player.seekToNext()
                                else if (currentItem < currentMediaIndex)
                                    playerConnection.player.seekToPreviousMediaItem()
                            }

                            LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
                                // When the media item changes, scroll to it
                                val index = maxOf(0, currentMediaIndex)

                                // Only animate scroll when player expanded, otherwise animated scroll won't work
                                if (state.isExpanded)
                                    thumbnailLazyGridState.animateScrollToItem(index)
                                else
                                    thumbnailLazyGridState.scrollToItem(index)
                            }

                            val horizontalLazyGridItemWidthFactor = 1f
                            val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
                                SnapLayoutInfoProvider(
                                    lazyGridState = thumbnailLazyGridState,
                                    positionInLayout = { layoutSize, itemSize ->
                                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                                    }
                                )
                            }
                            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor


                            LazyHorizontalGrid(
                                state = thumbnailLazyGridState,
                                rows = GridCells.Fixed(1),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                                userScrollEnabled = state.isExpanded && swipeToSkip
                            ) {
                                items(
                                    items = mediaItems,
                                    key = { it.id }
                                ) {
                                    Thumbnail(
                                        sliderPositionProvider = { sliderPosition },
                                        modifier = Modifier
                                            .width(horizontalLazyGridItemWidth)
                                            .animateContentSize(),
                                        showLyricsOnClick = true,
                                        customMediaMetadata = it
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            // "percentage to half width", not "percentage of width"
                            .weight(if (showLyrics) 0.65f else 1f, false)
                            .animateContentSize()
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                            // Only this column dodges the queue sheet's peek; the artwork does not
                            // need to, since the arrow is centred on the window and never reaches
                            // the artwork's half.
                            .padding(bottom = queueSheetState.collapsedBound)
                    ) {
                        // Like/more sit at the very top of the column rather than riding the
                        // centred block, so they line up with the top of the artwork.
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = hPadding)
                        ) {
                            actionButtons()
                        }

                        Spacer(Modifier.weight(1f))

                        mediaMetadata?.let {
                            controlsContent(it)
                        }

                        Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                        .padding(bottom = queueSheetState.collapsedBound)
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
                            val thumbnailLazyGridState = rememberLazyGridState()
                            val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
                            val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

                            LaunchedEffect(itemScrollOffset) {
                                if (!thumbnailLazyGridState.isScrollInProgress || itemScrollOffset != 0) return@LaunchedEffect

                                if (currentItem > currentMediaIndex)
                                    playerConnection.player.seekToNext()
                                else if (currentItem < currentMediaIndex)
                                    playerConnection.player.seekToPreviousMediaItem()
                            }

                            LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
                                // When the media item changes, scroll to it
                                val index = maxOf(0, currentMediaIndex)

                                // Only animate scroll when player expanded, otherwise animated scroll won't work
                                if (state.isExpanded)
                                    thumbnailLazyGridState.animateScrollToItem(index)
                                else
                                    thumbnailLazyGridState.scrollToItem(index)
                            }

                            val horizontalLazyGridItemWidthFactor = 1f
                            val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
                                SnapLayoutInfoProvider(
                                    lazyGridState = thumbnailLazyGridState,
                                    positionInLayout = { layoutSize, itemSize ->
                                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                                    }
                                )
                            }
                            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor

                            LazyHorizontalGrid(
                                state = thumbnailLazyGridState,
                                rows = GridCells.Fixed(1),
                                flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                                userScrollEnabled = swipeToSkip && state.isExpanded,
                                modifier = Modifier.padding(vertical = QueuePeekHeight / 2)
                            ) {
                                items(
                                    items = mediaItems,
                                    key = { it.id }
                                ) {
                                    Thumbnail(
                                        modifier = Modifier
                                            .width(horizontalLazyGridItemWidth)
                                            .animateContentSize(),
                                        sliderPositionProvider = { sliderPosition },
                                        showLyricsOnClick = true,
                                        customMediaMetadata = it
                                    )
                                }
                            }
                        }
                    }

                    mediaMetadata?.let {
                        controlsContent(it)
                    }

                    Spacer(Modifier.height(24.dp))
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
            navController = navController
        )
    }
}
