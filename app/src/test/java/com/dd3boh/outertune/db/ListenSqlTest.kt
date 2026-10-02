/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.constants.EndReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The listen log reads that decide what a play is, run on the JVM against the schema Room exported,
 * the same text ListenDao runs.
 */
class ListenSqlTest {
    private lateinit var db: Connection
    private val t = 1_790_000_000_000L
    private val minute = 60_000L

    @Before
    fun open() {
        db = SchemaDb.open()
        listOf("s", "other").forEach { exec("INSERT INTO song(id, title, duration, liked) VALUES ('$it', '$it', 200, 0)") }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun listen(
        song: String,
        endReason: Int,
        startedAt: Long = t,
        playedMs: Long = 3 * minute,
        durationMs: Long = 200_000,
        sessionId: Long = 1,
    ): Long {
        exec(
            """INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
                originSlot, queueId, autoplayDepth, sessionId, counted, endPositionMs)
            VALUES ('$song', $startedAt, ${startedAt + playedMs}, 120, $playedMs, $durationMs, 0.5, $endReason, 2, -1, 0, 0, $sessionId, 0, 83000)"""
        )
        return db.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    /** Room's named parameter, filled in for JDBC. */
    private fun lastResumable(song: String): Pair<Long, Int>? = db.createStatement().use { st ->
        st.executeQuery(ListenSql.LAST_RESUMABLE.replace(":songId", "'$song'")).use { rs ->
            if (rs.next()) rs.getLong("id") to rs.getInt("endReason") else null
        }
    }

    private fun justPlayed(dayAgo: Long, sessionId: Long): Set<String> = db.createStatement().use { st ->
        st.executeQuery(ListenSql.JUST_PLAYED.replace(":dayAgo", "$dayAgo").replace(":sessionId", "$sessionId")).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("id")) }
        }
    }

    // Linking a resume

    @Test
    fun `a failed play can be continued, as a stopped one can`() {
        val failed = listen("s", EndReason.ERROR)
        assertEquals(failed to EndReason.ERROR, lastResumable("s"))
        val stopped = listen("s", EndReason.STOPPED, startedAt = t + 10 * minute)
        assertEquals(stopped to EndReason.STOPPED, lastResumable("s"))
    }

    @Test
    fun `a stop that was already carried on is not offered again`() {
        // One real library: a play stopped near its start became the parent of every play of the
        // song from the top for a day, so separate plays were read as one.
        val stopped = listen("s", EndReason.STOPPED)
        val resumed = listen("s", EndReason.ENDED, startedAt = t + minute)
        exec("UPDATE listen SET continuesListenId = $stopped WHERE id = $resumed")
        assertNull(lastResumable("s"))
    }

    @Test
    fun `only the song's latest play can be carried on`() {
        listen("s", EndReason.STOPPED)
        listen("s", EndReason.ENDED, startedAt = t + minute)
        assertNull(lastResumable("s"))
    }

    @Test
    fun `a resume that stopped again can be carried on itself`() {
        val first = listen("s", EndReason.STOPPED)
        val second = listen("s", EndReason.STOPPED, startedAt = t + minute)
        exec("UPDATE listen SET continuesListenId = $first WHERE id = $second")
        assertEquals(second to EndReason.STOPPED, lastResumable("s"))
    }

    @Test
    fun `the latest of them, a newer open play included, and only this song's`() {
        listen("s", EndReason.STOPPED)
        listen("s", EndReason.ERROR, startedAt = t + minute)
        val open = listen("s", EndReason.OPEN, startedAt = t + 2 * minute)
        listen("other", EndReason.ERROR, startedAt = t + 3 * minute)
        assertEquals(open to EndReason.OPEN, lastResumable("s"))
    }

    @Test
    fun `a play that ended, was skipped or was replaced is never one to continue`() {
        listen("s", EndReason.ENDED)
        listen("s", EndReason.SKIPPED)
        listen("s", EndReason.REPLACED)
        listen("s", EndReason.UNKNOWN)
        assertNull(lastResumable("s"))
    }

    // Just played, for the Tidy pass

    @Test
    fun `a failed play is not just played, whatever share of it played`() {
        listen("s", EndReason.ERROR, startedAt = t, playedMs = 190_000, sessionId = 7)
        assertEquals(emptySet<String>(), justPlayed(dayAgo = t - 1, sessionId = 7))
        assertEquals(emptySet<String>(), justPlayed(dayAgo = t - 1, sessionId = 8))
    }

    @Test
    fun `a play heard well in the day, or anything started this session, still is`() {
        listen("s", EndReason.SKIPPED, startedAt = t, playedMs = 100_000, sessionId = 7)    // half of 200 s
        listen("other", EndReason.SKIPPED, startedAt = t, playedMs = 5_000, sessionId = 8)  // a glance, this session
        assertEquals(setOf("s"), justPlayed(dayAgo = t - 1, sessionId = 1))
        assertEquals(setOf("s", "other"), justPlayed(dayAgo = t - 1, sessionId = 8))
        // Too long ago, and in no session that is going on.
        assertEquals(emptySet<String>(), justPlayed(dayAgo = t + 1, sessionId = 1))
    }
}
