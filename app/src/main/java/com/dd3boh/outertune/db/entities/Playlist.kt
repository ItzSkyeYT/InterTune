package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.dd3boh.outertune.utils.PlaylistCovers

@Immutable
data class Playlist(
    @Embedded
    val playlist: PlaylistEntity,
    val songCount: Int,
    val downloadCount: Int,
    @Relation(
        entity = SongEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        projection = ["thumbnailUrl"],
        associateBy = Junction(
            value = PlaylistSongMapPreview::class,
            parentColumn = "playlistId",
            entityColumn = "songId"
        )
    )
    val songThumbnails: List<String?>, //  TODO: Remove during next db update
) : LocalItem() {
    override val id: String
        get() = playlist.id
    override val title: String
        get() = playlist.name
    override val thumbnailUrl: String?
        get() = null

    /**
     * The pictures a playlist is drawn from: its own cover, or the songs' when it has none. A
     * kept photo (PlaylistCovers) is drawn from its file by PlaylistThumbnail, so what is listed
     * here is the songs', which stand in when the file is gone.
     */
    val thumbnails: List<String>
        get() {
            return if (playlist.thumbnailUrl != null && !PlaylistCovers.isPhoto(playlist.thumbnailUrl))
                listOf(playlist.thumbnailUrl)
            else songThumbnails.filterNotNull()
        }

    /**
     * Where opening this playlist should navigate. local_playlist when it can be edited, is
     * local, has no browseId to fetch from online, or already has songs stored; online_playlist
     * otherwise, for a followed playlist synced with nothing stored yet, so it shows the real
     * songs from YouTube instead of an empty, falsely-editable local_playlist screen.
     */
    val navigationRoute: String
        get() = if (playlist.isEditable || playlist.isLocal || playlist.browseId == null || songCount != 0)
            "local_playlist/$id"
        else
            "online_playlist/${playlist.browseId}"
}
