package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.Relation
import com.dd3boh.outertune.db.RelationOrder

/**
 * Room builds an album with the second constructor below, the app with this one (@Ignore keeps
 * Room off it).
 */
@Immutable
data class AlbumWithSongs @Ignore constructor(
    @Embedded
    val album: AlbumEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = AlbumArtistMap::class,
            parentColumn = "albumId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>,
    @Relation(
        entity = SongEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SortedSongAlbumMap::class,
            parentColumn = "albumId",
            entityColumn = "songId"
        )
    )
    val songs: List<Song>,
    val downloadCount: Int,
) {
    /**
     * Room's way in: the songs in the album's order, put so from [songOrder] (RelationOrder says
     * why the view alone no longer does it on Android 14 and newer).
     */
    constructor(album: AlbumEntity, artists: List<ArtistEntity>, songs: List<Song>, downloadCount: Int, songOrder: List<SongAlbumMap>) :
        this(album, artists, RelationOrder.songs(songs, songOrder), downloadCount) {
        this.songOrder = songOrder
    }

    /** The album's rows of song_album_map, each song's index in it, as Room read them. Empty in a copy. */
    @Relation(entity = SongAlbumMap::class, parentColumn = "id", entityColumn = "albumId")
    var songOrder: List<SongAlbumMap> = emptyList()
        private set
}
