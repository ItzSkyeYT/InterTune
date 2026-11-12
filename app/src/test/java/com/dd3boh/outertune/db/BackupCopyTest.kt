/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A backup's copy of the library holds together while the app goes on writing (4 Nov 2026).
 * The backup used to checkpoint and then read song.db into the zip, seconds of compression,
 * while SQLite went on moving committed pages into the file: a backup could hold pages from
 * before and after the same change, found out on the day it was needed.
 *
 * Against SQLite itself (sqlite-jdbc), in write-ahead mode as Android keeps song.db, with
 * DatabaseFiles.copy as the backup calls it. The write lock is a BEGIN IMMEDIATE on a connection
 * of its own, as Room's transaction is on Android. Each write keeps totals beside its rows, so a
 * copy made of two moments shows: its totals no longer agree with its songs.
 */
class BackupCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(db: File): Connection = DriverManager.getConnection("jdbc:sqlite:${db.path}").also {
        it.exec("PRAGMA busy_timeout = 20000")
        // Only how often the disk is told to flush, which a power cut would care about and these
        // copies do not: everything written is in the files as soon as it is written.
        it.exec("PRAGMA synchronous = OFF")
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.long(sql: String): Long = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
    }

    private fun Connection.text(sql: String): String = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) }
    }

    /** song.db as the app keeps it: write-ahead mode, a table that grows, an index over it, and totals. */
    private fun library(songs: Int): File {
        val db = File(tmp.newFolder("databases"), "song.db")
        open(db).use { c ->
            c.exec("PRAGMA journal_mode = WAL")
            c.exec("CREATE TABLE total (id INTEGER PRIMARY KEY, songs INTEGER NOT NULL, plays INTEGER NOT NULL)")
            c.exec("INSERT INTO total VALUES (1, 0, 0)")
            c.exec("CREATE TABLE song (id INTEGER PRIMARY KEY, title TEXT NOT NULL, plays INTEGER NOT NULL, pad BLOB NOT NULL)")
            c.exec("CREATE INDEX song_title ON song (title)")
            repeat(songs) { c.add() }
            c.exec("PRAGMA wal_checkpoint(TRUNCATE)")
        }
        return db
    }

    /**
     * One write the way the app makes them: a song in, a play counted on an earlier song, and the
     * totals that go with both, in one transaction. The earlier song is spread over the whole
     * table, so writes change pages from the start of the file to its end.
     */
    private fun Connection.add() {
        exec("BEGIN IMMEDIATE")
        val songs = long("SELECT songs FROM total")
        val id = songs + 1
        exec("INSERT INTO song VALUES ($id, 'title ${(id * 7919) % 1009}', 0, zeroblob(700))")
        if (songs > 0) exec("UPDATE song SET plays = plays + 1 WHERE id = ${1 + (id * 7907) % songs}")
        exec("UPDATE total SET songs = songs + 1, plays = plays + ${if (songs > 0) 1 else 0}")
        exec("COMMIT")
    }

    /** The write lock for the length of the copy, as Room's transaction holds it on Android. */
    private fun writeLock(lock: Connection): (() -> Unit) -> Unit = { copy ->
        lock.exec("BEGIN IMMEDIATE")
        try {
            copy()
        } finally {
            lock.exec("ROLLBACK")
        }
    }

    /**
     * What [copy] holds once SQLite has opened it and folded its log in, as the backup does before
     * it zips it: the number of songs, or null when it does not hold together (SQLite's integrity
     * check, or totals out of step with the rows beside them).
     */
    private fun songsIn(copy: File): Long? = try {
        open(copy).use { c ->
            c.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            val whole = c.text("PRAGMA integrity_check") == "ok" &&
                c.long("SELECT songs FROM total") == c.long("SELECT count(*) FROM song") &&
                c.long("SELECT songs FROM total") == c.long("SELECT coalesce(max(id), 0) FROM song") &&
                c.long("SELECT plays FROM total") == c.long("SELECT coalesce(sum(plays), 0) FROM song")
            if (whole) c.long("SELECT songs FROM total") else null
        }
    } catch (e: java.sql.SQLException) {
        null
    }

    /** Copies [from] in two halves, with [between] run once the first half is in. */
    private fun copyInHalves(from: File, to: File, between: () -> Unit) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output ->
                val buffer = ByteArray(4096)
                var left = from.length() / 2
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
                between()
                // The rest as the file is now.
                input.copyTo(output)
            }
        }
    }

    /**
     * 400 songs in song.db, 150 more written since the last checkpoint, which are in the log only.
     * [copy] takes the copy while a checkpoint from another connection moves those 150 into song.db
     * half way through; it returns what the checkpoint moved, to show that it ran.
     */
    private fun checkpointHalfWay(copy: (db: File, into: File, checkpoint: () -> Unit) -> Unit): Pair<File, Long> {
        val db = library(songs = 400)
        val into = File(tmp.newFolder("copy"), "song.db")
        var moved = 0L
        open(db).use { app ->
            app.exec("PRAGMA wal_autocheckpoint = 0")
            repeat(150) { app.add() }
            open(db).use { other ->
                copy(db, into) {
                    other.createStatement().use { st ->
                        st.executeQuery("PRAGMA wal_checkpoint(PASSIVE)").use { rs ->
                            rs.next()
                            assertEquals("the checkpoint was not held up", 0, rs.getInt(1))
                            moved = rs.getLong(3)
                        }
                    }
                }
            }
        }
        return into to moved
    }

    @Test
    fun `a checkpoint half way through the copy changes nothing in it, because the log is copied with the file`() {
        val (copy, moved) = checkpointHalfWay { db, into, checkpoint ->
            open(db).use { lock ->
                DatabaseFiles.copy(db, into, writeLock(lock), copyFile = { from, to ->
                    if (from == db) copyInHalves(from, to, checkpoint) else from.copyTo(to, overwrite = true)
                })
            }
        }
        assertTrue("the checkpoint moved the 150 writes into song.db during the copy", moved > 0)
        assertEquals("every song, and the copy holds together", 550L, songsIn(copy))
        assertTrue("whole on its own once SQLite has folded its log in", DatabaseFiles.isWhole(copy))
    }

    @Test
    fun `the same checkpoint tears a copy of song dot db alone, which is what the backup used to zip`() {
        val (copy, moved) = checkpointHalfWay { db, into, checkpoint -> copyInHalves(db, into, checkpoint) }
        assertTrue(moved > 0)
        assertEquals("pages from before and after the same writes", null, songsIn(copy))
    }

    @Test
    fun `copies taken while another connection writes and checkpoints hold every song written before them`() {
        val db = library(songs = 300)
        val written = AtomicInteger(300)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val writer = thread {
            try {
                open(db).use { app ->
                    // A checkpoint every few writes, where Android's default is every 100 pages.
                    app.exec("PRAGMA wal_autocheckpoint = 8")
                    while (!stop.get()) {
                        app.add()
                        written.incrementAndGet()
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val copies = mutableListOf<Long>()
        try {
            open(db).use { lock ->
                repeat(12) { round ->
                    Thread.sleep(25)
                    val before = written.get()
                    val into = File(tmp.newFolder("copy-$round"), "song.db")
                    DatabaseFiles.copy(db, into, writeLock(lock))
                    val songs = songsIn(into)
                    assertTrue("copy $round does not hold together", songs != null)
                    assertTrue("copy $round holds $songs songs, $before were written before it", songs!! >= before)
                    copies += songs
                }
            }
        } finally {
            stop.set(true)
            writer.join()
        }
        failure.get()?.let { throw it }
        assertTrue("the writer kept writing between the copies: $copies", copies.distinct().size > 6)
    }

    @Test
    fun `nothing an earlier copy left behind is read as part of a new one`() {
        val db = library(songs = 50)
        val into = File(tmp.newFolder("copy"), "song.db")
        DatabaseFiles.SIDECARS.forEach { DatabaseFiles.sidecar(into, it).writeText("an earlier copy's") }
        open(db).use { lock -> DatabaseFiles.copy(db, into, writeLock(lock)) }
        // song.db's log was folded in and is empty, so nothing is beside the copy.
        DatabaseFiles.SIDECARS.forEach { assertFalse(it, DatabaseFiles.sidecar(into, it).exists()) }
        assertEquals(50L, songsIn(into))
    }

    // ---- The backup itself, read from the source: Room and Android's SQLite take a phone to run. ----

    private val writer = File("src/main/java/com/dd3boh/outertune/utils/BackupWriter.kt").readText()

    @Test
    fun `the backup zips a checked copy made under a Room transaction, never song dot db itself`() {
        assertTrue(
            "DatabaseFiles.copy(live, copy, holdingWriteLock = { copyFiles -> database.transactionNow { copyFiles() } })" in writer
        )
        assertTrue("checked before it is used", "if (foldLog(copy, check = true)) return copy" in writer)
        assertTrue("the restore's own check", "db.isDatabaseIntegrityOk" in writer)
        assertTrue("FileInputStream(copy).use" in writer)
        // openHelper.writableDatabase opens the database again when it has been closed.
        assertFalse("openHelper" in writer)
        assertTrue(
            "taken before anything goes into the zip",
            writer.indexOf("consistentCopy(context, database)") in 0 until writer.indexOf("zipOutputStream()")
        )
    }

    @Test
    fun `a copy with something left in its log is not whole`() {
        val db = library(songs = 20)
        open(db).use { app ->
            app.exec("PRAGMA wal_autocheckpoint = 0")
            app.add()
            assertTrue(DatabaseFiles.sidecar(db, "-wal").length() > 0)
            assertFalse(DatabaseFiles.isWhole(db))
        }
        // The last connection to close folds the log in and deletes it.
        assertTrue(DatabaseFiles.isWhole(db))
    }
}
