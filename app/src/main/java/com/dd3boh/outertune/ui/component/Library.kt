/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.AlbumListItem
import com.dd3boh.outertune.ui.component.items.ArtistGridItem
import com.dd3boh.outertune.ui.component.items.ArtistListItem
import com.dd3boh.outertune.ui.component.items.PlaylistGridItem
import com.dd3boh.outertune.ui.component.items.PlaylistListItem
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.ArtistMenu
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.menu.PlaylistMenu
import com.dd3boh.outertune.ui.menu.YouTubePlaylistMenu
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope

@Composable
fun LibraryArtistListItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier,
    // In an order of your own, unlocked: the handle the row is dragged by.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the row: Move up and Move down.
    moves: OrderMoves? = null,
) = ArtistListItem(
    artist = artist,
    trailingContent = {
        val haptic = LocalHapticFeedback.current
        IconButton(
            onClick = {
                menuState.show {
                    ArtistMenu(
                        originalArtist = artist,
                        coroutineScope = coroutineScope,
                        onDismiss = menuState::dismiss
                    )
                }
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.options)
            )
        }
        if (dragHandle != null) RowDragHandle(dragHandle)
    },
    modifier = modifier
        .orderMoves(moves)
        .fillMaxWidth()
        .clickable {
            navController.navigate("artist/${artist.id}")
        }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryArtistGridItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier,
    // In an order of your own, unlocked: the handle the tile is dragged by, on its picture's corner.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the tile: Move up and Move down.
    moves: OrderMoves? = null,
) = TileWithDragHandle(modifier, dragHandle, moves) { tile ->
    ArtistGridItem(
        artist = artist,
        fillMaxWidth = true,
        modifier = tile
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    navController.navigate("artist/${artist.id}")
                },
                onLongClick = {
                    menuState.show {
                        ArtistMenu(
                            originalArtist = artist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    }
                }
            )
    )
}

@Composable
fun LibraryAlbumListItem(
    modifier: Modifier = Modifier,
    navController: NavController,
    menuState: MenuState,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    // In an order of your own, unlocked: the handle the row is dragged by.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the row: Move up and Move down.
    moves: OrderMoves? = null,
) = AlbumListItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    trailingContent = {
        val haptic = LocalHapticFeedback.current
        IconButton(
            onClick = {
                menuState.show {
                    AlbumMenu(
                        originalAlbum = album,
                        navController = navController,
                        onDismiss = menuState::dismiss
                    )
                }
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.options)
            )
        }
        if (dragHandle != null) RowDragHandle(dragHandle)
    },
    modifier = modifier
        .orderMoves(moves)
        .fillMaxWidth()
        .clickable {
            navController.navigate("album/${album.id}")
        }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryAlbumGridItem(
    modifier: Modifier = Modifier,
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    // In an order of your own, unlocked: the handle the tile is dragged by, on its cover's corner.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the tile: Move up and Move down.
    moves: OrderMoves? = null,
) = TileWithDragHandle(modifier, dragHandle, moves) { tile ->
    AlbumGridItem(
        album = album,
        isActive = isActive,
        isPlaying = isPlaying,
        coroutineScope = coroutineScope,
        fillMaxWidth = true,
        modifier = tile
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    navController.navigate("album/${album.id}")
                },
                onLongClick = {
                    menuState.show {
                        AlbumMenu(
                            originalAlbum = album,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                    }
                }
            )
    )
}

@Composable
fun LibraryPlaylistListItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier,
    // In an order of your own, unlocked: the handle the row is dragged by, as a playlist's songs.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the row: Move up and Move down.
    moves: OrderMoves? = null,
) = PlaylistListItem(
    playlist = playlist,
    trailingContent = {
        val haptic = LocalHapticFeedback.current
        IconButton(
            onClick = {
                menuState.show {
                    // TODO: investigate why song count is needed. Remove if not needed
                    if (playlist.playlist.isEditable || playlist.playlist.isLocal || playlist.playlist.browseId == null || playlist.songCount != 0) {
                        PlaylistMenu(
                            navController = navController,
                            playlist = playlist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    } else {
                        val browseId = playlist.playlist.browseId
                        YouTubePlaylistMenu(
                            navController = navController,
                            playlist = PlaylistItem(
                                id = browseId,
                                title = playlist.playlist.name,
                                author = null,
                                songCountText = null,
                                thumbnail = null,
                                playEndpoint = WatchEndpoint(
                                    playlistId = browseId,
                                    params = playlist.playlist.playEndpointParams
                                ),
                                shuffleEndpoint = WatchEndpoint(
                                    playlistId = browseId,
                                    params = playlist.playlist.shuffleEndpointParams
                                ),
                                radioEndpoint = WatchEndpoint(
                                    playlistId = "RDAMPL$browseId",
                                    params = playlist.playlist.radioEndpointParams
                                ),
                                isEditable = false
                            ),
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    }
                }
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.options)
            )
        }
        if (dragHandle != null) RowDragHandle(dragHandle)
    },
    modifier = modifier
        .orderMoves(moves)
        .fillMaxWidth()
        .clickable {
            navController.navigate(playlist.navigationRoute)
        }
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPlaylistGridItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier,
    // In an order of your own, unlocked: the handle the tile is dragged by, on its cover's corner.
    dragHandle: Modifier? = null,
    // With it, TalkBack's way to move the tile: Move up and Move down.
    moves: OrderMoves? = null,
) {
    val clicks = Modifier
        .combinedClickable(
            onClick = {
                navController.navigate(playlist.navigationRoute)
            },
            onLongClick = {
                menuState.show {
                    if (playlist.playlist.isEditable || playlist.songCount != 0) {
                        PlaylistMenu(
                            navController = navController,
                            playlist = playlist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    } else {
                        playlist.playlist.browseId?.let { browseId ->
                            YouTubePlaylistMenu(
                                navController = navController,
                                playlist = PlaylistItem(
                                    id = browseId,
                                    title = playlist.playlist.name,
                                    author = null,
                                    songCountText = null,
                                    thumbnail = null,
                                    playEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.playEndpointParams
                                    ),
                                    shuffleEndpoint = WatchEndpoint(
                                        playlistId = browseId,
                                        params = playlist.playlist.shuffleEndpointParams
                                    ),
                                    radioEndpoint = WatchEndpoint(
                                        playlistId = "RDAMPL$browseId",
                                        params = playlist.playlist.radioEndpointParams
                                    ),
                                    isEditable = false
                                ),
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss
                            )
                        }
                    }
                }
            }
        )
    TileWithDragHandle(modifier, dragHandle, moves) { tile ->
        PlaylistGridItem(
            playlist = playlist,
            fillMaxWidth = true,
            modifier = tile
                .fillMaxWidth()
                .then(clicks)
        )
    }
}

/**
 * The handle a row is dragged by in an order of your own, after its menu button. Not a stop for TalkBack: it was
 * an unnamed button on every row that did nothing when tapped, and the row itself has Move up and Move down.
 */
@Composable
private fun RowDragHandle(dragHandle: Modifier) {
    IconButton(
        onClick = { },
        modifier = Modifier
            .clearAndSetSemantics { }
            .then(dragHandle)
    ) {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = null
        )
    }
}

/**
 * A tile of the grid, given the modifier it is laid out with; in an order of your own, unlocked,
 * with the handle it is dragged by on its cover's corner, and [moves] on the tile, where TalkBack
 * stops and its tap is.
 */
@Composable
private fun TileWithDragHandle(
    modifier: Modifier,
    dragHandle: Modifier?,
    moves: OrderMoves?,
    tile: @Composable (Modifier) -> Unit,
) {
    if (dragHandle == null) {
        tile(modifier)
        return
    }
    Box(modifier.fillMaxWidth()) {
        tile(Modifier.orderMoves(moves))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f), CircleShape)
                .clearAndSetSemantics { }
                .then(dragHandle)
        ) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Move up and Move down: how TalkBack and Switch Access arrange an order of your own, where a finger
 * drags the handle. Each takes the row or tile one place, as a drag over its neighbour does, and is
 * null where there is no place to go.
 */
class OrderMoves(val up: (() -> Unit)?, val down: (() -> Unit)?) {
    companion object {
        /** The moves of the item at [at] among [count] (-1: not listed, so none), each a [step] of -1 or 1. */
        fun of(at: Int, count: Int, step: (Int) -> Unit): OrderMoves {
            val up: () -> Unit = { step(-1) }
            val down: () -> Unit = { step(1) }
            return OrderMoves(up.takeIf { at in 1 until count }, down.takeIf { at in 0 until count - 1 })
        }
    }
}

/** [moves] as actions TalkBack offers on the row or tile they move; nothing without them. */
@Composable
private fun Modifier.orderMoves(moves: OrderMoves?): Modifier {
    if (moves == null) return this
    val up = stringResource(R.string.order_move_up)
    val down = stringResource(R.string.order_move_down)
    return semantics {
        customActions = listOfNotNull(
            moves.up?.let { move -> CustomAccessibilityAction(up) { move(); true } },
            moves.down?.let { move -> CustomAccessibilityAction(down) { move(); true } },
        )
    }
}
