/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.PlaylistSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The blocked artists as they stand, followed: a new value only when the list itself changes. */
val blockedArtists: Flow<ArtistBlock>
    get() = ContentFilter.changes.map { it.blocked }.distinctUntilChanged()

/**
 * A list of the library without the blocked artists' songs, albums and artists (ArtistBlock),
 * again whenever a block changes. With nothing blocked the list goes through as it came.
 */
fun <T : LocalItem> Flow<List<T>>.withoutBlocked(): Flow<List<T>> =
    combine(blockedArtists) { items, block -> block.library(items) }

/** A playlist's rows without the blocked artists' songs, the same way. */
@JvmName("playlistRowsWithoutBlocked")
fun Flow<List<PlaylistSong>>.withoutBlocked(): Flow<List<PlaylistSong>> =
    combine(blockedArtists) { rows, block -> if (block.isEmpty) rows else rows.filterNot { block.hides(it.song) } }
