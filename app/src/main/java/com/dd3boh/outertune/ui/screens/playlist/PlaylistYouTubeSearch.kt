/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FailedNote
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.utils.WhyStopped
import com.zionhuang.innertube.models.SongItem

/**
 * A playlist's search on YouTube Music (Unreleased.PLAYLIST_YT_SEARCH, his pick of 15 Nov 2026,
 * experiments/mocks-1115/2-playlist-search, A): the decisions, with no Android in them, so they are tested
 * (PlaylistYouTubeSearchTest).
 */
object PlaylistSearch {

    /**
     * Whether the search pill has the switch: on a playlist that can be changed, since a song found is for adding
     * to it, and never while the playlist shows the search of an empty one, which looks on YouTube Music already.
     */
    fun offered(flag: Boolean, editable: Boolean, addMode: Boolean): Boolean = flag && editable && !addMode

    /** Whether the search looks on YouTube Music now: the switch is there and set so. */
    fun online(offered: Boolean, source: SearchSource): Boolean = offered && source == SearchSource.ONLINE

    /** A song found shows the tick: in the playlist before the search, or added from it since. */
    fun inPlaylist(id: String, playlistIds: Set<String>, justAdded: Set<String>): Boolean = id in playlistIds || id in justAdded

    /**
     * Whether the wheel turns: a search on its way, or words typed that no answer is for yet (the search waits for
     * the typing to stop). Without the second, "Nothing found" flashed up between a word and its answer.
     */
    fun waiting(query: String, answered: String, searching: Boolean): Boolean = searching || (query.isNotBlank() && query != answered)
}

/**
 * The switch at the end of the search pill, as the library search has its own (SearchBarContainer): the picture is
 * where the search looks now, the playlist's queue or YouTube Music's globe, and a tap turns it to the other. Said
 * as a switch, "Search YouTube Music", on or off.
 */
@Composable
fun PlaylistSearchSwitch(online: Boolean, onChange: (Boolean) -> Unit) {
    val name = stringResource(R.string.search_yt_music)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .toggleable(value = online, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = name },
    ) {
        Icon(
            imageVector = if (online) Icons.Rounded.Language else Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = null,
        )
    }
}

/**
 * The songs YouTube Music finds for [query], each with the add button the search of an empty playlist has (a tick
 * once the song is in the playlist: the row stays, so a finger does not lose its place) and its three dots, whose
 * menu has Add to playlist too. A tap plays the song as a tap on a search result does; a long press opens its menu.
 * A search YouTube did not answer says why, with the fix (FailedNote); one it answered with nothing says so.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.playlistYouTubeResults(
    query: String,
    results: List<SongItem>,
    waiting: Boolean,
    failed: WhyStopped.Kind?,
    inPlaylist: (String) -> Boolean,
    playingId: String?,
    isPlaying: Boolean,
    onAdd: (SongItem) -> Unit,
    onPlay: (SongItem) -> Unit,
    onMenu: (SongItem) -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
) {
    if (waiting) {
        item(key = "yt searching") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                CircularProgressIndicator()
            }
        }
    }

    // Keyed by song, each once: the view model's list is distinct (AddSongsOnceTest).
    items(items = results, key = { "yt_" + it.id }) { song ->
        val added = inPlaylist(song.id)
        YouTubeListItem(
            item = song,
            isActive = song.id == playingId,
            isPlaying = isPlaying,
            trailingContent = {
                IconButton(
                    enabled = !added,
                    onClick = { onAdd(song) }
                ) {
                    Icon(
                        imageVector = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.add_to_playlist),
                    )
                }
                IconButton(
                    onClick = { onMenu(song) }
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.options)
                    )
                }
            },
            modifier = Modifier
                .combinedClickable(
                    onClick = { onPlay(song) },
                    onLongClick = { onMenu(song) },
                )
                .animateItem()
        )
    }

    if (!waiting && results.isEmpty() && query.isNotBlank()) {
        if (failed != null) {
            item(key = "yt failed") {
                FailedNote(kind = failed, onSignIn = onSignIn, onRetry = onRetry)
            }
        } else {
            item(key = "yt nothing") {
                EmptyPlaceholder(
                    icon = Icons.Rounded.MusicNote,
                    text = stringResource(R.string.playlist_empty_no_results),
                )
            }
        }
    }
}
