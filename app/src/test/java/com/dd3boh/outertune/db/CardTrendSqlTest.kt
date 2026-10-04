/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.db.daos.TeamOutcome
import com.dd3boh.outertune.engine.EngineSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * The two fortnights behind "going up" on How it's doing, run against the exported schema: the
 * engine's own judged cards only, each in the window it was seen in, played at a grade of one half,
 * and only those of rows built under the choice asked for under Quick picks leans toward.
 * And the per source counts the summary is worked out from, which must take the same cards.
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

    /** A row built with [lean] chosen (a Lean code) and [leanApplied] followed; build 1, from [open], is Auto's. */
    private fun build(id: Int, lean: Int, leanApplied: Int = lean, rowKey: Int = 1) =
        exec("""INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, shownIds, lean, leanApplied, leadWeight)
            VALUES ($id, 500, $rowKey, 1, 0, 0, 15, 0, '[]', '{}', '', $lean, $leanApplied, 1.0)""")

    private fun impression(visibleAt: Long, y: Double, team: Int = 1, outcome: Int = 3, graded: Boolean = true, slot: Int = 0, build: Int = 1) {
        id++
        exec("""INSERT INTO impression(id, buildId, songId, slot, team, outcome, visibleAt, y, gradedAt)
            VALUES ($id, $build, 'a', $slot, $team, $outcome, $visibleAt, $y, ${if (graded) visibleAt + 100 else "NULL"})""")
    }

    /** Room's named parameters, filled in for JDBC. [lean] is the choice as it stands, Auto unless given. */
    private fun trend(lean: Int = 0): CardTrendRow = db.createStatement().use { st ->
        st.executeQuery(EngineSql.CARD_TREND.replace(":from", "$from").replace(":mid", "$mid").replace(":to", "$to").replace(":lean", "$lean")).use { rs ->
            rs.next()
            CardTrendRow(
                rs.getInt("recentSeen"), rs.getInt("recentPlayed"), rs.getInt("earlierSeen"), rs.getInt("earlierPlayed"),
                recentAny = rs.getInt("recentAny"), earlierAny = rs.getInt("earlierAny"),
            )
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
        impression(visibleAt = 2_500, y = 1.0, outcome = 5)                  // the code kept for pool picks
        impression(visibleAt = 2_500, y = 1.0, outcome = 1, slot = -1)       // a pool pick as EngineLearning writes it
        impression(visibleAt = 2_500, y = 0.0, outcome = 6)                  // dropped
        impression(visibleAt = 2_500, y = 0.0, outcome = 7)                  // lost
        impression(visibleAt = 999, y = 1.0, outcome = 1)                    // before the earlier fortnight
        impression(visibleAt = 3_000, y = 1.0, outcome = 1)                  // the last day, not yet fair
        assertEquals(CardTrendRow(0, 0, 0, 0), trend())
    }

    private fun gradedByTeam(): List<TeamOutcome> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.GRADED_BY_TEAM).use { rs ->
            buildList { while (rs.next()) add(TeamOutcome(rs.getInt("team"), rs.getInt("outcome"), rs.getInt("n"), rs.getInt("wins"))) }
        }.sortedWith(compareBy({ it.team }, { it.outcome }))
    }

    @Test
    fun `a pool pick was never on screen, so the per source counts leave it out too`() {
        impression(visibleAt = 2_500, y = 0.0)                              // ignored
        impression(visibleAt = 2_500, y = 0.6, outcome = 1)                 // played from the card
        impression(visibleAt = 2_500, y = 1.0, outcome = 1, slot = -1)      // pool pick: played, never shown
        impression(visibleAt = 2_500, y = 1.0, team = 3, outcome = 1)       // YouTube's card
        impression(visibleAt = 2_500, y = 0.0, graded = false, outcome = 0) // not judged yet
        assertEquals(
            listOf(TeamOutcome(1, 1, n = 1, wins = 1), TeamOutcome(1, 3, n = 1, wins = 0), TeamOutcome(3, 1, n = 1, wins = 1)),
            gradedByTeam(),
        )
        assertEquals(CardTrendRow(recentSeen = 2, recentPlayed = 1, earlierSeen = 0, earlierPlayed = 0), trend())
    }

    // Quick picks leans toward: a fortnight under one choice is never compared with one under another.

    @Test
    fun `cards shown under another choice are left out of the two fortnights, and counted apart`() {
        build(2, lean = 1)                                              // Never heard chosen
        impression(visibleAt = 1_500, y = 1.0, outcome = 1)             // Auto's, earlier
        impression(visibleAt = 1_600, y = 0.0)                          // Auto's, earlier
        impression(visibleAt = 2_500, y = 0.0, build = 2)               // Never heard's, recent
        impression(visibleAt = 2_600, y = 0.0, build = 2)
        impression(visibleAt = 2_700, y = 1.0, outcome = 1, build = 2)
        // Back on Auto, the recent fortnight has nothing of Auto's to compare: not "going down".
        assertEquals(CardTrendRow(recentSeen = 0, recentPlayed = 0, earlierSeen = 2, earlierPlayed = 1, recentAny = 3, earlierAny = 2), trend(lean = 0))
        // With Never heard chosen, only its own cards count.
        assertEquals(CardTrendRow(recentSeen = 3, recentPlayed = 1, earlierSeen = 0, earlierPlayed = 0, recentAny = 3, earlierAny = 2), trend(lean = 1))
        // A choice no row was built under yet has nothing, with every card still counted apart.
        assertEquals(CardTrendRow(recentSeen = 0, recentPlayed = 0, earlierSeen = 0, earlierPlayed = 0, recentAny = 3, earlierAny = 2), trend(lean = 3))
    }

    @Test
    fun `with nothing but Auto ever chosen, every card counts as it always did`() {
        impression(visibleAt = 1_500, y = 1.0, outcome = 1)
        impression(visibleAt = 2_500, y = 0.6, outcome = 2)
        impression(visibleAt = 2_600, y = 0.0)
        val row = trend()
        assertEquals(CardTrendRow(recentSeen = 2, recentPlayed = 1, earlierSeen = 1, earlierPlayed = 1), row)
        assertEquals(row.recentSeen, row.recentAny)
        assertEquals(row.earlierSeen, row.earlierAny)
    }

    @Test
    fun `it is the choice that counts, not whether a chip set it aside for that row`() {
        build(2, lean = 2, leanApplied = 0)                             // Your artists chosen, Favourites on that evening
        build(3, lean = 2)
        impression(visibleAt = 2_500, y = 1.0, outcome = 1, build = 2)
        impression(visibleAt = 2_600, y = 0.0, build = 3)
        assertEquals(CardTrendRow(recentSeen = 2, recentPlayed = 1, earlierSeen = 0, earlierPlayed = 0), trend(lean = 2))
        assertEquals(CardTrendRow(recentSeen = 0, recentPlayed = 0, earlierSeen = 0, earlierPlayed = 0, recentAny = 2, earlierAny = 0), trend(lean = 0))
    }

    @Test
    fun `Try both's engine cards follow the choice their row was built under`() {
        build(2, lean = 4, rowKey = 5)
        impression(visibleAt = 2_500, y = 1.0, outcome = 1, build = 2)
        impression(visibleAt = 2_500, y = 1.0, team = 3, outcome = 1, build = 2)   // the other source's card in the same row
        assertEquals(CardTrendRow(recentSeen = 1, recentPlayed = 1, earlierSeen = 0, earlierPlayed = 0), trend(lean = 4))
    }
}
