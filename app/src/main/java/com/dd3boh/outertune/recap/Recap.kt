/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.stats.StatsBounds
import com.dd3boh.outertune.stats.StatsSongBefore

/** What one year's Recap is worked out from, all of it read before the pass starts. */
class RecapInput(
    val year: Int,
    val now: Long,
    /** The phone's offset from UTC now, which decides the local day that today is. */
    val nowOffsetMin: Int,
    /**
     * Every closed listen that ended since a little before the year began, in any order. Those
     * whose local day is not in the year are left out of it, and the ones before it count as
     * heard before.
     */
    val listens: List<RecapListen>,
    /** Every artist credited on the songs of [listens], each at its place. */
    val credits: List<RecapCredit>,
    /** Each song's first credited artist, for every song in the log; a song with none is absent. */
    val firstCredits: Map<String, String>,
    /** One row per song heard before the year: what tells a new song or artist from one that came back. */
    val before: List<StatsSongBefore>,
    /** The songs of [listens] that are liked. */
    val liked: Set<String>,
    val bounds: StatsBounds,
    /** What recognition named, from its history. */
    val heard: List<RecapHeard> = emptyList(),
    /** The Listen together rooms this phone was in. None are kept yet: see [RecapRoom]. */
    val rooms: List<RecapRoom> = emptyList(),
)

/**
 * One year on InterTune, as the Recap tells it. Numbers, ids and local epoch days only: the
 * screen words it and fetches the songs' names and covers. Days are local, from each listen's own
 * offset, as on the Stats page, so a song at 23:30 in Paris belongs to that evening.
 */
data class Recap(
    val year: Int,
    /** The log begins in this year: the Recap says "since you started on" its first day. */
    val firstYear: Boolean,
    /** The first day the Recap covers: the first listen in a first year, the 1st of January otherwise. */
    val sinceDay: Long,
    /** The last day it covers: today, or the 31st of December once the year is over. */
    val lastDay: Long,
    /** The year's first play, and the minute of its day it began. */
    val firstSong: FirstSong,
    /** All the time heard, skips included, as the Stats page counts it. */
    val playedMs: Long,
    val plays: Int,
    /** Songs with a play, and artists with a play by their first credit, as the Stats page counts them. */
    val songs: Int,
    val artists: Int,
    /** Listening on each day from [sinceDay] to [lastDay], in that order. */
    val msByDay: List<Long>,
    val biggestDay: DayAmount,
    /** The song heard longest, of those played three times or more. Null when none was. */
    val mostTime: SongAmount?,
    /** Listening in each local hour, 0 to 23, each listen spread over the hours it ran through. */
    val msByHour: List<Long>,
    val peakHour: Int,
    /** The song with the most plays started in [peakHour]. */
    val peakSong: SongAmount,
    /** Nights with a play started after midnight, when there were at least two. */
    val lateNights: LateNights?,
    /** The longest run of days with any listening, when it is two days or more. */
    val streak: Streak?,
    /** At most five, the most played first. */
    val topSongs: List<TopSong>,
    /** The songs ranked after them, sixth to thirtieth: covers from the year that give nothing away. */
    val alsoPlayed: List<String>,
    /** At most five, YouTube artists only, every credit counted. Empty when none had a play. */
    val topArtists: List<TopArtist>,
    /** Artists new this year: see [NewArtists]. Null when the log is too short to tell. */
    val newArtists: NewArtists?,
    /** What Auto brought in, when one of its finds was kept. */
    val auto: AutoFinds?,
    val recognition: Recognitions?,
    val rooms: Rooms?,
    /**
     * The year's songs in one order, the most played first and by the rule of the tops: at most
     * [Recaps.PLAYLIST] of them, the first [Recaps.TOP] being [topSongs] and the next ones
     * [alsoPlayed]. What the year card's Save as a playlist makes its playlist of. Empty only in a
     * Recap built by hand.
     */
    val ranking: List<String> = emptyList(),
    /** The song skipped most often this year, when one was skipped [Recaps.SKIPPED_MIN] times or more. */
    val mostSkipped: MostSkipped? = null,
    /** Where the year's plays came from, for someone with nothing for Only on InterTune: see [WhereFrom]. */
    val whereFrom: WhereFrom? = null,
) {
    /** How many days the Recap covers. */
    val days: Int get() = (lastDay - sinceDay + 1).toInt()
}

data class FirstSong(val songId: String, val day: Long, val minute: Int)

data class DayAmount(val day: Long, val ms: Long)

/** A song with what it amounts to on one slide: its time, its plays. */
data class SongAmount(val songId: String, val ms: Long, val plays: Int)

data class LateNights(val nights: Int, val latestMinute: Int)

data class Streak(
    val days: Int,
    val firstDay: Long,
    val lastDay: Long,
    /** The song there on the most days of the streak, then the most played in it. */
    val songId: String,
    val songDays: Int,
)

data class TopSong(
    val songId: String,
    val plays: Int,
    val ms: Long,
    /** When it was first heard at all, which may be before the year, and that local day and minute. */
    val firstHeardDay: Long,
    val firstHeardMinute: Int,
    /** Days with a play of it. */
    val days: Int,
    /** The day it was played most, the later of equal days, and how often. */
    val bestDay: Long,
    val bestDayPlays: Int,
    /** Its longest run of plays back to back in one session, and the day of it. */
    val inARow: Int,
    val inARowDay: Long,
    /** Plays in each month of the year, 1 to 12; months without one are absent. */
    val byMonth: Map<Int, Int>,
)

data class TopArtist(
    val artistId: String,
    val plays: Int,
    val ms: Long,
    /** Their songs with a play. */
    val songs: Int,
    /** Their most played song, and its plays. */
    val topSongId: String,
    val topSongPlays: Int,
)

/**
 * Artists heard this year who were not before. In a first year everyone is new, so it is those
 * found after the first month ([afterFirstMonth]). By first credit, as the Stats page finds them.
 */
data class NewArtists(
    val count: Int,
    val afterFirstMonth: Boolean,
    /** The most played of them, when one was played [Recaps.BEST_FIND_MIN_PLAYS] times or more. */
    val bestFind: BestFind?,
)

/** The artist found, the day of their first play, their plays since, and their most played song, whose cover stands in for a missing picture. */
data class BestFind(val artistId: String, val firstDay: Long, val plays: Int, val topSongId: String)

/**
 * Songs Auto brought: first heard from Quick picks, on Auto (its first chip), with nothing heard
 * of them before. [marks] has one per song in the order they came, [MARK_KEPT] when it was played
 * [Recaps.KEPT_PLAYS] times or more and [MARK_LIKED] when it is liked.
 */
data class AutoFinds(
    /** The day where-from began to be kept, when that is after [Recap.sinceDay]; null otherwise. */
    val sinceDay: Long?,
    val songs: Int,
    val kept: Int,
    val liked: Int,
    val marks: List<Int>,
    /** The find with the most plays since. */
    val stuck: SongAmount,
    val stuckFirstDay: Long,
) {
    companion object {
        const val MARK_KEPT = 1
        const val MARK_LIKED = 2
    }
}

data class Recognitions(
    /** Songs recognition named this year. */
    val count: Int,
    /** The first day one was named and the last: one day when they are the same. */
    val firstDay: Long,
    val lastDay: Long,
    /** The YouTube songs they were matched to, the latest first, at most three. */
    val songIds: List<String>,
)

data class Rooms(val rooms: Int, val people: Int)

/**
 * The song the listener moved on from before its end most often this year (EndReason.SKIPPED):
 * its skips, and how often it was played to the end all the same (EndReason.ENDED). Added
 * 22 Nov 2026.
 */
data class MostSkipped(
    val songId: String,
    val skips: Int,
    val finished: Int,
    /** The next ones by the same rule, at most [Recaps.SKIPPED_MORE], listed under it. */
    val more: List<SongSkips> = emptyList(),
)

/** A song and how often it was skipped in the year. */
data class SongSkips(val songId: String, val skips: Int)

/**
 * Where the year's plays were started from (PlayOrigin, grouped as [Source] says), for the slide that
 * takes Only on InterTune's place when there is nothing for it: no Auto find kept, no song named by
 * ear, no room. The sources by plays, at most [Recaps.FROM_ROWS] of them and the rest as one, always
 * last, each with the song played most from it that no row above shows. Added 22 Nov 2026.
 */
data class WhereFrom(
    /** The day where-from began to be kept, when that is after [Recap.sinceDay]; null otherwise. */
    val sinceDay: Long?,
    val rows: List<FromRow>,
) {
    /** Where a play began: the library takes in albums, artists and folders, where a listener of their own files plays from. */
    enum class Source { PLAYLISTS, RADIOS, SEARCH, LIBRARY, HOME, REST }

    /** Each row's share in whole percents that add up to a hundred: the largest remainders get the points left over. */
    fun percents(): List<Int> {
        val total = rows.sumOf { it.plays }
        if (total <= 0) return rows.map { 0 }
        val exact = rows.map { it.plays * 100.0 / total }
        val whole = exact.map { it.toInt() }.toMutableList()
        val left = 100 - whole.sum()
        exact.indices.sortedWith(compareByDescending<Int> { exact[it] - whole[it] }.thenBy { it }).take(left).forEach { whole[it]++ }
        return whole
    }
}

/** One source, its plays, and the song played most from it, [songPlays] times from there. */
data class FromRow(val source: WhereFrom.Source, val plays: Int, val songId: String, val songPlays: Int)
