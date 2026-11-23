package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.Relation
import com.dd3boh.outertune.db.RelationOrder

/**
 * Room builds a song with the second constructor below, the app with this one (@Ignore keeps Room
 * off it).
 */
@Immutable
data class Song @Ignore @JvmOverloads constructor(
    @Embedded val song: SongEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SortedSongArtistMap::class,
            parentColumn = "songId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>,
    @Relation(
        entity = AlbumEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SongAlbumMap::class,
            parentColumn = "songId",
            entityColumn = "albumId"
        )
    )
    val album: AlbumEntity? = null,
    @Relation(
        entity = GenreEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy = Junction(
            value = SongGenreMap::class,
            parentColumn = "songId",
            entityColumn = "genreId"
        )
    )
    val genre: List<GenreEntity>? = null,

    @Relation(
        entity = PlayCountEntity::class,
        entityColumn = "song",
        parentColumn = "id",
    )
    val playCount: List<PlayCountEntity>? = null,
) : LocalItem() {
    /**
     * Room's way in: the artists in the order the song credits them, put so from [artistOrder]
     * (RelationOrder says why the view alone no longer does it on Android 14 and newer).
     */
    constructor(
        song: SongEntity,
        artists: List<ArtistEntity>,
        album: AlbumEntity?,
        genre: List<GenreEntity>?,
        playCount: List<PlayCountEntity>?,
        artistOrder: List<SongArtistMap>,
    ) : this(song, RelationOrder.artists(artists, artistOrder), album, genre, playCount) {
        this.artistOrder = artistOrder
    }

    /** The song's rows of song_artist_map, each artist's place in the credit, as Room read them. Empty in a copy. */
    @Relation(entity = SongArtistMap::class, parentColumn = "id", entityColumn = "songId")
    var artistOrder: List<SongArtistMap> = emptyList()
        private set

    override val id: String
        get() = song.id
    override val title: String
        get() = song.title
    override val thumbnailUrl: String?
        get() = song.thumbnailUrl
}

data class QueueSong(
    @Embedded val song: Song,
    @ColumnInfo(name = "shuffledIndex") val shuffledIndex: Int
)

/** A song YouTube lists beside the favourites' songs, and how many of them list it (FavouritesSql). */
data class SimilarSong(
    @Embedded val song: Song,
    @ColumnInfo(name = "refs") val refs: Int
)