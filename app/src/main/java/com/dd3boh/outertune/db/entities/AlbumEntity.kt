package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.PendingChanges
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val playlistId: String? = null,
    val title: String,
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val themeColor: Int? = null,
    val songCount: Int,
    val duration: Int,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val bookmarkedAt: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "false")
    val isLocal: Boolean = false
) {

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now()
    )

    /** Saved or not, and kept for the YouTube Music account until it has it (PendingChanges). */
    fun toggleLike() = localToggleLike().also {
        PendingChanges.savedAlbum(this, saved = bookmarkedAt == null)
    }

    companion object {
        fun generateAlbumId() = "LB" + RandomStringUtils.insecure().next(8, true, false)
    }
}