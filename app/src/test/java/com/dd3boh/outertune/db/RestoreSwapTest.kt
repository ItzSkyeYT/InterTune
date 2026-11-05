/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager

/**
 * A restore puts the backup's database in place in one step and never leaves a cut-off song.db
 * (4 Nov 2026). It used to copy the checked file over song.db, which empties song.db before
 * writing it: a process that died in that second, with the player and the sync still running
 * against a closed database, left a cut-off file, which SQLite's open helper deletes as damaged,
 * and the library came back empty.
 *
 * DatabaseFiles.replace against SQLite itself (sqlite-jdbc); the order of the restore around it
 * is read from the source below, since Room and the player take a phone to run.
 */
class RestoreSwapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(db: File): Connection = DriverManager.getConnection("jdbc:sqlite:${db.path}").also {
        it.createStatement().use { st -> st.execute("PRAGMA synchronous = OFF") }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.text(sql: String): String = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) }
    }

    /** A library of [songs] songs at [db], in write-ahead mode as Android keeps song.db, closed. */
    private fun library(db: File, songs: Int): File {
        open(db).use { c ->
            c.exec("PRAGMA journal_mode = WAL")
            c.exec("CREATE TABLE song (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
            c.exec("CREATE INDEX song_title ON song (title)")
            c.exec("BEGIN")
            for (id in 1..songs) c.exec("INSERT INTO song VALUES ($id, 'title $id')")
            c.exec("COMMIT")
        }
        return db
    }

    /**
     * Leaves [writes] songs in [db]'s log at rest, as a process that died before its checkpoint
     * does: the close folds the log in and deletes it, so it is put back as it was before.
     */
    private fun leaveInLog(db: File, writes: Int) {
        val log = DatabaseFiles.sidecar(db, "-wal")
        val kept = File(db.path + ".kept")
        open(db).use { c ->
            c.exec("PRAGMA wal_autocheckpoint = 0")
            repeat(writes) { c.exec("INSERT INTO song (title) VALUES ('later $it')") }
            log.copyTo(kept, overwrite = true)
        }
        assertTrue(kept.renameTo(log))
    }

    /** The songs in [db], once SQLite has found it whole. */
    private fun songsIn(db: File): String = open(db).use { c ->
        assertEquals("ok", c.text("PRAGMA integrity_check"))
        c.text("SELECT count(*) || ' songs, the last ' || max(id) FROM song")
    }

    @Test
    fun `the checked file takes song dot db's place whole, and the old file is moved aside, never written over`() {
        val folder = tmp.newFolder("databases")
        val live = library(File(folder, "song.db"), songs = 300)
        leaveInLog(live, writes = 20)
        val probe = library(File(folder, "probe_song.db"), songs = 75)
        val old = live.readBytes()
        FileInputStream(live).use { reader ->
            DatabaseFiles.replace(probe, live)
            assertArrayEquals("the old file was moved aside whole", old, reader.readBytes())
        }
        assertFalse(probe.exists())
        // The old library's log would be replayed into the new file: it went first.
        DatabaseFiles.SIDECARS.forEach { assertFalse(it, DatabaseFiles.sidecar(live, it).exists()) }
        assertEquals("75 songs, the last 75", songsIn(live))
    }

    @Test
    fun `copying over song dot db, as the restore did, empties it before writing a byte`() {
        val live = library(File(tmp.newFolder("databases"), "song.db"), songs = 300)
        FileOutputStream(live).use {
            // Where a process that died left song.db: an empty file, which SQLite opens as a new,
            // empty database, and the library is gone.
            assertEquals(0L, live.length())
        }
    }

    @Test
    fun `a file with something left in its log is not moved in, and song dot db stays as it was`() {
        val folder = tmp.newFolder("databases")
        val live = library(File(folder, "song.db"), songs = 300)
        val probe = library(File(folder, "probe_song.db"), songs = 75)
        leaveInLog(probe, writes = 5)
        val old = live.readBytes()
        assertTrue(runCatching { DatabaseFiles.replace(probe, live) }.exceptionOrNull() is IOException)
        assertArrayEquals(old, live.readBytes())
        assertTrue(probe.exists())
    }

    @Test
    fun `when the old log cannot be deleted, nothing moves`() {
        val folder = tmp.newFolder("databases")
        val live = library(File(folder, "song.db"), songs = 300)
        val probe = library(File(folder, "probe_song.db"), songs = 75)
        // A folder with something in it, which delete() refuses.
        File(DatabaseFiles.sidecar(live, "-wal"), "in the way").apply { parentFile!!.mkdirs(); writeText("x") }
        val old = live.readBytes()
        assertTrue(runCatching { DatabaseFiles.replace(probe, live) }.exceptionOrNull() is IOException)
        assertArrayEquals(old, live.readBytes())
        assertTrue(probe.exists())
    }

    // ---- The restore around it, read from the source ----

    private val restore = File("src/main/java/com/dd3boh/outertune/viewmodels/BackupRestoreViewModel.kt").readText()
        .let { it.substring(it.indexOf("fun restoreValidatedDatabase(")) }

    @Test
    fun `the music stops before the database closes, and the checked file moves in after it, under the backup's lock`() {
        val stop = restore.indexOf("MusicService.alive()?.stopForRestore()")
        val lock = restore.indexOf("synchronized(DatabaseFiles.lock)")
        val close = restore.indexOf("database.close()")
        val replace = restore.indexOf("DatabaseFiles.replace(probeFile, context.getDatabasePath(InternalDatabase.DB_NAME))")
        assertTrue("stop $stop, lock $lock, close $close, replace $replace", stop in 0 until lock && lock < close && close < replace)
        assertFalse("nothing is copied over song.db", "FileOutputStream(liveDbFile)" in restore)
        // Folded into its own file first, so the file that moves holds all of the migration.
        assertTrue(restore.indexOf("BackupWriter.foldLog(probeFile, check = false)") in 0 until stop)
    }

    @Test
    fun `once the database is closed the app restarts whatever happens, and the settings follow the library`() {
        val close = restore.indexOf("database.close()")
        val afterClose = restore.substring(close)
        // A move that fails still restarts: the app cannot go on without its database.
        val failed = afterClose.substring(afterClose.indexOf("} catch (e: Exception) {"))
        assertTrue(failed.substringBefore("\n        }\n").contains("return true"))
        assertTrue(afterClose.indexOf("writeSettings(") > afterClose.indexOf("DatabaseFiles.replace("))
        assertTrue("a settings file that cannot be written does not stop the restart", "runCatching { writeSettings(" in afterClose)
    }
}
