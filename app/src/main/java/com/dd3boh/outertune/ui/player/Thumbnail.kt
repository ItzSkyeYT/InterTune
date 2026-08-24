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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.Lyrics
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
    customMediaMetadata: MediaMetadata? = null
) {
    val context = LocalContext.current
    val currentView = LocalView.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    val playerMediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata = customMediaMetadata ?: playerMediaMetadata

    // keepScreenOn is deliberately NOT set here. It is a single boolean on a single View, and
    // BottomSheetPlayer now owns it so the lyrics request and the immersive-landscape request can
    // be OR'd rather than clobbering one another. See the DisposableEffect in Player.kt.

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !showLyrics && error == null,
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
                horizontalAlignment =
                    if (isLandscape) Alignment.Start else Alignment.CenterHorizontally,
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
                    val artPx = with(LocalDensity.current) {
                        minOf(maxWidth, maxHeight).roundToPx()
                    }
                    AsyncImage(
                        model = mediaMetadata?.getThumbnailModel(artPx, artPx),
                        contentDescription = null,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = showLyricsOnClick,
                            ) {
                                showLyrics = !showLyrics
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                    )
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
                // Same gesture that opened it closes it again. Only wired when tapping the artwork
                // is what toggles lyrics in the first place, so the two stay symmetrical.
                onNoLyricsClick = if (showLyricsOnClick) {
                    {
                        showLyrics = false
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                } else null
            )
        }

        AnimatedVisibility(
            visible = error != null,
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
