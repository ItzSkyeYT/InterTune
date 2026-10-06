/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.utils.ActivityLog
import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.Lyrics
import com.dd3boh.outertune.ui.utils.artSizeBucket
import com.dd3boh.outertune.utils.rememberPreference

@SuppressLint("UnusedBoxWithConstraintsScope")
/**
 * Crossfade timings for the artwork / lyrics / error swap.
 *
 * The three states share one Box, so their fades run at the same time. Bare fadeIn()/fadeOut() both
 * use the same default curve and duration, which makes the outgoing view still be at half opacity
 * while the incoming one is also at half, and the swap reads as a flicker rather than a fade. The
 * incoming view is given a slower, decelerating curve and the outgoing one a quicker accelerating
 * curve, so the old view clears out of the way before the new one arrives.
 *
 * Deliberately local to this file: the bottom sheet's own transitions are already tuned and are not
 * touched.
 */
private val ThumbnailEnter = fadeIn(tween(durationMillis = 320, easing = LinearOutSlowInEasing))
private val ThumbnailExit = fadeOut(tween(durationMillis = 180, easing = FastOutLinearInEasing))

@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyricsOnClick: Boolean = false,
    customMediaMetadata: MediaMetadata? = null,
    /** Shown in the small player; passed on to [Lyrics]. */
    smallWindow: Boolean = false,
    /**
     * Extra bottom room to leave clear under the lyrics view's close/more row. Callers whose
     * layout already reserves space for the collapsed queue sheet around the whole Thumbnail
     * (portrait, tablet) leave this at zero; the landscape two-pane player does not, since the
     * artwork itself does not need the room, and passes its own queue clearance through here.
     */
    lyricsBottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val currentView = LocalView.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    val playerMediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata = customMediaMetadata ?: playerMediaMetadata

    // error is one flag for the whole player, but in the swipe-to-skip strip this Thumbnail may be
    // showing a neighbour of the song that failed. Only the Thumbnail showing the player's current
    // song takes the error: the strip never holds a neighbour with the current song's id
    // (nextInStrip and previousInStrip in BottomSheetPlayer), and the other callers pass the
    // current song itself.
    val ownsError = customMediaMetadata == null || customMediaMetadata.id == playerMediaMetadata?.id

    // keepScreenOn is deliberately NOT set here. It is a single boolean on a single View, and
    // KeepScreenOnController now owns it so every reason that wants the screen held awake (lyrics,
    // immersive landscape, recognition) can be OR'd rather than clobbering one another. See
    // KeepScreenOnController.

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !showLyrics && (error == null || !ownsError),
            enter = ThumbnailEnter,
            exit = ThumbnailExit,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {

            // In the two-pane landscape player the artwork shares the width with the controls, so
            // it hugs the outer edge and spends almost nothing on a gutter. Centring it inside a
            // 32dp-inset column (as portrait does) strands it in the middle of its half and any
            // alignment applied by the caller is overridden here, since this Column fills the
            // parent.
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Column(
                verticalArrangement = Arrangement.Center,
                // Start-aligned in phone landscape, where the artwork shares the width with the
                // controls and hugging the outer edge is right. A tablet gives it a whole pane, so
                // there it centres like portrait does.
                horizontalAlignment =
                    if (isLandscape && !context.tabMode()) Alignment.Start
                    else Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isLandscape) 8.dp else PlayerHorizontalPadding)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f, false)
                ) {
                    // Ask the CDN for an image the size we are actually going to draw. The box is
                    // square (aspectRatio(1f) below), so the shorter side wins. Without this the
                    // default thumbnail gets upscaled and looks soft — very visible in landscape,
                    // where the artwork is far larger than it is in portrait.
                    //
                    // Rounded up to a bucket rather than used exactly. The url is the cache key, so
                    // an exact size mints a separate download, disk entry and decode for every
                    // distinct pixel width: portrait measured 1152 and landscape 1248 on the same
                    // device, which is two full copies of one cover for no visible gain. Bucketing
                    // makes a rotation reuse what portrait already fetched.
                    val artPx = with(LocalDensity.current) {
                        artSizeBucket(minOf(maxWidth, maxHeight).roundToPx())
                    }
                    // The living background puts its aura round the cover, so the cover says where it
                    // is: the one showing the song that plays, not a neighbour in the swipe strip.
                    // Told again whenever that changes hands, since a cover that has not moved is
                    // not placed again, and taken back when this one leaves, which is when the
                    // lyrics or an error take its place.
                    var place by remember { mutableStateOf<Rect?>(null) }
                    val showsWhatPlays by rememberUpdatedState(ownsError)
                    LaunchedEffect(ownsError, place) {
                        if (ownsError && place != null) PlayerCoverPlace.bounds = place
                    }
                    DisposableEffect(Unit) {
                        onDispose { if (showsWhatPlays) PlayerCoverPlace.bounds = null }
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .onGloballyPositioned { place = Rect(it.positionInRoot(), it.size.toSize()) }
                            .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = showLyricsOnClick,
                            ) {
                                if (!showLyrics) mediaMetadata?.id?.let { ActivityLog.note(context, database, it, SignalKind.LYRICS) }
                                showLyrics = !showLyrics
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                    ) {
                        // Low resolution underlay, drawn first and left in place.
                        //
                        // This is the whole progressive-loading trick, and it needs no measurement
                        // of the connection. The small cover is a few kB and lands almost at once,
                        // so a slow or flaky link shows the artwork immediately instead of an empty
                        // square; the full size version is still fetched, and simply covers this
                        // when it arrives, however long that takes. Fast link: the sharp one wins so
                        // quickly the underlay is never really seen. Slow link: blurry now, sharp
                        // later. Permanently slow link: still sharp eventually, just later.
                        //
                        // Deliberately NOT bucketed to artPx. It is one fixed small size, so it is
                        // fetched once per cover ever and is a cache hit for every later play, on
                        // any screen and either orientation.
                        AsyncImage(
                            model = mediaMetadata?.getThumbnailModel(
                                ART_PREVIEW_PX,
                                ART_PREVIEW_PX
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        AsyncImage(
                            model = mediaMetadata?.getThumbnailModel(artPx, artPx),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showLyrics && error == null,
            enter = ThumbnailEnter,
            exit = ThumbnailExit
        ) {
            Lyrics(
                sliderPositionProvider = sliderPositionProvider,
                smallWindow = smallWindow,
                // Same gesture that opened it closes it again. Only wired when tapping the artwork
                // is what toggles lyrics in the first place, so the two stay symmetrical.
                onNoLyricsClick = if (showLyricsOnClick) {
                    {
                        showLyrics = false
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                } else null,
                bottomPadding = lyricsBottomPadding
            )
        }

        AnimatedVisibility(
            visible = error != null && ownsError,
            enter = ThumbnailEnter,
            exit = ThumbnailExit,
        ) {
            error?.let { error ->
                ThumbnailPlaybackError(
                    error = error,
                    retry = playerConnection.player::prepare
                )
            }
        }
    }
}

/**
 * Size of the low resolution cover drawn underneath the real one, in pixels.
 *
 * Small enough to arrive on a bad connection (roughly 12 kB against 350 kB or more for the full
 * size one) and still carry the colours and rough shapes, which is all it has to do for the moment
 * before the sharp version lands on top of it.
 */
private const val ART_PREVIEW_PX = 128
