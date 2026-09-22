/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.MusicDatabase.Companion.MUSIC_DATABASE_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The favourite-artists query, run against the schema Room exported for the current version.
 *
 * The failures this is here to catch are the quiet ones. A mix built from slightly the wrong set
 * of songs still plays and still sounds like a mix, so neither a duplicated track nor a whole
 * unadded catalogue leaking in would prompt anybody to look. Both are one join away.
 */
class FavouritesSqlTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The real schema rather than an imitation, for the reason RecommendationSqlTest gives: a
        // hand-written CREATE TABLE drifts from the app the first time a column changes, and the
        // test goes on passing against a database that no longer exists.
        val schemaFile = File("schemas/com.dd3boh.outertune.db.InternalDatabase/$MUSIC_DATABASE_VERSION.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        db.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    st.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, inLibrary: Boolean) = exec(
        "INSERT INTO song(id, title, duration, liked, inLibrary) " +
                "VALUES ('$id', '$id', 200, 0, ${if (inLibrary) 1 else "NULL"})"
    )

    private fun artist(id: String, bookmarked: Boolean) = exec(
        "INSERT INTO artist(id, name, lastUpdateTime, bookmarkedAt, isLocal) " +
                "VALUES ('$id', '$id', 0, ${if (bookmarked) 1 else "NULL"}, 0)"
    )

    private fun by(songId: String, artistId: String, position: Int = 0) = exec(
        "INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$songId', '$artistId', $position)"
    )

    private fun result(): List<String> = db.createStatement().use { st ->
        st.executeQuery(FavouritesSql.BY_BOOKMARKED_ARTISTS).use { rs ->
            buildList { while (rs.next()) add(rs.getString("id")) }
        }
    }

    @Test
    fun `only library songs by bookmarked artists`() {
        artist("FAVE", bookmarked = true)
        artist("OTHER", bookmarked = false)

        song("keep", inLibrary = true); by("keep", "FAVE")
        song("notAdded", inLibrary = false); by("notAdded", "FAVE")
        song("notFavourite", inLibrary = true); by("notFavourite", "OTHER")
        song("orphan", inLibrary = true)

        assertEquals(listOf("keep"), result())
    }

    @Test
    fun `a song by two bookmarked artists is returned once`() {
        // The reason this is EXISTS and not a join. A join against song_artist_map multiplies the
        // row by the number of bookmarked artists on it, so a collaboration between two favourites
        // would be handed to the mix twice and play twice.
        artist("A", bookmarked = true)
        artist("B", bookmarked = true)
        song("duet", inLibrary = true)
        by("duet", "A", position = 0)
        by("duet", "B", position = 1)

        assertEquals(listOf("duet"), result())
    }

    @Test
    fun `one bookmarked artist among several is enough`() {
        artist("FAVE", bookmarked = true)
        artist("GUEST", bookmarked = false)
        song("feat", inLibrary = true)
        by("feat", "GUEST", position = 0)
        by("feat", "FAVE", position = 1)

        assertEquals("a favourite billed second still counts", listOf("feat"), result())
    }

    @Test
    fun `unbookmarking an artist empties the mix`() {
        artist("FAVE", bookmarked = true)
        song("s1", inLibrary = true); by("s1", "FAVE")
        assertEquals(listOf("s1"), result())

        exec("UPDATE artist SET bookmarkedAt = NULL WHERE id = 'FAVE'")
        assertEquals(emptyList<String>(), result())
    }

    @Test
    fun `no bookmarks gives nothing rather than everything`() {
        // The failure that would be least visible: a mix that quietly becomes the whole library.
        artist("A", bookmarked = false)
        song("s1", inLibrary = true); by("s1", "A")
        song("s2", inLibrary = true); by("s2", "A")

        assertEquals(emptyList<String>(), result())
    }
}
