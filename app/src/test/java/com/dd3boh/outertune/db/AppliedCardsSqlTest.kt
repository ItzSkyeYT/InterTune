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
 * The cards Your data says it learned from and rebuilt from: what a rebuild replays, less the pool
 * picks, which were never on screen and which no figure on How it's doing counts as a card.
 */
class AppliedCardsSqlTest {
    private lateinit var db: Connection
    private var id = 0

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('a', 'a', 200, 0)")
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds)
            VALUES (1, 500, 1, 1, 0, 0, 15, 0, '[]', '{}', '')""")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun impression(slot: Int, applied: Boolean, u: Double, features: Boolean = true) {
        id++
        exec("""INSERT INTO impression(id, buildId, songId, slot, team, outcome, visibleAt, features, y, u, gradedAt, appliedAt)
            VALUES ($id, 1, 'a', $slot, 1, 3, 1000, ${if (features) "'0.1,0.2'" else "NULL"}, 0, $u, 2000, ${if (applied) 3000 + id else "NULL"})""")
    }

    private fun count(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> var n = 0; while (rs.next()) n++; n }
    }

    private fun scalar(sql: String): Int = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
    }

    @Test
    fun `the cards it learned from are the replayed examples on screen`() {
        impression(slot = 0, applied = true, u = 1.0)        // played
        impression(slot = 3, applied = true, u = 0.3)        // passed over
        impression(slot = -1, applied = true, u = 0.5)       // a pool pick, never on screen
        impression(slot = 2, applied = true, u = 0.0)        // forgotten: weighs nothing
        impression(slot = 4, applied = false, u = 0.3)       // judged, waiting for a later day
        impression(slot = 5, applied = true, u = 1.0, features = false)   // another source's card

        assertEquals(3, count(EngineSql.APPLIED_EXAMPLES))
        assertEquals(2, scalar(EngineSql.APPLIED_CARDS))
    }
}
