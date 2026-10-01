/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.engine.EngineSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The two fortnights behind "going up" on How it's doing, run against the exported schema: the
 * engine's own judged cards only, each in the window it was seen in, played at a grade of one half.
 */
class CardTrendSqlTest {
    private lateinit var db: Connection
    private var id = 0

    private val from = 1_000L
    private val mid = 2_000L
    private val to = 3_000L

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

    private fun impression(visibleAt: Long, y: Double, team: Int = 1, outcome: Int = 3, graded: Boolean = true) {
        id++
        exec("""INSERT INTO impression(id, buildId, songId, slot, team, outcome, visibleAt, y, gradedAt)
            VALUES ($id, 1, 'a', 0, $team, $outcome, $visibleAt, $y, ${if (graded) visibleAt + 100 else "NULL"})""")
    }

    /** Room's named parameters, filled in for JDBC. */
    private fun trend(): CardTrendRow = db.createStatement().use { st ->
        st.executeQuery(EngineSql.CARD_TREND.replace(":from", "$from").replace(":mid", "$mid").replace(":to", "$to")).use { rs ->
            rs.next()
            CardTrendRow(rs.getInt("recentSeen"), rs.getInt("recentPlayed"), rs.getInt("earlierSeen"), rs.getInt("earlierPlayed"))
        }
    }

    @Test
    fun `an empty table gives noughts, not no row`() {
        assertEquals(CardTrendRow(0, 0, 0, 0), trend())
    }

    @Test
    fun `each judged engine card counts in the fortnight it was seen in`() {
        impression(visibleAt = 1_000, y = 1.0, outcome = 1)   // first moment of the earlier window
        impression(visibleAt = 1_500, y = 0.5, outcome = 2)   // played elsewhere at half grade: played
        impression(visibleAt = 1_999, y = 0.0)                 // ignored
        impression(visibleAt = 2_000, y = 0.8, outcome = 1)   // first moment of the recent window
        impression(visibleAt = 2_500, y = 0.4, outcome = 1)   // tapped, left early: seen, not played
        impression(visibleAt = 2_999, y = 0.0)
        assertEquals(CardTrendRow(recentSeen = 3, recentPlayed = 1, earlierSeen = 3, earlierPlayed = 2), trend())
    }

    @Test
    fun `other sources, unjudged cards and cards outside both fortnights are left out`() {
        impression(visibleAt = 2_500, y = 1.0, team = 3, outcome = 1)        // YouTube's card
        impression(visibleAt = 2_500, y = 1.0, team = 4, outcome = 1)        // the unseen row
        impression(visibleAt = 2_500, y = 1.0, graded = false, outcome = 1)  // not judged yet
        impression(visibleAt = 2_500, y = 0.0, outcome = 0)                  // pending
        impression(visibleAt = 2_500, y = 0.0, outcome = 4)                  // unseen
        impression(visibleAt = 2_500, y = 1.0, outcome = 5)                  // pool pick
        impression(visibleAt = 2_500, y = 0.0, outcome = 6)                  // dropped
        impression(visibleAt = 2_500, y = 0.0, outcome = 7)                  // lost
        impression(visibleAt = 999, y = 1.0, outcome = 1)                    // before the earlier fortnight
        impression(visibleAt = 3_000, y = 1.0, outcome = 1)                  // the last day, not yet fair
        assertEquals(CardTrendRow(0, 0, 0, 0), trend())
    }
}
