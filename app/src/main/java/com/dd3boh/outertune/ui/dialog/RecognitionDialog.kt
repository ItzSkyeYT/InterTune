/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.fingerprint.SignatureGenerator
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.MicrophoneSnippet
import com.dd3boh.outertune.recognition.RecognitionResult
import com.dd3boh.outertune.recognition.ShazamClient
import com.dd3boh.outertune.utils.urlEncode
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Phase {
    data object NeedsPermission : Phase
    data object Listening : Phase
    data class Done(val result: RecognitionResult) : Phase
}

/**
 * Listen to the room, and say what is playing.
 *
 * Our own playback is paused for the duration and resumed afterwards, because a microphone a few
 * centimetres from the speaker hears this app louder than it hears the room, and would identify
 * the song already playing every single time.
 */
@Composable
fun RecognitionDialog(
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    var phase by remember {
        mutableStateOf<Phase>(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) Phase.Listening else Phase.NeedsPermission
        )
    }
    var attempt by remember { mutableStateOf(0) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        phase = if (granted) Phase.Listening
        else Phase.Done(RecognitionResult.Failed(context.getString(R.string.recognise_no_mic)))
    }

    LaunchedEffect(phase, attempt) {
        if (phase !is Phase.Listening) return@LaunchedEffect

        // Only stop the music when the music is in the room. On headphones the mic hears the room
        // and our playback does not reach it, so there is nothing to gain by interrupting somebody
        // mid-song. On the phone's own speaker they share the same air and leaving it running
        // would recognise the song already playing, every time.
        //
        // wasPlaying is also what stops a recognition started while paused from starting playback
        // when it finishes.
        val wasPlaying = playerConnection?.player?.isPlaying == true
        val mustPause = wasPlaying && !MicrophoneSnippet.playbackIsPrivate(context)
        if (mustPause) playerConnection.player.pause()

        phase = try {
            val samples = MicrophoneSnippet.record()
            if (samples == null) {
                Phase.Done(RecognitionResult.Failed(context.getString(R.string.recognise_no_mic)))
            } else {
                val signature = withContext(Dispatchers.Default) {
                    SignatureGenerator.makeSignature(samples)
                }
                if (signature.peaksByBand.sumOf { it.size } == 0) {
                    Phase.Done(RecognitionResult.NoMatch)
                } else {
                    Phase.Done(ShazamClient.identify(signature, samples.size))
                }
            }
        } finally {
            if (mustPause) playerConnection?.player?.play()
        }
    }

    if (showAddToPlaylist) {
        val match = (phase as? Phase.Done)?.result as? RecognitionResult.Match
        AddToPlaylistDialog(
            navController = navController,
            songIds = match?.youtubeId?.let { listOf(it) },
            onDismiss = { showAddToPlaylist = false },
        )
    }

    DefaultDialog(
        onDismiss = onDismiss,
        // The icon slot is not decoration. DefaultDialog aligns its title to the start without one
        // and to the centre with one, and everything below here is centred, so without it the
        // title is the only thing hanging off to the left.
        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
        title = { Text(stringResource(R.string.recognise)) },
        buttons = {
            // Every action lives here. A button floating in the content sat a long way above this
            // row with nothing between them, which read as the dialog having failed to fill.
            when (phase) {
                Phase.NeedsPermission -> TextButton(onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.recognise_allow)) }

                is Phase.Done -> TextButton(onClick = { attempt++; phase = Phase.Listening }) {
                    Text(stringResource(R.string.recognise_again))
                }

                Phase.Listening -> Unit
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.explain_close)) }
        },
    ) {
        when (val p = phase) {
            Phase.NeedsPermission -> Text(
                text = stringResource(R.string.recognise_no_mic),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )

            Phase.Listening -> {
                // Side by side rather than stacked. A spinner above a word left a tall sparse
                // block with the content bunched at the top of the dialog and Close stranded a
                // long way below it.
                val lines = stringArrayResource(R.array.recognise_listening_lines)
                var line by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    // Ten seconds of a spinner and one unchanging word feels far longer than ten
                    // seconds. The first line is shown on arrival, so the wait starts here.
                    while (true) {
                        delay(2_200)
                        line = (line + 1) % lines.size
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = lines[line],
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is Phase.Done -> when (val r = p.result) {
                is RecognitionResult.Match -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    r.artworkUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(144.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(r.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (r.artist.isNotBlank()) {
                        Text(
                            text = r.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A YouTube id plays straight away. Without one there is nothing to queue,
                        // so the search is handed the title and artist instead of guessing an id.
                        if (r.youtubeId != null) {
                            TextButton(onClick = {
                                scope.launch {
                                    playerConnection?.playQueue(
                                        YouTubeQueue(WatchEndpoint(videoId = r.youtubeId))
                                    )
                                }
                                onDismiss()
                            }) { Text(stringResource(R.string.recognise_play)) }
                        } else {
                            TextButton(onClick = {
                                navController.navigate("search/${"${r.title} ${r.artist}".urlEncode()}")
                                onDismiss()
                            }) { Text(stringResource(R.string.recognise_search)) }
                        }
                        TextButton(onClick = { showAddToPlaylist = true }) {
                            Text(stringResource(R.string.add_to_playlist))
                        }
                    }
                }

                RecognitionResult.NoMatch -> Text(
                    text = stringResource(R.string.recognise_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )

                is RecognitionResult.Failed -> Text(
                    text = r.reason.ifBlank { stringResource(R.string.recognise_failed) },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}
