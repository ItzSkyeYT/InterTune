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
 * and with which model. Kept small on purpose; the candidates are in [Impression].
 */
@Immutable
@Entity(tableName = "row_build")
data class RowBuild(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val builtAt: Long,
    /** A [com.dd3boh.outertune.constants.QuickPicksSource] ordinal-free code: 0 library query, 1 YouTube, 2 engine. */
    val source: Int,
    val sessionId: Long,
    val modelVersion: Int,
    val context: String? = null,
)
