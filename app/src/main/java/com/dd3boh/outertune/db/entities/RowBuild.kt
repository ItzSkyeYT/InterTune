/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One assembly of a recommendation row.
 *
 * An impression only means something against the row it belonged to: which source built it, when,
 * under which settings, and with which weights. The unshown candidates are kept for a fortnight so
 * a song the listener went and played on their own can still be recognised as a pick the engine
 * had rated low.
 */
@Immutable
@Entity(tableName = "row_build")
data class RowBuild(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val builtAt: Long,
    /** 1 engine, 2 classic query, 3 YouTube's row, 4 shadow build, 5 compare. */
    val rowKey: Int,
    val sessionId: Long,
    /** Weekday or weekend times night, morning, afternoon, evening: 0 to 7. */
    val bucket: Int = 0,
    val contextChip: Int = 0,
    val dial: Int = 15,
    val engineVersion: Int = 0,
    val seeds: String = "[]",
    val weights: String = "{}",
    val pool: String? = null,
    /** The row's cards as RowBuildCodec text, so Home can show the last build at once and a shadow build can be judged. */
    val cards: String? = null,
    /** After the build's day: the listener's picks in that time, and how many the row held. */
    val plays: Int? = null,
    val hits: Int? = null,
    val gradedAt: Long? = null,
)
