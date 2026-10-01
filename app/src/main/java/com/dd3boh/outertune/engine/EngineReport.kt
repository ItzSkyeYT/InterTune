/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.orOffered
import com.dd3boh.outertune.db.daos.BuildScore
import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.db.daos.TeamOutcome
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * The arithmetic behind How it's doing, apart from the screen so it can be tested: what the
 * summary at the top says, and the few numbers the rows below it work out.
 */

/** Whose cards they were, as the impression table writes it. */
const val ENGINE_TEAM = 1

/** Cards seen, and how many of them were played. */
data class CardCounts(val seen: Int, val played: Int) {
    val per100: Double get() = per100(played, seen)
}

fun per100(part: Int, whole: Int): Double = if (whole > 0) 100.0 * part / whole else 0.0

/** One source's graded cards. */
data class TeamCards(val team: Int, val cards: CardCounts)

/**
 * Seen and played per source, in team order. Only cards graded played, played elsewhere or
 * ignored count: pending, unseen, pool picks, dropped and lost cards were never a fair question.
 */
fun cardsByTeam(rows: List<TeamOutcome>): List<TeamCards> =
    rows.filter { it.outcome in Outcome.PLAYED..Outcome.IGNORED }
        .groupBy { it.team }
        .toSortedMap()
        .map { (team, r) -> TeamCards(team, CardCounts(seen = r.sumOf { it.n }, played = r.sumOf { it.wins })) }

/** Whether the engine builds any of the Quick picks row: on its own, or under Try both. */
fun engineShowing(source: QuickPicksSource): Boolean =
    source.orOffered().let { it == QuickPicksSource.ENGINE || it == QuickPicksSource.COMPARE }

/**
 * The two fortnights the trend compares. Both end a day ago: a card that was not tapped is only
 * graded a day after it was seen, so the last day holds its plays and not yet its misses, and
 * would make any week look better than it was.
 */
data class TrendWindows(val from: Long, val mid: Long, val to: Long) {
    companion object {
        const val DAY_MS = 86_400_000L
        const val SPAN_MS = 14 * DAY_MS

        fun at(now: Long): TrendWindows {
            val to = now - DAY_MS
            return TrendWindows(from = to - 2 * SPAN_MS, mid = to - SPAN_MS, to = to)
        }
    }
}

/** Fewer cards than this in either fortnight and there is no telling a change from luck. */
const val MIN_TREND_CARDS = 50

/** A difference this many standard errors from nothing is called a change; about 95 in 100. */
private const val TREND_Z = 1.96

sealed interface Trend {
    data object TooEarly : Trend
    data class Up(val recent: Double, val earlier: Double) : Trend
    data class Down(val recent: Double, val earlier: Double) : Trend
    data class NoClearChange(val recent: Double, val earlier: Double) : Trend
}

/**
 * Whether the last fortnight's play rate is really above or below the one before: a two
 * proportion z test, so a handful of lucky plays on a small week does not read as going up.
 */
fun trendOf(recent: CardCounts, earlier: CardCounts, minCards: Int = MIN_TREND_CARDS): Trend {
    if (recent.seen < minCards || earlier.seen < minCards) return Trend.TooEarly
    val p1 = recent.played.toDouble() / recent.seen
    val p2 = earlier.played.toDouble() / earlier.seen
    val pooled = (recent.played + earlier.played).toDouble() / (recent.seen + earlier.seen)
    val se = sqrt(pooled * (1 - pooled) * (1.0 / recent.seen + 1.0 / earlier.seen))
    val z = if (se > 0) (p1 - p2) / se else 0.0
    return when {
        z >= TREND_Z -> Trend.Up(recent.per100, earlier.per100)
        z <= -TREND_Z -> Trend.Down(recent.per100, earlier.per100)
        else -> Trend.NoClearChange(recent.per100, earlier.per100)
    }
}

/** What the card at the top of How it's doing says. */
sealed interface DoingSummary {
    /** Another source fills Quick picks and the engine has no cards of its own to count. */
    data object NotSource : DoingSummary

    /** The engine fills Quick picks but nothing it showed has been judged yet. */
    data object Waiting : DoingSummary

    /** [fromBefore] when these are from an earlier time: the engine is not in the row now. */
    data class Numbers(val cards: CardCounts, val trend: Trend, val fromBefore: Boolean) : DoingSummary
}

fun doingSummary(engineShowing: Boolean, teams: List<TeamCards>, trend: CardTrendRow?): DoingSummary {
    val engine = teams.firstOrNull { it.team == ENGINE_TEAM }?.cards
    if (engine == null || engine.seen == 0) return if (engineShowing) DoingSummary.Waiting else DoingSummary.NotSource
    val t = trend?.let {
        trendOf(CardCounts(it.recentSeen, it.recentPlayed), CardCounts(it.earlierSeen, it.earlierPlayed))
    } ?: Trend.TooEarly
    return DoingSummary.Numbers(engine, t, fromBefore = !engineShowing)
}

/**
 * Whether any of the engine's own figures has something in it. With none, the rows that would
 * each say "Nothing yet" are left out, and the summary above says why in one line.
 */
fun hasNumbers(teams: List<TeamCards>, held: List<BuildScore>, predictions: Int, weightUpdates: Int): Boolean =
    teams.any { it.cards.seen > 0 } || held.isNotEmpty() || predictions > 0 || weightUpdates > 0

/** How many plays the engine expected of its cards, per 100, beside how many it got. */
data class Prediction(val cards: Int, val expectedPer100: Double, val playedPer100: Double)

/** From prediction and grade pairs; a card counts as played at a grade of one half, as in the Brier score. */
fun predictionOf(pairs: List<Pair<Double, Double>>): Prediction =
    if (pairs.isEmpty()) Prediction(0, 0.0, 0.0)
    else Prediction(
        cards = pairs.size,
        expectedPer100 = 100.0 * pairs.sumOf { it.first } / pairs.size,
        playedPer100 = per100(pairs.count { it.second >= 0.5 }, pairs.size),
    )

/**
 * A number of cards in 100, for reading rather than for the record: whole from one up, one
 * decimal under one, so a small rate does not read as nothing. Under one means under one once
 * rounded, so 0.96 reads 1, not 1.0.
 */
fun per100Text(value: Double, locale: Locale, decimals: Int = if (value > 0 && value < 0.95) 1 else 0): String {
    val shown = if (decimals == 1 && value > 0) value.coerceAtLeast(0.1) else value
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        roundingMode = RoundingMode.HALF_UP
    }.format(shown)
}

/** Two rates side by side, with a decimal when rounding would make different ones look equal. */
fun per100Texts(a: Double, b: Double, locale: Locale): Pair<String, String> {
    val whole = per100Text(a, locale) to per100Text(b, locale)
    return if (whole.first == whole.second && abs(a - b) > 1e-9) {
        per100Text(a, locale, 1) to per100Text(b, locale, 1)
    } else {
        whole
    }
}
