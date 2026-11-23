package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Relation
import com.dd3boh.outertune.db.RelationOrder
import com.dd3boh.outertune.utils.PlaylistCovers

/**
 * Room builds a playlist with the second constructor below, the app with this one (@Ignore keeps
 * Room off it).
 */
@Immutable
data class Playlist @Ignore constructor(
    @Embedded
    val playlist: PlaylistEntity,
    val songCount: Int,
    val downloadCount: Int,
    /** The pictures of the playlist's first songs, in its order: what Room's constructor makes of [coverSongs]. */
    @Ignore
    val songThumbnails: List<String?>, //  TODO: Remove during next db update
) : LocalItem() {
    /**
     * Room's way in: the pictures of the first songs in the playlist's order, put so from
     * [coverSongs] (RelationOrder says why the view alone no longer does it on Android 14 and newer).
     * Its parameters come in another order than the first constructor's only so the two differ on the JVM.
     */
    constructor(playlist: PlaylistEntity, coverSongs: List<PlaylistCoverSong>, songCount: Int, downloadCount: Int) :
        this(playlist, songCount, downloadCount, RelationOrder.coverPictures(coverSongs)) {
        this.coverSongs = coverSongs
    }

    /** The playlist's first songs (playlist_song_map_preview), each with its position and picture, as Room read them. Empty in a copy. */
    @Relation(entity = PlaylistSongMapPreview::class, parentColumn = "id", entityColumn = "playlistId")
    var coverSongs: List<PlaylistCoverSong> = emptyList()
        private set

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
                listOf(PlaylistCovers.songCover(playlist.thumbnailUrl)!!)
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
