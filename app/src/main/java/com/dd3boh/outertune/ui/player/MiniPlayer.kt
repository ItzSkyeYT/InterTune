/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import android.annotation.SuppressLint
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.button.IconButton
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.util.lerp
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.ui.utils.LocalAppBackdrop
import com.dd3boh.outertune.ui.utils.keyboardClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.dd3boh.outertune.ui.utils.rememberGlassSpec
import com.dd3boh.outertune.utils.rememberPreference
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    /** Opens the player from the keys: the centre key on the title. Touch opens it from the sheet around this. */
    onExpand: () -> Unit = {},
    /** Lets the player hand focus back to the title when it closes. */
    expandFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val glass = rememberGlassSpec()
    val glassOn = glass != null
    val glassShape = RoundedCornerShape(24.dp)
    // Slightly more opaque than the dock: this panel carries two lines of text over whatever the
    // library happens to be showing, so it is the first place a section heading bleeds through.
    val glassTint = glass?.tint(min = 0.66f, max = 0.98f) ?: Color.Transparent

    // On its side the sheet has already put this panel clear of the rail and the cutout, at its
    // upright width (Landscape.panelSpan), and the insets again would squeeze it.
    val placedBySheet = LocalLandscape.current.active
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            // Inset clearance first, then the panel's own gutter.
            .then(
                if (placedBySheet) Modifier
                else Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            )
//            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
            .then(
                if (glassOn) {
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .drawBackdrop(
                            backdrop = glass!!.backdrop,
                            shape = { glassShape },
                            effects = {
                                vibrancy()
                                blur(glass.blur.toPx())
                                // 12/24 rather than a symmetric rim: the panel is 60dp tall, so a
                                // 24dp rim from both edges would leave no flat centre.
                                lens(
                                    refractionHeight = 12f.dp.toPx() * glass.lensT,
                                    refractionAmount = 24f.dp.toPx() * glass.lensT,
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            }
                        )
                        .background(glassTint, glassShape)
                } else Modifier
            )
    ) {
        LinearProgressIndicator(
            progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            drawStopIndicator = { },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (glassOn) 20.dp else 0.dp)
                .height(2.dp)
                .then(if (glassOn) Modifier.clip(CircleShape) else Modifier)
                .align(Alignment.BottomCenter),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Was `modifier`, re-applying the caller's modifier that the outer Box already
            // consumed. Inert while nothing passed one; not inert once the glass lands above.
            modifier = Modifier.fillMaxSize(),
        ) {
            val iconButtonColor = MaterialTheme.colorScheme.onSecondaryContainer
            Box(
                Modifier
                    .weight(1f)
                    .focusRequester(expandFocusRequester)
                    .keyboardClickable(onExpand)
            ) {
                mediaMetadata?.let {
                    MiniMediaInfo(
                        mediaMetadata = it,
                        error = error,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }

            // The mini player had play and next and nothing to go back with, so the only way
            // to hear something again was to open the full player or reach for the headset. It
            // takes width from the title, which is the point of the row, so it is the narrower of
            // the two jobs that loses out: the text truncates a little sooner. In a window too
            // narrow to spare that width the title wins, see miniPlayerShowsPrevious.
            if (miniPlayerShowsPrevious(windowWidth())) IconButton(
                enabled = canSkipPrevious,
                onClick = {
                    if (playerConnection.player.currentMediaItem == null) {
                        playerConnection.service.queueBoard.setCurrQueue()
                        playerConnection.player.playWhenReady = true
                    }
                    playerConnection.player.seekToPrevious()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_previous),
                    tint = iconButtonColor.copy(alpha = (if (canSkipPrevious) 1f else 0.5f)),
                    contentDescription = stringResource(R.string.widget_previous)
                )
            }

            IconButton(
                onClick = {
                    // Same three-tap bug as the full player. The connection loads the saved queue
                    // into an empty player, then prepares, rewinds when ended, and plays.
                    playerConnection.togglePlayPause()
                }
            ) {
                Icon(
                    imageVector = if (playbackState == Player.STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    tint = iconButtonColor,
                    // The three buttons were bare pictures: a screen reader had nothing to read out
                    // for them, and nothing that drives the app by what is on screen could find
                    // them. They are named as the widget names its own.
                    contentDescription = stringResource(if (isPlaying) R.string.widget_pause else R.string.widget_play)
                )
            }

            IconButton(
                enabled = canSkipNext,
                onClick = {
                    if (playerConnection.player.currentMediaItem == null) {
                        playerConnection.service.queueBoard.setCurrQueue()
                        playerConnection.player.playWhenReady = true
                    }
                    playerConnection.player.seekToNext()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_next),
                    tint = iconButtonColor.copy(alpha = (if (canSkipNext) 1f else 0.5f)),
                    contentDescription = stringResource(R.string.widget_next)
                )
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun MiniMediaInfo(
    mediaMetadata: MediaMetadata,
    error: PlaybackException?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val playerConnection = LocalPlayerConnection.current
    val isWaitingForNetwork by playerConnection?.waitingForNetworkConnection?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }

    val px = (ListThumbnailSize.value * density.density).roundToInt()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(6.dp)
                .size(48.dp)
        ) {
            AsyncImage(
                model = mediaMetadata.getThumbnailModel(px, px),
                contentDescription = null,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = error != null || isWaitingForNetwork,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(ThumbnailCornerRadius)
                        )
                ) {
                    if (isWaitingForNetwork) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
        ) {
            Text(
                text = mediaMetadata.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
