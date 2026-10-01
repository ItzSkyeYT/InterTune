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

    // Linking a resume

    @Test
    fun `a failed play can be continued, as a stopped one can`() {
        val failed = listen("s", EndReason.ERROR)
        assertEquals(failed to EndReason.ERROR, lastResumable("s"))
        val stopped = listen("s", EndReason.STOPPED, startedAt = t + 10 * minute)
        assertEquals(stopped to EndReason.STOPPED, lastResumable("s"))
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
}
