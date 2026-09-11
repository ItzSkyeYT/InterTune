/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A card the listener was actually shown, in a row, in a slot.
 *
 * This is the half of the learning loop the app never had. A play from the row is a win only
 * against the alternatives that were on screen and passed over; a card shown again and again and
 * never touched is the loss. Neither can be reconstructed after the fact, so they are written at
 * the moment the card is on screen.
 */
@Immutable
@Entity(
    tableName = "impression",
    foreignKeys = [
        ForeignKey(
            entity = RowBuild::class,
            parentColumns = ["id"],
            childColumns = ["buildId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("buildId"), Index("songId"), Index("shownAt")]
)
data class Impression(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildId: Long,
    val songId: String,
    val slot: Int,
    /** The engine's predicted probability of a play, or -1 when the row was not built by it. */
    val p: Float,
    /** The features the prediction saw, so a loss is graded against what was actually known. */
    val featuresJson: String? = null,
    val shownAt: Long,
    val visibleMs: Long = 0,
    /** The listen that resolved this impression, once one has. */
    val outcomeListenId: Long? = null,
)
