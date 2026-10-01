/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.db.daos.CardsSeenRow
import com.dd3boh.outertune.db.daos.ListenDao.EndCount
import com.dd3boh.outertune.db.daos.TeamOutcome
import com.dd3boh.outertune.engine.EndLabel
import com.dd3boh.outertune.engine.EngineSql
import com.dd3boh.outertune.engine.cardsByTeam
import com.dd3boh.outertune.engine.endCounts
import com.dd3boh.outertune.engine.endLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The counts under What it has to learn from, run against the exported schema. Cards you saw must
 * give the same judged count per source as Cards played and the summary, which read another query,
 * and How they ended must split off a song that ended early exactly where Recent listens does.
 */
class DoingCountsSqlTest {
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

    private fun impression(team: Int, outcome: Int, graded: Boolean = outcome != 0, slot: Int = 0, y: Double = 0.0, tapped: Boolean = false) {
        id++
        exec("""INSERT INTO impression(id, buildId, songId, slot, team, outcome, visibleAt, tappedAt, y, gradedAt)
            VALUES ($id, 1, 'a', $slot, $team, $outcome, 1000, ${if (tapped) 1100 else "NULL"}, $y, ${if (graded) 2000 else "NULL"})""")
    }

    private fun listen(endReason: Int, ratio: Float) {
        id++
        exec("""INSERT INTO listen(id, songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
            originSlot, queueId, autoplayDepth, sessionId, counted, learn)
            VALUES ($id, 'a', $id, ${id + 1}, 0, 1000, 200000, $ratio, $endReason, 0, -1, 0, 0, 1, 0, 1)""")
    }

    private fun cardsSeen(): List<CardsSeenRow> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.CARDS_SEEN).use { rs ->
            buildList {
                while (rs.next()) add(CardsSeenRow(rs.getInt("team"), rs.getInt("judged"), rs.getInt("waiting"), rs.getInt("leftOut"), rs.getInt("tapped")))
            }
        }
    }

    private fun gradedByTeam(): List<TeamOutcome> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.GRADED_BY_TEAM).use { rs ->
            buildList { while (rs.next()) add(TeamOutcome(rs.getInt("team"), rs.getInt("outcome"), rs.getInt("n"), rs.getInt("wins"))) }
        }
    }

    private fun byEnd(): List<EndCount> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.LISTENS_BY_END).use { rs ->
            buildList { while (rs.next()) add(EndCount(rs.getInt("code"), rs.getInt("early") != 0, rs.getInt("n"))) }
        }
    }

    @Test
    fun `cards seen judges the same cards as cards played, and names the rest beside them`() {
        impression(team = 1, outcome = 1, y = 1.0, tapped = true)    // played
        impression(team = 1, outcome = 2, y = 0.5)                   // played elsewhere
        impression(team = 1, outcome = 3)                            // passed over
        impression(team = 1, outcome = 3)
        impression(team = 1, outcome = 0)                            // not judged yet
        impression(team = 1, outcome = 0, tapped = true)             // tapped, still playing
        impression(team = 1, outcome = 6)                            // its play forgotten
        impression(team = 1, outcome = 7, tapped = true)             // tapped, never heard
        impression(team = 1, outcome = 1, slot = -1, y = 0.9)        // a pool pick, never on screen
        impression(team = 4, outcome = 3)                            // Discover something new
        impression(team = 4, outcome = 0)

        val seen = cardsSeen()
        assertEquals(
            listOf(
                CardsSeenRow(team = 1, judged = 4, waiting = 2, leftOut = 2, tapped = 3),
                CardsSeenRow(team = 4, judged = 1, waiting = 1, leftOut = 0, tapped = 0),
            ),
            seen,
        )
        // What Cards played and the summary count as seen, from their own query.
        assertEquals(cardsByTeam(gradedByTeam()).associate { it.team to it.cards.seen }, seen.associate { it.team to it.judged })
    }

    @Test
    fun `how they ended splits off a song that ended early on the same line as Recent listens`() {
        val ratios = listOf(-1f, 0f, 0.09f, 0.79f, 0.8f, 1f, 1.7f)
        ratios.forEach { listen(EndReason.ENDED, it) }
        listen(EndReason.SKIPPED, 0.09f)
        listen(EndReason.OPEN, 0.1f)

        val expected = (ratios.map { endLabel(EndReason.ENDED, it) } + EndLabel.SKIPPED + EndLabel.IN_PROGRESS)
            .groupingBy { it }.eachCount().toSortedMap().toList()
        assertEquals(expected, endCounts(byEnd()))
        assertEquals(listOf(EndLabel.REACHED_END to 4, EndLabel.ENDED_EARLY to 3, EndLabel.SKIPPED to 1, EndLabel.IN_PROGRESS to 1), endCounts(byEnd()))
    }
}
