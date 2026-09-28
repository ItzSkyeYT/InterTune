/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * LgAlbumRepair over a copy of a real library, run twice. Gated on `LG_REPAIR_DB=/path/to/song.db`
 * so the ordinary test run never depends on private data. The copy takes the -wal beside the file
 * too, when there is one, as a database pulled from a phone that is still open keeps its latest
 * writes there. Reports what there was to repair, what came of it and how long it took, and
 * asserts that the library comes out whole and that the second run changes nothing.
 */
class LgAlbumRepairTrial {
    private val lgId = "'LG[A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z]'"

    /** The songs the repair moves: in an LG row that is there, not a file, with an album id of their own. */
    private val moves = "FROM song_album_map m JOIN album a ON a.id = m.albumId JOIN song s ON s.id = m.songId " +
        "WHERE m.albumId GLOB $lgId AND s.isLocal <> 1 AND trim(s.albumId) <> '' AND s.albumId NOT GLOB $lgId"

    private fun Connection.count(sql: String): Long = createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }

    private fun Connection.rows(sql: String): List<List<String?>> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
    }

    /** In one transaction with foreign keys off, as the migration to 25 runs it. */
    private fun Connection.repair(): Long {
        createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        autoCommit = false
        val t0 = System.nanoTime()
        LgAlbumRepair.STEPS.forEach { sql -> createStatement().use { it.execute(sql) } }
        commit()
        autoCommit = true
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `a real library is repaired once and only once`() {
        val source = System.getenv("LG_REPAIR_DB"); assumeTrue("set LG_REPAIR_DB to run", !source.isNullOrBlank())
        val copy = File.createTempFile("lg-repair-trial", ".db").apply { deleteOnExit() }
        File(source!!).copyTo(copy, overwrite = true)
        File("$source-wal").takeIf { it.exists() }?.copyTo(File("${copy.path}-wal").apply { deleteOnExit() }, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val tables = db.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").map { it[0]!! }
            fun sizes() = tables.associateWith { db.count("SELECT COUNT(*) FROM `$it`") }
            fun inLg() = db.count("SELECT COUNT(*) FROM song_album_map WHERE albumId GLOB $lgId")

            val before = sizes()
            val inAlbums = db.count("SELECT COUNT(DISTINCT songId) FROM song_album_map")
            val foreignKeys = db.rows("PRAGMA foreign_key_check").size
            val lgSongs = inLg()
            val moving = db.count("SELECT COUNT(*) $moves")
            println("before: ${before["album"]} albums, ${db.count("SELECT COUNT(*) FROM album WHERE id GLOB $lgId")} under an LG id, " +
                "$lgSongs songs in them, $moving to move and ${lgSongs - moving} to stay")
            println("  real albums they name: ${db.count("SELECT COUNT(DISTINCT s.albumId) $moves")}, " +
                "already stored: ${db.count("SELECT COUNT(DISTINCT s.albumId) $moves AND EXISTS (SELECT 1 FROM album r WHERE r.id = s.albumId)")}, " +
                "LG rows holding several: ${db.count("SELECT COUNT(*) FROM (SELECT m.albumId $moves GROUP BY m.albumId HAVING COUNT(DISTINCT s.albumId) > 1)")}")

            val ms = db.repair()
            val after = sizes()
            println("after, in $ms ms: ${after["album"]} albums, ${db.count("SELECT COUNT(*) FROM album WHERE id GLOB $lgId")} under an LG id, " +
                "${inLg()} songs in them; song_album_map ${before["song_album_map"]} -> ${after["song_album_map"]}")

            assertEquals(listOf(listOf("ok")), db.rows("PRAGMA integrity_check"))
            assertEquals("rows pointing at nothing", foreignKeys, db.rows("PRAGMA foreign_key_check").size)
            assertEquals("every song still in an album", inAlbums, db.count("SELECT COUNT(DISTINCT songId) FROM song_album_map"))
            assertEquals("only what stays is under an LG id", lgSongs - moving, inLg())
            assertEquals("no other table changes size", before - "album" - "song_album_map", after - "album" - "song_album_map")

            val repaired = listOf("album", "song_album_map", "album_artist_map")
            val once = repaired.associateWith { db.rows("SELECT rowid, * FROM `$it` ORDER BY rowid") }
            println("second run: ${db.repair()} ms")
            assertEquals(once, repaired.associateWith { db.rows("SELECT rowid, * FROM `$it` ORDER BY rowid") })
        }
    }
}
