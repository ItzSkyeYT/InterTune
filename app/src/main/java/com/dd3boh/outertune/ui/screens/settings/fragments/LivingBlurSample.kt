/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.player.LivingBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Living blur as it will look in the player, small, beside the two sliders that shape it: the real
 * background with a cover standing in it, moving to whatever is playing at the sliders' values as
 * they are dragged. Tuning it by going to the player and back for every change is not tuning.
 *
 * It needs music to move to. With a song playing it uses that. With none it says so and offers to
 * play one: something from what has been played most these three months, which is what somebody
 * keeps coming back to, begun a third of the way in, past the intro, where a song has its
 * movement. Not begun by itself: opening a settings screen must not start music.
 */
@Composable
fun LivingBlurSample(strength: Float, smoothing: Float, colours: LivingColours, modifier: Modifier = Modifier) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val song by playerConnection.mediaMetadata.collectAsState()
    var coverAt by remember { mutableStateOf<Rect?>(null) }
    val queueTitle = stringResource(R.string.player_living_sample_queue)
    val coverPx = with(LocalDensity.current) { COVER.roundToPx() }

    // Over the picture as the player has it, so that what is tuned here is what is seen there.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrim = (if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.3f else 0.45f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        LivingBackground(
            cover = song?.getThumbnailModel(100, 100),
            tap = playerConnection.service.levelTap,
            playing = isPlaying,
            onScreen = true,
            strength = strength,
            smoothing = smoothing,
            colours = colours,
            modifier = Modifier.matchParentSize(),
            coverPlace = { coverAt },
        )
        Box(
            Modifier
                .matchParentSize()
                .background(scrim)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 28.dp)
        ) {
            AsyncImage(
                model = song?.getThumbnailModel(coverPx, coverPx),
                contentDescription = null,
                modifier = Modifier
                    .size(COVER)
                    .onGloballyPositioned { coverAt = Rect(it.positionInRoot(), it.size.toSize()) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (isPlaying && song != null) {
                    Text(
                        text = song!!.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.player_living_sample_playing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.player_living_sample_silent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val candidates = withContext(Dispatchers.IO) {
                                database.mostPlayedSongs(System.currentTimeMillis() - NINETY_DAYS_MS, limit = 12).first()
                                    .ifEmpty { database.likedSongsByCreateDateAsc().first().takeLast(12) }
                            }
                            val pick = candidates.randomOrNull()
                            if (pick == null) {
                                Toast.makeText(context, R.string.player_living_sample_nothing, Toast.LENGTH_LONG).show()
                            } else {
                                // MENU: asked for by name from a button, like a play from a menu.
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = queueTitle,
                                        items = listOf(pick.toMediaMetadata()),
                                        // a third of the way in, in milliseconds; from the start when the length is not known
                                        position = pick.song.duration.coerceAtLeast(0) * 1000L / 3,
                                    ),
                                    origin = PlayOrigin.MENU,
                                )
                            }
                        }
                    }) {
                        Text(stringResource(R.string.player_living_sample_play))
                    }
                }
            }
        }
    }
}

private val COVER = 96.dp
private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000
