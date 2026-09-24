/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.text.style.TextOverflow
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.TopBarActions

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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.RecogniseKeepListeningKey
import com.dd3boh.outertune.constants.RecognisePauseOnSpeakerKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.constants.RecogniseKeepAwakeKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.AudioRoute
import com.dd3boh.outertune.recognition.RecognitionEngine
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.AnimatedDots
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.rememberRecognitionPhrase
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.backButtonSurface
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.urlEncode
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    val snackbarHostState = LocalSnackbarHostState.current

    val state by viewModel.state.collectAsState()
    val running by viewModel.running.collectAsState()
    val continuous by viewModel.continuous.collectAsState()
    val skipped by viewModel.skipped.collectAsState()
    val recognised by viewModel.recognised.collectAsState()
    val following by viewModel.following.collectAsState()

    // Held only while it is actually listening, and released the moment it stops or the screen
    // leaves. Listening itself survives the screen going off, since the service holds a foreground
    // microphone type; this is only so somebody watching it work does not have to keep tapping.
    val (keepAwake) = rememberPreference(RecogniseKeepAwakeKey, defaultValue = false)
    val view = LocalView.current
    DisposableEffect(running, keepAwake) {
        val on = running && keepAwake
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = false }
    }

    val (keepListeningDefault) = rememberPreference(RecogniseKeepListeningKey, defaultValue = false)
    val (pauseOnSpeaker) = rememberPreference(RecognisePauseOnSpeakerKey, defaultValue = true)

    var addToPlaylistFor by remember { mutableStateOf<SongItem?>(null) }
    var addAllToPlaylist by remember { mutableStateOf(false) }
    var pendingContinuous by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setContinuous(pendingContinuous)
            viewModel.start(null)
        }
    }

    /**
     * The escape hatch, for every case where the engine cannot hand over something playable.
     *
     * Shazam gives a title and an artist even when the YouTube search finds nothing, and the app
     * already has a search that takes exactly that. Sending it there beats showing a name with no
     * way to act on it, and it is also the answer when the match is right but the version is not:
     * a song has a dozen uploads and Shazam has an opinion about none of them.
     */
    fun searchFor(title: String, artist: String) {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isNotBlank()) navController.navigate("search/${query.urlEncode()}")
    }

    // Music started while listening, from a row here, a song menu's radio or the mini player. If it
    // comes out of the speaker the microphone will only ever name our own song from here on, so
    // the run stops, by the same rule listen() pauses the music by. Only a start counts, not the
    // value on arrival, which can still be true for a moment after listen() paused it.
    LaunchedEffect(playerConnection, running, pauseOnSpeaker) {
        if (!running || !pauseOnSpeaker) return@LaunchedEffect
        val playing = playerConnection?.isPlaying ?: return@LaunchedEffect
        var was = playing.value
        playing.collect { now ->
            if (now && !was && !AudioRoute.playbackIsPrivate(context)) viewModel.stop()
            was = now
        }
    }

    fun listen(keepGoing: Boolean) {
        if (running) {
            // The mode that is running stops it. The other one switches the run over without
            // closing the microphone: the engine reads the mode afresh on every window.
            if (keepGoing == continuous) viewModel.stop() else viewModel.setContinuous(keepGoing)
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

    if (addAllToPlaylist) {
        AddToPlaylistDialog(
            navController = navController,
            // Null, so the picker takes the ids from onPreAdd, which runs once a playlist is picked
            // and so sees every song heard while the picker was open.
            songIds = null,
            onPreAdd = { playlist -> viewModel.prepareForPlaylist(playlist, recognised) },
            onAdded = { playlist -> viewModel.follow(playlist.playlist) },
            onDismiss = { addAllToPlaylist = false },
        )
    }

    /**
     * Saving the list as a playlist. Non-null while the naming dialog is open, holding the name it
     * opened with, which needs the library's playlist names and so is worked out before it opens,
     * the way the queue sheet does it.
     */
    var saveName by remember { mutableStateOf<String?>(null) }

    fun proposeSave() {
        scope.launch {
            val today = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            saveName = viewModel.proposePlaylistName(
                context.getString(R.string.recognise_playlist_name, today)
            )
        }
    }

    saveName?.let { proposed ->
        TextFieldDialog(
            icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
            title = { Text(stringResource(R.string.recognise_save_as_playlist)) },
            initialTextFieldValue = TextFieldValue(proposed, TextRange(proposed.length)),
            isInputValid = { it.isNotBlank() },
            onDismiss = { saveName = null },
            onDone = { typed ->
                // The list as it is when the name is confirmed, in the order it was heard, which
                // includes anything recognised while the dialog was open.
                val songs = recognised
                val name = typed.trim()
                scope.launch {
                    val saved = viewModel.saveAsPlaylist(name, songs)
                    snackbarHostState.showSnackbar(
                        if (saved) context.getString(R.string.saved_as_playlist, name)
                        else context.getString(R.string.recognise_save_failed)
                    )
                }
            },
        )
    }

    val level = (state as? RecognitionEngine.State.Listening)?.level ?: 0f
    val identifying = (state as? RecognitionEngine.State.Listening)?.identifying == true

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
                compact = recognised.isNotEmpty() || skipped.isNotEmpty() ||
                        state is RecognitionEngine.State.Found,
                // The big button always stops a run, whichever mode it is in.
                onClick = { if (running) viewModel.stop() else listen(keepListeningDefault) },
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
                            // These are songs it heard and named but could not place confidently.
                            // They were text, which made the list a tally of near misses with no
                            // way to act on any of them.
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { searchFor(entry.title, entry.artist) }
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // What it heard but could not place confidently. The candidates are shown rather than one
        // guess being taken, because a search for a title and an artist lands on live takes,
        // covers and sped-up edits, and picking the wrong one silently is worse than asking.
        (state as? RecognitionEngine.State.Found)?.let { found ->
            // Placed with confidence, in which case the engine has put it in the list below and
            // it is drawn there, newest first, with the full song menu. Showing the candidates as
            // well put the same song on screen twice, so only the way out is kept: the others are
            // the covers and live takes the search would show anyway.
            val listed = found.certain && recognised.any { it.id == found.candidates.firstOrNull()?.id }
            item(key = "found_header") {
                Text(
                    text = found.track.title + (found.track.artist?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "find_other") {
                Text(
                    text = stringResource(
                        if (found.candidates.isEmpty()) R.string.recognition_find_nothing
                        else R.string.recognition_find_other
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { searchFor(found.track.title, found.track.artist.orEmpty()) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            // Keys carry a prefix because a candidate can be a song already in the list below,
            // heard on an earlier listen, and a key used twice in one LazyColumn is a crash.
            if (!listed) items(found.candidates, key = { "candidate/${it.id}" }) { song ->
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
        // screen, because a title with nothing under it is a promise the screen has not kept. From
        // the first song on, it is where every song lands: there is no separate "now playing"
        // line under the cards any more, which only repeated what was about to appear here.
        if (recognised.isNotEmpty()) {
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
                    TextButton(onClick = { viewModel.reset(); viewModel.clearRecognised() }) {
                        Text(stringResource(R.string.recognise_clear_history))
                    }
                }
            }

            item(key = "save_playlist") {
                val target = following
                if (target == null) {
                    // Either makes the list a playlist or puts it in one, and from then on every
                    // new song goes in as well, so a run left going fills it by itself.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        FilledTonalButton(onClick = { proposeSave() }, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.create_playlist), maxLines = 1)
                        }
                        FilledTonalButton(onClick = { addAllToPlaylist = true }, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Rounded.LibraryAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_to_playlist), maxLines = 1)
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.recognise_following, target.name),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.stopFollowing() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.recognise_stop_following),
                            )
                        }
                    }
                }
            }
        }

        // Newest first, so the song just heard sits under the button rather than off the bottom
        // of the screen after an evening of listening. A saved playlist runs the other way, in
        // the order the songs were played, which is the order the engine keeps them in.
        items(recognised.asReversed(), key = { "recognised/${it.id}" }) { song ->
            RecognisedSongRow(
                song = song,
                navController = navController,
                modifier = Modifier.animateItem(),
            )
        }
    }

    FloatingTopBar(
        title = stringResource(R.string.recognise),
        navController = navController,
        actions = {
            TopBarActions {
                IconButton(onClick = { navController.navigate("recognition/history") }) {
                    Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.recognition_history))
                }
            }
        },
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
 * A song this session recognised, drawn exactly as a search result is.
 *
 * Not a lookalike of one: the same [YouTubeListItem] that search draws, the same [YouTubeSongMenu]
 * behind the three dots and on a long press, the same swipe to queue, and a tap that starts the
 * same radio a tapped search result does. Anything a person can do with a song they found by typing
 * they can do with one they found by holding the phone up.
 */
@Composable
private fun RecognisedSongRow(
    song: SongItem,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val snackbarHostState = LocalSnackbarHostState.current
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    fun openMenu() = menuState.show {
        YouTubeSongMenu(
            song = song,
            navController = navController,
            onDismiss = menuState::dismiss,
        )
    }

    SwipeToQueueBox(
        item = song.toMediaItem(),
        swipeEnabled = swipeEnabled,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        YouTubeListItem(
            item = song,
            isActive = mediaMetadata?.id == song.id,
            isPlaying = isPlaying,
            trailingContent = {
                IconButton(onClick = { openMenu() }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (song.id == mediaMetadata?.id) {
                        playerConnection.player.togglePlayPause()
                    } else {
                        // Radio, as search does. The origin is the one that exists for songs
                        // identified by ear, which the recommendations weigh as they do a search.
                        playerConnection.playQueue(
                            YouTubeQueue.radio(song.toMediaMetadata()),
                            isRadio = true,
                            replace = true,
                            origin = PlayOrigin.RECOGNISED,
                        )
                    }
                },
                onLongClick = { openMenu() },
            ),
        )
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
