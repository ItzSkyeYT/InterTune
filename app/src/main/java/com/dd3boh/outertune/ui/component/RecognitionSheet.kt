/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.random.Random
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.recognition.RecognitionEngine
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.zionhuang.innertube.models.SongItem

/**
 * Listen to what is playing nearby, then show what it was.
 *
 * It stops on the result and waits rather than adding the first hit. Shazam answers with a title
 * and an artist, never a YouTube id, so the song still has to be found by searching, and a search
 * for a title and an artist can return a live take, a cover or a sped-up edit. Showing the
 * candidates is not politeness, it is the only way the right recording gets picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val continuous by viewModel.continuous.collectAsState()
    val added by viewModel.added.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val running by viewModel.running.collectAsState()
    val startedAt by viewModel.startedAt.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    // Ticks once a second so the sheet can show how long it has been listening. A run with nothing
    // to report otherwise looks identical to one that has died.
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(running, startedAt) {
        while (running && startedAt > 0L) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            delay(1000)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start(playlist)
        else viewModel.reset()
    }

    // Asked when listening starts, not at launch. A run already going is joined rather than
    // restarted, so reopening the sheet on a continuous run does not interrupt it.
    LaunchedEffect(Unit) {
        if (!viewModel.running.value) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            when (val s = state) {
                RecognitionEngine.State.Idle ->
                    Listening(level = 0f, identifying = false, elapsed = elapsed)

                is RecognitionEngine.State.Listening -> {
                    Listening(level = s.level, identifying = s.identifying, elapsed = elapsed)
                    Spacer24()
                    OutlinedButton(onClick = { viewModel.stop(); onDismiss() }) {
                        Text(stringResource(R.string.recognition_stop))
                    }
                }

                is RecognitionEngine.State.Confirming ->
                    Listening(
                        level = 0f,
                        identifying = true,
                        elapsed = elapsed,
                        message = stringResource(R.string.recognition_confirming, s.track.title),
                    )

                is RecognitionEngine.State.Found -> Found(
                    title = s.track.title,
                    artist = s.track.artist,
                    artwork = s.track.artworkUrl,
                    candidates = s.candidates,
                    onAdd = { song ->
                        viewModel.accept(song, playlist)
                        if (!continuous) onDismiss()
                    },
                    onRetry = { viewModel.start(playlist) },
                )

                RecognitionEngine.State.NoMatch -> Problem(
                    text = stringResource(R.string.recognition_no_match),
                    onRetry = { viewModel.start(playlist) },
                )

                is RecognitionEngine.State.Failed -> Problem(
                    text = stringResource(
                        if (s.heardNothing) R.string.recognition_heard_nothing
                        else R.string.recognition_failed
                    ),
                    onRetry = { viewModel.start(playlist) },
                )
            }

            // Off by default. Continuous listening keeps the microphone open and makes a request
            // every few seconds, which is not something to switch on for somebody.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.recognition_keep_listening),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.recognition_keep_listening_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = continuous,
                    onCheckedChange = { viewModel.setContinuous(it) },
                )
            }

            nowPlaying?.let { playing ->
                val position = remember(elapsed, playing) { playing.positionSeconds() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                ) {
                    Text(
                        text = listOfNotNull(playing.title, playing.artist).joinToString(" - "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    val duration = playing.durationSeconds
                    if (duration != null && duration > 0) {
                        LinearProgressIndicator(
                            progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        Text(
                            text = "%d:%02d / %d:%02d".format(
                                position / 60, position % 60, duration / 60, duration % 60
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (skipped.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recognition_skipped_count, skipped.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                for (entry in skipped.asReversed().take(3)) {
                    Text(
                        text = "${entry.title} - ${entry.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }

            if (added.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recognition_added_count, added.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                for (entry in added.asReversed().take(4)) {
                    Text(
                        text = "${entry.title} - ${entry.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Spacer24() = Box(Modifier.size(24.dp))

/** Shown while it listens, cycling so the screen never looks frozen on one sentence. */
private val LISTENING_MESSAGES = listOf(
    R.string.recognition_listening_hint,
    R.string.recognition_msg_ears_open,
    R.string.recognition_msg_waiting_chorus,
    R.string.recognition_msg_leave_running,
    R.string.recognition_msg_closer_helps,
    R.string.recognition_msg_nodding,
    R.string.recognition_msg_eavesdrop,
    R.string.recognition_msg_hum,
)

private val IDENTIFYING_MESSAGES = listOf(
    R.string.recognition_identifying,
    R.string.recognition_msg_matching,
    R.string.recognition_msg_asking,
    R.string.recognition_msg_narrowing,
    R.string.recognition_msg_squiggles,
    R.string.recognition_msg_california,
    R.string.recognition_msg_thinking_hard,
)

/**
 * One in a thousand, and no more often than that.
 *
 * Rolled once per phrase rather than per frame, so it cannot flicker in and out while somebody is
 * reading it, and remembered against the same bucket the phrase uses so recomposition does not get
 * a second go at the dice.
 */
private const val SECRET_ODDS = 1000

/**
 * Three dots that appear one at a time and start over.
 *
 * Reserves the width of all three from the start, so the text beside them does not jump about as
 * they come and go.
 */
@Composable
private fun AnimatedDots(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "step",
    )
    val shown = step.toInt().coerceIn(0, 3)
    Box {
        Text(
            text = "...",
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color.Transparent,
        )
        Text(
            text = ".".repeat(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun Title(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = 8.dp)
)

/**
 * The listening state.
 *
 * The circle breathes with the microphone level rather than on a timer, so a room that is actually
 * silent looks silent. That is worth more than an animation: it is the difference between "it is
 * not working" and "it cannot hear anything from there".
 *
 * There is no countdown any more. Listening is continuous, the microphone never closes between
 * windows, and a number ticking to twelve implied a wait that no longer exists.
 */
@Composable
private fun Listening(
    level: Float,
    identifying: Boolean,
    elapsed: Int,
    message: String? = null,
) {
    val scale by animateFloatAsState(
        targetValue = 1f + (level.coerceIn(0f, 1f) * 0.35f),
        animationSpec = tween(120),
        label = "level",
    )

    // A slow ring that expands and fades regardless of what the room is doing. The level animation
    // alone is honest but useless in a quiet moment: a still circle reads as a dead feature, and
    // this is a screen somebody looks at precisely when they are unsure it is working.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )

    Title(stringResource(R.string.recognition_listening))
    Spacer24()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .scale(1f + ring * 0.22f)
                .alpha((1f - ring) * 0.45f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            modifier = Modifier
                .size(132.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(52.dp),
        )
    }
    Spacer24()
    Text(
        text = "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    // A line that changes while it works, rather than one sentence sitting there for minutes. The
    // dots carry the movement; the wording changes slowly enough to read.
    val messages = if (identifying) IDENTIFYING_MESSAGES else LISTENING_MESSAGES
    val bucket = elapsed / 5
    val roll = remember(bucket, identifying) { Random.nextInt(SECRET_ODDS) }
    val phrase = message ?: stringResource(
        if (roll == 0) R.string.recognition_msg_secret else messages[bucket % messages.size]
    )
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = phrase,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AnimatedDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Found(
    title: String,
    artist: String?,
    artwork: String?,
    candidates: List<SongItem>,
    onAdd: (SongItem) -> Unit,
    onRetry: () -> Unit,
) {
    Title(stringResource(R.string.recognition_found))
    Spacer24()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (artist != null) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer24()
    Text(
        text = stringResource(
            if (candidates.isEmpty()) R.string.recognition_not_on_youtube
            else R.string.recognition_pick_version
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    // Listed rather than auto-added. The top hit is often right and sometimes a karaoke version.
    for (candidate in candidates) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            AsyncImage(
                model = candidate.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = candidate.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Button(onClick = { onAdd(candidate) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.recognition_add), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    Spacer24()
    TextButton(onClick = onRetry) { Text(stringResource(R.string.recognition_listen_again)) }
}

@Composable
private fun Problem(text: String, onRetry: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(top = 16.dp)
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            imageVector = Icons.Rounded.MicOff,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer24()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer24()
    Button(onClick = onRetry) { Text(stringResource(R.string.recognition_try_again)) }
}
