/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.SongsDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/** SongsDao's own queries, run against the exported schema as on the device. */
class SongsSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String) = exec(
        "INSERT INTO song(id, title, duration, liked) VALUES ('$id', '$id', 200, 0)"
    )

    private fun event(song: String, at: Long, playTime: Long) =
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('$song', $at, $playTime)")

    private fun mostPlayedSongIds(fromTimeStamp: Long, limit: Int = 6, offset: Int = 0): List<String> =
        db.prepareStatement(SongsDao.MOST_PLAYED_SONGS).use { ps ->
            ps.setLong(1, fromTimeStamp)
            ps.setLong(2, limit.toLong())
            ps.setLong(3, offset.toLong())
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    @Test
    fun `most played songs come back most played first`() {
        // Inserted in id order, played in the reverse of it, so a query that just walked the
        // primary key would give aaa first when ggg is the one actually played the most.
        val minute = 60_000L
        for (id in listOf("aaa", "bbb", "ccc", "eee", "fff", "ggg")) song(id)
        event("aaa", 0, playTime = 1 * minute)
        event("bbb", 0, playTime = 2 * minute)
        event("ccc", 0, playTime = 3 * minute)
        event("eee", 0, playTime = 4 * minute)
        event("fff", 0, playTime = 5 * minute)
        event("ggg", 0, playTime = 6 * minute)

        assertEquals(
            listOf("ggg", "fff", "eee", "ccc", "bbb", "aaa"),
            mostPlayedSongIds(fromTimeStamp = -1L),
        )
    }

    @Test
    fun `a song's plays across several events are summed before ranking`() {
        song("scattered")
        song("one-shot")
        // Three small plays should still outrank one bigger one.
        event("scattered", 0, playTime = 40_000L)
        event("scattered", 1, playTime = 40_000L)
        event("scattered", 2, playTime = 40_000L)
        event("one-shot", 0, playTime = 100_000L)
        assertEquals(listOf("scattered", "one-shot"), mostPlayedSongIds(fromTimeStamp = -1L))
    }

    @Test
    fun `limit and offset page through the ranked order`() {
        val minute = 60_000L
        for ((id, minutes) in listOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L)) {
            song(id)
            event(id, 0, playTime = minutes * minute)
        }
        assertEquals(listOf("d", "c"), mostPlayedSongIds(fromTimeStamp = -1L, limit = 2, offset = 0))
        assertEquals(listOf("b", "a"), mostPlayedSongIds(fromTimeStamp = -1L, limit = 2, offset = 2))
    }
}
