/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.pill.AutoChoice
import com.dd3boh.outertune.mix.pill.Pill
import com.dd3boh.outertune.mix.pill.PillSong
import com.dd3boh.outertune.mix.pill.Pills
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.automix.MixSwitches
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A queue song as a pill's side. Its length is in seconds, and 0 or less while not known. */
fun MediaMetadata.pillSong() = PillSong(id, album?.id, if (duration > 0) duration * 1000L else -1)

/**
 * The Mix playlist the queue playing was started from, with its switch on or off, or null when
 * the queue did not come from a library playlist. Always null without Unreleased.AUTOMIX_EDITOR.
 */
@Composable
fun rememberQueueMix(): Pair<String, Boolean>? {
    if (!Unreleased.AUTOMIX_EDITOR) return null
    val playerConnection = LocalPlayerConnection.current ?: return null
    val playlistId by playerConnection.service.mixPlaylistId.collectAsState()
    val on = rememberMixOn(playlistId)
    return playlistId?.let { it to on }
}

/**
 * The Mix chip in the queue's Songs header (DESIGN.md 2.6): the switch of the playlist the queue
 * came from, so Mix can be turned off from here. Turned off mid-song, the next change is plain.
 * Shown only while the queue came from a library playlist.
 */
@Composable
fun MixQueueChip(modifier: Modifier = Modifier) {
    val (playlistId, on) = rememberQueueMix() ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    FilterChip(
        selected = on,
        onClick = { scope.launch(NonCancellable) { MixSwitches.set(context, playlistId, !on) } },
        label = { Text(stringResource(R.string.mix)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_mix), contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

/**
 * The quiet line under the song playing in a Mix queue (DESIGN.md 2.6): the next change, or why
 * it stays plain. What AutoMix has already decided for this very change wins; before that, it is
 * what the playlist's pill would say, Auto only when shuffled. A tap opens the editor on the pair.
 * Nothing at the end of the queue, and nothing while Mix is off. [songs] is the queue in play
 * order and [index] the song playing in it.
 */
@Composable
fun MixNextLine(
    songs: List<MediaMetadata>,
    index: Int,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val (_, on) = rememberQueueMix() ?: return
    if (!on) return
    val playerConnection = LocalPlayerConnection.current ?: return
    val repeat by playerConnection.repeatMode.collectAsState()
    val shuffled by playerConnection.shuffleModeEnabled.collectAsState()
    val current = songs.getOrNull(index) ?: return
    // With repeat on, the last song mixes into the first.
    val next = songs.getOrNull(index + 1) ?: songs.firstOrNull()?.takeIf { repeat == Player.REPEAT_MODE_ALL } ?: return
    val saved = rememberSavedTransitions()
    val blocker = rememberMixBlocker()
    val planner = rememberMixPlanner()
    val decidedFlow = playerConnection.service.mixDecided ?: NOTHING_DECIDED
    val decided by decidedFlow.collectAsState()
    val pair = SongPair(current.id, next.id)
    // What AutoMix has decided is what will play, so it is said as it is, even over a saved change.
    val pill = decided?.takeIf { it.pair == pair }?.choice?.let { choice ->
        when (choice) {
            is AutoChoice.Plain -> Pill.Plain(choice.reason)
            is AutoChoice.Mixed -> Pill.Move(choice.preset, choice.length)
        }
    } ?: Pills.of(current.pillSong(), next.pillSong(), saved[pair], blocker, planner, shuffled)
    val words = MixWords.text(pill).let { if (pill.edited) stringResource(R.string.mix_pill_yours, it) else it }

    Text(
        text = stringResource(R.string.mix_next, words, next.title),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.mix_edit_transition)) { navController.openMixEditor(pair) }
            .padding(start = 74.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    )
}

/** The player menu's way into the editor: the pair it opens, and whether that is the change just heard. */
data class MixMenuEntry(val pair: SongPair, val last: Boolean)

/**
 * "Edit transition" in the player's menu (DESIGN.md 3.1), for [songId] when it is the song playing
 * in a Mix queue with its switch on: the change into the next song, or for the first 30 s of a song
 * the change just heard, "Edit the last transition", as that is when people want to fix one. Null
 * when the menu has no such item.
 */
@Composable
fun rememberMixMenuEntry(songId: String): MixMenuEntry? {
    val (_, on) = rememberQueueMix() ?: return null
    if (!on) return null
    val playerConnection = LocalPlayerConnection.current ?: return null
    val current by playerConnection.mediaMetadata.collectAsState()
    if (current?.id != songId) return null
    val player = playerConnection.player
    // Read as the menu opens; the item does not change under the finger while it is open.
    return remember(songId) {
        val previous = player.previousMediaItemIndex.takeIf { it >= 0 }?.let { player.getMediaItemAt(it).mediaId }
        val next = player.nextMediaItemIndex.takeIf { it >= 0 }?.let { player.getMediaItemAt(it).mediaId }
        when {
            previous != null && player.currentPosition < LAST_TRANSITION_MS -> MixMenuEntry(SongPair(previous, songId), last = true)
            next != null -> MixMenuEntry(SongPair(songId, next), last = false)
            else -> null
        }
    }
}

/** How long after a song starts the menu offers the change into it rather than the next one. */
private const val LAST_TRANSITION_MS = 30_000L

private val NOTHING_DECIDED = MutableStateFlow(null)
