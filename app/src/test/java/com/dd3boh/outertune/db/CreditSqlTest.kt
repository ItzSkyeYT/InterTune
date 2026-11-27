/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.models.ArtistCredit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection

/**
 * A song's credit stored as one artist, put back into its artists, run against the exported schema as on the
 * device: which songs ArtistCreditRepair asks about, and what DatabaseDao.splitStoredCredit does to one, his
 * "Thread The Needle" of 27 Nov 2026, liked, downloaded and played, by one artist "Hoax, So What, & Dynamite MC".
 */
class CreditSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        ps.executeUpdate()
    }

    private fun <T> query(sql: String, vararg args: Any?, row: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }

    private fun artist(id: String, name: String, isLocal: Any = 0, bookmarkedAt: Long? = null) =
        exec("INSERT INTO artist(id, name, lastUpdateTime, isLocal, bookmarkedAt) VALUES (?, ?, 0, ?, ?)", id, name, isLocal, bookmarkedAt)

    private fun song(
        id: String, liked: Boolean = false, inLibrary: Long? = null, dateDownload: Long? = null, isLocal: Any = 0,
    ) = exec(
        "INSERT INTO song(id, title, duration, liked, likedDate, inLibrary, dateDownload, isLocal) VALUES (?, ?, 284, ?, ?, ?, ?, ?)",
        id, "Song $id", if (liked) 1 else 0, if (liked) 1764200000000 else null, inLibrary, dateDownload, isLocal,
    )

    private fun credits(songId: String, vararg artistIds: String) = artistIds.forEachIndexed { i, a ->
        exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES (?, ?, ?)", songId, a, i)
    }

    private fun asked(after: String = "", limit: Int = 50) =
        query(CreditSql.CREDITS_TO_ASK, after, limit) { StoredCredit(it.getString("songId"), it.getString("name")) }

    private fun artistsOf(songId: String) =
        query(CreditSql.ARTISTS_OF_SONG, songId) { Triple(it.getString("id"), it.getString("name"), it.getInt("isLocal")) }

    private val thread = "RuFu8i6JLhE"
    private val combined = "Hoax, So What, & Dynamite MC"

    /** His song as his phone has it: liked, in the library, downloaded, by one artist with an id made up here. */
    private fun his() {
        artist("LAhoaxComb", combined)
        song(thread, liked = true, inLibrary = 1764200000000, dateDownload = 1764200100000)
        credits(thread, "LAhoaxComb")
    }

    @Test
    fun `his song is asked about`() {
        his()
        assertEquals(listOf(StoredCredit(thread, combined)), asked())
    }

    @Test
    fun `only kept songs from YouTube whose one artist was made up here are asked about`() {
        his()
        // A file's song and a file's artist: their tag is theirs.
        artist("LAfileArti", "Alpha, Beta & Gamma", isLocal = 1)
        song("LSlocalSong", liked = true, isLocal = 1)
        credits("LSlocalSong", "LAfileArti")
        song("youtubeFile", liked = true)
        credits("youtubeFile", "LAfileArti")
        // YouTube's own artist, two artists already, a song kept nowhere.
        artist("UC0-C_jjGdhQ4x9pmMSEZLcg", "Hoax")
        artist("LAsoWhatAB", "So What")
        song("linkedSong1", liked = true)
        credits("linkedSong1", "UC0-C_jjGdhQ4x9pmMSEZLcg")
        song("twoArtists1", liked = true)
        credits("twoArtists1", "UC0-C_jjGdhQ4x9pmMSEZLcg", "LAsoWhatAB")
        artist("LAradioOnl", "Radio & Only")
        song("radioOnly11")
        credits("radioOnly11", "LAradioOnl")
        // Kept only in a playlist; and a row older than the column, whose isLocal reads 'false'.
        exec("INSERT INTO playlist(id, name) VALUES ('LPmine', 'Mine')")
        artist("LAinPlayli", "In & Playlist")
        song("inPlaylist1")
        credits("inPlaylist1", "LAinPlayli")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('LPmine', 'inPlaylist1', 0)")
        artist("LAoldRowsA", "Old, Rows & Text", isLocal = "false")
        song("oldRowText1", inLibrary = 1700000000000, isLocal = "false")
        credits("oldRowText1", "LAoldRowsA")

        assertEquals(listOf(thread, "inPlaylist1", "oldRowText1"), asked().map { it.songId })
    }

    @Test
    fun `a page at a time, after the last one done`() {
        listOf("aaaa", "bbbb", "cccc", "dddd", "eeee").forEachIndexed { i, letters ->
            artist("LApage$letters", "A$i & B$i")
            song("song$i", liked = true)
            credits("song$i", "LApage$letters")
        }
        assertEquals(listOf("song0", "song1"), asked(limit = 2).map { it.songId })
        assertEquals(listOf("song2", "song3"), asked(after = "song1", limit = 2).map { it.songId })
        assertEquals(listOf("song4"), asked(after = "song3", limit = 2).map { it.songId })
    }

    @Test
    fun `a song's artists come in their credited order`() {
        artist("UCEMEp1vS0srzZBre3-J9JbQ", "Dynamite MC")
        artist("UC0-C_jjGdhQ4x9pmMSEZLcg", "Hoax")
        artist("LAsoWhatAB", "So What")
        song(thread)
        credits(thread, "UC0-C_jjGdhQ4x9pmMSEZLcg", "LAsoWhatAB", "UCEMEp1vS0srzZBre3-J9JbQ")
        assertEquals(listOf("Hoax", "So What", "Dynamite MC"), artistsOf(thread).map { it.second })
    }

    @Test
    fun `an artist row goes only when nothing names it and nobody saved it`() {
        artist("LAunusedRo", "Unused")
        artist("LAstillSon", "Still a song's")
        artist("LAstillAlb", "Still an album's")
        artist("LAsavedOne", "Saved", bookmarkedAt = 1764200000000)
        song("someSong123")
        credits("someSong123", "LAstillSon")
        exec("INSERT INTO album(id, title, songCount, duration, lastUpdateTime) VALUES ('MPREb_x', 'X', 1, 1, 0)")
        exec("INSERT INTO album_artist_map(albumId, artistId, `order`) VALUES ('MPREb_x', 'LAstillAlb', 0)")
        for (id in listOf("LAunusedRo", "LAstillSon", "LAstillAlb", "LAsavedOne")) exec(CreditSql.DELETE_IF_UNUSED, id)
        assertEquals(listOf("LAsavedOne", "LAstillAlb", "LAstillSon"), query("SELECT id FROM artist ORDER BY id") { it.getString(1) })
    }

    /**
     * DatabaseDao.splitStoredCredit's steps, in its order, with the names YouTube Music gives the song now (get_queue,
     * 27 Nov 2026): Hoax and Dynamite MC linked, So What with no page, which takes a row of its own made up here.
     */
    @Test
    fun `his song gets its three artists, and keeps its like, its download and its plays`() {
        his()
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES (?, 1764200200000, 284000)", thread)
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES (?, 1764200500000, 120000)", thread)
        val rowBefore = query("SELECT * FROM song WHERE id = ?", thread) { rs -> (1..rs.metaData.columnCount).map { rs.getObject(it) } }

        val youtube = listOf("UC0-C_jjGdhQ4x9pmMSEZLcg" to "Hoax", null to "So What", "UCEMEp1vS0srzZBre3-J9JbQ" to "Dynamite MC")
        val stored = artistsOf(thread).single()
        assertTrue(ArtistCredit.splits(stored.first, stored.second, stored.third == 1, youtube.map { it.second }))
        exec("DELETE FROM song_artist_map WHERE songId = ?", thread) // AlbumsDao.unlinkSongArtists
        youtube.forEachIndexed { i, (id, name) -> // DatabaseDao.linkArtists
            val artistId = id ?: query("SELECT id FROM artist WHERE name = ?", name) { it.getString(1) }.firstOrNull() ?: "LAsoWhatNe"
            exec("INSERT OR IGNORE INTO artist(id, name, lastUpdateTime, isLocal) VALUES (?, ?, 0, 0)", artistId, name)
            exec("INSERT OR IGNORE INTO song_artist_map(songId, artistId, position) VALUES (?, ?, ?)", thread, artistId, i)
        }
        exec(CreditSql.DELETE_IF_UNUSED, stored.first)

        assertEquals(listOf("Hoax", "So What", "Dynamite MC"), artistsOf(thread).map { it.second })
        assertEquals(emptyList<String>(), query("SELECT id FROM artist WHERE name = ?", combined) { it.getString(1) })
        assertEquals(rowBefore, query("SELECT * FROM song WHERE id = ?", thread) { rs -> (1..rs.metaData.columnCount).map { rs.getObject(it) } })
        assertEquals(2, query("SELECT count(*) FROM event WHERE songId = ?", thread) { it.getInt(1) }.single())
        // And it is not asked about again.
        assertEquals(emptyList<StoredCredit>(), asked())
    }

    @Test
    fun `the DAO splits with these steps, and a song met again goes through it`() {
        val code = File("src/main/java/com/dd3boh/outertune/db/DatabaseDao.kt").readText()
        val split = code.substringAfter("fun splitStoredCredit(").substringBefore("\n    }\n")
        for (step in listOf("artistsOfSong(songId).singleOrNull()", "ArtistCredit.splits(", "unlinkSongArtists(songId)",
            "linkArtists(songId, artists)", "deleteArtistIfUnused(stored.id)")) {
            assertTrue("splitStoredCredit has no $step", step in split)
        }
        // The branch insert(mediaMetadata) takes for a song it has already.
        val known = code.substringAfter("if (insert(mediaMetadata.toSongEntity().let(block)) == -1L) {").substringBefore("return")
        assertTrue("insert(mediaMetadata) no longer puts a stored credit right", "splitStoredCredit(mediaMetadata.id, mediaMetadata.artists)" in known)
    }
}
