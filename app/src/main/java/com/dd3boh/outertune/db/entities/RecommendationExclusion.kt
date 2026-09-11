/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something the recommendation rows must not suggest: a song (and its other versions) or an
 * artist, banned, snoozed for a while, or rested automatically after a skip. Touches only the
 * recommendation rows, never search, library, playlists or radio.
 */
@Immutable
@Entity(tableName = "recommendation_exclusion", indices = [Index(value = ["kind", "targetId"], unique = true)])
data class RecommendationExclusion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1 song, 2 artist. */
    val kind: Int,
    val targetId: String,
    val label: String,
    /** 1 ban, 2 snooze, 3 automatic rest. The strongest reason wins on conflict. */
    val reason: Int,
    val createdAt: Long,
    /** Null: until removed. */
    val expiresAt: Long? = null,
)
