/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.layout.asPaddingValues
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.LocalPlayerConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.dd3boh.outertune.constants.ListThumbnailSize
import kotlin.math.roundToInt
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.viewmodels.CachedSongsViewModel
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember

/**
 * Songs the app kept because you played them, and will therefore play with no network.
 *
 * Not downloads. Downloads are deliberate and stay until deleted; this is a cache with a size limit
 * and an eviction policy, so the list shrinks on its own as it fills. The copy says so, because a
 * library tab whose contents vanish without the user doing anything is otherwise a support question
 * waiting to happen.
 */
@Composable
fun LibraryCachedScreen(
    navController: NavController,
    libraryFilterContent: @Composable (() -> Unit)? = null,
    viewModel: CachedSongsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val songs by viewModel.songs.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()

    // With the song cache set to Off, AppModule builds a LeastRecentlyUsedCacheEvictor sized zero,
    // so every byte is dropped as soon as it lands and nothing can ever appear here. Found by
    // testing this tab: the cache climbed to 2 MB, fell to 40 K, climbed again, and the empty state
    // meanwhile promised songs would show up after playing them, which was untrue.
    val (maxCacheSize) = rememberPreference(MaxSongCacheSizeKey, defaultValue = 0)
    val cachingOff = maxCacheSize == 0

    // Re-read on every entry. The cache changes underneath this screen constantly, as songs finish
    // downloading into it and as the evictor drops the oldest, and a list loaded once in the view
    // model's init showed a song as missing for the whole session because it was still playing when
    // the tab was first opened.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            libraryFilterContent?.let {
                item(key = "filter", contentType = CONTENT_TYPE_HEADER) { it() }
            }

            if (!loading && songs.isNotEmpty()) {
                item(key = "cached header", contentType = CONTENT_TYPE_HEADER) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.cached_songs_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(
                items = songs,
                key = { _, item -> item.id },
                contentType = { _, _ -> CONTENT_TYPE_SONG },
            ) { index, song ->
                SongListItem(
                    song = song,
                    navController = navController,
                    snackbarHostState = snackbarHostState,
                    isActive = song.song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    inSelectMode = false,
                    isSelected = false,
                    onSelectedChange = {},
                    swipeEnabled = false,
                    thumbnailSize = thumbnailSize,
                    onPlay = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = context.getString(R.string.filter_cached),
                                items = songs.map { it.toMediaMetadata() },
                                startIndex = index,
                            ),
                            origin = PlayOrigin.LIBRARY,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) { CircularProgressIndicator() }
                }
            } else if (songs.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.CloudDone,
                        text = stringResource(
                            if (cachingOff) R.string.cached_songs_disabled
                            else R.string.cached_songs_empty
                        ),
                    )
                }
            }
        }
    }
}
