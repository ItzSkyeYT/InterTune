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
 * Forget the last session and Forget today's listening, run against the exported schema. Each
 * says how many listens it forgot, so it must count only the ones it changed: a second tap has
 * nothing left to forget. And the count Your data asks with must be the same rows.
 */
class ForgetSqlTest {
    private lateinit var db: Connection
    private var id = 0

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('a', 'a', 200, 0)")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun listen(sessionId: Long, startedAt: Long, learn: Boolean = true) {
        id++
        exec("""INSERT INTO listen(id, songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
            originSlot, queueId, autoplayDepth, sessionId, counted, learn)
            VALUES ($id, 'a', $startedAt, ${startedAt + 1_000}, 0, 1000, 200000, 0.005, 2, 0, -1, 0, 0, $sessionId, 0, ${if (learn) 1 else 0})""")
    }

    /** Room's named parameters, filled in for JDBC. */
    private fun bind(sql: String, vararg params: Pair<String, Long>) =
        params.fold(sql) { s, (name, value) -> s.replace(":$name", "$value") }

    private fun update(sql: String): Int = db.createStatement().use { it.executeUpdate(sql) }

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
    }

    private fun forgetSession(session: Long) = update(bind(EngineSql.FORGET_SESSION, "sessionId" to session))
    private fun forgettableInSession(session: Long) = count(bind(EngineSql.FORGETTABLE_IN_SESSION, "sessionId" to session))
    private fun forgetBetween(from: Long, to: Long) = update(bind(EngineSql.FORGET_BETWEEN, "from" to from, "to" to to))
    private fun forgettableBetween(from: Long, to: Long) = count(bind(EngineSql.FORGETTABLE_BETWEEN, "from" to from, "to" to to))

    @Test
    fun `forgetting a session counts what it changed, and a second time there is nothing to forget`() {
        listen(sessionId = 1, startedAt = 100)
        listen(sessionId = 1, startedAt = 200)
        listen(sessionId = 1, startedAt = 300, learn = false)   // forgotten already, from its queue
        listen(sessionId = 2, startedAt = 400)                  // another session

        assertEquals(2, forgettableInSession(1))
        assertEquals(2, forgetSession(1))
        assertEquals(0, forgettableInSession(1))
        assertEquals(0, forgetSession(1))
        assertEquals(1, forgettableInSession(2))
    }

    @Test
    fun `forgetting today counts what it changed, and a second time there is nothing to forget`() {
        listen(sessionId = 1, startedAt = 999)                   // yesterday
        listen(sessionId = 1, startedAt = 1_000)                 // midnight
        listen(sessionId = 2, startedAt = 1_500)
        listen(sessionId = 2, startedAt = 1_600, learn = false)
        listen(sessionId = 2, startedAt = 2_000)                 // after the end of the window

        assertEquals(2, forgettableBetween(1_000, 2_000))
        assertEquals(2, forgetBetween(1_000, 2_000))
        assertEquals(0, forgettableBetween(1_000, 2_000))
        assertEquals(0, forgetBetween(1_000, 2_000))
        assertEquals(1, forgettableBetween(0, 1_000))
    }
}
