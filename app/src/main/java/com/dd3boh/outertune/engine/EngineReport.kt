/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.orOffered
import com.dd3boh.outertune.db.daos.BuildScore
import com.dd3boh.outertune.db.daos.CardTrendRow
import com.dd3boh.outertune.db.daos.CardsSeenRow
import com.dd3boh.outertune.db.daos.ListenDao.CodeCount
import com.dd3boh.outertune.db.daos.ListenDao.EndCount
import com.dd3boh.outertune.db.daos.TeamOutcome
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * ignored count: pending, unseen, dropped and lost cards were never a fair question. Pool picks
 * are left out by the query, since they were never on screen (see EngineSql.GRADED_BY_TEAM).
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
    /**
     * Another source fills Quick picks and the engine has no judged cards of its own to count.
     * [waiting] is how many it showed while it was the source that are not judged yet: they will
     * be, and then show as numbers from before, so "it has nothing to show" was untrue beside
     * Cards you saw listing them.
     */
    data class NotSource(val waiting: Int = 0) : DoingSummary

    /** The engine fills Quick picks but nothing it showed has been judged yet. */
    data object Waiting : DoingSummary

    /**
     * [fromBefore] when these are from an earlier time: the engine is not in the row now. [trend]
     * is null when there is none to give and never will be: from before, with too few new cards,
     * the fortnights only fill with nothing, and "too early" would stay up for good. [waiting] is
     * how many more of its cards were seen and are not judged yet, so the count at the top and
     * Cards you saw further down are seen to be the same cards.
     */
    data class Numbers(val cards: CardCounts, val trend: Trend?, val fromBefore: Boolean, val waiting: Int = 0) : DoingSummary
}

fun doingSummary(engineShowing: Boolean, teams: List<TeamCards>, trend: CardTrendRow?, waiting: Int = 0): DoingSummary {
    val engine = teams.firstOrNull { it.team == ENGINE_TEAM }?.cards
    if (engine == null || engine.seen == 0) return if (engineShowing) DoingSummary.Waiting else DoingSummary.NotSource(waiting)
    val t = trend?.let {
        trendOf(CardCounts(it.recentSeen, it.recentPlayed), CardCounts(it.earlierSeen, it.earlierPlayed))
    } ?: Trend.TooEarly
    val fromBefore = !engineShowing
    return DoingSummary.Numbers(engine, t.takeUnless { fromBefore && it == Trend.TooEarly }, fromBefore, waiting)
}

/**
 * The sources with cards to show under Cards you saw, in team order. Judged is the count every
 * other figure on the page uses; the cards still to be judged and those left out are named
 * beside it rather than added in, so no number here differs from the same thing counted above.
 */
fun cardsSeen(rows: List<CardsSeenRow>): List<CardsSeenRow> =
    rows.filter { it.judged + it.waiting + it.leftOut > 0 }.sortedBy { it.team }

/**
 * A source with cards seen but none judged yet reads "YouTube Music: 4 not judged yet", not
 * "YouTube Music: 0, 4 more not judged yet".
 */
fun onlyWaiting(row: CardsSeenRow): Boolean = row.judged == 0 && row.waiting > 0

/** How Recent listens and How they ended name the way a listen ended. */
enum class EndLabel { REACHED_END, ENDED_EARLY, SKIPPED, REPLACED, STOPPED, IN_PROGRESS, NOT_RECORDED }

/**
 * Heard less than this share of a song the player took to its end, and it ended early: a jump to
 * the last seconds, or a stream that gave out. "Reached the end, 9% heard" read as a contradiction.
 * EngineSql.LISTENS_BY_END draws the same line.
 */
const val ENDED_EARLY_BELOW = 0.8

/** True for a listen the player took to its end with well under the song heard. An unknown length tells nothing. */
fun endedEarly(endReason: Int, ratio: Float): Boolean =
    endReason == EndReason.ENDED && ratio >= 0f && ratio < ENDED_EARLY_BELOW

fun endLabel(endReason: Int, early: Boolean): EndLabel = when (endReason) {
    EndReason.ENDED -> if (early) EndLabel.ENDED_EARLY else EndLabel.REACHED_END
    EndReason.SKIPPED -> EndLabel.SKIPPED
    EndReason.REPLACED -> EndLabel.REPLACED
    EndReason.STOPPED -> EndLabel.STOPPED
    // Still playing, or paused: its row is closed when it stops.
    EndReason.OPEN -> EndLabel.IN_PROGRESS
    // The old play log, which never kept how a play ended.
    else -> EndLabel.NOT_RECORDED
}

fun endLabel(endReason: Int, ratio: Float): EndLabel = endLabel(endReason, endedEarly(endReason, ratio))

/** How they ended: one count per label, in [EndLabel] order, so not recorded comes last and once. */
fun endCounts(rows: List<EndCount>): List<Pair<EndLabel, Int>> =
    rows.groupBy { endLabel(it.code, it.early) }.mapValues { (_, r) -> r.sumOf { it.n } }
        .filterValues { it > 0 }.toSortedMap().toList()

/**
 * Where they were started from: one count per origin, in the order of the codes, with the ones
 * that say nothing (unknown, or a code this version does not know) together and last.
 */
fun originCounts(rows: List<CodeCount>): List<Pair<PlayOrigin, Int>> {
    val byOrigin = rows.groupBy { PlayOrigin.fromCode(it.code) }.mapValues { (_, r) -> r.sumOf { it.n } }.filterValues { it > 0 }
    return byOrigin.filterKeys { it != PlayOrigin.UNKNOWN }.toList().sortedBy { it.first.code } +
        listOfNotNull(byOrigin[PlayOrigin.UNKNOWN]?.let { PlayOrigin.UNKNOWN to it })
}

/**
 * How long a listen played, as a clock reads it: 4:04, or 1:02:09 past the hour. Null under a
 * minute, which reads better as seconds: "22s".
 */
fun clockLength(ms: Long): String? {
    val total = ms / 1000
    if (total < 60) return null
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** Which day a listen in Recent listens ended on, as the list names it beside the time. */
enum class ListenDay { TODAY, YESTERDAY, EARLIER }

/**
 * Today, yesterday or earlier, by the calendar in [zone]. The list gave the time alone, so a
 * listen at 10:37 AM today sat above one at 10:12 PM yesterday and the newest-first order looked
 * wrong. A listen stamped later than [now], from a clock set back, is today.
 */
fun listenDay(endedAt: Long, now: Long, zone: ZoneId): ListenDay {
    val day = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> ListenDay.TODAY
        day == today.minusDays(1) -> ListenDay.YESTERDAY
        else -> ListenDay.EARLIER
    }
}

/**
 * One group of How well it predicts: the cards it gave a chance from [fromPer100] up to
 * [toPer100] in 100, and how many of them were played. Counted, not given as a share, so the row
 * says "in 100" for chances and plain numbers for cards, never percent beside it.
 */
data class PredictionBand(val fromPer100: Int, val toPer100: Int, val cards: Int, val played: Int)

/** The groups with cards in them, lowest chance first, by the same bands as the reliability table. */
fun predictionBands(pairs: List<Pair<Double, Double>>, bands: Int = 5): List<PredictionBand> =
    Calibration.reliability(pairs, bands).filter { it.count > 0 }.map { b ->
        PredictionBand((b.lo * 100).roundToInt(), (b.hi * 100).roundToInt(), b.count, (b.playRate * b.count).roundToInt())
    }

/**
 * Whether any of the engine's own figures has something in it. With none, the rows that would
 * each say "Nothing yet" are left out, and the summary above says why in one line.
 */
fun hasNumbers(teams: List<TeamCards>, held: List<BuildScore>, predictions: Int, weightUpdates: Int): Boolean =
    teams.any { it.cards.seen > 0 } || held.isNotEmpty() || predictions > 0 || weightUpdates > 0

/**
 * What giving every card the same chance would have scored, that chance being the share that
 * turned out to be played: the plain bar a Brier score is read against. A score of 0.012 alone read
 * as near perfect beside a row saying 11 in 100 were expected and none played; with none played
 * this bar is 0, and any chance above nothing does worse. NaN with no cards.
 */
fun brierReference(pairs: List<Pair<Double, Double>>): Double {
    if (pairs.isEmpty()) return Double.NaN
    val played = pairs.count { it.second >= 0.5 }.toDouble() / pairs.size
    return played * (1 - played)
}

enum class BrierVerdict { BETTER, SAME, WORSE }

/** Lower is better. Compared as shown, to three decimals, so two numbers that read alike are the same. */
fun brierVerdict(score: Double, reference: Double): BrierVerdict {
    val s = (score * 1000).roundToInt()
    val r = (reference * 1000).roundToInt()
    return when {
        s < r -> BrierVerdict.BETTER
        s > r -> BrierVerdict.WORSE
        else -> BrierVerdict.SAME
    }
}

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

