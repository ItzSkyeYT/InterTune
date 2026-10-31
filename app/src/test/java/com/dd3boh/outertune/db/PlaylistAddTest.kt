/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.PlaylistsDao
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException

/**
 * Which songs an add to a playlist writes and where (#62), without a database.
 *
 * The add used to insert whatever ids it was handed, and a map row whose song is not in the song
 * table fails its foreign key even with OR IGNORE. On a coroutine with no handler that ended the
 * app. It now looks first, so what it is handed may be wrong and nothing is thrown.
 */
class PlaylistAddPlanTest {

    private fun plan(songs: List<String>, inTable: Set<String>, exists: Boolean = true, first: Int = 0) =
        PlaylistAdd.plan(songs, exists, inTable, first)

    @Test
    fun `songs that are all in the table go in, in order, from the first position`() {
        val p = plan(listOf("a", "b", "c"), setOf("a", "b", "c"), first = 4)
        assertEquals(listOf(PlaylistAdd.Row("a", 4), PlaylistAdd.Row("b", 5), PlaylistAdd.Row("c", 6)), p.rows)
        assertEquals(0, p.skipped)
    }

    @Test
    fun `a song that is not in the table is left out and takes no position`() {
        val p = plan(listOf("a", "ghost", "b"), setOf("a", "b"))
        assertEquals(listOf(PlaylistAdd.Row("a", 0), PlaylistAdd.Row("b", 1)), p.rows)
        assertEquals(1, p.skipped)
    }

    @Test
    fun `none of them in the table writes nothing and counts them all`() {
        val p = plan(listOf("x", "y", "z"), emptySet())
        assertEquals(emptyList<PlaylistAdd.Row>(), p.rows)
        assertEquals(3, p.skipped)
    }

    @Test
    fun `a playlist that is gone takes nothing, however good the songs are`() {
        val p = plan(listOf("a", "b"), setOf("a", "b"), exists = false)
        assertEquals(emptyList<PlaylistAdd.Row>(), p.rows)
        assertEquals(2, p.skipped)
    }

    @Test
    fun `a song asked for twice goes in twice, as Add anyway means`() {
        val p = plan(listOf("a", "a", "b"), setOf("a", "b"))
        assertEquals(listOf("a", "a", "b"), p.rows.map { it.songId })
        assertEquals(listOf(0, 1, 2), p.rows.map { it.position })
        assertEquals(0, p.skipped)
    }

    @Test
    fun `a song asked for twice that is not in the table is left out twice`() {
        val p = plan(listOf("ghost", "a", "ghost"), setOf("a"))
        assertEquals(listOf("a"), p.rows.map { it.songId })
        assertEquals(2, p.skipped)
    }

    @Test
    fun `nothing asked for is nothing written and nothing left out`() {
        val p = plan(emptyList(), setOf("a"))
        assertTrue(p.rows.isEmpty())
        assertEquals(0, p.skipped)
    }
}

/** A PlaylistsDao that answers nothing: whatever a test does not mean to ask is a failure. */
private fun refusingDao(): PlaylistsDao = Proxy.newProxyInstance(
    PlaylistsDao::class.java.classLoader, arrayOf(PlaylistsDao::class.java)
) { _, method, _ -> error("unexpected call to ${method.name}") } as PlaylistsDao

/**
 * PlaylistsDao.addSongToPlaylist itself, the Kotlin the phone runs, against the schema Room
 * exported, with foreign keys on as Room has them.
 *
 * The DAO is an interface, and Room writes its queries. Here the four things the method asks of the
 * database (is the playlist there, which of these songs are rows, where does the next one go, write
 * this row) are answered from an in-memory SQLite with the very statements the DAO is annotated
 * with (PlaylistSql), and the method's own body, the interface's default, is run as it is: any
 * other call is refused. What this does not have is Room's transaction around it, which is what
 * makes the look and the write one step on the phone.
 */
class AddToPlaylistGuardSqlTest {
    private lateinit var db: Connection

    private val playlist = Playlist(PlaylistEntity(id = "P", name = "P"), songCount = 0, downloadCount = 0, songThumbnails = emptyList())

    private inner class SqlDao : PlaylistsDao by refusingDao() {
        override fun addSongToPlaylist(playlist: Playlist, songIds: List<String>): List<String> =
            super<PlaylistsDao>.addSongToPlaylist(playlist, songIds)

        override fun playlistExists(playlistId: String): Boolean = exists(playlistId)

        override fun songIdsInTable(songIds: List<String>): List<String> = inTable(songIds).toList()

        override fun nextPlaylistPosition(playlistId: String): Int = nextPosition(playlistId)

        override fun insert(map: PlaylistSongMap) {
            // What Room's insert is, minus the generated id: OR IGNORE, as @Insert says.
            exec("INSERT OR IGNORE INTO playlist_song_map(playlistId, songId, position) VALUES ('${map.playlistId}', '${map.songId}', ${map.position})")
        }
    }

    private val dao: PlaylistsDao = SqlDao()

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO playlist(id, name) VALUES ('P', 'P')")
        for (song in listOf("a", "b", "c")) {
            exec("INSERT INTO song(id, title, duration, liked) VALUES ('$song', '$song', 200, 0)")
        }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun quoted(ids: Collection<String>) = ids.joinToString(",") { "'$it'" }

    private fun exists(playlistId: String): Boolean = db.createStatement().use { st ->
        st.executeQuery(PlaylistSql.EXISTS.replace(":playlistId", "'$playlistId'")).use { rs -> rs.next() && rs.getInt(1) == 1 }
    }

    private fun inTable(ids: List<String>): Set<String> = db.createStatement().use { st ->
        st.executeQuery(PlaylistSql.PRESENT_SONGS.replace(":songIds", quoted(ids))).use { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
    }

    private fun nextPosition(playlistId: String): Int = db.createStatement().use { st ->
        st.executeQuery(PlaylistSql.NEXT_POSITION.replace(":playlistId", "'$playlistId'")).use { rs -> rs.next(); rs.getInt(1) }
    }

    private fun map(playlistId: String = "P"): List<Pair<String, Int>> = db.createStatement().use { st ->
        st.executeQuery("SELECT songId, position FROM playlist_song_map WHERE playlistId = '$playlistId' ORDER BY position, id").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
        }
    }

    @Test
    fun `the write alone fails its foreign key for a song that is not in the table, with OR IGNORE too`() {
        // The crash of #62, as it was before the look: the insert was the first to find out.
        val e = assertThrows(SQLException::class.java) {
            exec("INSERT OR IGNORE INTO playlist_song_map(playlistId, songId, position) VALUES ('P', 'ghost', 0)")
        }
        assertTrue(e.message.orEmpty(), "FOREIGN KEY" in e.message.orEmpty().uppercase())
        assertEquals(emptyList<Pair<String, Int>>(), map())
    }

    @Test
    fun `songs that are all there go in and the ids that went in come back`() {
        assertEquals(listOf("a", "b", "c"), dao.addSongToPlaylist(playlist, listOf("a", "b", "c")))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), map())
    }

    @Test
    fun `a song that is not in the table is left out and the others go in`() {
        assertEquals(listOf("a", "b"), dao.addSongToPlaylist(playlist, listOf("a", "ghost", "b")))
        assertEquals(listOf("a" to 0, "b" to 1), map())
    }

    @Test
    fun `every song unknown writes nothing and throws nothing`() {
        assertEquals(emptyList<String>(), dao.addSongToPlaylist(playlist, listOf("x", "y")))
        assertEquals(emptyList<Pair<String, Int>>(), map())
    }

    @Test
    fun `a playlist that is gone takes nothing and throws nothing`() {
        exec("DELETE FROM playlist WHERE id = 'P'")
        assertEquals(emptyList<String>(), dao.addSongToPlaylist(playlist, listOf("a", "b")))
        assertEquals(emptyList<Pair<String, Int>>(), map())
    }

    @Test
    fun `a song deleted after the picker was drawn is left out`() {
        exec("DELETE FROM song WHERE id = 'b'")
        assertEquals(listOf("a", "c"), dao.addSongToPlaylist(playlist, listOf("a", "b", "c")))
        assertEquals(listOf("a" to 0, "c" to 1), map())
    }

    @Test
    fun `an empty playlist starts at 0 and a full one goes on from its last position`() {
        assertEquals(0, nextPosition("P"))
        dao.addSongToPlaylist(playlist, listOf("a", "b"))
        assertEquals(2, nextPosition("P"))
        // After a song was left out the next add still follows on with no gap.
        dao.addSongToPlaylist(playlist, listOf("ghost", "c"))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), map())
    }

    @Test
    fun `add anyway puts a song in twice`() {
        dao.addSongToPlaylist(playlist, listOf("a"))
        assertEquals(listOf("a", "a"), dao.addSongToPlaylist(playlist, listOf("a", "a")))
        assertEquals(listOf("a" to 0, "a" to 1, "a" to 2), map())
    }

    @Test
    fun `nothing to add writes nothing, whatever the playlist`() {
        assertEquals(emptyList<String>(), dao.addSongToPlaylist(playlist, emptyList()))
        exec("DELETE FROM playlist WHERE id = 'P'")
        assertEquals(emptyList<String>(), dao.addSongToPlaylist(playlist, emptyList()))
        assertEquals(emptyList<Pair<String, Int>>(), map())
    }

    @Test
    fun `more songs than one query takes are all looked at`() {
        // 2000 songs in the table plus one that is not, past SQLite's 999 arguments on older Androids.
        db.autoCommit = false
        (1..2000).forEach { exec("INSERT INTO song(id, title, duration, liked) VALUES ('s$it', 's$it', 200, 0)") }
        db.commit()
        db.autoCommit = true
        val ids = (1..2000).map { "s$it" } + "ghost"
        val went = dao.addSongToPlaylist(playlist, ids)
        assertEquals(2000, went.size)
        assertEquals((1..2000).map { "s$it" }, went)
        assertEquals(2000, map().size)
    }

    @Test
    fun `which songs are in the table is asked for the ids given and nothing else`() {
        assertEquals(setOf("a", "c"), inTable(listOf("a", "ghost", "c")))
        assertEquals(emptySet<String>(), inTable(listOf("ghost")))
    }

    @Test
    fun `the look at the playlist sees a row and its absence`() {
        assertTrue(exists("P"))
        assertTrue(!exists("Q"))
    }
}
