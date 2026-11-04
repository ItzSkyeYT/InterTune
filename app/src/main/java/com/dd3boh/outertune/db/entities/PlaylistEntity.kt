package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.PendingChanges
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String = generatePlaylistId(),
    val name: String,
    val browseId: String? = null,
    @ColumnInfo(name = "isEditable", defaultValue = "true")
    val isEditable: Boolean = true,
    val bookmarkedAt: LocalDateTime? = null,
    val thumbnailUrl: String? = null,
    val remoteSongCount: Int? = null,
    val playEndpointParams: String? = null,
    val shuffleEndpointParams: String? = null,
    val radioEndpointParams: String? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "false")
    val isLocal: Boolean = false,
) {
    companion object {
        const val LIKED_PLAYLIST_ID = "LP_LIKED"
        const val DOWNLOADED_PLAYLIST_ID = "LP_DOWNLOADED"

        fun generatePlaylistId() = "LP" + RandomStringUtils.insecure().next(8, true, false)
    }

    val shareLink: String?
        get() {
            return if (browseId != null)
                "https://music.youtube.com/playlist?list=$browseId"
            else null
        }

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now()
    )

    /** The heart turned over, and kept for the YouTube Music account until it has it (PendingChanges). */
    fun toggleLike() = localToggleLike().also {
        PendingChanges.savedPlaylist(this, saved = bookmarkedAt == null)
    }


}
