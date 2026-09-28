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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.RecogniseKeepAwakeKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.recognition.AudioRoute
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.player.KeepScreenOnController
import com.dd3boh.outertune.ui.player.KeepScreenOnReason
import com.dd3boh.outertune.recognition.RecognitionEngine
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.recognition.SheetRun
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
    val retryAt by viewModel.retryAt.collectAsState()

    // Whose run the engine is on. Any run going used to be joined, whatever it was for, so the
    // What's playing? screen's showed here as this playlist's: see SheetRun. Asked again whenever a
    // run starts or stops, which running and startedAt between them always show. Switching from
    // another run to this playlist's leaves running true throughout, and only startedAt moves.
    val run = remember(running, startedAt, playlist.id) { viewModel.sheetRun(playlist, running) }
    val own = run == SheetRun.Own
    // This sheet's own run listening, which is all it keeps the screen on for or stops for music.
    // Another run is left as it would be with the sheet closed.
    val listening = running && own

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

    // The screen's two listening settings apply here too; the sheet used to ignore both. On the
    // phone's own speaker the music and the microphone share the air, so without the pause it
    // named the song already playing and added it to the playlist.
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val (pauseOnSpeaker) = rememberPreference(RecognisePauseOnSpeakerKey, defaultValue = true)
    val (keepAwake) = rememberPreference(RecogniseKeepAwakeKey, defaultValue = false)
    val view = LocalView.current
    // Contributed to the shared holder rather than written to view.keepScreenOn directly: the
    // player writes the same flag for lyrics/immersive landscape, and a plain pause or resume
    // during a listen used to silently clear this sheet's request (or the reverse), whichever
    // disposed last. See KeepScreenOnController.
    DisposableEffect(view, listening, keepAwake) {
        val on = listening && keepAwake
        KeepScreenOnController.set(view, KeepScreenOnReason.RECOGNITION, on)
        onDispose { KeepScreenOnController.set(view, KeepScreenOnReason.RECOGNITION, false) }
    }
    // And music started on the speaker mid-run stops it, as on the screen.
    LaunchedEffect(playerConnection, listening, pauseOnSpeaker) {
        if (!listening || !pauseOnSpeaker) return@LaunchedEffect
        val playing = playerConnection?.isPlaying ?: return@LaunchedEffect
        var was = playing.value
        playing.collect { now ->
            if (now && !was && !AudioRoute.playbackIsPrivate(context)) viewModel.stop()
            was = now
        }
    }
    // Instead is from somebody else's run, which is stopped for this playlist's. Only from its own
    // button: every other start leaves a run already going alone, as the engine does.
    fun start(instead: Boolean = false) {
        if (pauseOnSpeaker && playerConnection?.player?.isPlaying == true && !AudioRoute.playbackIsPrivate(context)) {
            playerConnection.player.pause()
        }
        if (instead) viewModel.listenInstead(playlist) else viewModel.start(playlist)
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start()
        else viewModel.reset()
    }

    // Asked when listening starts, not at launch. A run already going is not restarted. This
    // playlist's is joined, so reopening the sheet on a continuous run does not interrupt it, and
    // anybody else's is only offered to be swapped for this playlist's.
    //
    // Once per opening of the sheet, remembered across a rotation. The effect runs again whenever
    // the activity is recreated, and a rotation or a theme change while a result waited to be
    // chosen used to open the microphone again and replace that result a window later. Not keyed
    // on the engine's state, which the What's playing? screen can leave holding its own last result.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        if (!viewModel.running.value) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Somebody else's run carries on. It is not this sheet's to stop, and closing the sheet
            // used to end the screen's Keep listening with it. Asked afresh, since the sheet can be
            // on its way out for a moment after the last recomposition.
            if (viewModel.sheetRun(playlist).resetOnClose) viewModel.reset()
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
            // Another run going is said plainly in place of its progress. What one left on show once
            // it stopped is not this playlist's either, and is shown as nothing at all.
            if (run is SheetRun.Other) Elsewhere(
                run = run,
                listenHere = stringResource(R.string.recognition_listen_here_instead),
                note = stringResource(R.string.recognition_elsewhere_desc),
                onListenHere = { start(instead = true) },
            ) else when (val s = if (own) state else RecognitionEngine.State.Idle) {
                // Idle is only listening in the moment before a run's first update. Otherwise
                // nothing is: after the microphone was refused, or a stop from the notification
                // with the sheet still open, it pulsed at 0:00 with no button, looking like a run.
                RecognitionEngine.State.Idle ->
                    if (running) Listening(level = 0f, identifying = false, elapsed = elapsed)
                    else Problem(
                        text = stringResource(R.string.recognise_tap_to_listen),
                        onRetry = { permission.launch(Manifest.permission.RECORD_AUDIO) },
                    )

                is RecognitionEngine.State.Listening -> {
                    Listening(
                        level = s.level,
                        identifying = s.identifying,
                        elapsed = elapsed,
                        // Shazam not answering, said in place of the jokes, which would otherwise
                        // carry on as if all were well.
                        message = shazamWaitMessage(retryAt?.takeIf { continuous }),
                    )
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
                    onRetry = { start() },
                )

                RecognitionEngine.State.NoMatch -> Problem(
                    text = stringResource(R.string.recognition_no_match),
                    onRetry = { start() },
                )

                is RecognitionEngine.State.Failed -> Problem(
                    text = stringResource(
                        if (s.heardNothing) R.string.recognition_heard_nothing
                        else R.string.recognition_failed
                    ),
                    onRetry = { start() },
                )
            }

            // Off by default. Continuous listening keeps the microphone open and makes a request
            // every few seconds, which is not something to switch on for somebody. Not over another
            // run: the engine reads the mode on every window, so this changed that run's from under
            // whoever started it.
            if (run !is SheetRun.Other) Row(
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

            // What this playlist's run heard and added, and nobody else's. Under the screen's run
            // the added count sat at nothing however much it heard, and another playlist's counts
            // say nothing about this one.
            nowPlaying?.takeIf { own }?.let { playing ->
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

            if (own && skipped.isNotEmpty()) {
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

            if (own && added.isNotEmpty()) {
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
    // dots carry the movement; the wording changes slowly enough to read. Drawn and timed by the
    // same code as the screen's. This one stepped through the list on its own five second clock,
    // which is how the two came to change at different speeds.
    val drawn by rememberRecognitionPhrase(listening = true, identifying = identifying, adding = true)
    val phrase = message ?: drawn
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

/**
 * Another run has the microphone: the What's playing? screen's, or another playlist's.
 *
 * In place of that run's progress, which the sheet used to show as its own while nothing it heard
 * went into this playlist. The one thing offered is to listen here instead, which [listenHere]
 * words for the sheet or the screen, and closing the sheet leaves the other run going, as [note]
 * says. The screen shows the same over a playlist's run, without a note, having nothing to close.
 */
@Composable
internal fun Elsewhere(run: SheetRun.Other, listenHere: String, note: String?, onListenHere: () -> Unit) {
    Title(
        if (run.playlist != null) {
            stringResource(R.string.recognition_elsewhere_playlist, run.playlist)
        } else {
            stringResource(R.string.recognition_elsewhere_screen, stringResource(R.string.recognise))
        }
    )
    Spacer24()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer24()
    if (note != null) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer24()
    }
    Button(onClick = onListenHere) { Text(listenHere) }
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
