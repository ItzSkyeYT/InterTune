/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.stats.ListeningInsights
import java.time.LocalDate

/**
 * Works out a year's InterTune Recap from the listen log, on the phone. Nothing here is sent
 * anywhere, and nothing reads the clock or the time zone: the caller says what now is.
 *
 * Plain Kotlin over rows of the log, so every rule is tested on the JVM with made-up listening
 * (RecapsTest). It follows the Stats page's rules (ListeningInsights): a play is a counted listen
 * that does not continue one already counted, a listen's local day and hour come from the offset
 * stored with it, a start of 0 is dated back from the end, and time heard counts skips too.
 *
 * Songs are ranked by plays, then by time: by time alone a four hour mix played once would come
 * second. Top artists count every artist credited on a song, as the Stats page's Most played
 * artists row does, and only YouTube artists, who have a page and a picture. New artists and the
 * count of artists go by a song's first credit, as the Stats page's findings do.
 */
object Recaps {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** Below these a year has no Recap: three songs and ten minutes are not a year to tell. */
    const val MIN_PLAYS = 10
    const val MIN_LISTENING_MS = 30 * MINUTE_MS

    /** Songs and artists in the tops. */
    const val TOP = 5

    /** Songs after the top ones whose covers the opening screen may show. */
    const val ALSO_PLAYED = 25

    /** A song played this often was kept: an Auto find that stuck, the song heard longest. */
    const val KEPT_PLAYS = 3

    /** A new artist played less than this is no find. */
    const val BEST_FIND_MIN_PLAYS = 5

    /** In a first year everyone is new, so new means found after these first days. */
    const val FIRST_MONTH_DAYS = 30

    /** A play starting before this hour, local, went past midnight. */
    const val LATE_NIGHT_END_HOUR = 5
    const val LATE_NIGHTS_MIN = 2

    const val STREAK_MIN = 2

    /** Recognised songs shown as covers beside their line. */
    const val RECOGNISED_SHOWN = 3

    /** One listen with its local times worked out once. */
    private class Row(val l: RecapListen) {
        val start: Long = if (l.startedAt > 0) l.startedAt else l.endedAt - l.playedMs.coerceAtLeast(0)
        val local: Long = start + l.tzOffsetMin * MINUTE_MS
        val day: Long = Math.floorDiv(local, DAY_MS)
        val minute: Int = (Math.floorMod(local, DAY_MS) / MINUTE_MS).toInt()
        val hour: Int get() = minute / 60
        val played: Long = l.playedMs.coerceAtLeast(0)
        var play = false
    }

    fun isYouTubeArtist(id: String) = id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")

    fun compute(input: RecapInput): Recap? {
        val firstDayOfYear = LocalDate.of(input.year, 1, 1).toEpochDay()
        val lastDayOfYear = LocalDate.of(input.year, 12, 31).toEpochDay()
        val today = ListeningInsights.localDay(input.now, input.nowOffsetMin)
        val lastDay = minOf(today, lastDayOfYear)

        val all = input.listens.map(::Row).sortedWith(compareBy<Row>({ it.start }, { it.l.id }))
        // The play rule over every row read, so that a play paused before midnight on New Year's
        // Eve and finished after it is still one play.
        val countedIds = all.filter { it.l.counted }.mapTo(HashSet()) { it.l.id }
        for (r in all) r.play = r.l.counted && (r.l.continuesListenId == null || r.l.continuesListenId !in countedIds)

        val rows = all.filter { it.day in firstDayOfYear..lastDay }
        val plays = rows.filter { it.play }
        val total = rows.sumOf { it.played }
        if (plays.size < MIN_PLAYS || total < MIN_LISTENING_MS) return null

        // Heard before the year: the log before it, and the rows read from just before its first day.
        val earlier = all.filter { it.day < firstDayOfYear }
        val heardBefore = HashSet<String>().apply {
            input.before.mapTo(this) { it.songId }
            earlier.mapTo(this) { it.l.songId }
        }
        val historyStartDay = input.bounds.firstAt?.let { ListeningInsights.localDay(it, rows.first().l.tzOffsetMin) }
            ?: rows.first().day
        val firstYear = historyStartDay >= firstDayOfYear && earlier.isEmpty() && input.before.isEmpty()
        val sinceDay = if (firstYear) rows.first().day else firstDayOfYear

        val songPlays = HashMap<String, Int>()
        for (p in plays) songPlays.merge(p.l.songId, 1, Int::plus)
        val songTime = HashMap<String, Long>()
        for (r in rows) songTime.merge(r.l.songId, r.played, Long::plus)
        val bySongRank = compareByDescending<String> { songPlays[it] ?: 0 }.thenByDescending { songTime[it] ?: 0L }.thenBy { it }

        val msByDay = LongArray((lastDay - sinceDay + 1).toInt())
        for (r in rows) if (r.day >= sinceDay) msByDay[(r.day - sinceDay).toInt()] += r.played
        val biggest = msByDay.indices.maxWith(compareBy<Int>({ msByDay[it] }, { it }))

        val hours = hourProfile(rows)
        val peakHour = hours.indices.maxBy { hours[it] }

        val firstPlay = plays.first()
        val ranked = songPlays.keys.sortedWith(bySongRank)
        return Recap(
            year = input.year,
            firstYear = firstYear,
            sinceDay = sinceDay,
            lastDay = lastDay,
            firstSong = FirstSong(firstPlay.l.songId, firstPlay.day, firstPlay.minute),
            playedMs = total,
            plays = plays.size,
            songs = songPlays.size,
            artists = plays.mapNotNullTo(HashSet()) { input.firstCredits[it.l.songId] }.size,
            msByDay = msByDay.toList(),
            biggestDay = DayAmount(sinceDay + biggest, msByDay[biggest]),
            mostTime = songPlays.keys.filter { (songPlays[it] ?: 0) >= KEPT_PLAYS }
                .maxWithOrNull(compareBy<String>({ songTime[it] ?: 0L }, { songPlays[it] ?: 0 }).thenByDescending { it })
                ?.let { SongAmount(it, songTime[it] ?: 0L, songPlays[it] ?: 0) },
            msByHour = hours.toList(),
            peakHour = peakHour,
            peakSong = peakSong(rows, plays, peakHour, songTime, bySongRank),
            lateNights = lateNights(plays),
            streak = streak(rows, plays),
            topSongs = ranked.take(TOP).map { topSong(it, all, rows, plays, input) },
            alsoPlayed = ranked.drop(TOP).take(ALSO_PLAYED),
            topArtists = topArtists(rows, plays, input.credits),
            newArtists = newArtists(rows, plays, input, heardBefore, firstYear, sinceDay, lastDay),
            auto = autoFinds(rows, songPlays, songTime, heardBefore, input, sinceDay),
            recognition = recognitions(rows, input, firstDayOfYear, lastDay),
            rooms = rooms(input, firstDayOfYear, lastDay),
        )
    }

    /** Each listen's time spread over the hours it ran through, from its start, as on the Stats page. */
    private fun hourProfile(rows: List<Row>): LongArray {
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
        return ms
    }

    /** The song with the most plays started in [hour]; failing that, the most heard started in it; failing that, the top song. */
    private fun peakSong(rows: List<Row>, plays: List<Row>, hour: Int, songTime: Map<String, Long>, rank: Comparator<String>): SongAmount {
        val atPeak = HashMap<String, Int>()
        for (p in plays) if (p.hour == hour) atPeak.merge(p.l.songId, 1, Int::plus)
        if (atPeak.isNotEmpty()) {
            val song = atPeak.keys.sortedWith(compareByDescending<String> { atPeak[it] ?: 0 }.then(rank)).first()
            return SongAmount(song, songTime[song] ?: 0L, atPeak[song] ?: 0)
        }
        val song = rows.filter { it.hour == hour }.map { it.l.songId }.distinct().sortedWith(rank).firstOrNull()
            ?: plays.map { it.l.songId }.distinct().sortedWith(rank).first()
        return SongAmount(song, songTime[song] ?: 0L, 0)
    }

    private fun lateNights(plays: List<Row>): LateNights? {
        val late = plays.filter { it.hour < LATE_NIGHT_END_HOUR }
        val nights = late.mapTo(HashSet()) { it.day }.size
        if (nights < LATE_NIGHTS_MIN) return null
        return LateNights(nights, late.maxOf { it.minute })
    }

    /** The longest run of days with any listening, the latest of equal runs, and the song of it. */
    private fun streak(rows: List<Row>, plays: List<Row>): Streak? {
        val days = rows.mapTo(sortedSetOf()) { it.day }.toList()
        var bestDays = 0
        var bestFrom = 0L
        var bestTo = 0L
        var runStart = days.first()
        for (i in days.indices) {
            if (i > 0 && days[i] != days[i - 1] + 1) runStart = days[i]
            val run = (days[i] - runStart + 1).toInt()
            if (run >= bestDays) {
                bestDays = run
                bestFrom = runStart
                bestTo = days[i]
            }
        }
        if (bestDays < STREAK_MIN) return null
        // The song there on the most of its days, then the most played in it.
        val inStreak = plays.filter { it.day in bestFrom..bestTo }.ifEmpty { rows.filter { it.day in bestFrom..bestTo } }
        val daysOf = inStreak.groupBy { it.l.songId }.mapValues { (_, own) -> own.mapTo(HashSet()) { it.day }.size }
        val playsOf = inStreak.groupingBy { it.l.songId }.eachCount()
        val song = daysOf.keys.sortedWith(
            compareByDescending<String> { daysOf[it] ?: 0 }.thenByDescending { playsOf[it] ?: 0 }.thenBy { it }
        ).first()
        return Streak(bestDays, bestFrom, bestTo, song, daysOf[song] ?: 0)
    }

    private fun topSong(songId: String, all: List<Row>, rows: List<Row>, plays: List<Row>, input: RecapInput): TopSong {
        val own = plays.filter { it.l.songId == songId }
        // First heard at all: the log before the year knows when, without the offset it was
        // heard at, so that day is read with the offset of the song's first row here.
        val firstRow = all.first { it.l.songId == songId }
        val before = input.before.firstOrNull { it.songId == songId }?.firstAt
        val firstAt = if (before != null && before < firstRow.start) before else firstRow.start
        val firstLocal = firstAt + firstRow.l.tzOffsetMin * MINUTE_MS
        val byDay = own.groupingBy { it.day }.eachCount()
        val best = byDay.entries.maxWith(compareBy<Map.Entry<Long, Int>>({ it.value }, { it.key }))
        val (inARow, inARowDay) = longestRun(rows, songId)
        return TopSong(
            songId = songId,
            plays = own.size,
            ms = rows.filter { it.l.songId == songId }.sumOf { it.played },
            firstHeardDay = Math.floorDiv(firstLocal, DAY_MS),
            firstHeardMinute = (Math.floorMod(firstLocal, DAY_MS) / MINUTE_MS).toInt(),
            days = byDay.size,
            bestDay = best.key,
            bestDayPlays = best.value,
            inARow = inARow,
            inARowDay = inARowDay,
            byMonth = own.groupingBy { LocalDate.ofEpochDay(it.day).monthValue }.eachCount(),
        )
    }

    /**
     * The longest run of plays of [songId] with nothing else in between, in one session, and its
     * day: the first of equal runs. A skipped row of the same song, such as the first half of a
     * paused play, does not break the run; any other song does. The Stats page's rule.
     */
    private fun longestRun(rows: List<Row>, songId: String): Pair<Int, Long> {
        var best = 0
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
            if (r.l.songId == songId && run > best) {
                best = run
                bestDay = r.day
            }
        }
        return best to bestDay
    }

    /** Every credited artist, a play each and the time heard each; YouTube artists only, the most played first. */
    private fun topArtists(rows: List<Row>, plays: List<Row>, credits: List<RecapCredit>): List<TopArtist> {
        val artistsOf = credits.groupBy { it.songId }
            .mapValues { (_, own) -> own.sortedBy { it.position }.map { it.artistId }.distinct().filter(::isYouTubeArtist) }
        val artistPlays = HashMap<String, Int>()
        val artistTime = HashMap<String, Long>()
        val artistSongPlays = HashMap<String, HashMap<String, Int>>()
        val songTime = HashMap<String, Long>()
        for (r in rows) {
            songTime.merge(r.l.songId, r.played, Long::plus)
            for (a in artistsOf[r.l.songId].orEmpty()) artistTime.merge(a, r.played, Long::plus)
        }
        for (p in plays) {
            for (a in artistsOf[p.l.songId].orEmpty()) {
                artistPlays.merge(a, 1, Int::plus)
                artistSongPlays.getOrPut(a) { HashMap() }.merge(p.l.songId, 1, Int::plus)
            }
        }
        return artistPlays.keys
            .sortedWith(compareByDescending<String> { artistPlays[it] ?: 0 }.thenByDescending { artistTime[it] ?: 0L }.thenBy { it })
            .take(TOP)
            .map { a ->
                val own = artistSongPlays[a].orEmpty()
                val top = own.keys.sortedWith(
                    compareByDescending<String> { own[it] ?: 0 }.thenByDescending { songTime[it] ?: 0L }.thenBy { it }
                ).first()
                TopArtist(a, artistPlays[a] ?: 0, artistTime[a] ?: 0L, own.size, top, own[top] ?: 0)
            }
    }

    private fun newArtists(
        rows: List<Row>,
        plays: List<Row>,
        input: RecapInput,
        heardBefore: Set<String>,
        firstYear: Boolean,
        sinceDay: Long,
        lastDay: Long,
    ): NewArtists? {
        val creditOf = input.firstCredits
        val firstPlayDay = HashMap<String, Long>()
        val artistPlays = HashMap<String, Int>()
        val songPlays = HashMap<String, Int>()
        for (p in plays) {
            val a = creditOf[p.l.songId] ?: continue
            firstPlayDay.putIfAbsent(a, p.day)
            artistPlays.merge(a, 1, Int::plus)
            songPlays.merge(p.l.songId, 1, Int::plus)
        }
        val artistTime = HashMap<String, Long>()
        for (r in rows) creditOf[r.l.songId]?.let { artistTime.merge(it, r.played, Long::plus) }
        val new = if (firstYear) {
            // A month of log, or less, cannot tell a find from where the log begins.
            if (lastDay - sinceDay + 1 <= FIRST_MONTH_DAYS) return null
            firstPlayDay.filterValues { it >= sinceDay + FIRST_MONTH_DAYS }.keys
        } else {
            val known = heardBefore.mapNotNullTo(HashSet()) { creditOf[it] }
            firstPlayDay.keys - known
        }
        if (new.isEmpty()) return null
        val best = new.sortedWith(
            compareByDescending<String> { artistPlays[it] ?: 0 }.thenByDescending { artistTime[it] ?: 0L }.thenBy { it }
        ).first()
        val bestSong = songPlays.keys.filter { creditOf[it] == best }
            .sortedWith(compareByDescending<String> { songPlays[it] ?: 0 }.thenBy { it })
            .first()
        val bestFind = BestFind(best, firstPlayDay[best] ?: sinceDay, artistPlays[best] ?: 0, bestSong)
            .takeIf { it.plays >= BEST_FIND_MIN_PLAYS }
        return NewArtists(new.size, firstYear, bestFind)
    }

    /**
     * Songs first heard from Quick picks on Auto, with nothing of them heard before. Backfilled
     * rows have no origin, so this is the live log's alone, and says from when.
     */
    private fun autoFinds(
        rows: List<Row>,
        songPlays: Map<String, Int>,
        songTime: Map<String, Long>,
        heardBefore: Set<String>,
        input: RecapInput,
        sinceDay: Long,
    ): AutoFinds? {
        val firstRows = LinkedHashMap<String, Row>()
        for (r in rows) if (r.l.songId !in heardBefore) firstRows.putIfAbsent(r.l.songId, r)
        val found = firstRows.values.filter {
            (it.l.origin == PlayOrigin.QUICK_PICKS.code || it.l.origin == PlayOrigin.WIDGET.code) && it.l.contextChip == ContextChip.AUTO
        }
        if (found.isEmpty()) return null
        val stuck = found.map { it.l.songId }
            .filter { (songPlays[it] ?: 0) >= KEPT_PLAYS }
            .sortedWith(compareByDescending<String> { songPlays[it] ?: 0 }.thenByDescending { songTime[it] ?: 0L }.thenBy { it })
            .firstOrNull() ?: return null
        val marks = found.map {
            (if ((songPlays[it.l.songId] ?: 0) >= KEPT_PLAYS) AutoFinds.MARK_KEPT else 0) +
                (if (it.l.songId in input.liked) AutoFinds.MARK_LIKED else 0)
        }
        val liveDay = input.bounds.firstLiveAt?.let { ListeningInsights.localDay(it, rows.first().l.tzOffsetMin) }
        return AutoFinds(
            sinceDay = liveDay?.takeIf { it > sinceDay },
            songs = found.size,
            kept = marks.count { it and AutoFinds.MARK_KEPT != 0 },
            liked = marks.count { it and AutoFinds.MARK_LIKED != 0 },
            marks = marks,
            stuck = SongAmount(stuck, songTime[stuck] ?: 0L, songPlays[stuck] ?: 0),
            stuckFirstDay = firstRows.getValue(stuck).day,
        )
    }

    /**
     * What recognition named in the year, from its history. A history that was cleared leaves the
     * plays started from a recognition, which the log keeps: those songs, then.
     */
    private fun recognitions(rows: List<Row>, input: RecapInput, firstDayOfYear: Long, lastDay: Long): Recognitions? {
        val heard = input.heard
            .filter { ListeningInsights.localDay(it.at, input.nowOffsetMin) in firstDayOfYear..lastDay }
            .sortedByDescending { it.at }
        if (heard.isNotEmpty()) {
            val days = heard.map { ListeningInsights.localDay(it.at, input.nowOffsetMin) }
            return Recognitions(
                count = heard.size,
                firstDay = days.min(),
                lastDay = days.max(),
                songIds = heard.mapNotNull { it.videoId }.distinct().take(RECOGNISED_SHOWN),
            )
        }
        val started = rows.filter { it.l.origin == PlayOrigin.RECOGNISED.code }
        if (started.isEmpty()) return null
        val firsts = started.groupBy { it.l.songId }.mapValues { (_, own) -> own.first() }
        return Recognitions(
            count = firsts.size,
            firstDay = firsts.values.minOf { it.day },
            lastDay = firsts.values.maxOf { it.day },
            songIds = firsts.values.sortedByDescending { it.start }.map { it.l.songId }.take(RECOGNISED_SHOWN),
        )
    }

    private fun rooms(input: RecapInput, firstDayOfYear: Long, lastDay: Long): Rooms? {
        val inYear = input.rooms.filter { ListeningInsights.localDay(it.at, input.nowOffsetMin) in firstDayOfYear..lastDay }
        if (inYear.isEmpty()) return null
        return Rooms(inYear.size, inYear.flatMapTo(HashSet()) { it.people }.size)
    }
}
