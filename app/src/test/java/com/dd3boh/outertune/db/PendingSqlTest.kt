/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.AddedRow
import com.dd3boh.outertune.db.daos.PlaylistsDao
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * What the changes kept for the YouTube Music account ask of the database, run against the exported
 * schema: the rows an add wrote, with their ids, which song was in already and which is a file on
 * the phone; the id an add's answer gives a row; the entries rows here already stand for; the files a
 * sync keeps in a synced playlist; and the one row a YouTube playlist is found by, never a second.
 */
class PendingSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO playlist(id, name, browseId) VALUES ('P', 'P', 'PL1')")
        for (song in listOf("a", "b", "c")) exec("INSERT INTO song(id, title, duration, liked) VALUES ('$song', '$song', 200, 0)")
        exec("INSERT INTO song(id, title, duration, liked, isLocal, localPath) VALUES ('LSfile0001', 'file', 200, 0, 1, '/music/file.mp3')")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun update(sql: String): Int = db.createStatement().use { it.executeUpdate(sql) }

    private fun strings(sql: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun quoted(ids: Collection<String>) = ids.joinToString(",") { "'$it'" }

    private fun maps(sql: String): List<PlaylistSongMap> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            buildList {
                while (rs.next()) add(
                    PlaylistSongMap(
                        id = rs.getInt("id"), playlistId = rs.getString("playlistId"), songId = rs.getString("songId"),
                        position = rs.getInt("position"), setVideoId = rs.getString("setVideoId"),
                    )
                )
            }
        }
    }

    // ---- the rows an add wrote, through the DAO's own Kotlin ----

    private fun refusingDao(): PlaylistsDao = Proxy.newProxyInstance(
        PlaylistsDao::class.java.classLoader, arrayOf(PlaylistsDao::class.java)
    ) { _, method, _ -> error("unexpected call to ${method.name}") } as PlaylistsDao

    /** PlaylistsDao.addSongRowsToPlaylist as the phone runs it, every query it asks answered by SQLite. */
    private inner class SqlDao : PlaylistsDao by refusingDao() {
        override fun addSongRowsToPlaylist(playlist: Playlist, songIds: List<String>): List<AddedRow> =
            super<PlaylistsDao>.addSongRowsToPlaylist(playlist, songIds)

        override fun addSongToPlaylist(playlist: Playlist, songIds: List<String>): List<String> =
            super<PlaylistsDao>.addSongToPlaylist(playlist, songIds)

        override fun playlistExists(playlistId: String): Boolean =
            strings(PlaylistSql.EXISTS.replace(":playlistId", "'$playlistId'")).single() == "1"

        override fun songIdsInTable(songIds: List<String>): List<String> = strings(PlaylistSql.PRESENT_SONGS.replace(":songIds", quoted(songIds)))

        override fun nextPlaylistPosition(playlistId: String): Int =
            strings(PlaylistSql.NEXT_POSITION.replace(":playlistId", "'$playlistId'")).single().toInt()

        override fun songMapsToPlaylist(playlistId: String, from: Int): List<PlaylistSongMap> =
            maps("SELECT * FROM playlist_song_map WHERE playlistId = '$playlistId' AND position >= $from ORDER BY position")

        override fun phoneFileIds(songIds: List<String>): List<String> = strings(PlaylistSql.PHONE_FILE_IDS.replace(":songIds", quoted(songIds)))

        override fun insert(map: PlaylistSongMap) {
            exec("INSERT OR IGNORE INTO playlist_song_map(playlistId, songId, position) VALUES ('${map.playlistId}', '${map.songId}', ${map.position})")
        }
    }

    private val playlist = Playlist(PlaylistEntity(id = "P", name = "P", browseId = "PL1"), songCount = 0, downloadCount = 0, songThumbnails = emptyList())

    @Test
    fun `an add gives back each row it wrote, with its id, and says which song was in already and which is a file here`() {
        val dao = SqlDao()
        val first = dao.addSongRowsToPlaylist(playlist, listOf("a", "b"))
        assertEquals(listOf("a" to false, "b" to false), first.map { it.map.songId to it.duplicate })
        // Add anyway: a is in already, and the second c is a copy of the first.
        val second = dao.addSongRowsToPlaylist(playlist, listOf("a", "c", "c", "LSfile0001"))
        assertEquals(listOf("a", "c", "c", "LSfile0001"), second.map { it.map.songId })
        assertEquals(listOf(true, false, true, false), second.map { it.duplicate })
        assertEquals(listOf(false, false, false, true), second.map { it.local })
        assertEquals(listOf(2, 3, 4, 5), second.map { it.map.position })
        // The ids are the rows' own, as a removal of one later finds it.
        val ids = maps("SELECT * FROM playlist_song_map WHERE playlistId = 'P' ORDER BY position").map { it.id }
        assertEquals(ids, (first + second).map { it.map.id })
    }

    @Test
    fun `an add of nothing that could go in gives back nothing`() {
        assertEquals(emptyList<AddedRow>(), SqlDao().addSongRowsToPlaylist(playlist, listOf("ghost")))
        assertEquals(emptyList<AddedRow>(), SqlDao().addSongRowsToPlaylist(playlist, emptyList()))
    }

    // ---- the id an add's answer gives its row ----

    private fun learn(mapId: Int, songId: String, setVideoId: String) = update(
        PlaylistSql.LEARN_SET_VIDEO_ID.replace(":setVideoId", "'$setVideoId'").replace(":mapId", "$mapId").replace(":songId", "'$songId'")
    )

    @Test
    fun `the answer's id goes on the row the add made, and only there`() {
        exec("INSERT INTO playlist_song_map(id, playlistId, songId, position) VALUES (1, 'P', 'a', 0)")
        exec("INSERT INTO playlist_song_map(id, playlistId, songId, position, setVideoId) VALUES (2, 'P', 'b', 1, 'S-old')")
        assertEquals(1, learn(1, "a", "S-new"))
        // Not a row of another song, not one that has an id already, not a row that is gone.
        assertEquals(0, learn(1, "b", "S-x"))
        assertEquals(0, learn(2, "b", "S-x"))
        assertEquals(0, learn(99, "a", "S-x"))
        assertEquals(listOf("S-new", "S-old"), strings("SELECT setVideoId FROM playlist_song_map ORDER BY position"))
    }

    @Test
    fun `the entries rows here stand for are those of the playlist's rows, by its YouTube id`() {
        exec("INSERT INTO playlist(id, name, browseId) VALUES ('Q', 'Q', 'PL2')")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position, setVideoId) VALUES ('P', 'a', 0, 'S1')")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'b', 1)")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position, setVideoId) VALUES ('Q', 'a', 0, 'S9')")
        assertEquals(listOf("S1"), strings(PlaylistSql.SET_VIDEO_IDS_HELD.replace(":browseId", "'PL1'")))
    }

    // ---- the files a sync keeps in a synced playlist ----

    @Test
    fun `a synced playlist's files from the phone are found, in their order`() {
        exec("INSERT INTO song(id, title, duration, liked, isLocal, localPath) VALUES ('LSfile0002', 'file2', 200, 0, 1, '/music/file2.mp3')")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'LSfile0002', 3)")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'a', 0)")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'LSfile0001', 1)")
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'b', 2)")
        val rows = maps(PlaylistSql.PHONE_FILE_ROWS.replace(":playlistId", "'P'"))
        assertEquals(listOf("LSfile0001" to 1, "LSfile0002" to 3), rows.map { it.songId to it.position })
        assertEquals(listOf("LSfile0001"), strings(PlaylistSql.PHONE_FILE_IDS.replace(":songIds", quoted(listOf("a", "LSfile0001")))))
    }

    // ---- one row for one YouTube playlist ----

    @Test
    fun `a YouTube playlist's rows come saved first, then oldest, and every synced row is listed saved or not`() {
        exec("DELETE FROM playlist")
        exec("INSERT INTO playlist(id, name, browseId, bookmarkedAt) VALUES ('LPold', 'old', 'PL1', NULL)")
        exec("INSERT INTO playlist(id, name, browseId, bookmarkedAt) VALUES ('LPsaved', 'saved', 'PL1', '2026-11-04T10:00:00')")
        exec("INSERT INTO playlist(id, name, browseId, bookmarkedAt) VALUES ('LPother', 'other', 'PL2', NULL)")
        exec("INSERT INTO playlist(id, name, browseId, bookmarkedAt, isLocal) VALUES ('LPlocal', 'local', NULL, '2026-11-04T10:00:00', 1)")
        assertEquals(listOf("LPsaved", "LPold"), strings(PlaylistSql.ROWS_BY_BROWSE_ID.replace(":browseId", "'PL1'").replace("SELECT *", "SELECT id")))
        assertEquals(listOf("LPold", "LPsaved", "LPother"), strings(PlaylistSql.SYNCED_ROWS.replace("SELECT *", "SELECT id")))
    }
}
