/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import java.time.LocalDate

/**
 * A play of the year as the slides of [RecapMoments] read it: its song, the local day and the minute of
 * that day it began (from the offset kept with its listen), and the time heard. Recaps makes them from
 * its own rows, so what counts as a play, and the day and the hour it falls on, are the rest of the
 * Recap's.
 */
data class RecapPlay(val songId: String, val day: Long, val minute: Int, val playedMs: Long)

/**
 * Four more slides from the phone's own play history, nothing new collected and nothing sent (his
 * "Yeah can we do all the recap things", 24 Nov 2026): your seasons, the week you couldn't stop, your
 * 2 AM songs and the one you came back to. Each is worked out here from the year's plays and is null
 * where there is too little to say: its slide is then left out, never shown half empty.
 *
 * Songs tied on a count go by the time heard, then by their plays in the whole year, then by id, as the
 * rest of the Recap ranks songs. Plain Kotlin, tested on the JVM with made-up listening
 * (RecapMomentsTest).
 */
object RecapMoments {

    // ---- your seasons

    /** A season counts from this many plays in it: a few songs a week. */
    const val SEASON_MIN_PLAYS = 30

    /** Its song was played at least this often in it. */
    const val SEASON_SONG_MIN_PLAYS = 3

    /** Fewer seasons than this make no slide: one season is not a year's seasons. */
    const val SEASONS_MIN = 2

    /**
     * The season a local [day] is in, by the weather's count: 0 for December to February, 1 for March
     * to May, 2 for June to August, 3 for September to November. Names are the screen's, which knows the
     * phone's side of the equator: the same months are summer in the south. A calendar year's December
     * goes with its January and February.
     */
    fun seasonOf(day: Long): Int = LocalDate.ofEpochDay(day).monthValue % 12 / 3

    /**
     * One song a season, for each season with [SEASON_MIN_PLAYS] plays or more and a song played
     * [SEASON_SONG_MIN_PLAYS] times in it; null for fewer than [SEASONS_MIN] such seasons. The busiest
     * season takes its most played song first, and each one after takes its most played song that no
     * busier one took, where it has one: four times the same song would say nothing about the seasons.
     */
    fun seasons(plays: List<RecapPlay>): Seasons? {
        val year = counts(plays)
        val bySeason = plays.groupBy { seasonOf(it.day) }.filterValues { it.size >= SEASON_MIN_PLAYS }
        if (bySeason.size < SEASONS_MIN) return null
        val taken = HashSet<String>()
        val picked = ArrayList<Season>()
        val busiestFirst = bySeason.entries.sortedWith(compareByDescending<Map.Entry<Int, List<RecapPlay>>> { it.value.size }.thenBy { it.key })
        for ((season, own) in busiestFirst) {
            val songs = ranked(own, year).filter { it.plays >= SEASON_SONG_MIN_PLAYS }
            val song = songs.firstOrNull { it.songId !in taken } ?: songs.firstOrNull() ?: continue
            taken += song.songId
            picked += Season(season, own.size, song.songId, song.plays)
        }
        if (picked.size < SEASONS_MIN) return null
        return Seasons(picked.sortedBy { it.season })
    }

    // ---- the week you couldn't stop

    /** The days of a week. */
    const val WEEK_DAYS = 7

    /** One song took the week over from this many plays of it in seven days... */
    const val WEEK_MIN_PLAYS = 10

    /** ...that were at least one in this many of all the plays of those days: a fifth. */
    const val WEEK_SHARE_PARTS = 5

    /**
     * The seven days in which one song was played most, at least [WEEK_MIN_PLAYS] times and at least
     * one in [WEEK_SHARE_PARTS] of all the plays of those days. A week begins on a day the song was played, so
     * that its dates begin with it; one that would run past [lastDay], the Recap's last, stops there.
     * Of two weeks as full, the one where the song took the larger share, then the later one, as the
     * longest streak takes the latest of equal runs.
     */
    fun onRepeat(plays: List<RecapPlay>, lastDay: Long): OnRepeat? {
        val perDay = HashMap<Long, Int>()
        for (p in plays) perDay[p.day] = (perDay[p.day] ?: 0) + 1
        var best: Week? = null
        for ((song, own) in plays.groupBy { it.songId }) {
            if (own.size < WEEK_MIN_PLAYS) continue
            val days = own.map { it.day }.sorted()
            var end = 0
            for (i in days.indices) {
                if (i > 0 && days[i] == days[i - 1]) continue
                val from = days[i]
                val to = minOf(from + WEEK_DAYS - 1, lastDay)
                while (end < days.size && days[end] <= to) end++
                val count = end - i
                if (count < WEEK_MIN_PLAYS) continue
                val all = (from..to).sumOf { perDay[it] ?: 0 }
                if (count * WEEK_SHARE_PARTS < all) continue
                val week = Week(song, from, to, count, all)
                if (best == null || week.beats(best)) best = week
            }
        }
        val week = best ?: return null
        val own = plays.filter { it.songId == week.songId }.groupingBy { it.day }.eachCount()
        return OnRepeat(week.songId, week.from, week.to, week.count, week.all, (week.from..week.to).map { own[it] ?: 0 })
    }

    private class Week(val songId: String, val from: Long, val to: Long, val count: Int, val all: Int) {
        /** More plays of it; on as many, a larger share of the week (count over all, compared crosswise); then later; then by id. */
        fun beats(other: Week): Boolean = when {
            count != other.count -> count > other.count
            count.toLong() * other.all != other.count.toLong() * all -> count.toLong() * other.all > other.count.toLong() * all
            from != other.from -> from > other.from
            else -> songId < other.songId
        }
    }

    // ---- your 2 AM songs, and the morning's

    /**
     * The day begins at 04:00: a play from midnight until then is the night's, and belongs to the day
     * before, as one stays up late on a Friday rather than gets up early on a Saturday. From then the
     * morning runs to [MORNING_ENDS_MIN].
     */
    const val DAY_BEGINS_MIN = 4 * 60

    /** First thing in the morning ends at 10:00. */
    const val MORNING_ENDS_MIN = 10 * 60

    /** A part of the day speaks from this many plays in it, on this many nights or mornings, its song from this many of its own. */
    const val PART_MIN_PLAYS = 15
    const val PART_MIN_DAYS = 3
    const val PART_SONG_MIN_PLAYS = 3

    /**
     * What played after midnight and what played first thing in the morning, by the local time each
     * play began, each where it had enough ([PART_MIN_PLAYS] plays on [PART_MIN_DAYS] nights or mornings,
     * a song played [PART_SONG_MIN_PLAYS] times in it); null where neither had. The morning takes a song
     * of its own where it has one: the slide is about how the two differ.
     */
    fun nightAndMorning(plays: List<RecapPlay>): NightAndMorning? {
        val year = counts(plays)
        val night = part(plays.filter { it.minute < DAY_BEGINS_MIN }, { it.day - 1 }, year, avoid = null)
        val morning = part(plays.filter { it.minute in DAY_BEGINS_MIN until MORNING_ENDS_MIN }, { it.day }, year, avoid = night?.songId)
        if (night == null && morning == null) return null
        return NightAndMorning(night, morning)
    }

    private fun part(own: List<RecapPlay>, dayOf: (RecapPlay) -> Long, year: Map<String, Count>, avoid: String?): PartOfDay? {
        if (own.size < PART_MIN_PLAYS) return null
        val days = own.mapTo(HashSet(), dayOf).size
        if (days < PART_MIN_DAYS) return null
        val songs = ranked(own, year).filter { it.plays >= PART_SONG_MIN_PLAYS }
        val song = songs.firstOrNull { it.songId != avoid } ?: songs.firstOrNull() ?: return null
        return PartOfDay(own.size, days, song.songId, song.plays)
    }

    // ---- the one you came back to

    /** Away from a song this many days or more is dropping it: two months. */
    const val AWAY_MIN_DAYS = 60

    /** Loved before it was dropped, and again once found: [RUN_MIN_PLAYS] plays or more within [RUN_DAYS] days on each side. */
    const val RUN_DAYS = 30
    const val RUN_MIN_PLAYS = 5

    /**
     * The song loved, dropped for months and found again: [RUN_MIN_PLAYS] plays or more in the
     * [RUN_DAYS] days up to its last play before a gap of [AWAY_MIN_DAYS] days or more without it, and
     * as many in the [RUN_DAYS] days from its first play after. Of several, the longest away, then the
     * one played most on the two sides, then the one back last; null where no song was.
     */
    fun cameBack(plays: List<RecapPlay>): CameBack? {
        var best: CameBack? = null
        for ((song, own) in plays.groupBy { it.songId }) {
            if (own.size < 2 * RUN_MIN_PLAYS) continue
            val byDay = own.groupingBy { it.day }.eachCount().toSortedMap()
            val days = byDay.keys.toList()
            for (k in 1 until days.size) {
                val last = days[k - 1]
                val back = days[k]
                if (back - last - 1 < AWAY_MIN_DAYS) continue
                val before = byDay.subMap(last - RUN_DAYS + 1, last + 1).values.sum()
                val after = byDay.subMap(back, back + RUN_DAYS).values.sum()
                if (before < RUN_MIN_PLAYS || after < RUN_MIN_PLAYS) continue
                val found = CameBack(song, last, back, before, after, byDay.map { (day, n) -> DayPlays(day, n) })
                if (best == null || found.beats(best)) best = found
            }
        }
        return best
    }

    private fun CameBack.beats(other: CameBack): Boolean = when {
        awayDays != other.awayDays -> awayDays > other.awayDays
        before + after != other.before + other.after -> before + after > other.before + other.after
        backOn != other.backOn -> backOn > other.backOn
        else -> songId < other.songId
    }

    // ---- what they share

    /** The songs the four slides show, for the Recap's reader to name and give covers to. */
    fun songs(recap: Recap): List<String> = buildList {
        recap.seasons?.seasons?.mapTo(this) { it.songId }
        recap.onRepeat?.let { add(it.songId) }
        recap.nightAndMorning?.let { n -> listOfNotNull(n.night, n.morning).mapTo(this) { it.songId } }
        recap.cameBack?.let { add(it.songId) }
    }

    private class Count(val songId: String) {
        var plays = 0
        var ms = 0L
    }

    private fun counts(plays: List<RecapPlay>): Map<String, Count> {
        val out = LinkedHashMap<String, Count>()
        for (p in plays) out.getOrPut(p.songId) { Count(p.songId) }.apply {
            this.plays++
            ms += p.playedMs
        }
        return out
    }

    /** The songs of [part], the most played in it first: on equal plays the one heard longer in it, then the one played more in the [year], then by id. */
    private fun ranked(part: List<RecapPlay>, year: Map<String, Count>): List<Count> =
        counts(part).values.sortedWith(
            compareByDescending<Count> { it.plays }
                .thenByDescending { it.ms }
                .thenByDescending { year[it.songId]?.plays ?: 0 }
                .thenByDescending { year[it.songId]?.ms ?: 0L }
                .thenBy { it.songId }
        )
}

/** One song a season, for the seasons that had enough plays: two of them or more, in the order of the year. */
data class Seasons(val seasons: List<Season>) {
    /** The season with the most plays, the earlier of two as busy: its song plays under the slide. */
    val busiest: Season get() = seasons.maxWith(compareBy<Season>({ it.plays }, { -it.season }))
}

/** A season ([RecapMoments.seasonOf]), its plays, and its song, played [songPlays] times in it. */
data class Season(val season: Int, val plays: Int, val songId: String, val songPlays: Int)

/**
 * The week one song took over: [plays] of it from [firstDay] to [lastDay], of [allPlays] plays in all
 * those days, and how many of it on each of them ([byDay], [firstDay]'s first).
 */
data class OnRepeat(val songId: String, val firstDay: Long, val lastDay: Long, val plays: Int, val allPlays: Int, val byDay: List<Int>)

/** What played after midnight, and first thing in the morning, each where it had enough plays: never both missing. */
data class NightAndMorning(val night: PartOfDay?, val morning: PartOfDay?)

/** A part of the day: its plays, on how many days (nights, for the night), and its song, played [songPlays] times in it. */
data class PartOfDay(val plays: Int, val days: Int, val songId: String, val songPlays: Int)

/**
 * A song loved, dropped and found again: its last play before the gap ([lastBefore]) and its first after
 * ([backOn]), the plays in the [RecapMoments.RUN_DAYS] days on each side, and each day of the year it was
 * played, for the line of its year on the slide.
 */
data class CameBack(val songId: String, val lastBefore: Long, val backOn: Long, val before: Int, val after: Int, val byDay: List<DayPlays>) {
    /** The days in between with no play of it. */
    val awayDays: Int get() = (backOn - lastBefore - 1).toInt()
}

data class DayPlays(val day: Long, val plays: Int)
