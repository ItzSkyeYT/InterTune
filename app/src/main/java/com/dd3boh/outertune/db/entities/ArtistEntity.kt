package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.PendingChanges
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "artist")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val bookmarkedAt: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "false")
    val isLocal: Boolean = false
) {
    val isYouTubeArtist: Boolean
        get() = id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
    )

    /** Followed or not, and kept for the YouTube Music account until it has it (PendingChanges). */
    fun toggleLike() = localToggleLike().also {
        PendingChanges.followed(this, followed = bookmarkedAt == null)
    }

    companion object {
        fun generateArtistId() = "LA" + RandomStringUtils.insecure().next(8, true, false)
    }
}
