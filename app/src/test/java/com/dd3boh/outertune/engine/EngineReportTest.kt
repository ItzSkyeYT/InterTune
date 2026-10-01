/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.db.daos.BuildScore
import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.db.daos.TeamOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** What How it's doing says at the top, and the arithmetic under its rows. */
class EngineReportTest {

    // Cards by source

    @Test
    fun `only played, played elsewhere and ignored cards count, summed per source in team order`() {
        val rows = listOf(
            TeamOutcome(team = 3, outcome = Outcome.IGNORED, n = 40, wins = 0),
            TeamOutcome(team = 1, outcome = Outcome.PLAYED, n = 5, wins = 4),
            TeamOutcome(team = 1, outcome = Outcome.ELSEWHERE, n = 3, wins = 1),
            TeamOutcome(team = 1, outcome = Outcome.IGNORED, n = 92, wins = 0),
            // Never a fair question: pending, unseen, pool pick, dropped, lost.
            TeamOutcome(team = 1, outcome = Outcome.PENDING, n = 50, wins = 0),
            TeamOutcome(team = 1, outcome = 4, n = 50, wins = 0),
            TeamOutcome(team = 1, outcome = 5, n = 50, wins = 50),
            TeamOutcome(team = 1, outcome = Outcome.DROPPED, n = 50, wins = 0),
            TeamOutcome(team = 1, outcome = Outcome.LOST, n = 50, wins = 0),
            TeamOutcome(team = 2, outcome = Outcome.PENDING, n = 9, wins = 0),
        )
        assertEquals(
            listOf(TeamCards(1, CardCounts(seen = 100, played = 5)), TeamCards(3, CardCounts(seen = 40, played = 0))),
            cardsByTeam(rows),
        )
        assertEquals(5.0, cardsByTeam(rows).first().cards.per100, 1e-9)
    }

    @Test
    fun `a share of nothing is nought, not a division by zero`() {
        assertEquals(0.0, per100(0, 0), 0.0)
        assertEquals(0.0, CardCounts(0, 0).per100, 0.0)
        assertEquals(25.0, per100(1, 4), 1e-9)
    }

    // Whether the engine is in the row

    @Test
    fun `the engine is showing on its own and under Try both, and nowhere else`() {
        assertTrue(engineShowing(QuickPicksSource.ENGINE))
        assertTrue(engineShowing(QuickPicksSource.COMPARE))
        assertFalse(engineShowing(QuickPicksSource.YOUTUBE))
        assertFalse(engineShowing(QuickPicksSource.LIBRARY))
        assertFalse(engineShowing(QuickPicksSource.OFF))
    }

    // The fortnights

    @Test
    fun `both fortnights end a day ago, back to back`() {
        val day = TrendWindows.DAY_MS
        val now = 1_760_000_000_000L
        val w = TrendWindows.at(now)
        assertEquals(now - day, w.to)
        assertEquals(w.to - 14 * day, w.mid)
        assertEquals(w.mid - 14 * day, w.from)
    }

    // The trend

    @Test
    fun `fewer than fifty cards in either fortnight is too early, however big the change`() {
        assertEquals(Trend.TooEarly, trendOf(CardCounts(49, 40), CardCounts(500, 5)))
        assertEquals(Trend.TooEarly, trendOf(CardCounts(500, 400), CardCounts(49, 0)))
    }

    @Test
    fun `a real rise reads as going up, with both rates per 100`() {
        // 30 in 300 against 10 in 300: z is about 3.3.
        val t = trendOf(CardCounts(300, 30), CardCounts(300, 10))
        assertTrue(t is Trend.Up)
        t as Trend.Up
        assertEquals(10.0, t.recent, 1e-9)
        assertEquals(10.0 / 3, t.earlier, 1e-9)
    }

    @Test
    fun `a real fall reads as going down`() {
        assertTrue(trendOf(CardCounts(300, 10), CardCounts(300, 30)) is Trend.Down)
    }

    @Test
    fun `a few lucky plays on a small fortnight is no clear change`() {
        // 8 in 60 against 4 in 60 looks like double, but z is about 1.2.
        assertTrue(trendOf(CardCounts(60, 8), CardCounts(60, 4)) is Trend.NoClearChange)
    }

    @Test
    fun `nothing played in either fortnight is no clear change, not a crash`() {
        assertEquals(Trend.NoClearChange(0.0, 0.0), trendOf(CardCounts(80, 0), CardCounts(90, 0)))
    }

    // The summary at the top

    private val engineCards = listOf(TeamCards(ENGINE_TEAM, CardCounts(200, 9)), TeamCards(3, CardCounts(100, 2)))

    @Test
    fun `with another source and no engine cards it says so instead of showing numbers`() {
        assertEquals(DoingSummary.NotSource, doingSummary(engineShowing = false, teams = emptyList(), trend = null))
        // YouTube's cards are not the engine's.
        assertEquals(DoingSummary.NotSource, doingSummary(false, listOf(TeamCards(3, CardCounts(100, 2))), null))
    }

    @Test
    fun `with the engine showing and nothing judged yet it says to wait`() {
        assertEquals(DoingSummary.Waiting, doingSummary(engineShowing = true, teams = emptyList(), trend = null))
        assertEquals(DoingSummary.Waiting, doingSummary(true, listOf(TeamCards(ENGINE_TEAM, CardCounts(0, 0))), null))
    }

    @Test
    fun `with engine cards it gives the engine's numbers and the trend`() {
        val trend = CardTrendRow(recentSeen = 300, recentPlayed = 30, earlierSeen = 300, earlierPlayed = 10)
        val s = doingSummary(engineShowing = true, teams = engineCards, trend = trend)
        assertEquals(DoingSummary.Numbers(CardCounts(200, 9), trendOf(CardCounts(300, 30), CardCounts(300, 10)), fromBefore = false), s)
    }

    @Test
    fun `engine numbers kept from an earlier time say so, with no trend when it could only say too early`() {
        // Not in the row, so no new cards: the fortnights fill with nothing and "too early" would
        // never go away.
        assertEquals(
            DoingSummary.Numbers(CardCounts(200, 9), trend = null, fromBefore = true),
            doingSummary(engineShowing = false, teams = engineCards, trend = null),
        )
        assertEquals(
            DoingSummary.Numbers(CardCounts(200, 9), trend = null, fromBefore = true),
            doingSummary(false, engineCards, CardTrendRow(recentSeen = 0, recentPlayed = 0, earlierSeen = 120, earlierPlayed = 6)),
        )
    }

    @Test
    fun `a trend from before the switch is still shown while there is one`() {
        val trend = CardTrendRow(recentSeen = 300, recentPlayed = 30, earlierSeen = 300, earlierPlayed = 10)
        val s = doingSummary(engineShowing = false, teams = engineCards, trend = trend) as DoingSummary.Numbers
        assertTrue(s.trend is Trend.Up)
        assertTrue(s.fromBefore)
    }

    @Test
    fun `while the engine is showing, too early stays, since it will fill in`() {
        val s = doingSummary(engineShowing = true, teams = engineCards, trend = CardTrendRow(10, 1, 0, 0)) as DoingSummary.Numbers
        assertEquals(Trend.TooEarly, s.trend)
    }

    @Test
    fun `numbers exist when any figure has something in it`() {
        assertFalse(hasNumbers(emptyList(), emptyList(), predictions = 0, weightUpdates = 0))
        assertFalse(hasNumbers(listOf(TeamCards(1, CardCounts(0, 0))), emptyList(), 0, 0))
        assertTrue(hasNumbers(listOf(TeamCards(3, CardCounts(1, 0))), emptyList(), 0, 0))
        assertTrue(hasNumbers(emptyList(), listOf(BuildScore(4, builds = 1, plays = 0, hits = 0)), 0, 0))
        assertTrue(hasNumbers(emptyList(), emptyList(), predictions = 1, weightUpdates = 0))
        assertTrue(hasNumbers(emptyList(), emptyList(), predictions = 0, weightUpdates = 1))
    }

    // How well it predicts, in plain numbers

    @Test
    fun `expected plays per 100 beside actual ones, a card played at a grade of one half`() {
        val pairs = listOf(0.1 to 1.0, 0.2 to 0.5, 0.3 to 0.49, 0.2 to 0.0)
        val p = predictionOf(pairs)
        assertEquals(4, p.cards)
        assertEquals(20.0, p.expectedPer100, 1e-9)
        assertEquals(50.0, p.playedPer100, 1e-9)
        assertEquals(Prediction(0, 0.0, 0.0), predictionOf(emptyList()))
    }

    // Formatting

    @Test
    fun `whole numbers from one up, one decimal under one, and a small rate never reads as nothing`() {
        assertEquals("5", per100Text(4.6, Locale.ENGLISH))
        assertEquals("12", per100Text(12.4, Locale.ENGLISH))
        assertEquals("0.4", per100Text(0.44, Locale.ENGLISH))
        assertEquals("0.1", per100Text(0.02, Locale.ENGLISH))
        assertEquals("0", per100Text(0.0, Locale.ENGLISH))
        assertEquals("100", per100Text(100.0, Locale.ENGLISH))
        // 1 in 104 rounds to one, so it reads as a whole number rather than 1.0.
        assertEquals("1", per100Text(100.0 / 104, Locale.ENGLISH))
        assertEquals("0.9", per100Text(0.94, Locale.ENGLISH))
    }

    @Test
    fun `the decimal follows the language`() {
        assertEquals("0,4", per100Text(0.44, Locale.FRENCH))
        assertEquals("4,6", per100Text(4.6, Locale.FRENCH, decimals = 1))
    }

    @Test
    fun `two different rates that round alike get a decimal each`() {
        assertEquals("4.2" to "4.4", per100Texts(4.2, 4.4, Locale.ENGLISH))
        assertEquals("6" to "4", per100Texts(6.1, 4.0, Locale.ENGLISH))
        assertEquals("3" to "3", per100Texts(3.0, 3.0, Locale.ENGLISH))
    }
}
