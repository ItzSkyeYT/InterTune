/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.compose.foundation.layout.asPaddingValues
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.MicrophoneSnippet
import com.dd3boh.outertune.recognition.RecognitionResult
import com.dd3boh.outertune.recognition.RecognitionService
import com.dd3boh.outertune.recognition.RecognitionState
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.urlEncode
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A screen rather than a dialog.
 *
 * Listening takes ten seconds at a time and can be asked to keep going, which is far too long to
 * hold somebody in front of a modal. A screen can be left, the phone can be put down, and the
 * service behind it keeps going with the display off.
 *
 * The shape follows what there is to show. Nothing recognised yet and the button owns the screen,
 * because there is nothing else to look at and a big target is easy to hit. One song and it moves
 * up to share the space with that song. Several and the list takes over, with the button still in
 * reach at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val state by RecognitionService.state.collectAsState()
    val continuous by RecognitionService.continuous.collectAsState()
    val history by RecognitionService.history.collectAsState()

    val (keepListeningDefault) = rememberPreference(RecogniseKeepListeningKey, defaultValue = false)
    val (pauseOnSpeaker) = rememberPreference(RecognisePauseOnSpeakerKey, defaultValue = true)

    var addToPlaylistFor by remember { mutableStateOf<RecognitionResult.Match?>(null) }
    var pendingContinuous by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) RecognitionService.start(context, pendingContinuous)
    }

    fun listen(keepGoing: Boolean) {
        if (state is RecognitionState.Listening) {
            RecognitionService.stop(context)
            return
        }
        // Pausing is the screen's job, not the service's: only the screen knows whether the user
        // is looking at a player, and the rule is the same one the dialog used. On headphones the
        // room and our playback are separate and there is nothing to interrupt.
        if (pauseOnSpeaker && playerConnection?.player?.isPlaying == true &&
            !MicrophoneSnippet.playbackIsPrivate(context)
        ) {
            playerConnection.player.pause()
        }

        pendingContinuous = keepGoing
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            RecognitionService.start(context, keepGoing)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    addToPlaylistFor?.let { match ->
        AddToPlaylistDialog(
            navController = navController,
            songIds = match.youtubeId?.let { listOf(it) },
            onDismiss = { addToPlaylistFor = null },
        )
    }

    val listening = state is RecognitionState.Listening

    // One LazyColumn for the whole screen rather than a scrolling Column with a list inside it.
    // Nesting two vertical scrollers is not a style choice: the inner one is measured with
    // unbounded height and Compose throws rather than guessing.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        item(key = "listen") {
            // The button's share of the screen depends on what else there is. Alone it gets the
            // top third to itself; with results to show it gives most of that back.
            ListenButton(
                listening = listening,
                continuous = continuous,
                compact = history.size > 1,
                onClick = { listen(keepListeningDefault) },
            )
        }

        item(key = "modes") {
            Row(
                // Centred under the button. Left aligned they read as a toolbar belonging to the
                // list below rather than as the two ways of pressing the thing above them.
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                FilterChip(
                    selected = listening && !continuous,
                    onClick = { listen(false) },
                    label = { Text(stringResource(R.string.recognise_once)) },
                )
                FilterChip(
                    selected = listening && continuous,
                    onClick = { listen(true) },
                    label = { Text(stringResource(R.string.recognise_keep_listening)) },
                )
            }
        }

        (state as? RecognitionState.Failed)?.let { failed ->
            item(key = "failure") {
                Text(
                    text = failed.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        // No heading and no empty state when nothing has been heard yet. An empty list with a
        // title over it is a promise the screen has not kept; the button alone says what to do.
        // One song is not a list. It keeps the big button and sits under it as a single row, so
        // the screen reads as "here is what that was" rather than as a history with one entry in
        // it. The heading and the clear button only earn their place once there is a list to head.
        if (history.size > 1) {
            item(key = "history_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recognise_history_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { RecognitionService.clearHistory() }) {
                        Text(stringResource(R.string.recognise_clear_history))
                    }
                }
            }

        }

        if (history.isNotEmpty()) {
            items(history, key = { it.key ?: (it.title + it.artist) }) { match ->
                RecognisedSongRow(
                    match = match,
                    onPlay = {
                        match.youtubeId?.let { id ->
                            scope.launch {
                                playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = id)))
                            }
                        } ?: navController.navigate(
                            "search/${"${match.title} ${match.artist}".urlEncode()}"
                        )
                    },
                    onAddToPlaylist = { addToPlaylistFor = match },
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.recognise)) },
        navigationIcon = {
            IconButton(
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

/**
 * The thing you came here to press.
 *
 * It pulses while listening rather than showing a spinner, because a spinner says "wait" and this
 * is something you are allowed to walk away from. Pressing it again stops.
 */
@Composable
private fun ListenButton(
    listening: Boolean,
    continuous: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "listen")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val container by animateColorAsState(
        if (listening) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer,
        label = "container",
    )

    val diameter = if (compact) 120.dp else 200.dp
    val lines = stringArrayResource(R.array.recognise_listening_lines)
    var line by remember { mutableStateOf(0) }
    if (listening) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                delay(2_200)
                line = (line + 1) % lines.size
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .scale(pulse)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = if (listening) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(diameter / 3),
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = when {
                listening -> lines[line]
                else -> stringResource(R.string.recognise_tap_to_listen)
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        if (listening && continuous) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.recognise_screen_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One recognised song, drawn the way every other song in the app is drawn.
 *
 * [ListItem] rather than something bespoke, so a Shazam result sits in the same visual language as
 * a search result or a library row: same height, same thumbnail, same overflow. Tapping plays it,
 * which is what people reach for; everything else is behind the three dots rather than competing
 * with it for the row.
 */
@Composable
private fun RecognisedSongRow(
    match: RecognitionResult.Match,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        title = match.title,
        subtitle = match.artist,
        thumbnailContent = {
            AsyncImage(
                model = match.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recognise_play)) },
                        onClick = { menuOpen = false; onPlay() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_to_playlist)) },
                        onClick = { menuOpen = false; onAddToPlaylist() },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}
