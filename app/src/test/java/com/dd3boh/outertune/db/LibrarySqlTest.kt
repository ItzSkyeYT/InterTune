/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSortType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/** The library queries in LibrarySql, run against the exported schema as on the device. */
class LibrarySqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** The first column of each row. */
    private fun ids(sql: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun song(id: String, liked: Boolean = false, inLibrary: Long? = null) = exec(
        "INSERT INTO song(id, title, duration, liked, inLibrary) VALUES ('$id', '$id', 200, ${if (liked) 1 else 0}, $inLibrary)"
    )

    private fun plays(id: String, count: Int) =
        exec("INSERT INTO playCount(song, year, month, count) VALUES ('$id', 2026, 9, $count)")

    @Test
    fun `liked songs by play count are the liked songs only`() {
        song("liked-often", liked = true)
        song("liked-once", liked = true)
        song("liked-never", liked = true)
        song("seen-often")
        song("seen-never")
        plays("liked-often", 12)
        plays("liked-once", 1)
        plays("seen-often", 40)
        // Least played first, never played (a null count) before any.
        assertEquals(listOf("liked-never", "liked-once", "liked-often"), ids(LibrarySql.LIKED_SONGS_BY_PLAY_COUNT))
    }

    private fun album(id: String, title: String, local: Boolean) = exec(
        "INSERT INTO album(id, title, songCount, duration, lastUpdateTime, isLocal) VALUES ('$id', '$title', 1, 200, 0, ${if (local) 1 else 0})"
    )

    @Test
    fun `an album's songs come in the album's order`() {
        album("MPREb_album", "Album", local = false)
        // Written the way an album page is: the songs new to the database first, the one stored
        // before (track 2, played earlier) last.
        for ((id, index) in listOf("t1" to 0, "t3" to 2, "t4" to 3, "t2" to 1)) {
            song(id)
            exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('$id', 'MPREb_album', $index)")
        }
        album("other", "Other", local = false)
        exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('t1', 'other', 0)")
        assertEquals(listOf("t1", "t2", "t3", "t4"), ids(LibrarySql.ALBUM_SONGS.replace(":albumId", "'MPREb_album'")))
    }

    /** Room's named parameters, filled in for JDBC. */
    private fun search(sql: String, query: String) = sql.replace(":query", "'$query'").replace(":previewSize", "100")

    /** Each row's first column and the named one, a count. */
    private fun counts(sql: String, column: String): List<Pair<String, Int>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(column)) } }
    }

    private fun playlist(id: String, name: String, bookmarked: Boolean, local: Boolean) = exec(
        "INSERT INTO playlist(id, name, bookmarkedAt, isLocal) VALUES ('$id', '$name', ${if (bookmarked) 1 else "NULL"}, ${if (local) 1 else 0})"
    )

    private fun inPlaylist(playlistId: String, songId: String, position: Int) =
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('$playlistId', '$songId', $position)")

    @Test
    fun `search finds the playlists Library lists, whatever their songs`() {
        song("stored")
        song("kept", inLibrary = 1)
        // Synced: its songs are stored outside the library.
        playlist("synced", "Road trip synced", bookmarked = true, local = false)
        inPlaylist("synced", "stored", 0)
        playlist("empty", "Road trip empty", bookmarked = false, local = true)
        // Seen on YouTube and stored, never saved, though it has a library song.
        playlist("seen", "Road trip seen", bookmarked = false, local = false)
        inPlaylist("seen", "kept", 0)
        assertEquals(
            listOf("empty" to 0, "synced" to 1),
            counts(search(LibrarySql.SEARCH_PLAYLISTS, "road trip"), "songCount").sortedBy { it.first },
        )
    }

    @Test
    fun `search finds saved albums with no library songs`() {
        album("saved", "Hits saved", local = false)
        exec("UPDATE album SET bookmarkedAt = 1 WHERE id = 'saved'")
        album("played", "Hits played", local = false)
        album("kept", "Hits kept", local = false)
        song("related")
        exec("UPDATE song SET albumId = 'played' WHERE id = 'related'")
        song("library", inLibrary = 1)
        exec("UPDATE song SET albumId = 'kept' WHERE id = 'library'")
        assertEquals(listOf("kept", "saved"), ids(search(LibrarySql.SEARCH_ALBUMS, "hits")).sorted())
    }

    private fun artist(id: String, bookmarked: Boolean) = exec(
        "INSERT INTO artist(id, name, lastUpdateTime, bookmarkedAt) VALUES ('$id', 'Band $id', 0, ${if (bookmarked) 1 else "NULL"})"
    )

    private fun by(songId: String, artistId: String) =
        exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$songId', '$artistId', 0)")

    @Test
    fun `search finds saved artists, counting library songs only`() {
        artist("saved", bookmarked = true)
        artist("heard", bookmarked = false)
        artist("kept", bookmarked = false)
        song("r1"); song("r2"); song("r3")
        song("l1", inLibrary = 1); song("l2", inLibrary = 1)
        by("r1", "saved")
        by("r2", "heard")
        by("r3", "kept"); by("l1", "kept"); by("l2", "kept")
        assertEquals(
            listOf("kept" to 2, "saved" to 0),
            counts(search(LibrarySql.SEARCH_ARTISTS, "band"), "songCount").sortedBy { it.first },
        )
    }

    @Test
    fun `a liked artist counts its library songs, not every song stored`() {
        artist("liked", bookmarked = true)
        artist("other", bookmarked = false)
        song("r1"); song("r2"); song("r3")
        song("l1", inLibrary = 1); song("l2", inLibrary = 1)
        exec("UPDATE song SET dateDownload = 5 WHERE id = 'l2'")
        for (id in listOf("r1", "r2", "r3", "l1", "l2")) by(id, "liked")
        by("l1", "other")
        val liked = LibrarySql.artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE)
        assertEquals(listOf("liked" to 2), counts(liked, "songCount"))
        assertEquals(listOf("liked" to 1), counts(liked, "downloadCount"))
        // The other filters count what they filter on, as before.
        assertEquals(
            listOf("liked" to 2, "other" to 1),
            counts(LibrarySql.artists(ArtistFilter.LIBRARY, ArtistSortType.NAME), "songCount"),
        )
        assertEquals(
            listOf("liked" to 1),
            counts(LibrarySql.artists(ArtistFilter.DOWNLOADED, ArtistSortType.NAME), "songCount"),
        )
        // And the local switch is valid SQL.
        assertEquals(listOf("liked"), ids(LibrarySql.artists(ArtistFilter.LIKED, ArtistSortType.NAME, localOnly = false)))
        assertEquals(emptyList<String>(), ids(LibrarySql.artists(ArtistFilter.LIKED, ArtistSortType.NAME, localOnly = true)))
    }

    @Test
    fun `a title finds only a local album`() {
        album("MPREb_queen", "Greatest Hits", local = false)
        assertEquals(emptyList<String>(), ids(LibrarySql.LOCAL_ALBUM_BY_TITLE.replace(":title", "'Greatest Hits'")))
        album("LBfirst", "Greatest Hits", local = true)
        album("LBsecond", "Greatest Hits", local = true)
        assertEquals(listOf("LBfirst"), ids(LibrarySql.LOCAL_ALBUM_BY_TITLE.replace(":title", "'Greatest Hits'")))
    }
}
