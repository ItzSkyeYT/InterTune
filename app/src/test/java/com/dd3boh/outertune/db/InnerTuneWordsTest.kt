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
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * An InnerTune backup restored on Android 7 to 10 goes through Migration12To13 on SQLite 3.9 to 3.22,
 * which reads the new columns' DEFAULT false and DEFAULT true as the text 'false' and 'true' and gives
 * every row there was that text. Room reads both as 0, so every `isLocal = 0` in the app's SQL missed
 * those songs: a download of one never showed under Downloaded. The step now makes them the numbers a
 * newer SQLite stores.
 *
 * The tables are schema 13's, as the step finds them (SQLite itself, built from the exported schema),
 * and the rows hold the words as an old phone stores them. The step's inserts are counted rather than
 * run: a ContentValues holds nothing in these tests.
 */
class InnerTuneWordsTest {

    private val c: Connection = SchemaDb.open(version = 13)

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

    private fun rows(sql: String): List<String> = c.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test
    fun `the words an old SQLite left become numbers, and the songs are found again`() {
        // As Android 7 to 10 leave the rows after the step's new columns: the words, as text in INTEGER columns.
        exec("INSERT INTO song (id, title, duration, liked, totalPlayTime, isLocal) VALUES ('a', 'A', 1, 1, 0, 'false'), ('b', 'B', 1, 0, 0, 'false')")
        exec("INSERT INTO artist (id, name, lastUpdateTime, isLocal) VALUES ('ar', 'Artist', 0, 'false')")
        exec("INSERT INTO album (id, title, songCount, duration, lastUpdateTime, isLocal) VALUES ('al', 'Album', 1, 1, 0, 'false')")
        exec("INSERT INTO playlist (id, name, browseId, isEditable, isLocal) VALUES ('mine', 'Mine', NULL, 'true', 'false'), ('yt', 'From YouTube', 'VLyt', 'true', 'false')")
        assertEquals("as an old phone stores them", listOf("text"), rows("SELECT DISTINCT typeof(isLocal) FROM song"))
        assertEquals("missed by isLocal = 0 before the step", listOf("0"), rows("SELECT count(*) FROM song WHERE isLocal = 0"))

        Migration12To13().onPostMigrate(database)

        for (table in listOf("song", "artist", "album", "playlist")) {
            assertEquals(table, listOf("integer"), rows("SELECT DISTINCT typeof(isLocal) FROM $table"))
        }
        assertEquals("both songs found by isLocal = 0", listOf("2"), rows("SELECT count(*) FROM song WHERE isLocal = 0"))
        // A playlist of the phone's own is local and one from YouTube is not; both can be edited.
        assertEquals(listOf("mine|1|1", "yt|0|1"), rows("SELECT id || '|' || isLocal || '|' || isEditable FROM playlist ORDER BY id"))
        assertEquals(listOf("integer"), rows("SELECT DISTINCT typeof(isEditable) FROM playlist"))
        assertEquals("the liked songs' and the library's playlists, and the liked song in the first", 3, inserted.size)
    }

    @Test
    fun `on a newer SQLite, which stores the numbers, the step changes none of them`() {
        exec("INSERT INTO song (id, title, duration, liked, totalPlayTime) VALUES ('a', 'A', 1, 0, 0)")
        exec("INSERT INTO playlist (id, name, browseId) VALUES ('yt', 'From YouTube', 'VLyt')")

        Migration12To13().onPostMigrate(database)

        assertEquals(listOf("0|integer"), rows("SELECT isLocal || '|' || typeof(isLocal) FROM song"))
        assertEquals(listOf("0|1"), rows("SELECT isLocal || '|' || isEditable FROM playlist"))
    }
}
