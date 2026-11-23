/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.RelatedSongMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * ListenBrainz's similar recordings in Last.fm's place (Unreleased.LB_SIMILAR), source 2 of related_song_map: the
 * engine reads them per seed with YouTube's list wherever ListenBrainz has nothing, Both merges them with YouTube's,
 * and Last.fm's lists kept from before are neither read with them nor touched by them. Run against the exported
 * schema with foreign keys on, as RelatedSqlTest runs Last.fm's.
 */
class ListenBrainzSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        listOf("seed", "known", "unknown", "a", "b", "c", "d").forEach { exec("INSERT INTO song(id, title, duration, liked) VALUES ('$it', '$it', 200, 0)") }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun bind(sql: String, songId: String = "", source: Int = 0) =
        sql.replace(":songId", "'$songId'").replace(":source", "$source")

    private fun long(sql: String): Long? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
    }

    private fun edge(seed: String, to: String, source: Int, fetchedAt: Long = 0) =
        exec("INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source) VALUES ('$seed', '$to', $fetchedAt, $source)")

    private fun edgeBits(sql: String): Map<Pair<String, String>, Int> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildMap { while (rs.next()) put(rs.getString("songId") to rs.getString("relatedSongId"), rs.getInt("sources")) } }
    }

    private val all get() = db.createStatement().use { st ->
        st.executeQuery("SELECT songId, relatedSongId, source FROM related_song_map").use { rs ->
            buildSet { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getInt(3))) }
        }
    }

    private val lb = RelatedSongMap.SOURCE_LISTENBRAINZ

    @Test
    fun `ListenBrainz's lists are source 2, apart from YouTube's and Last-fm's`() {
        assertEquals(0, RelatedSongMap.SOURCE_YOUTUBE)
        assertEquals(1, RelatedSongMap.SOURCE_LASTFM)
        assertEquals(2, lb)
    }

    @Test
    fun `the engine reads ListenBrainz's list where it has one, and YouTube's for a seed it has nothing for`() {
        // "known": ListenBrainz knew it. "unknown": it did not, so nothing was written, and YouTube's list stands.
        // "seed" has a Last.fm list from before 1 December: not ListenBrainz's, so YouTube's stands for it too.
        edge("known", "a", 0); edge("known", "b", lb)
        edge("unknown", "c", 0)
        edge("seed", "d", 0); edge("seed", "a", 1)
        assertEquals(
            mapOf(("known" to "b") to 2, ("unknown" to "c") to 1, ("seed" to "d") to 1),
            edgeBits(bind(RelatedSql.ENGINE_EDGES, source = lb)),
        )
        // Without the flag the same query reads Last.fm's, exactly as before: its edge bit 2, YouTube's bit 1.
        assertEquals(
            mapOf(("known" to "a") to 1, ("unknown" to "c") to 1, ("seed" to "a") to 2),
            edgeBits(bind(RelatedSql.ENGINE_EDGES, source = 1)),
        )
        assertEquals(
            mapOf(("known" to "a") to 1, ("unknown" to "c") to 1, ("seed" to "d") to 1),
            edgeBits(bind(RelatedSql.ENGINE_EDGES, source = 0)),
        )
    }

    @Test
    fun `Both merges YouTube's and ListenBrainz's lists, one edge a pair, and leaves Last-fm's out`() {
        edge("known", "a", 0); edge("known", "b", lb); edge("known", "c", 0); edge("known", "c", lb)
        edge("seed", "d", 1); edge("seed", "d", 0)
        edge("unknown", "a", 0); edge("unknown", "a", 0)
        assertEquals(
            mapOf(("known" to "a") to 1, ("known" to "b") to 2, ("known" to "c") to 3, ("seed" to "d") to 1, ("unknown" to "a") to 1),
            edgeBits(RelatedSql.ENGINE_EDGES_ALL_LISTENBRAINZ),
        )
        // And Both without the flag is Last.fm's, as before.
        assertEquals(
            mapOf(("known" to "a") to 1, ("known" to "c") to 1, ("seed" to "d") to 3, ("unknown" to "a") to 1),
            edgeBits(RelatedSql.ENGINE_EDGES_ALL),
        )
    }

    @Test
    fun `a ListenBrainz refresh replaces its own list only, and says when it was fetched`() {
        edge("seed", "a", 0, fetchedAt = 100); edge("seed", "b", 1, fetchedAt = 200); edge("seed", "c", lb, fetchedAt = 300)
        assertEquals(300L, long(bind(RelatedSql.LISTENBRAINZ_FETCHED_AT, "seed")))
        assertEquals(200L, long(bind(RelatedSql.LASTFM_FETCHED_AT, "seed")))
        assertNull(long(bind(RelatedSql.LISTENBRAINZ_FETCHED_AT, "known")))
        exec(bind(RelatedSql.DELETE_LISTENBRAINZ_SIMILAR, "seed"))
        assertEquals(setOf(Triple("seed", "a", 0), Triple("seed", "b", 1)), all)
        // Nor do Last.fm's and YouTube's own refreshes touch ListenBrainz's.
        edge("seed", "c", lb, fetchedAt = 300)
        exec(bind(RelatedSql.DELETE_LASTFM_SIMILAR, "seed"))
        exec(bind(RelatedSql.DELETE_YOUTUBE_RELATED, "seed"))
        assertEquals(setOf(Triple("seed", "c", lb)), all)
    }

    @Test
    fun `a ListenBrainz edge to a song that is gone is skipped, not thrown`() {
        fun insert(to: String) = exec(RelatedSql.INSERT_LISTENBRAINZ_EDGE
            .replace(":songId", "'seed'").replace(":relatedSongId", "'$to'").replace(":fetchedAt", "9"))
        insert("a"); insert("gone")
        assertEquals(setOf(Triple("seed", "a", lb)), all)
        assertEquals(1L, long("SELECT COUNT(*) FROM related_song_map WHERE source = 2 AND fetchedAt = 9"))
    }

    @Test
    fun `a pair ListenBrainz and Last-fm both list survives the duplicate sweep, once each`() {
        edge("seed", "a", lb); edge("seed", "a", lb); edge("seed", "a", 1); edge("seed", "a", 0)
        exec(RelatedSql.DROP_DUPLICATE_EDGES)
        assertEquals(setOf(Triple("seed", "a", 0), Triple("seed", "a", 1), Triple("seed", "a", lb)), all)
        assertEquals(3L, long("SELECT COUNT(*) FROM related_song_map"))
    }

    private fun listen(id: String, startedAt: Long, playedMs: Long = 120_000) = exec(
        "INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin, originSlot, queueId, autoplayDepth, sessionId, counted) " +
            "VALUES ('$id', $startedAt, ${startedAt + playedMs}, 0, $playedMs, 200000, 0.5, 0, 0, -1, 0, 0, 1, 1)")

    private fun catchUp(sql: String, since: Long, limit: Int = 10): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql.replace(":since", "$since").replace(":limit", "$limit"))
            .use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `the catch-up asks ListenBrainz about the same songs as Last-fm's, and a Last-fm list does not count as its`() {
        exec("UPDATE song SET liked = 1, likedDate = 5 WHERE id = 'c'")
        listen("a", 1_000); listen("b", 3_000); listen("seed", 2_000)
        listen("known", 4_000, playedMs = 10_000)  // a skip, not a play
        listen("seed", 100)                          // before the window
        assertEquals(catchUp(RelatedSql.LASTFM_CATCH_UP, since = 500), catchUp(RelatedSql.LISTENBRAINZ_CATCH_UP, since = 500))
        assertEquals(listOf("b", "seed", "a", "c"), catchUp(RelatedSql.LISTENBRAINZ_CATCH_UP, since = 500))
        edge("b", "a", 1)                            // Last.fm's, from before
        edge("seed", "a", 0)                         // YouTube's
        assertEquals(listOf("b", "seed", "a", "c"), catchUp(RelatedSql.LISTENBRAINZ_CATCH_UP, since = 500))
        edge("b", "c", lb)
        assertEquals(listOf("seed", "a", "c"), catchUp(RelatedSql.LISTENBRAINZ_CATCH_UP, since = 500))
        assertEquals(listOf("seed", "a"), catchUp(RelatedSql.LISTENBRAINZ_CATCH_UP, since = 500, limit = 2))
    }
}
