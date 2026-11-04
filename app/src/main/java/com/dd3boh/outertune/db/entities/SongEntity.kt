package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.dd3boh.outertune.utils.PendingChanges
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset

@Immutable
@Entity(
    tableName = "song",
    indices = [
        Index(
            value = ["albumId"]
        )
    ]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val duration: Int = -1, // in seconds TODO: in milliseconds
    val thumbnailUrl: String? = null,
    val inLibrary: LocalDateTime? = null, // doubles as "date added"
    @ColumnInfo(name = "isLocal", defaultValue = "false")
    val isLocal: Boolean = false,
    @ColumnInfo(index = true)
    val localPath: String?,
    val dateDownload: LocalDateTime? = null, // doubles as "isDownloaded" for new downloader system
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,

    // misc non-critical tags
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumId: String? = null,
    val albumName: String? = null,
//    val albumArtist // if anyone wants to implement album artists in a sane way, pull requests are welcome.
    val year: Int? = null,
    val date: LocalDateTime? = null, // ID3 tag property
    val dateModified: LocalDateTime? = null, // file property
) {

    fun localToggleLike() = copy(
        liked = !liked,
        likedDate = if (!liked) LocalDateTime.now() else null,
    )

    /**
     * The like turned over, and kept for the YouTube Music account until it has it (PendingChanges):
     * a like sent once and dropped when it failed was undone by the next sync. Local files have no
     * YouTube id, and a like made signed out or in "Read only" stays here: nothing reaches the
     * account then.
     */
    fun toggleLike() = copy(
        liked = !liked,
        likedDate = if (!liked) LocalDateTime.now() else null,
        inLibrary = if (!liked) inLibrary ?: LocalDateTime.now() else inLibrary
    ).also {
        PendingChanges.liked(this, liked = !liked)
    }

    /**
     * In or out of the library. Out takes the like with it, and that unlike goes to the account as
     * the heart's does: it never did, and the next liked sync liked the song again (4 Nov
     * 2026). In changes nothing on the account (the app has no request for it).
     */
    fun toggleLibrary() = copy(
        inLibrary = if (inLibrary == null) LocalDateTime.now() else null,
        liked = if (inLibrary == null) liked else false,
        likedDate = if (inLibrary == null) likedDate else null
    ).also {
        if (inLibrary != null) PendingChanges.tookOutOfLibrary(this)
    }

    /**
     * Returns a full date string. If no full date is present, returns the year.
     * This is the song's tag's date/year, NOT dateModified.
     */
    fun getDateString(): String? {
        return date?.toLocalDate()?.toString()
            ?: if (year != null) {
                return year.toString()
            } else {
                return null
            }
    }

    /**
     * Get the value of the date released in Epoch Seconds
     */
    fun getDateLong(): Long? {
        return date?.toEpochSecond(ZoneOffset.UTC)
            ?: if (year != null) {
                LocalDateTime.of(year, Month.JANUARY, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)
            } else {
                null
            }
    }

    /**
     * Get the value of the date modified in Epoch Seconds
     */
    fun getDateModifiedLong(): Long? = dateModified?.toEpochSecond(ZoneOffset.UTC)

    fun getThumbnailModel(sizeX: Int = -1, sizeY: Int = -1): Any? {
        return if (isLocal) {
            LocalArtworkPath(thumbnailUrl ?: localPath, sizeX, sizeY)
        } else {
            thumbnailUrl
        }
    }

    companion object {
        fun generateSongId() = "LS" + RandomStringUtils.insecure().next(8, true, false)
    }
}
