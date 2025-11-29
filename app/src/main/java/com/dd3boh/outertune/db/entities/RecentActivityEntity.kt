package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Deprecated("Removed for local-only version")
@Immutable
enum class RecentActivityType {
    PLAYLIST, ALBUM, ARTIST
}

@Deprecated("Removed for local-only version")
@Entity(tableName = "recent_activity")
@Immutable
data class RecentActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val thumbnail: String?,
    val explicit: Boolean,
    val shareLink: String,
    val type: RecentActivityType,
    val playlistId: String?,
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
    val date: LocalDateTime = LocalDateTime.now()
)
