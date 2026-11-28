/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * A backup from OuterTune 0.7.0 to 0.7.4 (its database 16) is brought up to date by MIGRATION_16_17,
 * which pairs each queue's songs with their shuffled twins. Those versions saved a song with no twin
 * when it was removed while the queue was shuffled, and the pairing threw on it, so the whole backup
 * was refused as one this version cannot open. A song with no twin is left out, as the migration's
 * own comment says, and the rest of the queue comes over.
 *
 * The migration runs on a real database 16 (SQLite itself, built from the exported schema). Its
 * inserts are counted rather than run: a ContentValues holds nothing in these tests.
 */
class QueueFromOuterTuneTest {

    private val c: Connection = SchemaDb.open(version = 16)

    @After
    fun close() = c.close()

    private val inserted = ArrayList<String>()

    private fun cursor(sql: String): Cursor {
        val rows = ArrayList<List<String?>>()
        val names = ArrayList<String>()
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                (1..rs.metaData.columnCount).forEach { names += rs.metaData.getColumnName(it) }
                while (rs.next()) rows += names.indices.map { rs.getString(it + 1) }
            }
        }
        var at = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> names.indexOf(args!![0] as String)
                "moveToNext" -> ++at < rows.size
                "getString" -> rows[at][args!![0] as Int]
                "close" -> null
                else -> throw AssertionError("the migration asked its cursor for ${method.name}, which this test does not answer")
            }
        } as Cursor
    }

    private val database = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
        val given = args.orEmpty()
        val sql = given.singleOrNull() as? String
        when {
            method.name == "execSQL" && sql != null -> { exec(sql); null }
            method.name == "query" && sql != null -> cursor(sql)
            method.name == "insert" -> { inserted += given[0] as String; inserted.size.toLong() }
            else -> throw AssertionError("the migration asked for ${method.name}, which this test does not answer")
        }
    } as SupportSQLiteDatabase

    private fun exec(sql: String) = c.createStatement().use { it.execute(sql) }

    @Test
    fun `a queue song with no shuffled twin is left out, and the backup comes over`() {
        exec("INSERT INTO song (id, title, duration, liked, totalPlayTime) VALUES ('a', 'A', 1, 0, 0), ('b', 'B', 1, 0, 0), ('c', 'C', 1, 0, 0), ('x', 'X', 1, 0, 0), ('y', 'Y', 1, 0, 0)")
        exec("INSERT INTO queue (id, title, shuffled, queuePos) VALUES (1, 'Removed while shuffled', 1, 0), (2, 'Whole', 0, 0)")
        // Queue 1 as OuterTune 0.7.x saved it after C was removed while shuffled: C is left in its
        // unshuffled list only. Queue 2 has every song in both.
        exec("INSERT INTO queue_song_map (queueId, songId, shuffled) VALUES (1, 'a', 0), (1, 'b', 0), (1, 'c', 0), (1, 'b', 1), (1, 'a', 1)")
        exec("INSERT INTO queue_song_map (queueId, songId, shuffled) VALUES (2, 'x', 0), (2, 'y', 0), (2, 'y', 1), (2, 'x', 1)")

        MIGRATION_16_17.migrate(database)

        assertEquals("A and B of the first queue, both of the second, and not C", 4, inserted.size)
        assertTrue(inserted.all { it == "queue_song_map" })
        val columns = c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(queue_song_map)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }
        assertEquals(listOf("queueId", "index", "shuffledIndex", "id", "songId"), columns)
    }
}
