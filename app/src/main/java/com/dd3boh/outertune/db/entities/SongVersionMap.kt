/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity

/**
 * YouTube's own word that two ids are versions of one song: its "Other performances" shelf, kept
 * per seed at the moment the related list is fetched. Catches covers and third-party remixes that
 * no title rule can. No foreign keys, because the versions themselves are not inserted as songs.
 */
@Immutable
@Entity(tableName = "song_version_map", primaryKeys = ["songId", "versionId"])
data class SongVersionMap(
    val songId: String,
    val versionId: String,
    val fetchedAt: Long,
)
