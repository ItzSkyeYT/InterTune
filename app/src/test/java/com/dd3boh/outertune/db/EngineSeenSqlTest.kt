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
 * The cards a build counts as seen: passed over, and tapped and left at once. A card tapped and
 * abandoned in seconds was not counted and came back the next day as if never shown.
 */
class EngineSeenSqlTest {
    private lateinit var db: Connection
    private var id = 0

    @Before
    fun open() {
        db = SchemaDb.open()
        for (s in listOf("passed", "left", "heard", "waiting", "old")) exec("INSERT INTO song(id, title, duration, liked) VALUES ('$s', '$s', 200, 0)")
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds)
            VALUES (1, 500, 1, 1, 0, 0, 15, 0, '[]', '{}', '')""")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun impression(song: String, visibleAt: Long, tappedAt: Long?, outcome: Int, y: Double?) {
        id++
        exec("""INSERT INTO impression(id, buildId, songId, slot, team, outcome, visibleAt, tappedAt, features, y, u)
            VALUES ($id, 1, '$song', $id, 1, $outcome, $visibleAt, ${tappedAt ?: "NULL"}, '0.1,0.2', ${y ?: "NULL"}, 1)""")
    }

    private fun seen(since: Long): List<String> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.SEEN.replace(":since", since.toString())).use { rs ->
            val out = ArrayList<String>(); while (rs.next()) out += rs.getString("songId"); out
        }
    }

    @Test
    fun `a card passed over and one tapped and left at once are seen, one heard is not`() {
        impression("passed", 1000, null, outcome = 0, y = null)        // shown, not tapped
        impression("left", 1000, 1100, outcome = 1, y = 0.0)           // tapped, left in seconds
        impression("heard", 1000, 1100, outcome = 1, y = 0.8)          // tapped and heard well
        impression("waiting", 1000, 1100, outcome = 0, y = null)       // tapped, not graded yet
        impression("old", 10, null, outcome = 0, y = null)             // before the window
        assertEquals(listOf("left", "passed"), seen(since = 500).sorted())
    }
}
