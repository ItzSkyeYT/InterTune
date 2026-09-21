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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.AudioRoute
import com.dd3boh.outertune.recognition.RecognitionEngine
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.AnimatedDots
import com.dd3boh.outertune.ui.component.rememberRecognitionPhrase
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Naming what is playing, as a screen rather than a sheet.
 *
 * The sheet this shares an engine with is reached from a playlist and exists to fill it: you pick
 * the playlist first, and everything confidently recognised is added to it. This is the other half
 * of the same feature, reached from the search bar with no playlist in mind, for the times the
 * question is simply what that song is. The engine takes a null playlist for exactly this, so
 * nothing is added on its own and the results are a list to act on.
 *
 * A screen and not a dialog because a continuous listen has no end until it is stopped, and a modal
 * that cannot be left is the wrong shape for something you are meant to put the phone down during.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsState()
    val running by viewModel.running.collectAsState()
    val continuous by viewModel.continuous.collectAsState()
    val added by viewModel.added.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    val (keepListeningDefault) = rememberPreference(RecogniseKeepListeningKey, defaultValue = false)
    val (pauseOnSpeaker) = rememberPreference(RecognisePauseOnSpeakerKey, defaultValue = true)

    var addToPlaylistFor by remember { mutableStateOf<SongItem?>(null) }
    var pendingContinuous by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setContinuous(pendingContinuous)
            viewModel.start(null)
        }
    }

    fun listen(keepGoing: Boolean) {
        if (running) {
            viewModel.stop()
            return
        }
        // Only stop the music when the music is in the room. On headphones the microphone hears
        // the room and our playback never reaches it, so interrupting somebody mid-song buys
        // nothing. On the phone's own speaker they share the same air, and leaving it running
        // would name the song already playing every single time.
        if (pauseOnSpeaker && playerConnection?.player?.isPlaying == true &&
            !AudioRoute.playbackIsPrivate(context)
        ) {
            playerConnection.player.pause()
        }

        pendingContinuous = keepGoing
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.setContinuous(keepGoing)
            viewModel.start(null)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    addToPlaylistFor?.let { song ->
        AddToPlaylistDialog(
            navController = navController,
            songIds = listOf(song.id),
            onDismiss = { addToPlaylistFor = null },
        )
    }

    val level = (state as? RecognitionEngine.State.Listening)?.level ?: 0f
    val identifying = (state as? RecognitionEngine.State.Listening)?.identifying == true
    val heard = added + skipped

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        item(key = "listen") {
            ListenButton(
                listening = running,
                identifying = identifying,
                continuous = continuous,
                level = level,
                compact = heard.isNotEmpty() || state is RecognitionEngine.State.Found,
                onClick = { listen(keepListeningDefault) },
            )
        }

        item(key = "modes") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                ModeCard(
                    title = stringResource(R.string.recognise_once),
                    description = stringResource(R.string.recognise_once_desc),
                    icon = Icons.Rounded.GraphicEq,
                    selected = running && !continuous,
                    onClick = { listen(false) },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    title = stringResource(R.string.recognise_keep_listening),
                    description = stringResource(R.string.recognise_keep_listening_short),
                    icon = Icons.Rounded.AllInclusive,
                    selected = running && continuous,
                    onClick = { listen(true) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // What is playing in the room, which is the question the screen is named after. Shazam
        // answers it on its own, before YouTube is consulted at all, so this survives the search
        // coming back with nothing. Without it a run that recognised the song perfectly well
        // showed a blank screen, because everything below here needs a YouTube match to render.
        nowPlaying?.let { playing ->
            item(key = "now_playing") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = playing.title,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    playing.artist?.let { artist ->
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Heard, named, but not confidently placed on YouTube. Recorded by the engine and, until
        // now, shown only by the sheet: on this screen a continuous run filled this list and
        // displayed none of it, so it sat there looking like nothing had happened.
        if (skipped.isNotEmpty()) {
            item(key = "skipped") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.recognition_skipped_count, skipped.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (entry in skipped.asReversed().take(3)) {
                        Text(
                            text = "${entry.title} - ${entry.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // What it heard but could not place confidently. The candidates are shown rather than one
        // guess being taken, because a search for a title and an artist lands on live takes,
        // covers and sped-up edits, and picking the wrong one silently is worse than asking.
        (state as? RecognitionEngine.State.Found)?.let { found ->
            item(key = "found_header") {
                Text(
                    text = found.track.title + (found.track.artist?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(found.candidates, key = { it.id }) { song ->
                CandidateRow(
                    song = song,
                    onPlay = {
                        scope.launch {
                            playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = song.id)))
                        }
                    },
                    onAddToPlaylist = { addToPlaylistFor = song },
                )
            }
        }

        (state as? RecognitionEngine.State.Failed)?.let { failed ->
            item(key = "failure") {
                Text(
                    // reason is a developer string: an exception message, or a word from the
                    // Shazam client. It stays in the logs. RecognitionSheet already picked
                    // between these two and this screen should not read differently.
                    text = stringResource(
                        if (failed.heardNothing) R.string.recognition_heard_nothing
                        else R.string.recognition_failed
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        if (state is RecognitionEngine.State.NoMatch) {
            item(key = "no_match") {
                Text(
                    text = stringResource(R.string.recognise_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        // No heading over an empty list. Before anything has been heard the button is the whole
        // screen, because a title with nothing under it is a promise the screen has not kept.
        if (heard.size > 1) {
            item(key = "heard_header") {
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
                    TextButton(onClick = { viewModel.reset() }) {
                        Text(stringResource(R.string.recognise_clear_history))
                    }
                }
            }
        }

        items(heard, key = { it.title + it.artist }) { entry ->
            ListItem(
                title = entry.title,
                subtitle = entry.artist,
                thumbnailContent = {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ListThumbnailSize),
                    )
                },
            )
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
 * The bars move with the room rather than on a timer. A canned animation says "something is
 * happening"; one driven by the microphone says "I can hear you", which is the question somebody
 * holding a phone towards a speaker actually has. The level is the one the engine already computes
 * from the samples it is recording, so nothing listens twice.
 */
@Composable
private fun ListenButton(
    listening: Boolean,
    identifying: Boolean,
    continuous: Boolean,
    level: Float,
    compact: Boolean,
    onClick: () -> Unit,
) {
    // Smoothed, because raw buffer levels jitter and a bar that jitters reads as broken rather
    // than responsive. Rising fast and falling slow is what makes it look like a meter.
    val smoothed by animateFloatAsState(
        targetValue = if (listening) level else 0f,
        animationSpec = tween(durationMillis = if (level > 0.5f) 90 else 260),
        label = "level",
    )

    val transition = rememberInfiniteTransition(label = "listen")
    val sway by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "sway",
    )
    val container by animateColorAsState(
        if (listening) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer,
        label = "container",
    )
    val onContainer = if (listening) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onPrimaryContainer

    val diameter = if (compact) 120.dp else 200.dp

    // adding = false: this screen has no playlist, so the phrases that promise to fill one are
    // left out of the pool rather than shown and quietly untrue.
    val phrase by rememberRecognitionPhrase(
        listening = listening,
        identifying = identifying,
        adding = false,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 32.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick),
        ) {
            if (listening) {
                Canvas(modifier = Modifier.size(diameter * 0.52f)) {
                    val bars = 5
                    val gap = size.width / (bars * 2f - 1f)
                    for (i in 0 until bars) {
                        // Each bar sits at its own point in the sway, so they rise and fall as a
                        // wave rather than as one block, and the middle leads.
                        val phase = sway + i * 0.9f
                        val wobble = (kotlin.math.sin(phase.toDouble()).toFloat() + 1f) / 2f
                        val centreBias = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / bars
                        // The sway alone has to carry it when the room is silent, or five bars sat
                        // at the floor read as five dots and the thing looks broken rather than
                        // quiet. Loudness rides on top of that.
                        val amount = (0.30f + smoothed * 1.7f * centreBias) * (0.40f + 0.60f * wobble)
                        val h = (size.height * amount).coerceIn(size.height * 0.16f, size.height)
                        drawRoundRect(
                            color = onContainer,
                            topLeft = Offset(i * gap * 2f, (size.height - h) / 2f),
                            size = Size(gap, h),
                            cornerRadius = CornerRadius(gap / 2f, gap / 2f),
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(diameter / 3),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (listening) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Text(
                    text = phrase,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                AnimatedDots(style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(
                text = stringResource(R.string.recognise_tap_to_listen),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }

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
 * One of the two ways to listen, as a card rather than a chip.
 *
 * A chip is the right size for a filter and the wrong size for the choice that decides what the
 * biggest control on the screen does. These say what each mode is rather than relying on the label
 * alone, and are large enough to hit without looking.
 */
@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "border",
    )
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A song it might have been, drawn the way every other song in the app is drawn.
 *
 * [ListItem] rather than something bespoke, so a candidate sits in the same visual language as a
 * search result or a library row. Tapping plays it, which is what people reach for; the rest is
 * behind the three dots rather than competing with it for the row.
 */
@Composable
private fun CandidateRow(
    song: SongItem,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        title = song.title,
        subtitle = song.artists.joinToString { it.name },
        thumbnailContent = {
            AsyncImage(
                model = song.thumbnail,
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
