/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The lists a radio made on the phone is taken from (RadioSql, LocalRadio), run against the
 * exported schema with foreign keys on, as on the device.
 */
class RadioSqlTest {
    private lateinit var db: Connection
    private var listenId = 0L
    private var edgeId = 0L

    @Before
    fun open() {
        db = SchemaDb.open()
        listOf("seed", "a", "b", "c", "d", "e", "f").forEach { exec("INSERT INTO song(id, title, duration, liked) VALUES ('$it', '$it', 200, 0)") }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Room's named parameters, filled in for JDBC. */
    private fun bind(sql: String, songId: String = "seed", source: Int = 0, limit: Int = 50, now: Long = 0) =
        sql.replace(":songId", "'$songId'").replace(":source", "$source").replace(":limit", "$limit").replace(":now", "$now")

    private fun ids(sql: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    private fun edge(from: String, to: String, source: Int = 0) =
        exec("INSERT INTO related_song_map(id, songId, relatedSongId, fetchedAt, source) VALUES (${++edgeId}, '$from', '$to', 0, $source)")

    private fun listen(song: String, session: Long, playedMs: Long = 120_000, at: Long = session) {
        exec("""INSERT INTO listen(id, songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
            originSlot, queueId, autoplayDepth, sessionId, counted, learn)
            VALUES (${++listenId}, '$song', $at, ${at + playedMs}, 0, $playedMs, 200000, 0.6, 1, 0, -1, 0, 0, $session, 1, 1)""")
    }

    private fun artist(id: String) = exec("INSERT INTO artist(id, name, lastUpdateTime, isLocal) VALUES ('$id', 'Name $id', 0, 0)")
    private fun by(song: String, artist: String) = exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$song', '$artist', 0)")

    @Test
    fun `each source's list for the song, in its own order, each song once`() {
        edge("seed", "c", source = 1); edge("seed", "a", source = 0); edge("seed", "b", source = 1); edge("seed", "c", source = 1)
        edge("a", "d", source = 1)
        assertEquals(listOf("c", "b"), ids(bind(RadioSql.LISTED_FOR, source = 1)))
        assertEquals(listOf("a"), ids(bind(RadioSql.LISTED_FOR, source = 0)))
        assertEquals(listOf("c"), ids(bind(RadioSql.LISTED_FOR, source = 1, limit = 1)))
    }

    @Test
    fun `the songs whose own lists name it, from either source`() {
        edge("b", "seed", source = 0); edge("a", "seed", source = 1); edge("b", "seed", source = 1); edge("c", "d")
        assertEquals(listOf("b", "a"), ids(bind(RadioSql.LISTING)))
    }

    @Test
    fun `one step out, the songs most of its neighbours list first, never the song itself`() {
        edge("seed", "a"); edge("seed", "b")
        edge("a", "e"); edge("a", "seed"); edge("a", "d")
        edge("b", "d"); edge("b", "seed")
        assertEquals(listOf("d", "e"), ids(bind(RadioSql.NEIGHBOURS_LIST)))
    }

    @Test
    fun `the listener's own, what they heard in the sessions they heard it in`() {
        listen("seed", session = 100); listen("a", session = 100); listen("b", session = 100, playedMs = 5_000)
        listen("seed", session = 200); listen("a", session = 200); listen("c", session = 200, at = 250)
        listen("d", session = 300)
        // a shares two sessions, c one; b was only heard for five seconds, d never beside it.
        assertEquals(listOf("a", "c"), ids(bind(RadioSql.HEARD_WITH)))
    }

    @Test
    fun `its artists' songs, the most heard first, then the liked`() {
        artist("x"); artist("y")
        by("seed", "x"); by("a", "x"); by("b", "x"); by("c", "x"); by("d", "y")
        exec("UPDATE song SET liked = 1 WHERE id = 'c'")
        listen("b", session = 1); listen("b", session = 2); listen("a", session = 3, playedMs = 10_000)
        assertEquals(listOf("b", "c", "a"), ids(bind(RadioSql.BY_ITS_ARTISTS)))
    }

    @Test
    fun `only exclusions in force are read`() {
        exec("INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) VALUES (1, 'a', 'a', 1, 0, NULL)")
        exec("INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) VALUES (1, 'b', 'b', 2, 0, 500)")
        exec("INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) VALUES (2, 'x', 'X', 1, 0, 2000)")
        val rows = db.createStatement().use { st ->
            st.executeQuery(bind(RadioSql.EXCLUSIONS_IN_FORCE, now = 1000)).use { rs -> buildList { while (rs.next()) add(rs.getString("targetId")) } }
        }
        assertEquals(listOf("a", "x"), rows)
    }
}
