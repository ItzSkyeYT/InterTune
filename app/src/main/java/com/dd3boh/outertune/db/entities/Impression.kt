/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A card the listener was actually shown, in a row, in a slot, and what became of it.
 *
 * This is the half of the learning loop the app never had. A play from the row is a win only
 * against the alternatives that were on screen and passed over; a card shown again and again and
 * never touched is the loss. Neither can be reconstructed after the fact, so they are written at
 * the moment the card is on screen, and graded later against the features the prediction saw.
 */
@Immutable
@Entity(
    tableName = "impression",
    foreignKeys = [
        ForeignKey(entity = RowBuild::class, parentColumns = ["id"], childColumns = ["buildId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("buildId"), Index("songId"), Index("visibleAt")]
)
data class Impression(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buildId: Long,
    val songId: String,
    val slot: Int,
    /** 0 none, 1 related, 2 artist, 3 rediscover, 4 explore. */
    @ColumnInfo(defaultValue = "0") val lane: Int = 0,
    /** 1 engine, 2 classic, 3 YouTube: whose card this was, which matters in Compare. */
    @ColumnInfo(defaultValue = "0") val team: Int = 0,
    @ColumnInfo(defaultValue = "0") val sampled: Boolean = false,
    /** The engine's predicted probability of a play; null when the row was not scored. */
    val p: Float? = null,
    val features: String? = null,
    val reasons: String? = null,
    val visibleAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val visibleMs: Long = 0,
    val tappedAt: Long? = null,
    /** 0 pending, 1 played, 2 played elsewhere, 3 ignored, 4 unseen, 5 pool pick. */
    @ColumnInfo(defaultValue = "0") val outcome: Int = 0,
    val listenId: Long? = null,
    val y: Float? = null,
    val u: Float? = null,
    val gradedAt: Long? = null,
    val appliedAt: Long? = null,
)
