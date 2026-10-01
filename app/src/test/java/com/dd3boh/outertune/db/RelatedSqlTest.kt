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

    private fun edgeBits(sql: String): Map<Pair<String, String>, Int> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildMap { while (rs.next()) put(rs.getString("songId") to rs.getString("relatedSongId"), rs.getInt("sources")) } }
    }

    @Test
    fun `the single-source query marks each edge with its source's bit`() {
        edge("seed", "a", 0); edge("seed", "b", 1); edge("other", "c", 0)
        assertEquals(mapOf(("seed" to "a") to 1, ("other" to "c") to 1), edgeBits(bind(RelatedSql.ENGINE_EDGES, source = 0)))
        // Last.fm's own edge carries bit 2; the fallback to YouTube's list for a seed Last.fm has none for keeps bit 1.
        assertEquals(mapOf(("seed" to "b") to 2, ("other" to "c") to 1), edgeBits(bind(RelatedSql.ENGINE_EDGES, source = 1)))
    }

    @Test
    fun `Both merges the lists into one edge per pair, with a bit for each source that lists it`() {
        edge("seed", "a", 0); edge("seed", "b", 1); edge("seed", "c", 0); edge("seed", "c", 1)
        // Written twice by one source, which the duplicate sweep would remove later: still one edge.
        edge("other", "a", 0); edge("other", "a", 0)
        val bits = edgeBits(RelatedSql.ENGINE_EDGES_ALL)
        assertEquals(mapOf(("seed" to "a") to 1, ("seed" to "b") to 2, ("seed" to "c") to 3, ("other" to "a") to 1), bits)
        val rows = db.createStatement().use { st -> st.executeQuery(RelatedSql.ENGINE_EDGES_ALL).use { rs -> var n = 0; while (rs.next()) n++; n } }
        assertEquals(4, rows)
    }

    @Test
    fun `the share's evidence query returns exactly the qualifying impressions`() {
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds)
            VALUES (1, 1000, 1, 1, 0, 0, 15, 0, '[]', '{}', '')""")
        var id = 0
        fun impression(team: Int = 1, slot: Int = 0, lane: Int = 1, sources: Int = 5, outcome: Int = 3, visibleAt: Long = 5_000): Int {
            id++
            exec("""INSERT INTO impression(id, buildId, songId, slot, lane, team, outcome, visibleAt, y, sources)
                VALUES ($id, 1, 'a', $slot, $lane, $team, $outcome, $visibleAt, 0.5, $sources)""")
            return id
        }
        val wanted = listOf(
            impression(),
            impression(team = 4, lane = 4, sources = 6),
            impression(outcome = 0),
            impression(outcome = 1),
            impression(outcome = 2),
        )
        // Each of these misses the filter by one condition.
        impression(team = 2); impression(team = 3); impression(slot = -1)
        impression(lane = 2); impression(lane = 3); impression(lane = 5)
        impression(sources = 1); impression(sources = 2); impression(sources = 3); impression(sources = 7); impression(sources = 0); impression(sources = 4)
        impression(outcome = 4); impression(outcome = 6); impression(outcome = 7); impression(outcome = 8)
        impression(visibleAt = 3_999)
        // The app's text as it is, and again with the id selected so the rows can be named.
        val sql = com.dd3boh.outertune.engine.EngineSql.SOURCE_EVIDENCE.replace(":since", "4000")
        val count = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> var n = 0; while (rs.next()) n++; n } }
        assertEquals(wanted.size, count)
        val ids = db.createStatement().use { st ->
            st.executeQuery(sql.replace("SELECT songId,", "SELECT id, songId,")).use { rs -> buildSet { while (rs.next()) add(rs.getInt("id")) } }
        }
        assertEquals(wanted.toSet(), ids)
    }

    @Test
    fun `a card marked waiting stays pending and leaves the share's evidence`() {
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds)
            VALUES (1, 1000, 1, 1, 0, 0, 15, 0, '[]', '{}', '')""")
        exec("""INSERT INTO impression(id, buildId, songId, slot, lane, team, outcome, visibleAt, tappedAt, sources)
            VALUES (1, 1, 'a', 0, 1, 1, 0, 5000, 5000, 5)""")
        val evidence = com.dd3boh.outertune.engine.EngineSql.SOURCE_EVIDENCE.replace(":since", "4000")
        fun count() = db.createStatement().use { st -> st.executeQuery(evidence).use { rs -> var n = 0; while (rs.next()) n++; n } }
        val markWaiting = com.dd3boh.outertune.engine.EngineSql.MARK_WAITING.replace(":id", "1")
        // Tapped and not graded yet, the card counts as seen and not heard.
        assertEquals(1, count())
        exec(markWaiting)
        assertEquals(0, count())
        assertEquals(8L, long("SELECT outcome FROM impression WHERE id = 1"))
        assertNull(long("SELECT gradedAt FROM impression WHERE id = 1"))
        // A card already graded keeps its grade.
        exec("UPDATE impression SET outcome = 1, y = 1, gradedAt = 9000 WHERE id = 1")
        exec(markWaiting)
        assertEquals(1L, long("SELECT outcome FROM impression WHERE id = 1"))
    }

    @Test
    fun `marking a card that is already waiting changes no row`() {
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds)
            VALUES (1, 1000, 1, 1, 0, 0, 15, 0, '[]', '{}', '')""")
        exec("""INSERT INTO impression(id, buildId, songId, slot, lane, team, outcome, visibleAt, tappedAt, sources)
            VALUES (1, 1, 'a', 0, 1, 1, 0, 5000, 5000, 5)""")
        val markWaiting = com.dd3boh.outertune.engine.EngineSql.MARK_WAITING.replace(":id", "1")
        fun changed() = db.createStatement().use { it.executeUpdate(markWaiting) }
        assertEquals(1, changed())
        // Marked again, it is not matched. A row matched counts as changed, same values or not, and
        // fires the table's update triggers.
        assertEquals(0, changed())
        assertEquals(8L, long("SELECT outcome FROM impression WHERE id = 1"))
        assertNull(long("SELECT gradedAt FROM impression WHERE id = 1"))
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
