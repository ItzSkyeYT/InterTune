/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.db.daos.BuildScore
import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.db.daos.CardsSeenRow
import com.dd3boh.outertune.db.daos.ListenDao.CodeCount
import com.dd3boh.outertune.db.daos.ListenDao.EndCount
import com.dd3boh.outertune.db.daos.TeamOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
        assertEquals(DoingSummary.NotSource(), doingSummary(engineShowing = false, teams = emptyList(), trend = null))
        // YouTube's cards are not the engine's.
        assertEquals(DoingSummary.NotSource(), doingSummary(false, listOf(TeamCards(3, CardCounts(100, 2))), null))
    }

    @Test
    fun `not the source, with its cards from before still waiting, it says how many will count`() {
        // The banner said "it has nothing to show" while Cards you saw listed "Best
        // recommendations: 4 not judged yet", for a day after the source was switched away.
        assertEquals(DoingSummary.NotSource(waiting = 4), doingSummary(engineShowing = false, teams = emptyList(), trend = null, waiting = 4))
        assertEquals(DoingSummary.NotSource(waiting = 0), doingSummary(engineShowing = false, teams = emptyList(), trend = null, waiting = 0))
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

    // With Learn from listening off, EngineLearning.run returns at once: nothing is judged.

    @Test
    fun `with learning off and nothing judged it says it is not learning, not that this fills in`() {
        assertEquals(DoingSummary.NotLearning, doingSummary(engineShowing = true, teams = emptyList(), trend = null, waiting = 5, learning = false))
        assertEquals(DoingSummary.Waiting, doingSummary(engineShowing = true, teams = emptyList(), trend = null, waiting = 5, learning = true))
    }

    @Test
    fun `with learning off nothing is said to wait for a judging that will not come`() {
        // Not the source: its cards from before are not judged a day later either.
        assertEquals(
            DoingSummary.NotSource(waiting = 0, learning = false),
            doingSummary(engineShowing = false, teams = emptyList(), trend = null, waiting = 4, learning = false),
        )
        // With numbers: they stay, and the cards still waiting are not counted as about to be.
        val s = doingSummary(engineShowing = true, teams = engineCards, trend = null, waiting = 52, learning = false) as DoingSummary.Numbers
        assertEquals(CardCounts(200, 9), s.cards)
        assertEquals(0, s.waiting)
        assertFalse(s.learning)
        assertFalse(s.fromBefore)
    }

    @Test
    fun `with learning off too early is left out, since no new cards come to fill it`() {
        val s = doingSummary(engineShowing = true, teams = engineCards, trend = CardTrendRow(10, 1, 0, 0), learning = false) as DoingSummary.Numbers
        assertNull(s.trend)
        // A real change already in the fortnights is still worth saying.
        val up = doingSummary(true, engineCards, CardTrendRow(recentSeen = 300, recentPlayed = 30, earlierSeen = 300, earlierPlayed = 10), learning = false)
        assertTrue((up as DoingSummary.Numbers).trend is Trend.Up)
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
    fun `exactly one is said in words, and every other count as a number`() {
        // "The card it showed you before", not "The 1 card"; French "La carte qui", which the
        // plural's one form cannot say, since it covers nought there too.
        assertTrue(oneInWords(1))
        assertFalse(oneInWords(0))
        assertFalse(oneInWords(2))
        assertFalse(oneInWords(21))
    }

    @Test
    fun `two different rates that round alike get a decimal each`() {
        assertEquals("4.2" to "4.4", per100Texts(4.2, 4.4, Locale.ENGLISH))
        assertEquals("6" to "4", per100Texts(6.1, 4.0, Locale.ENGLISH))
        assertEquals("3" to "3", per100Texts(3.0, 3.0, Locale.ENGLISH))
    }

    // Every count agrees with the others

    @Test
    fun `the summary carries how many more cards wait to be judged`() {
        val teams = listOf(TeamCards(ENGINE_TEAM, CardCounts(seen = 103, played = 0)))
        val s = doingSummary(engineShowing = true, teams, trend = null, waiting = 8) as DoingSummary.Numbers
        assertEquals(103, s.cards.seen)
        assertEquals(8, s.waiting)
        assertEquals(0, (doingSummary(true, teams, null) as DoingSummary.Numbers).waiting)
    }

    @Test
    fun `cards seen keeps the sources with any card, in team order`() {
        val rows = listOf(
            CardsSeenRow(team = 4, judged = 90, waiting = 8, leftOut = 0, tapped = 1),
            CardsSeenRow(team = 3, judged = 0, waiting = 0, leftOut = 0, tapped = 0),
            CardsSeenRow(team = 1, judged = 103, waiting = 8, leftOut = 2, tapped = 1),
            CardsSeenRow(team = 2, judged = 0, waiting = 3, leftOut = 0, tapped = 0),
        )
        assertEquals(listOf(1, 2, 4), cardsSeen(rows).map { it.team })
    }

    @Test
    fun `a source with nothing judged yet gives only its cards waiting, not a nought`() {
        assertTrue(onlyWaiting(CardsSeenRow(team = 3, judged = 0, waiting = 4, leftOut = 0, tapped = 0)))
        assertFalse(onlyWaiting(CardsSeenRow(team = 1, judged = 103, waiting = 20, leftOut = 0, tapped = 0)))
        assertFalse(onlyWaiting(CardsSeenRow(team = 1, judged = 0, waiting = 0, leftOut = 2, tapped = 0)))
    }

    // How a listen ended

    @Test
    fun `a song that reached its end with little of it heard ended early, and an unknown length tells nothing`() {
        assertEquals(EndLabel.ENDED_EARLY, endLabel(EndReason.ENDED, 0.09f))
        assertEquals(EndLabel.ENDED_EARLY, endLabel(EndReason.ENDED, 0.79f))
        assertEquals(EndLabel.REACHED_END, endLabel(EndReason.ENDED, 0.8f))
        assertEquals(EndLabel.REACHED_END, endLabel(EndReason.ENDED, 1.0f))
        assertEquals(EndLabel.REACHED_END, endLabel(EndReason.ENDED, 2.1f))  // a repeat
        assertEquals(EndLabel.REACHED_END, endLabel(EndReason.ENDED, -1f))
        assertEquals(EndLabel.SKIPPED, endLabel(EndReason.SKIPPED, 0.09f))
    }

    @Test
    fun `a listen still open is in progress, and the old play log is not recorded`() {
        assertEquals(EndLabel.IN_PROGRESS, endLabel(EndReason.OPEN, 0.5f))
        assertEquals(EndLabel.NOT_RECORDED, endLabel(EndReason.UNKNOWN, -1f))
        assertEquals(EndLabel.NOT_RECORDED, endLabel(EndReason.ERROR, -1f))
        assertEquals(EndLabel.STOPPED, endLabel(EndReason.STOPPED, 0.2f))
        assertEquals(EndLabel.REPLACED, endLabel(EndReason.REPLACED, 0.2f))
    }

    @Test
    fun `how they ended gives one count per label, ended early apart and not recorded last`() {
        val rows = listOf(
            EndCount(EndReason.UNKNOWN, early = false, n = 1),
            EndCount(EndReason.ENDED, early = false, n = 3),
            EndCount(EndReason.ENDED, early = true, n = 1),
            EndCount(EndReason.SKIPPED, early = false, n = 11),
            EndCount(EndReason.ERROR, early = false, n = 2),
            EndCount(EndReason.OPEN, early = false, n = 1),
        )
        assertEquals(
            listOf(EndLabel.REACHED_END to 3, EndLabel.ENDED_EARLY to 1, EndLabel.SKIPPED to 11, EndLabel.IN_PROGRESS to 1, EndLabel.NOT_RECORDED to 3),
            endCounts(rows),
        )
    }

    @Test
    fun `where they were started from puts the ones that say nothing together and last`() {
        val rows = listOf(
            CodeCount(PlayOrigin.UNKNOWN.code, 24),
            CodeCount(PlayOrigin.DISCOVER.code, 3),
            CodeCount(PlayOrigin.QUICK_PICKS.code, 4),
            CodeCount(99, 1),   // a code this version does not know
        )
        assertEquals(
            listOf(PlayOrigin.QUICK_PICKS to 4, PlayOrigin.DISCOVER to 3, PlayOrigin.UNKNOWN to 25),
            originCounts(rows),
        )
    }

    @Test
    fun `a minute or more reads as a clock does, and under a minute is left to seconds`() {
        assertNull(clockLength(0))
        assertNull(clockLength(59_999))
        assertEquals("1:00", clockLength(60_000))
        assertEquals("4:04", clockLength(244_000))
        assertEquals("4:04", clockLength(244_999))
        assertEquals("59:59", clockLength(3_599_000))
        assertEquals("1:02:09", clockLength(3_729_000))
    }

    // How well it predicts, counted

    @Test
    fun `the Brier bar is what one chance for every card, the share played, would score`() {
        // 103 cards, none played: the bar is 0, and guessing anything at all does worse.
        assertEquals(0.0, brierReference(List(103) { 0.11 to 0.0 }), 1e-12)
        // One in four played: a quarter for every card scores 0.25 x 0.75.
        val pairs = List(4) { i -> 0.3 to (if (i == 0) 1.0 else 0.0) }
        assertEquals(0.1875, brierReference(pairs), 1e-12)
        // A grade of one half counts as played, as in the score itself.
        assertEquals(0.25, brierReference(listOf(0.1 to 0.5, 0.1 to 0.2)), 1e-12)
        assertTrue(brierReference(emptyList()).isNaN())
    }

    @Test
    fun `lower than the bar is better, higher is worse, and alike to three decimals is the same`() {
        // His emulator on 1 Oct: 0.012 against a bar of 0.000, guesses too high.
        assertEquals(BrierVerdict.WORSE, brierVerdict(0.012, brierReference(List(103) { 0.11 to 0.0 })))
        assertEquals(BrierVerdict.BETTER, brierVerdict(0.15, 0.1875))
        assertEquals(BrierVerdict.SAME, brierVerdict(0.18751, 0.1875))
        assertEquals(BrierVerdict.WORSE, brierVerdict(0.1890, 0.1875))
    }

    @Test
    fun `a listen says yesterday or its day when it did not end today, by the local calendar`() {
        val paris = ZoneId.of("Europe/Paris")
        // 1 Oct 2026, 11:53 in Paris (09:53 UTC).
        val now = java.time.ZonedDateTime.of(2026, 10, 1, 11, 53, 0, 0, paris).toInstant().toEpochMilli()
        fun at(day: Int, hour: Int, minute: Int) = java.time.ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, paris).toInstant().toEpochMilli()
        fun sep(day: Int, hour: Int) = java.time.ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, paris).toInstant().toEpochMilli()
        assertEquals(ListenDay.TODAY, listenDay(at(1, 10, 37), now, paris))
        assertEquals(ListenDay.TODAY, listenDay(at(1, 0, 5), now, paris))
        // 10:12 PM the night before, which sat under 10:37 AM with no day and looked out of order.
        assertEquals(ListenDay.YESTERDAY, listenDay(sep(30, 22) + 12 * 60_000L, now, paris))
        // 23:30 UTC on 30 Sep is already 1 Oct in Paris: the phone's calendar decides.
        assertEquals(ListenDay.TODAY, listenDay(java.time.Instant.parse("2026-09-30T23:30:00Z").toEpochMilli(), now, paris))
        assertEquals(ListenDay.EARLIER, listenDay(sep(28, 20), now, paris))
        // A clock set back can stamp a listen after now; it is still today, not an error.
        assertEquals(ListenDay.TODAY, listenDay(now + 60_000L, now, paris))
    }

    @Test
    fun `prediction bands count their cards and plays, lowest first, empty bands left out`() {
        val pairs = listOf(0.05 to 0.0, 0.1 to 1.0, 0.19 to 0.0, 0.25 to 0.5, 0.3 to 0.0, 1.0 to 1.0)
        assertEquals(
            listOf(PredictionBand(0, 20, cards = 3, played = 1), PredictionBand(20, 40, cards = 2, played = 1), PredictionBand(80, 100, cards = 1, played = 1)),
            predictionBands(pairs),
        )
        assertEquals(emptyList<PredictionBand>(), predictionBands(emptyList()))
    }
}
