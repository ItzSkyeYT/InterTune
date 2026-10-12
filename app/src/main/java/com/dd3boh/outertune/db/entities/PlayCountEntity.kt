package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity

/**
 * A data class, like every row: a song carries its play counts, and a song read again has to equal
 * the one read before, or whatever shows it draws again for nothing (RowsCompareByValueTest).
 */
@Immutable
@Entity(
    tableName = "playCount",
    primaryKeys = ["song", "year", "month"]
)
data class PlayCountEntity(
    val song: String, // song id
    val year: Int = -1,
    val month: Int = -1,
    val count: Int = -1,
)