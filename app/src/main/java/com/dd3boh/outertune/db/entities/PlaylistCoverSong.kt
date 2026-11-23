/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.room.Relation

/**
 * One of the first songs of a playlist, those its cover is drawn from: its row of
 * playlist_song_map_preview and the song's picture. Its position puts the cover in the playlist's
 * order (RelationOrder.coverPictures).
 */
data class PlaylistCoverSong(
    val playlistId: String,
    val songId: String,
    val position: Int,
    @Relation(entity = SongEntity::class, parentColumn = "songId", entityColumn = "id", projection = ["thumbnailUrl"])
    val thumbnailUrl: List<String?>,
)
