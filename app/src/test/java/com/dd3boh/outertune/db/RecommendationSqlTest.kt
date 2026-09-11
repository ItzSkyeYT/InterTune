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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The recommendation SQL, run on the JVM against the schema Room exported for the current database
 * version, with foreign keys enforced as they are on the device.
 *
 * Both queries were broken in ways no amount of looking at the Home screen would reveal, because a
 * wrong recommendation row still looks like a recommendation row. This is where they are pinned.
 */
class RecommendationSqlTest {

    private lateinit var db: Connection
    private val now = 1_757_570_000_000L
    private val day = 86_400_000L

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // The real schema, not an imitation of it: a hand-written CREATE TABLE here would drift from
        // the app the first time a column changed, and the test would go on passing against a
        // database that no longer exists.
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

    private fun song(vararg ids: String) = ids.forEach {
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('$it', '$it', 200, 0)")
    }

    private fun play(id: String, daysAgo: Double, playTimeMs: Long = 200_000) =
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('$id', ${now - (daysAgo * day).toLong()}, $playTimeMs)")

    private fun related(seed: String, vararg to: String) = to.forEach {
        exec("INSERT INTO related_song_map(songId, relatedSongId) VALUES ('$seed', '$it')")
    }

    /** Every :now in a query shares one parameter index in SQLite, so one bind covers them all. */
    private fun ids(sql: String): List<String> = db.prepareStatement(sql).use { ps ->
        ps.setLong(1, now)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    // Quick picks

    /** S1 and S2 are the seeds. X is related to both; Y to S1 and to three songs that are not seeds. */
    private fun quickPicksWorld(yEdgesSeedLast: Boolean) {
        song("S1", "S2", "OLD1", "OLD2", "OLD3", "X", "Y")
        play("S1", daysAgo = 0.1)
        play("S2", daysAgo = 0.05)
        related("S1", "X")
        related("S2", "X")
        val oldEdges = { related("OLD1", "Y"); related("OLD2", "Y"); related("OLD3", "Y") }
        if (yEdgesSeedLast) { oldEdges(); related("S1", "Y") } else { related("S1", "Y"); oldEdges() }
    }

    @Test
    fun `quick picks ranks by how many seeds point at a song`() {
        quickPicksWorld(yEdgesSeedLast = true)
        assertEquals(listOf("X", "Y"), ids(RecommendationSql.QUICK_PICKS))
    }

    @Test
    fun `quick picks does not depend on the order rows were written`() {
        // The inherited query read songId as a bare column in a GROUP BY, which SQLite fills from an
        // arbitrary row. Written one way round it dropped Y; written the other it ranked Y first on
        // a count of four. Identical data has to give an identical row.
        quickPicksWorld(yEdgesSeedLast = true)
        val one = ids(RecommendationSql.QUICK_PICKS)
        close(); open()
        quickPicksWorld(yEdgesSeedLast = false)
        assertEquals(one, ids(RecommendationSql.QUICK_PICKS))
    }

    @Test
    fun `quick picks ignores edges from songs that are not seeds`() {
        song("S1", "OLD1", "C")
        play("S1", daysAgo = 0.1)
        related("OLD1", "C")
        assertFalse("C" in ids(RecommendationSql.QUICK_PICKS))
    }

    @Test
    fun `a pair stored twice counts as one seed`() {
        // Z is related to S1 three times over; W to two different seeds. Two seeds agreeing is the
        // stronger signal. A plain COUNT would score Z three and put it first, so this fails on
        // anything short of counting distinct seeds (the first version stored the pair twice, tied
        // at two, and passed on the broken query by tie-break luck).
        song("S1", "S2", "Z", "W")
        play("S1", daysAgo = 0.1)
        play("S2", daysAgo = 0.05)
        related("S1", "Z", "Z", "Z", "W")
        related("S2", "W")
        assertEquals(listOf("W", "Z"), ids(RecommendationSql.QUICK_PICKS))
    }

    // Forgotten favourites

    private fun loved(id: String, playsBefore: Int, playTimeMs: Long = 210_000) =
        repeat(playsBefore) { play(id, daysAgo = 60.0 + it, playTimeMs = playTimeMs) }

    @Test
    fun `a favourite stopped entirely is found`() {
        // The case the row exists for, and the one the inherited inner join could never return.
        song("FORGOTTEN")
        loved("FORGOTTEN", playsBefore = 10)
        assertEquals(listOf("FORGOTTEN"), ids(RecommendationSql.FORGOTTEN_FAVORITES))
    }

    @Test
    fun `a favourite merely cut back is still found`() {
        song("REDUCED")
        loved("REDUCED", playsBefore = 10)
        play("REDUCED", daysAgo = 7.0, playTimeMs = 40_000)
        assertEquals(listOf("REDUCED"), ids(RecommendationSql.FORGOTTEN_FAVORITES))
    }

    @Test
    fun `something heard once is not a forgotten favourite`() {
        song("ONEOFF")
        play("ONEOFF", daysAgo = 60.0, playTimeMs = 40_000)
        assertTrue(ids(RecommendationSql.FORGOTTEN_FAVORITES).isEmpty())
    }

    @Test
    fun `something still in rotation is not forgotten`() {
        song("CURRENT")
        loved("CURRENT", playsBefore = 5)
        repeat(5) { play("CURRENT", daysAgo = 2.0 + it) }
        assertTrue(ids(RecommendationSql.FORGOTTEN_FAVORITES).isEmpty())
    }

    @Test
    fun `the most loved come first so the limit keeps the best hundred`() {
        song("LIGHT", "HEAVY")
        loved("LIGHT", playsBefore = 3)
        loved("HEAVY", playsBefore = 12)
        assertEquals(listOf("HEAVY", "LIGHT"), ids(RecommendationSql.FORGOTTEN_FAVORITES))
    }
}
