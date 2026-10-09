package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.DownloadSql
import com.dd3boh.outertune.db.FavouritesSql
import com.dd3boh.outertune.db.LibrarySql
import com.dd3boh.outertune.db.LocalSql
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.SimilarSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.utils.fixFilePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.ZoneOffset

@Dao
interface SongsDao {

    companion object {
        /**
         * Most played songs, most played first.
         *
         * The ranking has to happen in the subquery and the outer ORDER BY has to repeat it:
         * without the outer ORDER BY, the outer SELECT is free to come back in whatever order its
         * own index walk finds fastest (song's primary key, in practice), which is not the play
         * time order the subquery worked out. Kept as a constant, the way [LibrarySql] does it,
         * so SongsSqlTest runs the exact same text as the DAO.
         */
        const val MOST_PLAYED_SONGS = """
            SELECT song.*
            FROM (
                SELECT songId, SUM(playTime) AS totalPlayTime
                FROM event
                WHERE timestamp > :fromTimeStamp
                GROUP BY songId
                ORDER BY totalPlayTime DESC
                LIMIT :limit
                OFFSET :offset
            ) ranked
            JOIN song ON song.id = ranked.songId
            ORDER BY ranked.totalPlayTime DESC
        """
    }

    // region Gets
    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId")
    fun song(songId: String?): Flow<Song?>

    /**
     * The song's own row, read once and returned, without its artists, album, genres and play
     * counts. For the player's loader, which only asks where the file is. No @Transaction, and
     * none wanted: on Android versions where Room cannot begin a read-only one it begins an
     * exclusive one, and that waits for the write connection.
     */
    @Query("SELECT * FROM song WHERE id = :songId")
    fun songRow(songId: String?): SongEntity?

    /** The names of a song's artists in the order the song lists them, read once like [songRow]. */
    @Query(
        "SELECT artist.name FROM song_artist_map JOIN artist ON artist.id = song_artist_map.artistId " +
            "WHERE song_artist_map.songId = :songId ORDER BY song_artist_map.position"
    )
    fun artistNamesOf(songId: String): List<String>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' AND (inLibrary IS NOT NULL OR dateDownload IS NOT NULL) LIMIT :previewSize")
    fun searchSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun searchSongsInDb(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' AND isLocal = 1 LIMIT :previewSize")
    fun searchSongsAllLocal(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>


    /**
     * Does not include unavailable songs
     */
    fun searchSongsAllLocalInDir(dir: String, query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>> {
        return _searchSongsAllLocalInDir(fixFilePath(dir), query, previewSize)
    }

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :dir || '%' AND title LIKE '%' || :query || '%'
        LIMIT :previewSize
        """)
    fun _searchSongsAllLocalInDir(dir: String, query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query(MOST_PLAYED_SONGS)
    fun mostPlayedSongs(fromTimeStamp: Long, limit: Int = 6, offset: Int = 0): Flow<List<Song>>

    @Query("SELECT sum(count) from playCount WHERE song = :songId")
    fun getLifetimePlayCount(songId: String?): Int

    @Query("SELECT sum(count) from playCount WHERE song = :songId AND year = :year")
    fun getPlayCountByYear(songId: String?, year: Int): Flow<Int>

    @Query("SELECT count from playCount WHERE song = :songId AND year = :year AND month = :month")
    fun getPlayCountByMonth(songId: String?, year: Int, month: Int): Flow<Int>

    /**
     * Liked songs with no usable download.
     *
     * dateDownload = 0 is STATE_INVALID, not a real download: Converters stores LocalDateTime as
     * epoch millis and scanDownloads() used to write epoch 0 for failed and stopped downloads.
     * Treating it as downloaded would lock a song that once failed out of auto-download forever.
     * rescanDownloads clears those now (DownloadSql), but the check costs nothing.
     *
     * isLocal and localPath mirror the sibling download queries. A local file can never acquire a
     * dateDownload, so it would be handed to media3 as a video id and retried on every backfill.
     *
     * Newest like first, so the song you just liked is at the front of a backfill rather than behind
     * two thousand older ones.
     */
    @Query(
        """
        SELECT * FROM song
        WHERE liked AND isLocal = 0 AND localPath IS NULL
          AND (dateDownload IS NULL OR dateDownload = 0)
        ORDER BY likedDate DESC
        """
    )
    fun likedSongsNotDownloaded(): Flow<List<SongEntity>>

    // region Songs Sort
    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY rowId")
    fun songsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary")
    fun songsByCreateDateAsc(): Flow<List<Song>>

    /**
     * The library rows for a set of ids, in the order the ids are given.
     *
     * Room cannot sort by an IN list, so the caller reorders. Used by the cached tab, where the
     * order that matters is the cache's, not the library's.
     */
    @Transaction
    @Query("SELECT * FROM song WHERE id IN (:songIds)")
    fun songsByIds(songIds: List<String>): Flow<List<Song>>

    /**
     * The stored rows, for a change to be made on them rather than on a row rebuilt from
     * metadata, which lacks the library and download dates. Keep [songIds] to a few hundred:
     * SQLite before Android 12 takes at most 999 arguments.
     */
    @Query("SELECT * FROM song WHERE id IN (:songIds)")
    fun songEntitiesByIds(songIds: List<String>): List<SongEntity>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY date")
    fun songsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY dateModified")
    fun songsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun songsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE inLibrary IS NOT NULL 
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun songsByArtistAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL LIMIT :previewSize")
    fun artistSongsPreview(artistId: String, previewSize: Int = 3): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("""
        SELECT song.*, (SELECT SUM(playCount.count) 
            FROM playCount 
            WHERE playCount.song = song.id) AS pc 
        FROM song 
        WHERE inLibrary IS NOT NULL 
        ORDER BY pc ASC
    """)
    fun songsByPlayCountAsc(): Flow<List<Song>>

    fun songs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> songsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> songsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> songsByReleaseDateAsc()
            SongSortType.NAME -> songsByNameAsc()
            SongSortType.ARTIST -> songsByArtistAsc()
            SongSortType.PLAY_COUNT -> songsByPlayCountAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 1 and inLibrary IS NOT NULL")
    fun allLocalSongs(): List<Song>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 1")
    fun allLocalDbSongs(): List<Song>

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 1 AND localpath LIKE :filter || '%'
    """)
    fun localDbSongsInDir(filter: String): Flow<List<Song>>

    /**
     * Does not include unavailable songs
     */
    fun localSongsInDirShallow(filter: String): List<Song> {
        return _localSongsInDirShallow(fixFilePath(filter))
    }

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :filter || '%' 
        AND instr(substr(localpath, length(:filter) + 1), '/') = 0
        UNION
        SELECT * FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localpath LIKE :filter || '%'
        GROUP BY rtrim(localPath, replace(localPath, '/', ''))
    """)
    fun _localSongsInDirShallow(filter: String): List<Song>

    fun localSongsInDirDeep(filter: String): List<Song> {
        return _localSongsInDirDeep(fixFilePath(filter))
    }

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 1 and inLibrary IS NOT NULL AND localpath LIKE :filter || '%'")
    fun _localSongsInDirDeep(filter: String): List<Song>

    @Transaction
    @Query("SELECT count(*) FROM song WHERE isLocal = 1 and inLibrary IS NOT NULL AND localpath LIKE :path || '%'")
    fun localSongCountInPath(path: String): Flow<Int>

    /**
     * Local songs sharing a file, for the scan's duplicate sweep, which deletes all but one. Local
     * songs only: a download in a scan folder is also a local song with the same path, and the
     * sweep could delete the YouTube song's row, likes and history with it.
     */
    @Query(LocalSql.DUPLICATED_LOCAL_SONGS)
    fun duplicatedLocalSongs(): List<SongEntity>
    // endregion

    // region Liked Songs Sort
    @Query("SELECT COUNT(1) FROM song WHERE liked")
    fun likedSongsCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY rowId")
    fun likedSongsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate")
    fun likedSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY date")
    fun likedSongsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY dateModified")
    fun likedSongsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY title COLLATE NOCASE ASC")
    fun likedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song 
        WHERE liked 
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun likedSongsByArtistAsc(): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(LibrarySql.LIKED_SONGS_BY_PLAY_COUNT)
    fun likedSongsByPlayCountAsc(): Flow<List<Song>>

    fun likedSongs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> likedSongsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> likedSongsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> likedSongsByReleaseDateAsc()
            SongSortType.NAME -> likedSongsByNameAsc()
            SongSortType.ARTIST -> likedSongsByArtistAsc()
            SongSortType.PLAY_COUNT -> likedSongsByPlayCountAsc()
        }.map { it.reversed(descending) }
    // endregion

    // region favourite artists
    /**
     * Every song the app knows of by an artist that has been bookmarked.
     *
     * Not restricted to the library, which is the opposite of how this was first written. On a
     * real device that restriction left thirteen songs out of 279 and silenced four of the ten
     * bookmarked artists outright. See FavouritesSql for the numbers and the reasoning.
     *
     * Deliberately unordered. The caller shuffles across artists rather than sorting, because a
     * sort by anything at all defeats the point: see interleaveByArtist for why a flat shuffle of
     * these rows is not a mix.
     */
    @Transaction
    @Query(FavouritesSql.BY_BOOKMARKED_ARTISTS)
    fun songsByBookmarkedArtists(): Flow<List<Song>>

    /**
     * Songs YouTube lists beside the favourites' songs, by artists that are not bookmarked: the
     * guests of the favourites mix, the most listed first. See FavouritesSql for what is kept out.
     *
     * A plain read and not a Flow, on purpose. Related lists are written as songs play, so an
     * observed query would hand the mix a new set of guests in the middle of being listened to.
     */
    @Transaction
    @Query(FavouritesSql.SIMILAR_TO_BOOKMARKED_ARTISTS)
    fun songsSimilarToBookmarkedArtists(now: Long, limit: Int): List<SimilarSong>
    // endregion

    // region downloaded Songs utils
    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL AND dateDownload IS NOT 0")
    fun downloadedSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload = 0")
    fun downloadQueuedSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL")
    fun downloadedOrQueuedSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NULL AND localPath IS NULL")
    fun downloadRelinkableSongs(): Flow<List<Song>>

    @Query("UPDATE song SET dateDownload = :dateDownload WHERE id = :songId")
    fun updateDownloadStatus(songId: String, dateDownload: LocalDateTime?)

    @Transaction
    @Query("UPDATE song SET dateDownload = :dateDownload, localPath = :localPath WHERE id = :mediaId AND isLocal = 0")
    fun registerDownloadSong(mediaId: String, dateDownload: LocalDateTime, localPath: String)

    @Transaction
    @Query("UPDATE song SET dateDownload = NULL, localPath = NULL WHERE id = :mediaId AND isLocal = 0")
    fun removeDownloadSong(mediaId: String)

    @Transaction
    @Query("UPDATE song SET dateDownload = NULL, localPath = NULL WHERE isLocal = 0")
    fun removeAllDownloadedSongs()

    /** See DownloadSql. */
    @Query(DownloadSql.CLEAR_SENTINELS)
    fun clearDownloadSentinels()
    // endregion

    // region Downloaded Songs Sort
    @Transaction
    @Query(DownloadSql.DOWNLOADED_BY_DATE)
    fun downloadNoLocalSongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY inLibrary")
    fun downloadSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY date")
    fun downloadSongsByReleaseDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY dateModified")
    fun downloadSongsByDateModifiedAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun downloadSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT * FROM song
        WHERE isLocal = 0 AND dateDownload IS NOT NULL
        ORDER BY (
            SELECT LOWER(GROUP_CONCAT(name, ''))
            FROM artist
            WHERE id IN (SELECT artistId FROM song_artist_map WHERE songId = song.id)
            ORDER BY name
        ) COLLATE NOCASE
    """)
    fun downloadSongsByArtistAsc(): Flow<List<Song>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("""
        SELECT song.*, (SELECT SUM(playCount.count) 
            FROM playCount 
            WHERE playCount.song = song.id) AS pc 
        FROM song 
        WHERE isLocal = 0 AND dateDownload IS NOT NULL
        ORDER BY pc ASC
    """)
    fun downloadSongsByPlayCountAsc(): Flow<List<Song>>

    fun downloadSongs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> downloadSongsByCreateDateAsc()
            SongSortType.MODIFIED_DATE -> downloadSongsByDateModifiedAsc()
            SongSortType.RELEASE_DATE -> downloadSongsByReleaseDateAsc()
            SongSortType.NAME -> downloadSongsByNameAsc()
            SongSortType.ARTIST -> downloadSongsByArtistAsc()
            SongSortType.PLAY_COUNT -> downloadSongsByPlayCountAsc()
        }.map { it.reversed(descending) }
    // endregion
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playCountEntity: PlayCountEntity): Long
    // endregion

    // region Updates
    @Update
    fun update(song: SongEntity)

    @Query("UPDATE playCount SET count = count + 1 WHERE song = :songId AND year = :year AND month = :month")
    fun incrementPlayCount(songId: String, year: Int, month: Int)

    /**
     * Increment by one the play count with today's year and month.
     *
     * Two plain statements and no reading first: the insert leaves a month that already has its
     * row alone. This runs inside the transaction that counts a play, on the thread holding the
     * write connection, and it used to read the old count there by waiting on a Flow. Starting a
     * Flow makes Room bring its triggers up to date on another thread, under a lock, and when
     * there was a trigger to add or drop that thread wanted the write connection too. Neither
     * ever let go: the database stayed shut until the process died, every song after it sat
     * buffering and the app would not open past its splash screen.
     */
    @Transaction
    fun incrementPlayCount(songId: String) {
        val time = LocalDateTime.now().atOffset(ZoneOffset.UTC)
        insert(PlayCountEntity(songId, time.year, time.monthValue, 0))
        incrementPlayCount(songId, time.year, time.monthValue)
    }

    @Transaction
    fun toggleInLibrary(songId: String, inLibrary: LocalDateTime?) {
        inLibrary(songId, inLibrary)
        if (inLibrary == null) {
            removeLike(songId)
        }
    }

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE id = :songId")
    fun inLibrary(songId: String, inLibrary: LocalDateTime?)

    @Query("UPDATE song SET liked = 0, likedDate = null WHERE id = :songId")
    fun removeLike(songId: String)

    @Query("UPDATE song SET inLibrary = null WHERE localPath = null")
    fun disableInvalidLocalSongs()

    /**
     * Takes a local song out of the library while its file is missing. The path stays: it is what
     * the next scan matches the file on when it comes back, so the song returns as the same row with
     * its likes. With the path cleared the row could never match again, and the file came back as a
     * new song.
     */
    @Query("UPDATE song SET inLibrary = null WHERE id = :songId")
    fun disableLocalSong(songId: String)

    fun updateLocalSongPath(songId: String, inLibrary: LocalDateTime?, localPath: String?) {
        if (localPath != null) {
            _updateLSP(songId, inLibrary, localPath)
        }
    }

    /**
     * DON'T USE THIS DIRECTLY, USE updateLocalSongPath(...) instead!
     */
    @Query("UPDATE song SET inLibrary = :inLibrary, localPath = :localPath WHERE id = :songId")
    fun _updateLSP(songId: String, inLibrary: LocalDateTime?, localPath: String)
    // endregion

    // region Deletes
    @Delete
    fun delete(song: SongEntity)

    @Transaction
    @Query("DELETE FROM song WHERE isLocal = 1")
    fun nukeLocalSongs()
    // endregion
}