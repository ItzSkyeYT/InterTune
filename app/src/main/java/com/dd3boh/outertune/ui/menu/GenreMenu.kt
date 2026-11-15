/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.dd3boh.outertune.viewmodels.LocalGenre
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

@Composable
fun GenreMenu(
    genre: LocalGenre,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val px = (ListThumbnailSize.value * LocalDensity.current.density).roundToInt()

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
        modifier = Modifier.fillMaxWidth()
    )

    HorizontalDivider()

    GridMenu(
        kind = MenuKind.GENRE,
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (genre.songs.isNotEmpty()) {
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                playerConnection.playQueue(
                    ListQueue(
                        title = genre.name,
                        items = genre.songs.map { it.toMediaMetadata() },
                        startIndex = 0
                    ),
                    origin = PlayOrigin.LIBRARY
                )
                onDismiss()
            }

            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                playerConnection.playQueue(
                    ListQueue(
                        title = genre.name,
                        items = genre.songs.shuffled().map { it.toMediaMetadata() },
                        startIndex = 0
                    ),
                    origin = PlayOrigin.LIBRARY
                )
                onDismiss()
            }

            GridMenuItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                title = R.string.play_next
            ) {
                playerConnection.enqueueNext(genre.songs.map { it.toMediaItem() })
                onDismiss()
            }

            GridMenuItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = R.string.add_to_queue
            ) {
                playerConnection.enqueueEnd(genre.songs.map { it.toMediaItem() })
                onDismiss()
            }
        }
    }
}
