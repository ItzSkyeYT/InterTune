/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin

/** What one pass over a period needs, all of it read before the pass starts. */
class StatsInput(
    val now: Long,
    /** The phone's offset from UTC now, which decides the local day that today is. */
    val nowOffsetMin: Int,
    /** The first instant of the period, true UTC; 0 for All. */
    val periodStart: Long,
    /** Every closed listen that ended inside the period, in any order. */
    val listens: List<StatsListen>,
    /** One row per song heard before [periodStart]; empty for All. */
    val before: List<StatsSongBefore>,
    /** Each song's first credited artist, by song id; a song with none is absent. */
    val artists: Map<String, String>,
    /** The same length of time just before [periodStart]; null for All. */
    val previous: StatsTotals?,
    val bounds: StatsBounds,
)

/** Everything the page says about one period. */
data class ListeningStats(
    val summary: Summary?,
    val hours: HourProfile?,
    /** Strongest first, and mixed across kinds, so the top of the list is never five songs. */
    val insights: List<Insight>,
) {
    companion object {
        val EMPTY = ListeningStats(null, null, emptyList())
    }
}

data class Summary(
    val playedMs: Long,
    /** [playedMs] over the days the period and the log have in common, when that is two days or more. */
    val perDayMs: Long?,
    /** Songs and artists with at least one play in the period. */
    val songs: Int,
    val artists: Int,
    /** This period's listening over the same length before it, when the log covers both. */
    val change: Double?,
    /** The local day the log begins, when that is inside the period, as it always is for All. */
    val sinceDay: Long?,
)

/** Listening time in each local hour of the day, 0 to 23. */
data class HourProfile(val msByHour: List<Long>, val peakHour: Int)

/** Where listening was started from, in fewer and plainer groups than [PlayOrigin]. */
enum class ListenSource {
    QUICK_PICKS, RADIO, SEARCH, LIBRARY, HOME, RECOGNISED, OTHER;

    companion object {
        fun of(origin: Int): ListenSource = when (PlayOrigin.fromCode(origin)) {
            PlayOrigin.QUICK_PICKS, PlayOrigin.WIDGET -> QUICK_PICKS
            PlayOrigin.RADIO -> RADIO
            PlayOrigin.SEARCH -> SEARCH
            PlayOrigin.RECOGNISED -> RECOGNISED
            PlayOrigin.HOME_ROW, PlayOrigin.DISCOVER -> HOME
            PlayOrigin.PLAYLIST, PlayOrigin.ALBUM, PlayOrigin.ARTIST, PlayOrigin.LIBRARY,
            PlayOrigin.LOCAL_FILES, PlayOrigin.HISTORY, PlayOrigin.STATS -> LIBRARY
            else -> OTHER
        }
    }
}

/**
 * One thing worth saying about a period. Days are local epoch days, worked out from the offset
 * stored on each listen, so a play at 23:30 in Paris belongs to that evening and not to the next
 * UTC day. A [sinceDay] says the finding rests only on the live log, which began after the period
 * did: skips and sources were never recorded before it.
 */
sealed interface Insight {
    /** The song the finding is about, for its picture and for a tap to play it. */
    val songId: String? get() = null

    /** The artist the finding is about, for a tap to open them. */
    val artistId: String? get() = null

    /** The most plays one song had in one day, and the most of them that came back to back. */
    data class SongOfTheDay(override val songId: String, val plays: Int, val day: Long, val inARow: Int) : Insight

    /** The longest run of one song played back to back, when it is not already [SongOfTheDay]. */
    data class OnRepeat(override val songId: String, val times: Int, val day: Long) : Insight

    /** A song back after a long silence, with the plays since it came back. */
    data class Comeback(override val songId: String, val daysAway: Int, val playsSince: Int) : Insight

    /** When the period's top song was first heard, and how often it has been played since. */
    data class TopSongFound(override val songId: String, val firstDay: Long, val playsSince: Int) : Insight

    /** One song played on this many days in a row. */
    data class SongStreak(override val songId: String, val days: Int, val firstDay: Long) : Insight

    /** The share of listening that went to songs never heard before the period, and new artists. */
    data class NewToYou(val share: Double, val newArtists: Int) : Insight

    /** The artist first heard in the period who was played the most. */
    data class BestFind(override val artistId: String, val firstDay: Long, val plays: Int) : Insight

    data class Skips(val skipped: Double, val finished: Double, val sinceDay: Long?) : Insight

    /** A song skipped again and again and still played through again and again. */
    data class LoveHate(override val songId: String, val skips: Int, val plays: Int, val sinceDay: Long?) : Insight

    /** An artist heard to the end far more often than everything else. */
    data class AlwaysFinish(override val artistId: String, val finished: Double, val overall: Double, val sinceDay: Long?) : Insight

    /** Shares of listening time by where it was started from, largest first. */
    data class WhereFrom(val shares: List<Pair<ListenSource, Double>>, val sinceDay: Long?) : Insight

    /** Listening per weekend day over listening per weekday. */
    data class Weekends(val ratio: Double) : Insight

    data class BiggestDay(val day: Long, val playedMs: Long) : Insight

    data class LongestSession(val day: Long, val playedMs: Long) : Insight

    /** Nights with a play started after midnight, and the latest such start, in minutes past midnight. */
    data class LateNights(val nights: Int, val latestMinute: Int) : Insight

    /** The artist whose plays lean hardest towards the night. */
    data class NightArtist(override val artistId: String, val share: Double) : Insight

    /** The longest run of days with any listening at all. */
    data class EveryDay(val days: Int, val firstDay: Long, val lastDay: Long) : Insight

    /** The artist played in the most different weeks. */
    data class Loyal(override val artistId: String, val weeks: Int, val ofWeeks: Int) : Insight
}

/**
 * The part of the Stats page that says something about how you listen rather than listing what
 * you listened to.
 *
 * Plain Kotlin over rows of the listen log, so every rule is tested on the JVM with made-up
 * listening. A finding carries numbers, ids and days only; the screen words it. A finding the data
 * cannot back is left out rather than shown weak: too few listens, nothing notable, or a log too
 * short to tell. Nothing here reads the clock or the time zone.
 *
 * Two kinds of history sit in the log. Listens backfilled from the old play log are counted plays
 * only, with an unknown end and origin, because that log never kept anything else. The live log,
 * from 0.11, keeps every stop including skips. So anything about skips or sources reads only live
 * rows, and says since when; and a comparison that reaches back into the backfill compares counted
 * listening on both sides, or the live side would win by the skips the old side never recorded.
 *
 * A play is a counted listen. A pause long enough to close the row and a return to the same spot
 * leaves two rows for one play, linked by continuesListenId; the second is not a second play when
 * the first already counted.
 */
object ListeningInsights {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    // Below these, nothing is said at all.
    const val MIN_PLAYS = 3
    const val MIN_LISTENING_MS = MINUTE_MS

    /** The comparison needs this much in the period before, or "up 4000%" is all it can say. */
    const val MIN_PREVIOUS_MS = 10 * MINUTE_MS

    const val CHART_MIN_MS = HOUR_MS
    const val CHART_MIN_DAYS = 3

    const val WEEKEND_MIN_MS = 2 * HOUR_MS
    /** Weekends must be this many times weekdays, or weekdays this many times weekends. */
    const val WEEKEND_RATIO = 1.25

    const val BIGGEST_DAY_MIN_MS = HOUR_MS
    const val BIGGEST_DAY_MIN_DAYS = 3
    const val SESSION_MIN_MS = HOUR_MS

    const val SONG_OF_THE_DAY_MIN = 5
    const val ON_REPEAT_MIN = 5
    const val SONG_STREAK_MIN = 4

    const val COMEBACK_GAP_DAYS = 30
    const val COMEBACK_MIN_PLAYS = 3

    const val TOP_SONG_MIN_PLAYS = 5
    /** A first play this close to the start of the log may only be where the log starts. */
    const val TOP_SONG_LEAD_DAYS = 14

    /** "New to you" needs the log to reach back before the period at least this far, or the period's length if longer. */
    const val NEW_LEAD_DAYS = 30
    const val NEW_MIN_PLAYS = 20
    const val BEST_FIND_MIN_PLAYS = 5

    /** Live listens needed before saying anything about skips or sources. */
    const val LIVE_MIN_LISTENS = 30
    const val LOVE_HATE_MIN = 3
    const val FINISH_MIN_LISTENS = 10
    const val FINISH_MIN_SHARE = 0.6
    /** How far above the overall rate an artist's must be to be worth a line. */
    const val FINISH_ABOVE_OVERALL = 0.25
    const val SOURCE_MIN_SHARE = 0.25

    /** A play starting before this hour, local, is past midnight. */
    const val LATE_NIGHT_END_HOUR = 5
    const val LATE_NIGHTS_MIN = 2
    /** From this hour to [LATE_NIGHT_END_HOUR] is night, for the night artist. */
    const val NIGHT_START_HOUR = 23
    const val NIGHT_ARTIST_MIN_PLAYS = 10
    const val NIGHT_ARTIST_MIN_SHARE = 0.4
    /** An artist's night share must be at least this many times everyone's. */
    const val NIGHT_ARTIST_OVER_OVERALL = 2.0

    const val EVERY_DAY_MIN = 14

    const val LOYAL_MIN_DAYS = 28
    const val LOYAL_MIN_WEEKS = 4
    const val LOYAL_MIN_SHARE = 0.5

    /** One listen, with its artist and its local times worked out once. */
    private class Row(val l: StatsListen, val artistId: String?) {
        // One row in one real library was closed without ever being opened and carried a start of
        // 0, 1970. Its end and length are right, so it is dated back from those.
        val start: Long = if (l.startedAt > 0) l.startedAt else l.endedAt - l.playedMs.coerceAtLeast(0)
        val local: Long = start + l.tzOffsetMin * MINUTE_MS
        val day: Long = Math.floorDiv(local, DAY_MS)
        val minute: Int = (Math.floorMod(local, DAY_MS) / MINUTE_MS).toInt()
        val hour: Int get() = minute / 60
        val played: Long = l.playedMs.coerceAtLeast(0)
        var play = false
    }

    /** The local epoch day of an instant at an offset. */
    fun localDay(atMs: Long, offsetMin: Int): Long = Math.floorDiv(atMs + offsetMin * MINUTE_MS, DAY_MS)

    /** 0 is Monday: day 0 was a Thursday. */
    fun weekday(day: Long): Int = Math.floorMod(day + 3, 7L).toInt()

    private fun isWeekend(day: Long) = weekday(day) >= 5

    /** Weeks starting on Monday, numbered from the one holding day 0. */
    private fun week(day: Long): Long = Math.floorDiv(day + 3, 7L)

    fun compute(input: StatsInput): ListeningStats {
        val rows = input.listens.map { Row(it, input.artists[it.songId]) }.sortedWith(compareBy<Row>({ it.start }, { it.l.id }))
        if (rows.isEmpty()) return ListeningStats.EMPTY

        val countedIds = HashSet<Long>()
        val continuedIds = HashSet<Long>()
        for (r in rows) {
            if (r.l.counted) countedIds.add(r.l.id)
            r.l.continuesListenId?.let(continuedIds::add)
        }
        for (r in rows) r.play = r.l.counted && (r.l.continuesListenId == null || r.l.continuesListenId !in countedIds)
        val plays = rows.filter { it.play }
        val total = rows.sumOf { it.played }
        if (plays.size < MIN_PLAYS || total < MIN_LISTENING_MS) return ListeningStats.EMPTY

        val today = localDay(input.now, input.nowOffsetMin)
        val historyStart = input.bounds.firstAt ?: rows.first().start
        val logBeginsInside = historyStart > input.periodStart
        val firstDay = if (logBeginsInside) minOf(rows.first().day, today) else localDay(input.periodStart, input.nowOffsetMin)
        val days = (today - firstDay + 1).coerceAtLeast(1)
        val activeDays = rows.mapTo(HashSet()) { it.day }

        // The live log's own rows: a stop whose ending is known. The first half of a paused play
        // is left out, because its continuation carries how the play really ended.
        val live = rows.filter {
            it.l.endReason in LIVE_ENDINGS && it.l.id !in continuedIds
        }
        val liveSince = input.bounds.firstLiveAt
            ?.takeIf { it > maxOf(input.periodStart, historyStart) + DAY_MS }
            ?.let { firstLive -> live.firstOrNull()?.day ?: localDay(firstLive, input.nowOffsetMin) }

        val span = input.now - maxOf(input.periodStart, historyStart)
        val summary = Summary(
            playedMs = total,
            perDayMs = if (span >= 2 * DAY_MS) total * DAY_MS / span else null,
            songs = plays.mapTo(HashSet()) { it.l.songId }.size,
            artists = plays.mapNotNullTo(HashSet()) { it.artistId }.size,
            change = change(input, rows, total, historyStart),
            sinceDay = if (logBeginsInside) firstDay else null,
        )
        val hours = hourProfile(rows, total, activeDays.size)

        val songOfTheDay = songOfTheDay(plays)
        val onRepeat = onRepeat(rows)
        val repeat = buildList<Insight> {
            songOfTheDay?.let { add(it.copy(inARow = longestRun(rows.filter { r -> r.day == it.day }, it.songId))) }
            val comeback = comeback(plays, input)
            comeback?.let(::add)
            onRepeat?.takeIf { r -> songOfTheDay == null || r.songId != songOfTheDay.songId || r.day != songOfTheDay.day }?.let(::add)
            topSongFound(rows, plays, input, historyStart)
                ?.takeIf { it.songId != songOfTheDay?.songId && it.songId != comeback?.songId }
                ?.let(::add)
            songStreak(plays)?.let(::add)
        }
        val habit = buildList<Insight> {
            loveHate(rows, live, liveSince)?.let(::add)
            whereFrom(live, liveSince)?.let(::add)
            skips(live, liveSince)?.let(::add)
            alwaysFinish(live, liveSince)?.let(::add)
        }
        val time = buildList<Insight> {
            weekends(rows, total, firstDay, today)?.let(::add)
            val biggest = biggestDay(rows, activeDays.size)
            biggest?.let(::add)
            lateNights(plays)?.let(::add)
            longestSession(rows)?.takeIf { it.day != biggest?.day }?.let(::add)
            everyDay(activeDays)?.let(::add)
        }
        val artist = buildList<Insight> {
            nightArtist(plays)?.let(::add)
            loyal(plays, firstDay, today, days)?.let(::add)
        }
        val new = buildList<Insight> {
            val beforeIds = input.before.mapTo(HashSet()) { it.songId }
            val beforeArtists = input.before.mapNotNullTo(HashSet()) { input.artists[it.songId] }
            if (canTellNew(input, historyStart)) {
                newToYou(rows, plays, total, beforeIds, beforeArtists)?.let(::add)
                bestFind(plays, beforeArtists)?.let(::add)
            }
        }
        return ListeningStats(summary, hours, interleave(listOf(repeat, habit, time, artist, new)))
    }

    private val LIVE_ENDINGS = setOf(EndReason.ENDED, EndReason.SKIPPED, EndReason.REPLACED, EndReason.STOPPED, EndReason.ERROR)

    /** First of each kind, then the second of each, and so on. */
    private fun interleave(kinds: List<List<Insight>>): List<Insight> = buildList {
        var i = 0
        while (true) {
            val round = kinds.mapNotNull { it.getOrNull(i) }
            if (round.isEmpty()) break
            addAll(round)
            i++
        }
    }

    private fun change(input: StatsInput, rows: List<Row>, total: Long, historyStart: Long): Double? {
        val previous = input.previous ?: return null
        if (input.periodStart <= 0) return null
        val length = input.now - input.periodStart
        // A previous period the log only partly covers would make any period look like growth.
        if (historyStart > input.periodStart - length) return null
        val legacy = previous.legacyListens > 0 || rows.any { it.l.endReason == EndReason.UNKNOWN }
        val current = if (legacy) rows.filter { it.l.counted }.sumOf { it.played } else total
        val before = if (legacy) previous.countedMs else previous.playedMs
        if (before < MIN_PREVIOUS_MS) return null
        return current.toDouble() / before
    }

    /**
     * Each listen's time spread over the hours it ran through, from its start. A four hour mix
     * started at ten is four hours of the evening, not four hours at ten.
     */
    private fun hourProfile(rows: List<Row>, total: Long, activeDays: Int): HourProfile? {
        if (total < CHART_MIN_MS || activeDays < CHART_MIN_DAYS) return null
        val ms = LongArray(24)
        for (r in rows) {
            var at = r.local
            var left = r.played
            while (left > 0) {
                val chunk = minOf(left, HOUR_MS - Math.floorMod(at, HOUR_MS))
                ms[(Math.floorMod(at, DAY_MS) / HOUR_MS).toInt()] += chunk
                at += chunk
                left -= chunk
            }
        }
        val peak = ms.indices.maxBy { ms[it] }
        return HourProfile(ms.toList(), peak)
    }

    private fun songOfTheDay(plays: List<Row>): Insight.SongOfTheDay? {
        val count = HashMap<Pair<String, Long>, Int>()
        for (p in plays) count.merge(p.l.songId to p.day, 1, Int::plus)
        val best = count.entries.maxWithOrNull(compareBy({ it.value }, { it.key.second })) ?: return null
        if (best.value < SONG_OF_THE_DAY_MIN) return null
        return Insight.SongOfTheDay(best.key.first, best.value, best.key.second, 0)
    }

    /** The longest run of [songId] in [rows], by the same rule as [onRepeat]. */
    private fun longestRun(rows: List<Row>, songId: String): Int {
        var best = 0
        var song: String? = null
        var session = 0L
        var run = 0
        for (r in rows) {
            if (r.l.songId != song || r.l.sessionId != session) {
                song = r.l.songId
                session = r.l.sessionId
                run = 0
            }
            if (!r.play) continue
            run++
            if (r.l.songId == songId && run > best) best = run
        }
        return best
    }

    /**
     * Plays of one song with nothing else in between, in one session. A skipped row of the same
     * song, such as the first half of a paused play, does not break the run; any other song does.
     */
    private fun onRepeat(rows: List<Row>): Insight.OnRepeat? {
        var bestTimes = 0
        var bestSong = ""
        var bestDay = 0L
        var song: String? = null
        var session = 0L
        var run = 0
        for (r in rows) {
            if (r.l.songId != song || r.l.sessionId != session) {
                song = r.l.songId
                session = r.l.sessionId
                run = 0
            }
            if (!r.play) continue
            run++
            // At least as long, so the latest of equal runs wins.
            if (run >= bestTimes) {
                bestTimes = run
                bestSong = r.l.songId
                bestDay = r.day
            }
        }
        return if (bestTimes >= ON_REPEAT_MIN) Insight.OnRepeat(bestSong, bestTimes, bestDay) else null
    }

    /**
     * The song with the most plays since coming back from a silence of [COMEBACK_GAP_DAYS] or
     * more, the return inside the period. The silence may begin before it, which is what the last
     * play before the period is read for. Of several returns, the latest.
     */
    private fun comeback(plays: List<Row>, input: StatsInput): Insight.Comeback? {
        val lastBefore = input.before.associate { it.songId to it.lastPlayAt }
        var best: Insight.Comeback? = null
        for ((song, own) in plays.groupBy { it.l.songId }) {
            if (own.size < COMEBACK_MIN_PLAYS) continue
            val starts = ArrayList<Long>(own.size + 1)
            lastBefore[song]?.let(starts::add)
            own.mapTo(starts) { it.start }
            for (i in starts.indices.reversed()) {
                if (i == 0) break
                val gap = starts[i] - starts[i - 1]
                val since = starts.size - i
                if (since < COMEBACK_MIN_PLAYS) continue
                if (gap < COMEBACK_GAP_DAYS * DAY_MS || starts[i] < input.periodStart) continue
                val found = Insight.Comeback(song, (gap / DAY_MS).toInt(), since)
                if (best == null || since > best.playsSince || (since == best.playsSince && found.daysAway > best.daysAway)) best = found
                break
            }
        }
        return best
    }

    /**
     * The period's top song by time played, the order of the Most played list under it, and when
     * it was first heard. Only when that first listen is clear of the start of the log, which
     * would otherwise be passed off as a discovery.
     */
    private fun topSongFound(rows: List<Row>, plays: List<Row>, input: StatsInput, historyStart: Long): Insight.TopSongFound? {
        val time = HashMap<String, Long>()
        val count = HashMap<String, Int>()
        for (p in plays) {
            time.merge(p.l.songId, p.played, Long::plus)
            count.merge(p.l.songId, 1, Int::plus)
        }
        val top = time.entries.maxWithOrNull(compareBy({ it.value }, { count[it.key] ?: 0 }))?.key ?: return null
        val inPeriod = count[top] ?: 0
        if (inPeriod < TOP_SONG_MIN_PLAYS) return null
        val before = input.before.firstOrNull { it.songId == top }
        val firstRow = rows.first { it.l.songId == top }
        val firstAt = minOf(before?.firstAt ?: Long.MAX_VALUE, firstRow.start)
        if (firstAt < historyStart + TOP_SONG_LEAD_DAYS * DAY_MS) return null
        val firstDay = if (before != null && before.firstAt < firstRow.start) {
            localDay(before.firstAt, firstRow.l.tzOffsetMin)
        } else firstRow.day
        return Insight.TopSongFound(top, firstDay, inPeriod + (before?.playsBefore ?: 0))
    }

    private fun songStreak(plays: List<Row>): Insight.SongStreak? {
        var best: Insight.SongStreak? = null
        for ((song, own) in plays.groupBy { it.l.songId }) {
            if (own.size < SONG_STREAK_MIN) continue
            val days = own.mapTo(sortedSetOf()) { it.day }.toList()
            var runStart = days[0]
            var run = 1
            for (i in days.indices) {
                if (i > 0) {
                    if (days[i] == days[i - 1] + 1) run++ else { run = 1; runStart = days[i] }
                }
                val b = best
                if (b == null || run > b.days || (run == b.days && runStart > b.firstDay)) best = Insight.SongStreak(song, run, runStart)
            }
        }
        return best?.takeIf { it.days >= SONG_STREAK_MIN }
    }

    /** "New" means new to the log, so the log has to reach well back before the period. */
    private fun canTellNew(input: StatsInput, historyStart: Long): Boolean {
        if (input.periodStart <= 0) return false
        val lead = maxOf(NEW_LEAD_DAYS * DAY_MS, input.now - input.periodStart)
        return historyStart <= input.periodStart - lead
    }

    private fun newToYou(rows: List<Row>, plays: List<Row>, total: Long, beforeIds: Set<String>, beforeArtists: Set<String>): Insight.NewToYou? {
        if (plays.size < NEW_MIN_PLAYS) return null
        val newMs = rows.filter { it.l.songId !in beforeIds }.sumOf { it.played }
        val share = newMs.toDouble() / total
        // Under half a percent reads as 0%, which is not a finding.
        if (share < 0.005) return null
        val newArtists = plays.mapNotNullTo(HashSet()) { p -> p.artistId?.takeIf { it !in beforeArtists } }.size
        return Insight.NewToYou(share, newArtists)
    }

    private fun bestFind(plays: List<Row>, beforeArtists: Set<String>): Insight.BestFind? {
        val byArtist = plays.filter { it.artistId != null && it.artistId !in beforeArtists }.groupBy { it.artistId!! }
        val best = byArtist.entries.maxWithOrNull(compareBy({ it.value.size }, { e -> e.value.sumOf { it.played } })) ?: return null
        if (best.value.size < BEST_FIND_MIN_PLAYS) return null
        return Insight.BestFind(best.key, best.value.first().day, best.value.size)
    }

    private fun skips(live: List<Row>, sinceDay: Long?): Insight.Skips? {
        if (live.size < LIVE_MIN_LISTENS) return null
        val skipped = live.count { it.l.endReason == EndReason.SKIPPED }
        val finished = live.count { it.l.endReason == EndReason.ENDED }
        if (skipped == 0 || finished == 0) return null
        return Insight.Skips(skipped.toDouble() / live.size, finished.toDouble() / live.size, sinceDay)
    }

    /**
     * Skips from the live log against plays that were not skips. A skip past the play threshold
     * is also a counted play, and "skipped it 16 times and still played it 28" must not count
     * the same stop on both sides.
     */
    private fun loveHate(rows: List<Row>, live: List<Row>, sinceDay: Long?): Insight.LoveHate? {
        if (live.size < LIVE_MIN_LISTENS) return null
        val skips = HashMap<String, Int>()
        for (r in live) if (r.l.endReason == EndReason.SKIPPED) skips.merge(r.l.songId, 1, Int::plus)
        val kept = HashMap<String, Int>()
        for (r in rows) if (r.play && r.l.endReason != EndReason.SKIPPED) kept.merge(r.l.songId, 1, Int::plus)
        val best = skips.entries
            .map { Triple(it.key, it.value, kept[it.key] ?: 0) }
            .filter { it.second >= LOVE_HATE_MIN && it.third >= LOVE_HATE_MIN }
            .maxWithOrNull(compareBy({ minOf(it.second, it.third) }, { it.second + it.third }))
            ?: return null
        return Insight.LoveHate(best.first, best.second, best.third, sinceDay)
    }

    private fun alwaysFinish(live: List<Row>, sinceDay: Long?): Insight.AlwaysFinish? {
        if (live.size < LIVE_MIN_LISTENS) return null
        val decided = live.filter { it.l.endReason == EndReason.ENDED || it.l.endReason == EndReason.SKIPPED }
        if (decided.isEmpty()) return null
        val overall = decided.count { it.l.endReason == EndReason.ENDED }.toDouble() / decided.size
        val best = decided.filter { it.artistId != null }.groupBy { it.artistId!! }
            .filterValues { it.size >= FINISH_MIN_LISTENS }
            .map { (artist, own) -> Triple(artist, own.count { it.l.endReason == EndReason.ENDED }.toDouble() / own.size, own.size) }
            .filter { it.second >= maxOf(FINISH_MIN_SHARE, overall + FINISH_ABOVE_OVERALL) }
            .maxWithOrNull(compareBy({ it.second }, { it.third }))
            ?: return null
        return Insight.AlwaysFinish(best.first, best.second, overall, sinceDay)
    }

    /** By time rather than by count, so a radio skipped through ten times is not ten times a radio. */
    private fun whereFrom(live: List<Row>, sinceDay: Long?): Insight.WhereFrom? {
        if (live.size < LIVE_MIN_LISTENS) return null
        val ms = HashMap<ListenSource, Long>()
        for (r in live) ms.merge(ListenSource.of(r.l.origin), r.played, Long::plus)
        val total = ms.values.sum()
        if (total <= 0) return null
        val shares = ms.entries.map { it.key to it.value.toDouble() / total }.sortedByDescending { it.second }
        // The sentence names the largest known source, never "other".
        val top = shares.firstOrNull { it.first != ListenSource.OTHER } ?: return null
        if (top.second < SOURCE_MIN_SHARE) return null
        return Insight.WhereFrom(listOf(top) + shares.filter { it != top }, sinceDay)
    }

    private fun weekends(rows: List<Row>, total: Long, firstDay: Long, today: Long): Insight.Weekends? {
        if (total < WEEKEND_MIN_MS) return null
        var weekendDays = 0
        var weekDays = 0
        for (d in firstDay..today) if (isWeekend(d)) weekendDays++ else weekDays++
        if (weekendDays < 2 || weekDays < 4) return null
        val weekendMs = rows.filter { isWeekend(it.day) }.sumOf { it.played }
        val weekMs = total - weekendMs
        if (weekendMs <= 0 || weekMs <= 0) return null
        val ratio = (weekendMs.toDouble() / weekendDays) / (weekMs.toDouble() / weekDays)
        return if (ratio >= WEEKEND_RATIO || ratio <= 1 / WEEKEND_RATIO) Insight.Weekends(ratio) else null
    }

    private fun biggestDay(rows: List<Row>, activeDays: Int): Insight.BiggestDay? {
        if (activeDays < BIGGEST_DAY_MIN_DAYS) return null
        val byDay = HashMap<Long, Long>()
        for (r in rows) byDay.merge(r.day, r.played, Long::plus)
        val best = byDay.entries.maxWithOrNull(compareBy({ it.value }, { it.key })) ?: return null
        if (best.value < BIGGEST_DAY_MIN_MS) return null
        return Insight.BiggestDay(best.key, best.value)
    }

    /** By time heard, not from first start to last stop, which would count every pause. */
    private fun longestSession(rows: List<Row>): Insight.LongestSession? {
        val bySession = rows.groupBy { it.l.sessionId }
        if (bySession.size < 2) return null
        val best = bySession.values.maxWithOrNull(compareBy({ s -> s.sumOf { it.played } }, { s -> s.first().start })) ?: return null
        val ms = best.sumOf { it.played }
        if (ms < SESSION_MIN_MS) return null
        return Insight.LongestSession(best.first().day, ms)
    }

    private fun lateNights(plays: List<Row>): Insight.LateNights? {
        val late = plays.filter { it.hour < LATE_NIGHT_END_HOUR }
        val nights = late.mapTo(HashSet()) { it.day }.size
        if (nights < LATE_NIGHTS_MIN) return null
        return Insight.LateNights(nights, late.maxOf { it.minute })
    }

    private fun nightArtist(plays: List<Row>): Insight.NightArtist? {
        fun night(r: Row) = r.hour >= NIGHT_START_HOUR || r.hour < LATE_NIGHT_END_HOUR
        val credited = plays.filter { it.artistId != null }
        if (credited.isEmpty()) return null
        val overall = credited.count(::night).toDouble() / credited.size
        val best = credited.groupBy { it.artistId!! }
            .filterValues { it.size >= NIGHT_ARTIST_MIN_PLAYS }
            .map { (artist, own) -> Triple(artist, own.count(::night).toDouble() / own.size, own.size) }
            .filter { it.second >= maxOf(NIGHT_ARTIST_MIN_SHARE, overall * NIGHT_ARTIST_OVER_OVERALL) }
            .maxWithOrNull(compareBy({ it.second }, { it.third }))
            ?: return null
        return Insight.NightArtist(best.first, best.second)
    }

    private fun everyDay(activeDays: Set<Long>): Insight.EveryDay? {
        val days = activeDays.sorted()
        var best: Insight.EveryDay? = null
        var runStart = days.first()
        for (i in days.indices) {
            if (i > 0 && days[i] != days[i - 1] + 1) runStart = days[i]
            val run = (days[i] - runStart + 1).toInt()
            if (run >= (best?.days ?: 0)) best = Insight.EveryDay(run, runStart, days[i])
        }
        return best?.takeIf { it.days >= EVERY_DAY_MIN }
    }

    private fun loyal(plays: List<Row>, firstDay: Long, today: Long, days: Long): Insight.Loyal? {
        if (days < LOYAL_MIN_DAYS) return null
        val ofWeeks = (week(today) - week(firstDay) + 1).toInt()
        val best = plays.filter { it.artistId != null }.groupBy { it.artistId!! }
            .map { (artist, own) -> Triple(artist, own.mapTo(HashSet()) { week(it.day) }.size, own.size) }
            .maxWithOrNull(compareBy({ it.second }, { it.third }))
            ?: return null
        if (best.second < LOYAL_MIN_WEEKS || best.second < ofWeeks * LOYAL_MIN_SHARE) return null
        return Insight.Loyal(best.first, best.second, ofWeeks)
    }
}
