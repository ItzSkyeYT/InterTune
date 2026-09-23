/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * related_song_map holds two sources once Last.fm can be chosen, and every query over it has to
 * keep them apart. Run against the exported schema with foreign keys on, as on the device.
 */
class RelatedSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        listOf("seed", "other", "a", "b", "c").forEach { exec("INSERT INTO song(id, title, duration, liked) VALUES ('$it', '$it', 200, 0)") }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Room's named parameters, filled in for JDBC. */
    private fun bind(sql: String, songId: String = "", source: Int = 0) =
        sql.replace(":songId", "'$songId'").replace(":source", "$source")

    private fun long(sql: String): Long? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
    }

    private fun pairs(sql: String): Set<Pair<String, String>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
    }

    private fun edge(seed: String, to: String, source: Int, fetchedAt: Long = 0) =
        exec("INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source) VALUES ('$seed', '$to', $fetchedAt, $source)")

    private val all get() = pairs("SELECT songId, relatedSongId FROM related_song_map")

    @Test
    fun `Last-fm's edges do not pass for YouTube's list having been fetched`() {
        edge("seed", "a", 1, fetchedAt = 500)
        assertEquals(0L, long(bind(RelatedSql.HAS_YOUTUBE_RELATED, "seed")))
        assertNull(long(bind(RelatedSql.YOUTUBE_FETCHED_AT, "seed")))
        assertEquals(500L, long(bind(RelatedSql.LASTFM_FETCHED_AT, "seed")))
        edge("seed", "b", 0, fetchedAt = 700)
        assertEquals(1L, long(bind(RelatedSql.HAS_YOUTUBE_RELATED, "seed")))
        assertEquals(700L, long(bind(RelatedSql.YOUTUBE_FETCHED_AT, "seed")))
    }

    @Test
    fun `each refresh clears only its own source`() {
        edge("seed", "a", 0); edge("seed", "b", 1)
        exec(bind(RelatedSql.DELETE_YOUTUBE_RELATED, "seed"))
        assertEquals(setOf("seed" to "b"), all)
        edge("seed", "a", 0)
        exec(bind(RelatedSql.DELETE_LASTFM_SIMILAR, "seed"))
        assertEquals(setOf("seed" to "a"), all)
    }

    @Test
    fun `a pair both sources agree on survives the duplicate sweep`() {
        edge("seed", "a", 0); edge("seed", "a", 0); edge("seed", "a", 1); edge("seed", "a", 1)
        exec(RelatedSql.DROP_DUPLICATE_EDGES)
        assertEquals(2L, long("SELECT COUNT(*) FROM related_song_map"))
        assertEquals(2L, long("SELECT COUNT(DISTINCT source) FROM related_song_map"))
    }

    @Test
    fun `the engine reads the chosen source, and YouTube's only for seeds it has nothing for`() {
        edge("seed", "a", 0); edge("seed", "b", 1); edge("other", "c", 0)
        assertEquals(setOf("seed" to "a", "other" to "c"), pairs(bind(RelatedSql.ENGINE_EDGES, source = 0)))
        assertEquals(setOf("seed" to "b", "other" to "c"), pairs(bind(RelatedSql.ENGINE_EDGES, source = 1)))
    }

    @Test
    fun `a Last-fm edge to a song that is gone is skipped, not thrown`() {
        fun insert(to: String) = exec(RelatedSql.INSERT_LASTFM_EDGE
            .replace(":songId", "'seed'").replace(":relatedSongId", "'$to'").replace(":fetchedAt", "9"))
        insert("a"); insert("gone")
        assertEquals(setOf("seed" to "a"), all)
        assertEquals(1L, long("SELECT COUNT(*) FROM related_song_map WHERE source = 1 AND fetchedAt = 9"))
    }

    private fun listen(id: String, startedAt: Long, playedMs: Long = 120_000) = exec(
        "INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin, originSlot, queueId, autoplayDepth, sessionId, counted) " +
            "VALUES ('$id', $startedAt, ${startedAt + playedMs}, 0, $playedMs, 200000, 0.5, 0, 0, -1, 0, 0, 1, 1)")

    private fun catchUp(since: Long, limit: Int = 10): List<String> = db.createStatement().use { st ->
        st.executeQuery(RelatedSql.LASTFM_CATCH_UP.replace(":since", "$since").replace(":limit", "$limit"))
            .use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `the catch-up asks about recent plays latest first, then likes, and nothing already asked`() {
        exec("UPDATE song SET liked = 1, likedDate = 5 WHERE id = 'c'")
        listen("a", 1_000); listen("b", 3_000); listen("seed", 2_000)
        listen("other", 4_000, playedMs = 10_000)  // a skip, not a play
        listen("seed", 100)                          // before the window
        assertEquals(listOf("b", "seed", "a", "c"), catchUp(since = 500))
        edge("b", "a", 1)
        assertEquals(listOf("seed", "a", "c"), catchUp(since = 500))
        edge("seed", "a", 0)                          // YouTube's list is not Last.fm's
        assertEquals(listOf("seed", "a"), catchUp(since = 500, limit = 2))
    }
}
