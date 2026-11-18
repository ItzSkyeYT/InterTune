/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.dd3boh.outertune.db.MusicDatabase.Companion.MUSIC_DATABASE_VERSION
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Bringing a Metrolist library over (MetrolistImport), on real databases built from both apps' own exported schemas:
 * ours at the current version, Metrolist's Android app at 36 and its new app at 65 (their schemas are kept in
 * src/test/resources/metrolist, from Metrolist's repository, GPL-3.0 like ours). One backup holds every case a rule is
 * for, and is brought over twice: the second time must change nothing.
 */
class MetrolistImportTest {

    private val open = ArrayList<Connection>()

    @After
    fun close() = open.forEach { it.close() }

    /** A database with [schemaFile]'s tables, as Room would make them, at [version]. */
    private fun database(schemaFile: String, version: Int): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        open += c
        val schema = Json.parseToJsonElement(File(schemaFile).readText()).jsonObject["database"]!!.jsonObject
        c.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    st.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            st.execute("PRAGMA user_version = $version")
        }
        fields[c] = schema["entities"]!!.jsonArray.associate { e ->
            e.jsonObject["tableName"]!!.jsonPrimitive.content to e.jsonObject["fields"]!!.jsonArray.map { it.jsonObject }
        }
        return c
    }

    private val fields = HashMap<Connection, Map<String, List<JsonObject>>>()

    private fun ours() = database("schemas/com.dd3boh.outertune.db.InternalDatabase/$MUSIC_DATABASE_VERSION.json", MUSIC_DATABASE_VERSION)
    private fun theirs(version: Int) = database("src/test/resources/metrolist/schema-$version.json", version)

    /** A row with [values], every other column that must have a value given an empty one of its kind. */
    private fun Connection.row(table: String, vararg values: Pair<String, Any?>) {
        val given = values.toMap()
        val filled = LinkedHashMap<String, Any?>(given)
        for (f in fields[this]!![table]!!) {
            val name = f["columnName"]!!.jsonPrimitive.content
            if (name in filled || f["notNull"]?.jsonPrimitive?.boolean != true || f["defaultValue"] != null) continue
            filled[name] = if (f["affinity"]!!.jsonPrimitive.content == "TEXT") "" else 0
        }
        // An id Room counts up by itself is left to SQLite when it is not given.
        if ("id" !in given && fields[this]!![table]!!.any { it["columnName"]!!.jsonPrimitive.content == "id" && it["affinity"]!!.jsonPrimitive.content == "INTEGER" }) filled.remove("id")
        val columns = filled.keys.toList()
        prepareStatement("INSERT INTO \"$table\" (${columns.joinToString(",") { "\"$it\"" }}) VALUES (${columns.joinToString(",") { "?" }})").use { st ->
            columns.forEachIndexed { i, c -> st.setObject(i + 1, filled[c]) }
            st.executeUpdate()
        }
    }

    private fun Connection.one(sql: String): Any? = createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getObject(1) else null } }
    private fun Connection.all(sql: String): List<String> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private class JdbcDb(val c: Connection) : MetrolistImport.Db {
        override fun tables() = c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }

        override fun columns(table: String) = c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(\"$table\")").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }

        override fun userVersion() = c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

        override fun query(sql: String, args: List<Any?>, row: (List<Any?>) -> Unit) {
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    val n = rs.metaData.columnCount
                    while (rs.next()) row(List(n) { rs.getObject(it + 1) })
                }
            }
        }

        override fun exec(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    /** One Metrolist backup with a row for every rule, and ours with what it must meet. */
    private fun backup(version: Int): Pair<Connection, Connection> {
        val m = theirs(version)
        m.row("artist", "id" to "A1", "name" to "DJ Snake", "lastUpdateTime" to 1L)
        m.row("artist", "id" to "A2", "name" to "Daft Punk", "lastUpdateTime" to 1L, "bookmarkedAt" to 5_000L)
        m.row("album", "id" to "AL1", "title" to "Encore", "songCount" to 1, "duration" to 200, "lastUpdateTime" to 1L, "bookmarkedAt" to 6_000L)
        m.row("song", "id" to "S1", "title" to "Lean On", "duration" to 176, "liked" to 1, "likedDate" to 1_000L, "inLibrary" to 1_000L)
        m.row("song", "id" to "S2", "title" to "One More Time", "duration" to 320, "inLibrary" to 2_000L)
        m.row("song", "id" to "S3", "title" to "Only played", "duration" to 100)
        m.row("song", "id" to "S4", "title" to "Liked there", "duration" to 100, "liked" to 1, "likedDate" to 4_000L)
        m.row("song", "id" to "S5", "title" to "In both libraries", "duration" to 100, "inLibrary" to 9_999L)
        m.row("song_artist_map", "songId" to "S1", "artistId" to "A1", "position" to 0)
        m.row("song_artist_map", "songId" to "S2", "artistId" to "A2", "position" to 0)
        m.row("song_album_map", "songId" to "S1", "albumId" to "AL1", "index" to 0)
        m.row("lyrics", "id" to "S1", "lyrics" to "[00:01.00] lean on")
        m.row("playlist", "id" to "P1", "name" to "Mine there")
        m.row("playlist", "id" to "P2", "name" to "Followed on YouTube", "browseId" to "VLPLabc")
        m.row("playlist", "id" to "P3", "name" to "Same id as ours")
        m.row("playlist_song_map", "playlistId" to "P1", "songId" to "S2", "position" to 1)
        m.row("playlist_song_map", "playlistId" to "P1", "songId" to "S1", "position" to 0)
        m.row("playlist_song_map", "playlistId" to "P2", "songId" to "S1", "position" to 0)
        m.row("event", "songId" to "S1", "timestamp" to 10_000L, "playTime" to 176_000L)
        m.row("event", "songId" to "S2", "timestamp" to 20_000L, "playTime" to 320_000L)
        m.row("event", "songId" to "S4", "timestamp" to 30_000L, "playTime" to 100_000L)
        if (version >= 51) m.row("event", "songId" to "S3", "timestamp" to 40_000L, "playTime" to 0L, "source" to "YOUTUBE")
        m.row("playCount", "song" to "S1", "year" to 2026, "month" to 11, "count" to 5)
        m.row("playCount", "song" to "S4", "year" to 2026, "month" to 11, "count" to 2)
        m.row("search_history", "query" to "lean on")
        m.row("search_history", "query" to "daft punk")

        val o = ours()
        o.row("song", "id" to "S4", "title" to "Liked there", "duration" to 100, "liked" to 0)
        o.row("song", "id" to "S5", "title" to "In both libraries", "duration" to 100, "inLibrary" to 111L)
        o.row("playlist", "id" to "P9", "name" to "Followed here", "browseId" to "VLPLabc")
        o.row("playlist", "id" to "P3", "name" to "Ours")
        o.row("event", "songId" to "S4", "timestamp" to 30_000L, "playTime" to 100_000L)
        o.row("playCount", "song" to "S4", "year" to 2026, "month" to 11, "count" to 7)
        o.row("search_history", "query" to "daft punk")
        return m to o
    }

    private fun bringsEverythingOverOnce(version: Int) {
        val (m, o) = backup(version)
        val theirs = JdbcDb(m)
        val ours = JdbcDb(o)
        assertNull("a Metrolist database", MetrolistImport.refusal(theirs))
        assertEquals(MetrolistImport.Summary(inLibrary = 3, liked = 2, playlists = 3, plays = 3), MetrolistImport.summary(theirs))

        val first = MetrolistImport.merge(theirs, ours)
        assertEquals(MetrolistImport.Result(songs = 3, liked = 2, inLibrary = 2, playlists = 1, plays = 2), first)

        // Songs: new ones as they were there, ours liked and added where theirs were, ours kept otherwise.
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5"), o.all("SELECT id FROM song ORDER BY id"))
        assertEquals(1, o.one("SELECT liked FROM song WHERE id = 'S1'"))
        assertEquals(2_000L, (o.one("SELECT inLibrary FROM song WHERE id = 'S2'") as Number).toLong())
        assertNull(o.one("SELECT inLibrary FROM song WHERE id = 'S3'"))
        assertEquals(1, o.one("SELECT liked FROM song WHERE id = 'S4'"))
        assertEquals(4_000L, (o.one("SELECT likedDate FROM song WHERE id = 'S4'") as Number).toLong())
        assertEquals("our date stays", 111L, (o.one("SELECT inLibrary FROM song WHERE id = 'S5'") as Number).toLong())
        // Artists, albums and what joins them; saved ones saved.
        assertEquals(listOf("A1", "A2"), o.all("SELECT id FROM artist ORDER BY id"))
        assertEquals(5_000L, (o.one("SELECT bookmarkedAt FROM artist WHERE id = 'A2'") as Number).toLong())
        assertEquals(6_000L, (o.one("SELECT bookmarkedAt FROM album WHERE id = 'AL1'") as Number).toLong())
        assertEquals("A1", o.one("SELECT artistId FROM song_artist_map WHERE songId = 'S1'"))
        assertEquals("AL1", o.one("SELECT albumId FROM song_album_map WHERE songId = 'S1'"))
        assertEquals("[00:01.00] lean on", o.one("SELECT lyrics FROM lyrics WHERE id = 'S1'"))
        // Playlists: theirs made here with its songs in order; the one we follow on YouTube and the one with our id not again.
        assertEquals(listOf("Followed here", "Mine there", "Ours"), o.all("SELECT name FROM playlist ORDER BY name"))
        assertEquals(listOf("S1", "S2"), o.all("SELECT songId FROM playlist_song_map WHERE playlistId = 'P1' ORDER BY position"))
        assertEquals(0, o.one("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId IN ('P2', 'P3', 'P9')"))
        // Plays: theirs once, not the one we have, not YouTube's history; play counts the larger of the two.
        assertEquals(listOf("S1", "S2", "S4"), o.all("SELECT songId FROM event ORDER BY timestamp"))
        assertEquals(5, o.one("SELECT count FROM playCount WHERE song = 'S1'"))
        assertEquals(7, o.one("SELECT count FROM playCount WHERE song = 'S4'"))
        assertEquals(listOf("daft punk", "lean on"), o.all("SELECT query FROM search_history ORDER BY query"))
        assertTrue("every row has its parent", o.all("PRAGMA foreign_key_check").isEmpty())

        val second = MetrolistImport.merge(theirs, ours)
        assertEquals("the same backup twice changes nothing", MetrolistImport.Result(0, 0, 0, 0, 0), second)
        assertEquals(listOf("S1", "S2"), o.all("SELECT songId FROM playlist_song_map WHERE playlistId = 'P1' ORDER BY position"))
    }

    @Test
    fun `a backup from Metrolist's Android app comes over once, and ours stays`() = bringsEverythingOverOnce(36)

    @Test
    fun `a backup from Metrolist's new app comes over once, and its YouTube history stays out`() = bringsEverythingOverOnce(65)

    @Test
    fun `the new app's liked songs in its settings come over, newest first`() {
        val m = theirs(65)
        m.row("song", "id" to "S3", "title" to "Only played", "duration" to 100)
        val o = ours()
        val songs = """[{"id":"S6","title":"Only in the list","thumbnail":"https://i.ytimg.com/s6.jpg","artists":[{"id":"A3","name":"Justice"},{"id":null,"name":"A guest"}],"album":{"id":"AL9","name":"Cross"},"duration":245},""" +
                """{"id":"S3","title":"Only played","thumbnail":null,"artists":[],"album":null,"duration":100},{"title":"no id"}]"""
        val settings = """{"formatVersion":1,"entries":[{"key":"theme","type":"STRING","value":"dark"},""" +
                """{"key":"likedSongsJson","type":"STRING","value":${Json.encodeToString(String.serializer(), songs)}},""" +
                """{"key":"likedSongsRecencyJson","type":"STRING","value":${Json.encodeToString(String.serializer(), "[\"S3\",\"S6\"]")}}]}"""
        val liked = MetrolistImport.likedFromSettings(settings)
        assertEquals("newest first, as their own list has them", listOf("S3", "S6"), liked.map { it.id })
        assertEquals(listOf<Pair<String?, String>>("A3" to "Justice", null to "A guest"), liked[1].artists)
        assertEquals(2, MetrolistImport.summary(JdbcDb(m), liked).liked)

        val result = MetrolistImport.merge(JdbcDb(m), JdbcDb(o), liked, now = 1_000_000L)
        assertEquals(2, result.liked)
        assertEquals(1_000_000L, (o.one("SELECT likedDate FROM song WHERE id = 'S3'") as Number).toLong())
        assertEquals(999_000L, (o.one("SELECT likedDate FROM song WHERE id = 'S6'") as Number).toLong())
        assertEquals("Cross", o.one("SELECT albumName FROM song WHERE id = 'S6'"))
        assertEquals(245L, (o.one("SELECT duration FROM song WHERE id = 'S6'") as Number).toLong())
        assertEquals(listOf("A3"), o.all("SELECT artistId FROM song_artist_map WHERE songId = 'S6'"))
        assertEquals("an artist without an id is not made up", listOf("A3"), o.all("SELECT id FROM artist"))
        assertEquals(emptyList<MetrolistImport.LikedSong>(), MetrolistImport.likedFromSettings("not json"))
        assertEquals(emptyList<MetrolistImport.LikedSong>(), MetrolistImport.likedFromSettings(null))
    }

    @Test
    fun `our own backups, InnerTune's and other files are refused, each with its reason`() {
        assertEquals(MetrolistImport.Refusal.OURS, MetrolistImport.refusal(JdbcDb(ours())))
        val old = theirs(36)
        old.createStatement().use { it.execute("PRAGMA user_version = 13") }
        assertEquals(MetrolistImport.Refusal.TOO_OLD, MetrolistImport.refusal(JdbcDb(old)))
        val other = DriverManager.getConnection("jdbc:sqlite::memory:").also { open += it }
        other.createStatement().use { it.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY)") }
        assertEquals(MetrolistImport.Refusal.NOT_METROLIST, MetrolistImport.refusal(JdbcDb(other)))
    }
}
