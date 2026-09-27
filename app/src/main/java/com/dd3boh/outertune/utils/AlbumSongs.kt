/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * An album with its songs in the album's own order.
 *
 * The albumWithSongs relation reads through the sorted_song_album_map view, and SQLite drops a
 * view's ORDER BY once it is joined, so the songs came out in the order they were stored: a song
 * heard before the album was opened led it, on the album page (fixed there first), in the album
 * menu from search, on the play button of an album's cover and in the car. albumSongs sorts in the
 * query itself. Everything that plays or lists an album's songs reads this; only code that wants
 * the set of ids, where order does not matter, reads the relation directly.
 */
fun MusicDatabase.albumWithOrderedSongs(albumId: String): Flow<AlbumWithSongs?> =
    combine(albumWithSongs(albumId), albumSongs(albumId)) { album, ordered -> album?.copy(songs = ordered) }

/**
 * The songs the play button on an album's cover plays: the stored ones, or, for a YouTube album
 * with none stored, the ones on its page, which are stored on the way.
 *
 * A saved album arrives with no songs. Its button read that empty list as the album's songs,
 * skipped the fetch that a missing album got, and asked the player to play nothing, which carried
 * on with whatever queue it had. Empty when there is nothing to play; the caller plays nothing.
 */
suspend fun albumSongsToPlay(database: MusicDatabase, albumId: String): List<MediaMetadata> {
    val stored = database.albumWithOrderedSongs(albumId).first()
    if (stored != null && (stored.songs.isNotEmpty() || stored.album.isLocal)) {
        return stored.songs.map { it.toMediaMetadata() }
    }
    val page = YouTube.album(albumId).onFailure(::reportException).getOrNull() ?: return emptyList()
    database.transaction {
        // A saved album is already there, and insert would skip the whole page for it.
        val current = albumById(albumId)
        if (current == null) insert(page) else update(current, page)
    }
    return page.songs.map { it.toMediaMetadata() }
}
