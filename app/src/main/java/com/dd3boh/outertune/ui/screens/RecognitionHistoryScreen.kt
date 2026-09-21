/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.Heard
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.backButtonSurface
import com.dd3boh.outertune.ui.utils.backToMain
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Every song the app has heard in a room.
 *
 * Kept deliberately plain. This is a log, not a library: no artwork, no menus, no selection. The
 * one action that makes sense is playing something again, and only the entries that were placed on
 * YouTube can do that, so the rest are shown without pretending to be tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionHistoryScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val heard by viewModel.heard.collectAsStateWithLifecycle(initialValue = emptyList())
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    if (heard.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.recognition_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 120.dp),
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            items(heard, key = { it.at }) { entry ->
                HeardRow(
                    entry = entry,
                    onPlay = entry.videoId?.let { id ->
                        {
                            scope.launch {
                                playerConnection?.playQueue(
                                    YouTubeQueue(WatchEndpoint(videoId = id))
                                )
                            }
                            Unit
                        }
                    },
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.recognition_history)) },
        navigationIcon = {
            IconButton(
                modifier = Modifier.backButtonSurface(),
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun HeardRow(entry: Heard, onPlay: (() -> Unit)?) {
    val when_ = remember(entry.at) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.at))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        Text(
            text = if (entry.artist.isBlank()) when_ else "${entry.artist} · $when_",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
