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
import org.junit.Assert.assertTrue
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
    fun `only songs by bookmarked artists`() {
        artist("FAVE", bookmarked = true)
        artist("OTHER", bookmarked = false)

        song("keep", inLibrary = true); by("keep", "FAVE")
        song("notFavourite", inLibrary = true); by("notFavourite", "OTHER")
        song("orphan", inLibrary = true)

        assertEquals(listOf("keep"), result())
    }

    @Test
    fun `a song never added to the library still counts`() {
        // The bug this pins. Restricting to inLibrary read as obviously right and was measured
        // wrong on a real device: 279 songs by bookmarked artists, 13 of them marked inLibrary,
        // and four of the ten artists reduced to nothing. The bookmark is the choice; whether a
        // given track also picked up an inLibrary flag is not.
        artist("FAVE", bookmarked = true)
        song("browsedOnly", inLibrary = false); by("browsedOnly", "FAVE")
        song("added", inLibrary = true); by("added", "FAVE")

        assertEquals(setOf("browsedOnly", "added"), result().toSet())
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

    // The guests: songs YouTube lists beside the favourites' songs

    private val now = 1_000_000L

    /** YouTube's related shelf for [seed] names [related], or Last.fm's similar tracks with source 1. */
    private fun related(seed: String, related: String, source: Int = 0) = exec(
        "INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source) VALUES ('$seed', '$related', 1, $source)"
    )

    /** Kind 1 is a song and 2 an artist. No expiry is a ban, and a snooze or a rest has one. */
    private fun exclude(kind: Int, targetId: String, expiresAt: Long? = null) = exec(
        "INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) " +
                "VALUES ($kind, '$targetId', '$targetId', 1, 0, ${expiresAt ?: "NULL"})"
    )

    /** Each guest with how many of the favourites' songs list it, in the order the query gives them. */
    private fun similar(limit: Int = 100): List<Pair<String, Int>> =
        db.prepareStatement(FavouritesSql.SIMILAR_TO_BOOKMARKED_ARTISTS).use { ps ->
            ps.setLong(1, now)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id") to rs.getInt("refs")) } }
        }

    /** Two favourites with two songs each, and three artists nobody bookmarked. */
    private fun library() {
        artist("FAVE", bookmarked = true); artist("FAVE2", bookmarked = true)
        artist("NEAR", bookmarked = false); artist("FAR", bookmarked = false); artist("ELSE", bookmarked = false)
        song("f1", inLibrary = true); by("f1", "FAVE")
        song("f2", inLibrary = true); by("f2", "FAVE")
        song("g1", inLibrary = true); by("g1", "FAVE2")
        song("near1", inLibrary = false); by("near1", "NEAR")
        song("near2", inLibrary = false); by("near2", "NEAR")
        song("far1", inLibrary = false); by("far1", "FAR")
        song("else1", inLibrary = false); by("else1", "ELSE")
    }

    @Test
    fun `what the favourites' songs list is a guest, with how many of them list it`() {
        library()
        related("f1", "near1"); related("f2", "near1"); related("g1", "near1")
        related("f1", "far1")

        assertEquals(listOf("near1" to 3, "far1" to 1), similar())
    }

    @Test
    fun `a song listed by nothing of the favourites' is not a guest`() {
        library()
        // else1 is known and near2 is listed, but by a song that is not a favourite's.
        related("else1", "near2")
        related("f1", "near1")

        assertEquals(listOf("near1" to 1), similar())
    }

    @Test
    fun `a favourite's own song is never handed over as similar`() {
        library()
        // YouTube lists one favourite's songs beside another's all the time.
        related("f1", "f2"); related("f1", "g1"); related("g1", "f1")
        related("f1", "near1")

        assertEquals(listOf("near1" to 1), similar())
    }

    @Test
    fun `nor is a song a favourite is billed second on`() {
        library()
        song("feat", inLibrary = false)
        by("feat", "NEAR", position = 0)
        by("feat", "FAVE2", position = 1)
        related("f1", "feat"); related("f1", "near1")

        assertEquals(listOf("near1" to 1), similar())
    }

    @Test
    fun `the same pair stored twice counts once`() {
        library()
        related("f1", "near1"); related("f1", "near1")

        assertEquals(listOf("near1" to 1), similar())
    }

    @Test
    fun `a song by two favourites that lists a guest counts once`() {
        library()
        song("duet", inLibrary = true)
        by("duet", "FAVE", position = 0)
        by("duet", "FAVE2", position = 1)
        related("duet", "near1"); related("f1", "near1")

        assertEquals(listOf("near1" to 2), similar())
    }

    @Test
    fun `only YouTube's lists count, not Last_fm's`() {
        library()
        related("f1", "near1", source = 1)
        related("f1", "far1")

        assertEquals(listOf("far1" to 1), similar())
    }

    @Test
    fun `a banned song or artist is not invited, and a snooze that has run out is forgotten`() {
        library()
        related("f1", "near1"); related("f1", "near2"); related("f1", "far1"); related("f1", "else1")
        exclude(kind = 1, targetId = "near1")
        exclude(kind = 2, targetId = "FAR")
        exclude(kind = 2, targetId = "ELSE", expiresAt = now - 1)

        assertEquals(setOf("near2", "else1"), similar().map { it.first }.toSet())

        // A snooze still running keeps its artist out as a ban does.
        exec("UPDATE recommendation_exclusion SET expiresAt = ${now + 1} WHERE targetId = 'ELSE'")
        assertEquals(listOf("near2"), similar().map { it.first })
    }

    @Test
    fun `the cut keeps the songs listed most, and falls the same way on a tie`() {
        library()
        related("f1", "near1"); related("f2", "near1")
        related("f1", "near2"); related("f1", "far1"); related("f1", "else1")

        assertEquals(listOf("near1" to 2, "else1" to 1), similar(limit = 2))
    }

    @Test
    fun `the lists are reached from the favourites, never by reading every list there is`() {
        // The answer is the same either way, so nothing else here would notice. Left to choose,
        // SQLite walked all of related_song_map and looked up the artists of every edge: 1.4
        // seconds against a real library, where starting from the bookmarked artists takes 12 ms.
        val plan = db.prepareStatement("EXPLAIN QUERY PLAN " + FavouritesSql.SIMILAR_TO_BOOKMARKED_ARTISTS).use { ps ->
            ps.setLong(1, now)
            ps.setInt(2, 100)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
        }
        // "SCAN r" in a newer SQLite and "SCAN TABLE related_song_map AS r" in an older one.
        val edges = Regex("""\br\b""")
        val steps = plan.joinToString("\n")

        assertTrue("the edges are scanned:\n$steps", plan.none { it.startsWith("SCAN") && edges.containsMatchIn(it) })
        assertTrue(
            "the edges are not looked up by the song they belong to:\n$steps",
            plan.any { it.startsWith("SEARCH") && edges.containsMatchIn(it) && "songId" in it }
        )
        assertTrue("it does not start from the artists:\n$steps", plan.any { it.startsWith("SCAN") && "fave" in it })
    }

    @Test
    fun `with nothing bookmarked nobody is a guest`() {
        library()
        related("f1", "near1")
        exec("UPDATE artist SET bookmarkedAt = NULL")

        assertEquals(emptyList<Pair<String, Int>>(), similar())
    }
}
