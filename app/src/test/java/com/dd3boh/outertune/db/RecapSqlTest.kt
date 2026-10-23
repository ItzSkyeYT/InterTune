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
import java.sql.ResultSet

/**
 * The Recap's reads, run on the JVM against the schema Room exported, the same text the DAO runs.
 * Made-up songs and listens only.
 */
class RecapSqlTest {
    private lateinit var db: Connection
    private val t = 1_790_000_000_000L
    private val minute = 60_000L

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, vararg artists: String, liked: Boolean = false) {
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('$id', '$id', 200, ${if (liked) 1 else 0})")
        artists.withIndex().reversed().forEach { (i, a) ->
            exec("INSERT OR IGNORE INTO artist(id, name, lastUpdateTime) VALUES ('$a', '$a', 0)")
            exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$id', '$a', $i)")
        }
    }

    private fun listen(song: String, startedAt: Long, endReason: Int = 1, counted: Boolean = true, chip: Int = 0, playedMs: Long = 3 * minute) {
        val endedAt = if (endReason == 6) 0 else startedAt + playedMs
        exec(
            """INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
                originSlot, queueId, autoplayDepth, sessionId, counted, contextChip)
            VALUES ('$song', $startedAt, $endedAt, 60, $playedMs, 200000, 0.9, $endReason, 2, -1, 0, 0, $startedAt,
                ${if (counted) 1 else 0}, $chip)"""
        )
    }

    private fun <T> query(sql: String, vararg args: Long, row: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setLong(i + 1, v) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    @Test
    fun `listens since a moment with the chip they were started under, and no open rows`() {
        song("a", "UCa")
        listen("a", t - 10 * minute)
        listen("a", t + minute, chip = 2)
        listen("a", t + 10 * minute, endReason = 2, counted = false)
        listen("a", t + 20 * minute, endReason = 6)
        val rows = query(RecapSql.LISTENS, t) { listOf(it.getLong("endReason"), it.getBoolean("counted"), it.getInt("contextChip"), it.getInt("tzOffsetMin")) }
        assertEquals(setOf(listOf(1L, true, 2, 60), listOf(2L, false, 0, 60)), rows.toSet())
        assertEquals(2, rows.size)
    }

    @Test
    fun `every credit of every song heard since a moment, with its place`() {
        song("duo", "UCfirst", "UCsecond")
        song("solo", "UCsolo")
        song("earlier", "UCearlier")
        song("never", "UCnever")
        listen("duo", t)
        listen("solo", t + minute)
        listen("earlier", t - 10 * minute)
        listen("solo", t + 2 * minute, endReason = 6)
        val rows = query(RecapSql.CREDITS, t) { Triple(it.getString("songId"), it.getString("artistId"), it.getInt("position")) }
        assertEquals(setOf(Triple("duo", "UCfirst", 0), Triple("duo", "UCsecond", 1), Triple("solo", "UCsolo", 0)), rows.toSet())
    }

    @Test
    fun `the liked songs among those heard since a moment`() {
        song("loved", "UCa", liked = true)
        song("not", "UCa")
        song("loved earlier", "UCa", liked = true)
        song("loved unheard", "UCa", liked = true)
        listen("loved", t)
        listen("not", t)
        listen("loved earlier", t - 10 * minute)
        assertEquals(listOf("loved"), query(RecapSql.LIKED, t) { it.getString("id") })
    }

    @Test
    fun `how much there is in a stretch of time, counted plays and every second heard`() {
        song("a", "UCa")
        listen("a", t)
        listen("a", t + minute, endReason = 2, counted = false, playedMs = 20_000)
        listen("a", t + 2 * minute, endReason = 6)
        listen("a", t + 60 * minute)
        val (plays, played) = query(RecapSql.AMOUNT, t, t + 30 * minute) { it.getInt("plays") to it.getLong("playedMs") }.single()
        assertEquals(1, plays)
        assertEquals(3 * minute + 20_000, played)
        assertEquals(0 to 0L, query(RecapSql.AMOUNT, 0, 1) { it.getInt("plays") to it.getLong("playedMs") }.single())
    }
}
