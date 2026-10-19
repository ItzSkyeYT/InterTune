/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.engine.EngineSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The songs the adaptive queue reads in the service: those the listen log names, the liked ones and
 * the ones an exclusion names, each once with its first artist, and nothing else. Run against the
 * exported schema, as on the device.
 */
class HeardSongsSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, liked: Boolean = false) = exec("INSERT INTO song(id, title, duration, liked) VALUES ('$id', 'Title $id', 200, ${if (liked) 1 else 0})")

    private fun artist(id: String) = exec("INSERT INTO artist(id, name, lastUpdateTime, isLocal) VALUES ('$id', 'Name $id', 0, 0)")

    private fun by(songId: String, artistId: String, position: Int) =
        exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$songId', '$artistId', $position)")

    private var listenId = 0L
    private fun listen(songId: String) {
        listenId++
        exec("""INSERT INTO listen(id, songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
            originSlot, queueId, autoplayDepth, sessionId, counted, learn, runId)
            VALUES ($listenId, '$songId', $listenId, ${listenId + 1}, 0, 1000, 200000, 0.5, 1, 0, -1, 0, 0, 1, 0, 1, 0)""")
    }

    private fun rows(): List<Triple<String, String?, String?>> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.SONGS_HEARD_OR_LIKED).use { rs ->
            buildList { while (rs.next()) add(Triple(rs.getString("id"), rs.getString("artistId"), rs.getString("artistName"))) }
        }
    }

    @Test
    fun `the heard, the liked and the excluded songs, each once with its first artist`() {
        listOf("heard", "twice", "liked", "banned", "never").forEach { song(it, liked = it == "liked") }
        listOf("first", "second").forEach(::artist)
        by("heard", "second", 1)
        by("heard", "first", 0)
        by("twice", "second", 0)
        listen("heard")
        listen("twice")
        listen("twice")
        exec("INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) VALUES (1, 'banned', 'Title banned', 1, 0, NULL)")
        // An artist's exclusion names an artist, not a song.
        exec("INSERT INTO recommendation_exclusion(kind, targetId, label, reason, createdAt, expiresAt) VALUES (2, 'never', 'Name never', 1, 0, NULL)")

        val got = rows().sortedBy { it.first }
        assertEquals(listOf("banned", "heard", "liked", "twice"), got.map { it.first })
        assertEquals("first" to "Name first", got.first { it.first == "heard" }.let { it.second to it.third })
        assertEquals("second", got.first { it.first == "twice" }.second)
        assertEquals(null, got.first { it.first == "liked" }.second)
    }
}
