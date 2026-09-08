/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
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
    onAdd: (SongItem) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
        else viewModel.reset()
    }

    // Asked when listening starts, not at launch, and only once per opening of the sheet.
    LaunchedEffect(Unit) { permission.launch(Manifest.permission.RECORD_AUDIO) }

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
                RecognitionViewModel.State.Idle -> Listening(level = 0f, elapsedMs = 0)

                is RecognitionViewModel.State.Listening -> {
                    Listening(level = s.level, elapsedMs = s.elapsedMs)
                    Spacer24()
                    OutlinedButton(onClick = viewModel::stopAndIdentify) {
                        Text(stringResource(R.string.recognition_stop))
                    }
                }

                RecognitionViewModel.State.Identifying -> {
                    Title(stringResource(R.string.recognition_identifying))
                    Spacer24()
                    CircularProgressIndicator()
                    Spacer24()
                }

                is RecognitionViewModel.State.Found -> Found(
                    title = s.track.title,
                    artist = s.track.artist,
                    artwork = s.track.artworkUrl,
                    candidates = s.candidates,
                    onAdd = { onAdd(it); onDismiss() },
                    onRetry = viewModel::start,
                )

                RecognitionViewModel.State.NoMatch -> Problem(
                    text = stringResource(R.string.recognition_no_match),
                    onRetry = viewModel::start,
                )

                is RecognitionViewModel.State.Failed -> Problem(
                    text = stringResource(
                        if (s.heardNothing) R.string.recognition_heard_nothing
                        else R.string.recognition_failed
                    ),
                    onRetry = viewModel::start,
                )
            }
        }
    }
}

@Composable
private fun Spacer24() = Box(Modifier.size(24.dp))

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
 */
@Composable
private fun Listening(level: Float, elapsedMs: Long) {
    val scale by animateFloatAsState(
        targetValue = 1f + (level.coerceIn(0f, 1f) * 0.35f),
        animationSpec = tween(120),
        label = "level",
    )

    Title(stringResource(R.string.recognition_listening))
    Spacer24()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
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
        text = stringResource(R.string.recognition_seconds, (elapsedMs / 1000).toInt()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
