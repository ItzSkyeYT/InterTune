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
 * One learned number of the recommendation engine, with the prior it started from and the bounds
 * it may never leave. Eighteen of them in all; none belongs to a song or an artist.
 */
@Immutable
@Entity(tableName = "engine_weight")
data class EngineWeight(
    @PrimaryKey val name: String,
    val value: Float,
    val prior: Float,
    val lo: Float,
    val hi: Float,
    val updates: Int = 0,
)
