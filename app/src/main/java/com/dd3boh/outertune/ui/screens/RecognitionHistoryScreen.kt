/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.extensions.isInternetConnected
import com.dd3boh.outertune.history.HistoryFormat
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.recognition.Heard
import com.dd3boh.outertune.recognition.KeptListen
import com.dd3boh.outertune.recognition.RecognitionViewModel
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ItemThumbnail
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.items.rememberIsDontPlay
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.ui.utils.itemsInColumns
import com.dd3boh.outertune.ui.utils.rememberListColumns
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.urlEncode
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A line of What you have heard: a listen still waiting to be named, or a song heard. */
private sealed interface HeardLine {
    data class Waiting(val listen: KeptListen) : HeardLine
    data class Song(val entry: Heard) : HeardLine
}

/**
 * Every song the app has heard in a room, as the song rows of every other list.
 *
 * A song the library holds is its own row ([SongListItem]), as in Songs or History: its cover, its
 * name there, its marks and its menu. Any other is drawn from the same pieces ([ListItem],
 * [ItemThumbnail]) under the name Shazam gave it, with the picture of its video or Shazam's own
 * cover ([Heard.picture]), and the menu of search results when it has a video to act on. The
 * second line ends on when it was heard, short ([HistoryFormat.short]). A tap plays the song, or
 * searches for it when it was never placed on YouTube.
 *
 * Above the songs, what was heard with no connection and waits to be named (RecogniseLater), newest
 * first, in the same shape: the sound bars where the cover goes. Nothing to tap there yet: the song
 * takes its place in the list once it is named.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionHistoryScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val heard by viewModel.heard.collectAsStateWithLifecycle(initialValue = emptyList())
    val kept by viewModel.waiting.collectAsStateWithLifecycle(initialValue = emptyList())
    val waiting = if (Unreleased.RECOGNISE_OFFLINE) kept.asReversed() else emptyList()
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val scope = rememberCoroutineScope()

    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true
    val playingId = playerConnection?.mediaMetadata?.collectAsState()?.value?.id

    // The library's rows of the songs heard, followed, so a like or a download shows on its row at
    // once as in every list. Null until read once: a song the library holds is drawn as its own
    // row from the start, not as another that changes under the eye a moment later.
    val ids = remember(heard) { heard.mapNotNull { it.videoId }.distinct() }
    val library by remember(ids) {
        if (ids.isEmpty()) flowOf(emptyList()) else database.songsByIds(ids)
    }.collectAsStateWithLifecycle(initialValue = null)
    val inLibrary = remember(library) { library.orEmpty().associateBy { it.id } }

    // When each was heard, in the phone's language and on its clock, as History writes its times.
    val configuration = LocalConfiguration.current
    val todayText = stringResource(R.string.today)
    val yesterdayText = stringResource(R.string.yesterday)
    val format = remember(context, configuration, todayText, yesterdayText) {
        HistoryFormat(
            locale = configuration.locales[0],
            is24Hour = DateFormat.is24HourFormat(context),
            today = todayText,
            yesterday = yesterdayText,
        )
    }
    val today = LocalDate.now()
    fun heardWhen(at: Long): String =
        format.short(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime(), today)

    // Placed on YouTube at the time: play it. Named but never placed, which is a real and common
    // outcome: search for it, so the row is worth tapping either way rather than being a name with
    // nothing behind it.
    fun openEntry(entry: Heard) {
        scope.launch {
            // Handed the song when the library holds it, so it loads at once and the rest joins
            // it. The entry itself cannot stand in: it keeps what Shazam called the song, not its
            // title on YouTube, its artists or its cover, and the player would show it that way
            // and the library keep it that way. Unknown, it waits for the answer. The same row
            // says whether an entry saved before its video was checked is the song at all: see
            // Heard.idToPlay.
            val known = entry.videoId
                ?.let { id -> database.song(id).first()?.toMediaMetadata() }
            val id = entry.idToPlay(known)
            if (id != null) {
                playerConnection?.playQueue(
                    YouTubeQueue(WatchEndpoint(videoId = id), known),
                    origin = PlayOrigin.RECOGNISED,
                )
            } else {
                val query = listOf(entry.title, entry.artist)
                    .filter { it.isNotBlank() }.joinToString(" ")
                if (query.isNotBlank()) {
                    navController.navigate("search/${query.urlEncode()}")
                }
            }
        }
    }

    // The menu search results open for a song, on YouTube's own song for [videoId], asked for
    // now. With no answer nothing opens, and a toast says whether the phone is offline.
    fun openMenu(videoId: String) {
        scope.launch {
            val song = viewModel.songOnYouTube(videoId)
            if (song == null) {
                Toaster.show(context, if (context.isInternetConnected()) R.string.error_unknown else R.string.error_no_internet)
                return@launch
            }
            menuState.show {
                YouTubeSongMenu(
                    song = song,
                    navController = navController,
                    onDismiss = menuState::dismiss,
                )
            }
        }
    }

    if (heard.isEmpty() && waiting.isEmpty()) {
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
    } else if (library != null) {
        // Two abreast on a phone on its side, as every list of songs.
        val columns = rememberListColumns()
        val thumbnailSize = with(LocalDensity.current) { ListThumbnailSize.roundToPx() }
        val lines = remember(waiting, heard) {
            waiting.map { HeardLine.Waiting(it) } + heard.map { HeardLine.Song(it) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            itemsInColumns(
                items = lines,
                columns = columns,
                key = { _, line ->
                    when (line) {
                        is HeardLine.Waiting -> "waiting/${line.listen.heardAt}"
                        is HeardLine.Song -> "heard/${line.entry.at}/${line.entry.title}"
                    }
                },
            ) { _, line ->
                when (line) {
                    is HeardLine.Waiting -> WaitingRow(heardWhen = heardWhen(line.listen.heardAt))
                    is HeardLine.Song -> {
                        val entry = line.entry
                        val song = entry.videoId?.let { inLibrary[it] }
                        // The video a tap plays, by the tap's own rule (Heard.idToPlay): an entry
                        // saved before ids were checked keeps its video only when the library's
                        // row says it is the song.
                        val videoId = remember(entry, song) { entry.idToPlay(song?.toMediaMetadata()) }
                        if (song != null && videoId == song.id) {
                            SongListItem(
                                song = song,
                                navController = navController,
                                isActive = song.id == playingId,
                                isPlaying = isPlaying,
                                inSelectMode = null,
                                isSelected = false,
                                onSelectedChange = {},
                                // Off for every row: one the library does not hold has no song to
                                // queue until YouTube is asked, and its menu has Play next.
                                swipeEnabled = false,
                                thumbnailSize = thumbnailSize,
                                onPlay = { openEntry(entry) },
                                caption = heardWhen(entry.at),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            HeardListItem(
                                entry = entry,
                                videoId = videoId,
                                heardWhen = heardWhen(entry.at),
                                isActive = videoId != null && videoId == playingId,
                                isPlaying = isPlaying,
                                onOpen = { openEntry(entry) },
                                onTogglePlay = { playerConnection?.togglePlayPause() },
                                onMenu = videoId?.let { id -> { openMenu(id) } },
                            )
                        }
                    }
                }
            }
        }
    }

    FloatingTopBar(title = stringResource(R.string.recognition_history), navController = navController)
}

/**
 * A song heard that the library does not hold, built from the pieces every song row is
 * ([ListItem], [ItemThumbnail]): the cover, the name Shazam gave, the artist and when it was
 * heard. With a video to act on ([videoId]) it has the dots, and a long press opens the same
 * menu, as on a search result. With none, a tap searches for the song, and there is no menu:
 * liking, queueing or sharing needs a video.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeardListItem(
    entry: Heard,
    videoId: String?,
    heardWhen: String,
    isActive: Boolean,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onMenu: (() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        title = entry.title,
        subtitle = joinByBullet(entry.artist, heardWhen),
        thumbnailContent = {
            val picture = entry.picture(videoId)
            if (picture == null) {
                CoverPlaceholder(Icons.Rounded.MusicNote)
            } else {
                ItemThumbnail(
                    thumbnailUrl = picture,
                    isActive = isActive,
                    isPlaying = isPlaying,
                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                    // A video's still is wider than tall, the song's cover in its middle.
                    contentScale = if (videoId != null) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.size(ListThumbnailSize),
                )
            }
        },
        trailingContent = {
            if (onMenu != null) {
                IconButton(
                    onClick = {
                        onMenu()
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    }
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.options)
                    )
                }
            }
        },
        isActive = isActive,
        isDontPlay = videoId != null && rememberIsDontPlay(videoId, entry.title, entry.artist),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                // On the song playing, as on every row of a list: pause or play on.
                onClick = { if (isActive) onTogglePlay() else onOpen() },
                onLongClick = onMenu,
            ),
    )
}

/** A listen kept with no connection, in the shape of a song's row: when it was heard, and that it waits to be named. */
@Composable
private fun WaitingRow(heardWhen: String) {
    ListItem(
        title = stringResource(R.string.recognise_later_waiting),
        subtitle = heardWhen,
        thumbnailContent = { CoverPlaceholder(Icons.Rounded.GraphicEq) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The cover's square with an icon in it, for a row with no picture, as the Stats page draws a song
 * without one: the note for a song, the sound bars of recognition for a listen still to be named.
 */
@Composable
private fun CoverPlaceholder(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ListThumbnailSize)
            .clip(RoundedCornerShape(ThumbnailCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
