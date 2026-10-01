/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class ListeningInsightsTest {
    private val minute = ListeningInsights.MINUTE_MS
    private val hour = ListeningInsights.HOUR_MS
    private val day = ListeningInsights.DAY_MS

    /** Thursday 24 September 2026, 20:00 UTC. */
    private val now = LocalDate.of(2026, 9, 24).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val today = LocalDate.of(2026, 9, 24).toEpochDay()
    private val weekAgo = now - 7 * day
    private var nextId = 1L
    /** Each song's artist, as the listens below were made with it: the log reads this per song. */
    private val artistOf = HashMap<String, String>()

    /** Midnight UTC, [daysAgo] days before today, plus [at]. */
    private fun time(daysAgo: Int, at: Long = 12 * hour) = (today - daysAgo) * day + at

    private fun listen(
        song: String,
        startedAt: Long,
        playedMs: Long = 3 * minute,
        artist: String? = "artist of $song",
        offsetMin: Int = 0,
        endReason: Int = EndReason.ENDED,
        origin: Int = PlayOrigin.QUICK_PICKS.code,
        session: Long = startedAt / (6 * hour),
        counted: Boolean = true,
        continues: Long? = null,
        id: Long = nextId++,
    ): StatsListen {
        if (artist != null) artistOf[song] = artist
        return StatsListen(id, song, startedAt, startedAt + playedMs, offsetMin, playedMs, endReason, origin, session, counted, continues)
    }

    /** [n] plays of [song] back to back from [from], one session. */
    private fun run(song: String, from: Long, n: Int, playedMs: Long = 3 * minute, artist: String? = "artist of $song", endReason: Int = EndReason.ENDED) =
        (0 until n).map { listen(song, from + it * playedMs, playedMs, artist = artist, endReason = endReason, session = from) }

    private fun input(
        listens: List<StatsListen>,
        periodStart: Long = weekAgo,
        before: List<StatsSongBefore> = emptyList(),
        previous: StatsTotals? = null,
        firstAt: Long? = listens.minOfOrNull { it.startedAt },
        firstLiveAt: Long? = firstAt,
        nowOffsetMin: Int = 0,
    ) = StatsInput(now, nowOffsetMin, periodStart, listens, before, artistOf.toMap(), previous, StatsBounds(firstAt, firstLiveAt))

    private inline fun <reified T : Insight> ListeningStats.find(): T? = insights.filterIsInstance<T>().firstOrNull()

    /** Filler: a different song each time, so nothing repeats and no song stands out. */
    private fun variety(n: Int, from: Long, playedMs: Long = 3 * minute, endReason: Int = EndReason.ENDED) =
        (0 until n).map { listen("filler $it", from + it * playedMs, playedMs, endReason = endReason, session = from) }

    // Hidden when thin

    @Test
    fun `an empty period says nothing`() {
        assertEquals(ListeningStats.EMPTY, ListeningInsights.compute(input(emptyList())))
    }

    @Test
    fun `two plays are not enough to say anything`() {
        val stats = ListeningInsights.compute(input(run("a", time(1), 2)))
        assertNull(stats.summary)
        assertTrue(stats.insights.isEmpty())
    }

    @Test
    fun `skipped songs alone are not plays`() {
        val skips = (0 until 10).map { listen("s$it", time(1) + it * minute, 20_000, counted = false, endReason = EndReason.SKIPPED) }
        assertEquals(ListeningStats.EMPTY, ListeningInsights.compute(input(skips)))
    }

    @Test
    fun `a summary counts songs and artists with a play, and all the time heard`() {
        val listens = run("a", time(2), 3) + listen("b", time(1), 30_000, counted = false, endReason = EndReason.SKIPPED)
        val summary = ListeningInsights.compute(input(listens)).summary!!
        assertEquals(9 * minute + 30_000, summary.playedMs)
        assertEquals(1, summary.songs)
        assertEquals(1, summary.artists)
    }

    @Test
    fun `listening a day is spread over the days the period and the log share`() {
        // Four hours in a week of log: 34 minutes and a bit a day.
        val week = ListeningInsights.compute(input(variety(80, time(2)), firstAt = covered)).summary!!
        assertEquals(4 * hour / 7, week.perDayMs)
        // The log began three days ago: over those three days, not the week.
        val young = ListeningInsights.compute(input(variety(80, time(2)), firstAt = now - 3 * day)).summary!!
        assertEquals(4 * hour / 3, young.perDayMs)
        // Under two days, a day's worth is just the total again.
        assertNull(ListeningInsights.compute(input(variety(80, time(1)), firstAt = now - day)).summary!!.perDayMs)
    }

    @Test
    fun `nothing below a threshold is shown`() {
        // Four plays in a day, four back to back, a three day streak: all just under.
        val listens = run("a", time(3), 4) + listen("a", time(2)) + listen("a", time(1)) + variety(50, time(5), playedMs = 3 * minute + 30_000)
        val stats = ListeningInsights.compute(input(listens))
        assertNull(stats.find<Insight.SongOfTheDay>())
        assertNull(stats.find<Insight.OnRepeat>())
        assertNull(stats.find<Insight.SongStreak>())
    }

    // Local time

    @Test
    fun `the hour is local, from the offset stored on each listen`() {
        // 22:30 UTC in Paris in summer is half past midnight, the next day.
        val listens = (0 until 3).flatMap { d -> run("a", time(d + 1, 22 * hour + 30 * minute), 10, playedMs = 2 * minute).map { it.copy(tzOffsetMin = 120) } }
        val stats = ListeningInsights.compute(input(listens))
        assertEquals(0, stats.hours!!.peakHour)
        val late = stats.find<Insight.LateNights>()!!
        assertEquals(3, late.nights)
        assertEquals(30 + 9 * 2, late.latestMinute)
    }

    @Test
    fun `the day a play belongs to is local too`() {
        // 23:30 UTC on the 20th is 01:30 on the 21st at +2, and 19:30 on the 20th at -4.
        val at = time(4, 23 * hour + 30 * minute)
        val paris = run("a", at, 6).map { it.copy(tzOffsetMin = 120) }
        val stats = ListeningInsights.compute(input(paris))
        assertEquals(today - 3, stats.find<Insight.SongOfTheDay>()!!.day)
        val newYork = run("a", at, 6).map { it.copy(tzOffsetMin = -240) }
        assertEquals(today - 4, ListeningInsights.compute(input(newYork)).find<Insight.SongOfTheDay>()!!.day)
    }

    @Test
    fun `a long listen is spread over the hours it ran through`() {
        val mix = listen("mix", time(2, 22 * hour), playedMs = 4 * hour)
        val listens = listOf(mix) + variety(3, time(1, 9 * hour)) + variety(3, time(3, 9 * hour))
        val hours = ListeningInsights.compute(input(listens)).hours!!
        for (h in listOf(22, 23, 0, 1)) assertEquals(hour, hours.msByHour[h])
        assertEquals(18 * minute, hours.msByHour[9])
    }

    @Test
    fun `a row with no start is dated back from its end`() {
        val broken = listen("a", time(1, 10 * hour), 5 * minute).copy(startedAt = 0)
        val listens = listOf(broken) + variety(20, time(2, 15 * hour)) + variety(20, time(3, 15 * hour))
        val hours = ListeningInsights.compute(input(listens, firstAt = time(3, 15 * hour))).hours!!
        assertEquals(5 * minute, hours.msByHour[10])
    }

    @Test
    fun `the chart needs three days`() {
        val twoDays = variety(20, time(1)) + variety(20, time(2))
        assertNull(ListeningInsights.compute(input(twoDays)).hours)
        assertNotNull(ListeningInsights.compute(input(twoDays + variety(20, time(3)))).hours)
    }

    // The period before

    private val covered = weekAgo - 30 * day

    @Test
    fun `listening is compared with the same length of time before`() {
        val listens = variety(40, time(2))
        val previous = StatsTotals(listens = 20, playedMs = 60 * minute, countedMs = 60 * minute, legacyListens = 0)
        val change = ListeningInsights.compute(input(listens, previous = previous, firstAt = covered)).summary!!.change!!
        assertEquals(2.0, change, 1e-9)
    }

    @Test
    fun `no comparison when the log does not cover the whole period before`() {
        val listens = variety(40, time(2))
        val previous = StatsTotals(20, 60 * minute, 60 * minute, 0)
        // The log begins a day into the week before: that week looks short, and this one like growth.
        assertNull(ListeningInsights.compute(input(listens, previous = previous, firstAt = weekAgo - 6 * day)).summary!!.change)
    }

    @Test
    fun `no comparison with almost nothing before`() {
        val previous = StatsTotals(2, 5 * minute, 5 * minute, 0)
        assertNull(ListeningInsights.compute(input(variety(40, time(2)), previous = previous, firstAt = covered)).summary!!.change)
    }

    @Test
    fun `against the backfill, counted listening is compared on both sides`() {
        // The live week has 60 minutes of plays and 60 of skips; the backfilled week before kept
        // its 60 minutes of plays and never recorded its skips.
        val plays = variety(20, time(2))
        val skips = (0 until 60).map { listen("skip $it", time(3) + it * minute, minute, counted = false, endReason = EndReason.SKIPPED) }
        val previous = StatsTotals(listens = 20, playedMs = 60 * minute, countedMs = 60 * minute, legacyListens = 20)
        val summary = ListeningInsights.compute(input(plays + skips, previous = previous, firstAt = covered)).summary!!
        assertEquals(120 * minute, summary.playedMs)
        assertEquals(1.0, summary.change!!, 1e-9)
    }

    @Test
    fun `all has no comparison and says where the log begins`() {
        val listens = variety(10, time(40)) + variety(10, time(2))
        val summary = ListeningInsights.compute(input(listens, periodStart = 0, previous = null)).summary!!
        assertNull(summary.change)
        assertEquals(today - 40, summary.sinceDay)
    }

    @Test
    fun `a period that reaches back past the log says where the log begins`() {
        val listens = variety(10, time(3)) + variety(10, time(2))
        assertEquals(today - 3, ListeningInsights.compute(input(listens, periodStart = now - 90 * day)).summary!!.sinceDay)
        assertNull(ListeningInsights.compute(input(listens, firstAt = covered)).summary!!.sinceDay)
    }

    // Sessions and days

    @Test
    fun `the longest session is the most time heard, not the longest span`() {
        // Three songs spread over two hours, and eighty minutes of music straight through on another day.
        val spread = listOf(0L, 50L, 100L).map { listen("s$it", time(3) + it * minute, 4 * minute, session = 1) }
        val solid = (0 until 20).map { listen("x$it", time(5) + it * 4 * minute, 4 * minute, session = 2) }
        // The biggest day is a third day with two sessions that add up to more.
        val busy = (0 until 12).map { listen("y$it", time(1) + it * 4 * minute, 4 * minute, session = 3) } +
            (0 until 12).map { listen("z$it", time(1, 18 * hour) + it * 4 * minute, 4 * minute, session = 4) }
        val stats = ListeningInsights.compute(input(spread + solid + busy))
        assertEquals(Insight.BiggestDay(today - 1, 96 * minute), stats.find<Insight.BiggestDay>())
        assertEquals(Insight.LongestSession(today - 5, 80 * minute), stats.find<Insight.LongestSession>())
    }

    @Test
    fun `the longest session is not repeated when it is the biggest day`() {
        val solid = (0 until 20).map { listen("x$it", time(5) + it * 4 * minute, 4 * minute, session = 2) }
        val small = variety(5, time(3)) + variety(5, time(2))
        val stats = ListeningInsights.compute(input(solid + small))
        assertEquals(today - 5, stats.find<Insight.BiggestDay>()!!.day)
        assertNull(stats.find<Insight.LongestSession>())
    }

    @Test
    fun `a streak of days with music`() {
        val month = now - 30 * day
        val listens = (0..15).flatMap { variety(1, time(it)) } + variety(1, time(20))
        val streak = ListeningInsights.compute(input(listens, periodStart = month)).find<Insight.EveryDay>()!!
        assertEquals(Insight.EveryDay(16, today - 15, today), streak)
        val broken = listens.filterNot { LocalDate.ofEpochDay(Math.floorDiv(it.startedAt, day)) == LocalDate.ofEpochDay(today - 7) }
        assertNull(ListeningInsights.compute(input(broken, periodStart = month)).find<Insight.EveryDay>())
    }

    @Test
    fun `weekends need a real difference`() {
        fun weekdayListens(perWeekday: Int, perWeekendDay: Int) = (1..14).flatMap { d ->
            val date = LocalDate.ofEpochDay(today - d)
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            variety(if (weekend) perWeekendDay else perWeekday, time(d))
        }
        val twoWeeks = now - 14 * day
        assertNull(ListeningInsights.compute(input(weekdayListens(10, 11), periodStart = twoWeeks)).find<Insight.Weekends>())
        val more = ListeningInsights.compute(input(weekdayListens(10, 25), periodStart = twoWeeks)).find<Insight.Weekends>()!!
        // Today, a Thursday with nothing yet, is a weekday of the period too.
        assertEquals(2.5 * 11 / 10, more.ratio, 0.01)
        val less = ListeningInsights.compute(input(weekdayListens(10, 5), periodStart = twoWeeks)).find<Insight.Weekends>()!!
        assertTrue(less.ratio < 1 / ListeningInsights.WEEKEND_RATIO)
    }

    // Songs

    @Test
    fun `a song played again and again in one day, most of it back to back`() {
        val listens = run("a", time(2, 9 * hour), 6) + listen("b", time(2, 10 * hour)) + run("a", time(2, 11 * hour), 2, artist = "artist of a")
        val stats = ListeningInsights.compute(input(listens))
        assertEquals(Insight.SongOfTheDay("a", 8, today - 2, 6), stats.find<Insight.SongOfTheDay>())
        // The run is said as part of the day, not twice.
        assertNull(stats.find<Insight.OnRepeat>())
    }

    @Test
    fun `the day's own run is told, even when a longer one happened another day`() {
        val day = run("a", time(2, 9 * hour), 6) + (0 until 4).map { listen("a", time(2, 12 * hour) + it * hour) }
        val elsewhere = run("c", time(4), 7)
        val stats = ListeningInsights.compute(input(day + elsewhere))
        assertEquals(Insight.SongOfTheDay("a", 10, today - 2, 6), stats.find<Insight.SongOfTheDay>())
        assertEquals(Insight.OnRepeat("c", 7, today - 4), stats.find<Insight.OnRepeat>())
    }

    @Test
    fun `on repeat is broken by another song, not by the paused half of the same one`() {
        val first = listen("a", time(1), 90_000, endReason = EndReason.STOPPED, counted = true, session = 7)
        val second = listen("a", time(1) + 10 * minute, 90_000, continues = first.id, session = 7)
        val rest = (0 until 5).map { listen("a", time(1) + 20 * minute + it * 3 * minute, session = 7) }
        val other = listen("b", time(1) + 40 * minute, session = 7)
        val more = (0 until 3).map { listen("a", time(1) + 45 * minute + it * 3 * minute, session = 7) }
        // More plays of other songs in another day, never two in a row, so the run is a finding of its own.
        val elsewhere = (0 until 10).flatMap {
            listOf(listen("c", time(3) + it * 10 * minute, session = 99), listen("d", time(3) + it * 10 * minute + 4 * minute, session = 99))
        }
        val stats = ListeningInsights.compute(input(listOf(first, second) + rest + other + more + elsewhere))
        assertEquals(Insight.OnRepeat("a", 6, today - 1), stats.find<Insight.OnRepeat>())
    }

    @Test
    fun `the paused half of a counted play is not a second play`() {
        val first = listen("a", time(1), 2 * minute, endReason = EndReason.STOPPED, counted = true)
        val second = listen("a", time(1) + 5 * minute, 2 * minute, continues = first.id)
        val listens = listOf(first, second) + (0 until 4).map { listen("a", time(1) + (10 + it * 4) * minute) }
        assertEquals(5, ListeningInsights.compute(input(listens)).find<Insight.SongOfTheDay>()!!.plays)
    }

    @Test
    fun `a streak is days in a row, not plays`() {
        val streak = (1..4).map { listen("a", time(it)) } + variety(20, time(6))
        assertEquals(Insight.SongStreak("a", 4, today - 4), ListeningInsights.compute(input(streak)).find<Insight.SongStreak>())
        val gap = listOf(1, 2, 4, 5).map { listen("a", time(it)) } + variety(20, time(6))
        assertNull(ListeningInsights.compute(input(gap)).find<Insight.SongStreak>())
    }

    @Test
    fun `a comeback needs a month away and three plays since`() {
        val month = now - 30 * day
        val before = listOf(StatsSongBefore("a", firstAt = now - 200 * day, lastPlayAt = now - 50 * day, playsBefore = 12))
        val back = (1..3).map { listen("a", time(it)) } + variety(20, time(6))
        val found = ListeningInsights.compute(input(back, periodStart = month, before = before, firstAt = now - 300 * day)).find<Insight.Comeback>()!!
        assertEquals("a", found.songId)
        assertEquals(3, found.playsSince)
        // From the last play before, 50 days ago at 20:00, to noon three days ago: 46 whole days.
        assertEquals(46, found.daysAway)
        // Two plays since is not a comeback yet.
        assertNull(ListeningInsights.compute(input(back.drop(1), periodStart = month, before = before, firstAt = now - 300 * day)).find<Insight.Comeback>())
        // Twenty days away is not away.
        val recent = listOf(before[0].copy(lastPlayAt = now - 23 * day))
        assertNull(ListeningInsights.compute(input(back, periodStart = month, before = recent, firstAt = now - 300 * day)).find<Insight.Comeback>())
    }

    @Test
    fun `a comeback can happen inside a long period`() {
        val listens = (0 until 3).map { listen("a", time(90 + it)) } + (1..4).map { listen("a", time(it)) } + variety(20, time(50))
        val found = ListeningInsights.compute(input(listens, periodStart = 0)).find<Insight.Comeback>()!!
        assertEquals(Insight.Comeback("a", 86, 4), found)
    }

    @Test
    fun `when the top song was found, but not at the start of the log`() {
        val month = now - 30 * day
        val listens = (1..6).map { listen("a", time(it), 5 * minute) } + variety(20, time(8))
        val before = listOf(StatsSongBefore("a", firstAt = time(40), lastPlayAt = time(35), playsBefore = 4))
        val found = ListeningInsights.compute(input(listens, periodStart = month, before = before, firstAt = time(100))).find<Insight.TopSongFound>()!!
        assertEquals(Insight.TopSongFound("a", today - 40, 10), found)
        // First heard on the log's first day: that may only be where the log begins.
        assertNull(ListeningInsights.compute(input(listens, periodStart = month, before = before, firstAt = time(45))).find<Insight.TopSongFound>())
    }

    // New to you

    private fun discoveryListens() = variety(20, time(2)) + (0 until 20).map { listen("old $it", time(3) + it * 3 * minute, artist = "old artist") }
    private val knownBefore = (0 until 20).map { StatsSongBefore("old $it", firstAt = weekAgo - 50 * day, lastPlayAt = weekAgo - 2 * day, playsBefore = 3) }

    @Test
    fun `new to you needs a log that reaches well back before the period`() {
        val young = ListeningInsights.compute(input(discoveryListens(), before = knownBefore, firstAt = weekAgo - 10 * day))
        assertNull(young.find<Insight.NewToYou>())
        val old = ListeningInsights.compute(input(discoveryListens(), before = knownBefore, firstAt = weekAgo - 60 * day))
        val found = old.find<Insight.NewToYou>()!!
        assertEquals(0.5, found.share, 1e-9)
        assertEquals(20, found.newArtists)
    }

    @Test
    fun `all has nothing to be new against`() {
        assertNull(ListeningInsights.compute(input(discoveryListens(), periodStart = 0)).find<Insight.NewToYou>())
    }

    @Test
    fun `the best find is the new artist played most`() {
        val listens = discoveryListens() + (0 until 6).map { listen("found $it", time(1) + it * 3 * minute, artist = "new artist") }
        val stats = ListeningInsights.compute(input(listens, before = knownBefore, firstAt = weekAgo - 60 * day))
        assertEquals(Insight.BestFind("new artist", today - 1, 6), stats.find<Insight.BestFind>())
    }

    // Skips and sources: the live log only

    private val backfilled = (0 until 40).map { listen("legacy $it", time(6) + it * 3 * minute, endReason = EndReason.UNKNOWN, origin = PlayOrigin.UNKNOWN.code) }

    private fun liveWeek(skipped: Int, ended: Int, stopped: Int = 0): List<StatsListen> =
        (0 until skipped).map { listen("skip $it", time(2) + it * minute, 30_000, endReason = EndReason.SKIPPED, counted = false) } +
            (0 until ended).map { listen("end $it", time(3) + it * 3 * minute, endReason = EndReason.ENDED) } +
            (0 until stopped).map { listen("stop $it", time(4) + it * 3 * minute, endReason = EndReason.STOPPED) }

    @Test
    fun `skips are read from the live log only, and say since when`() {
        val live = liveWeek(skipped = 20, ended = 15, stopped = 5)
        val stats = ListeningInsights.compute(input(backfilled + live, firstAt = time(6), firstLiveAt = time(4)))
        val skips = stats.find<Insight.Skips>()!!
        assertEquals(0.5, skips.skipped, 1e-9)
        assertEquals(0.375, skips.finished, 1e-9)
        assertEquals(today - 4, skips.sinceDay)
    }

    @Test
    fun `a play that failed is in the live log and is neither a skip nor a finish`() {
        val failed = (0 until 10).map { listen("fail $it", time(5) + it * minute, 30_000, endReason = EndReason.ERROR, counted = false) }
        val skips = ListeningInsights.compute(input(liveWeek(skipped = 20, ended = 15, stopped = 5) + failed)).find<Insight.Skips>()!!
        assertEquals(20.0 / 50, skips.skipped, 1e-9)
        assertEquals(15.0 / 50, skips.finished, 1e-9)
    }

    @Test
    fun `a live log older than the period needs no since`() {
        val skips = ListeningInsights.compute(input(liveWeek(20, 15), firstAt = covered)).find<Insight.Skips>()!!
        assertNull(skips.sinceDay)
    }

    @Test
    fun `too few live listens say nothing about skips`() {
        assertNull(ListeningInsights.compute(input(backfilled + liveWeek(10, 10))).find<Insight.Skips>())
    }

    @Test
    fun `no zero percentages`() {
        assertNull(ListeningInsights.compute(input(liveWeek(0, 40))).find<Insight.Skips>())
    }

    @Test
    fun `skipped and still played through, without counting a skip as a play`() {
        // Skipped late, past the play threshold: a counted play, and still a skip.
        val lateSkips = (0 until 4).map { listen("a", time(2) + it * hour, 2 * minute, endReason = EndReason.SKIPPED, counted = true) }
        val through = (0 until 3).map { listen("a", time(3) + it * hour) }
        val onlySkipped = (0 until 5).map { listen("b", time(4) + it * hour, 2 * minute, endReason = EndReason.SKIPPED, counted = true) }
        val stats = ListeningInsights.compute(input(lateSkips + through + onlySkipped + liveWeek(10, 20)))
        assertEquals(Insight.LoveHate("a", 4, 3, null), stats.find<Insight.LoveHate>())
    }

    @Test
    fun `the artist heard to the end far more than the rest`() {
        val finished = (0 until 10).map { listen("f$it", time(1) + it * 3 * minute, artist = "finisher") }
        val skipped = (0 until 30).map { listen("s$it", time(2) + it * minute, 30_000, artist = "s$it", endReason = EndReason.SKIPPED, counted = false) }
        val stats = ListeningInsights.compute(input(finished + skipped + liveWeek(0, 5)))
        val found = stats.find<Insight.AlwaysFinish>()!!
        assertEquals("finisher", found.artistId)
        assertEquals(1.0, found.finished, 1e-9)
        assertEquals(15.0 / 45, found.overall, 1e-9)
    }

    @Test
    fun `where listening started, by time, naming the largest known source`() {
        val quickPicks = (0 until 20).map { listen("q$it", time(1) + it * 3 * minute, origin = PlayOrigin.QUICK_PICKS.code) }
        val widget = (0 until 5).map { listen("w$it", time(2) + it * 3 * minute, origin = PlayOrigin.WIDGET.code) }
        val search = (0 until 10).map { listen("s$it", time(3) + it * 3 * minute, origin = PlayOrigin.SEARCH.code) }
        val unknown = (0 until 30).map { listen("u$it", time(4) + it * 3 * minute, origin = PlayOrigin.UNKNOWN.code) }
        val found = ListeningInsights.compute(input(quickPicks + widget + search + unknown)).find<Insight.WhereFrom>()!!
        assertEquals(ListenSource.QUICK_PICKS, found.shares.first().first)
        assertEquals(25.0 / 65, found.shares.first().second, 1e-9)
        assertEquals(1.0, found.shares.sumOf { it.second }, 1e-9)
    }

    // Artists

    @Test
    fun `the late night artist leans to the night far more than everyone`() {
        val night = (0 until 10).map { listen("n$it", time(it % 5 + 1, 23 * hour + 10 * minute), artist = "owl", session = it.toLong()) } +
            (0 until 2).map { listen("n$it", time(it + 1, 15 * hour), artist = "owl") }
        val day = (0 until 40).map { listen("d$it", time(it % 6 + 1, 14 * hour) + it * 3 * minute, artist = "lark") }
        val found = ListeningInsights.compute(input(night + day)).find<Insight.NightArtist>()!!
        assertEquals("owl", found.artistId)
        assertEquals(10.0 / 12, found.share, 1e-9)
        // Everyone listening at night is no finding about one artist.
        val allNight = (0 until 20).map { listen("x$it", time(it % 5 + 1, 23 * hour + 10 * minute), artist = if (it % 2 == 0) "a" else "b") }
        assertNull(ListeningInsights.compute(input(allNight)).find<Insight.NightArtist>())
    }

    @Test
    fun `the loyal artist was there in most of the weeks`() {
        val month = now - 35 * day
        val weekly = (0 until 5).map { w -> listen("l$w", time(w * 7 + 1), artist = "loyal") }
        val burst = run("b", time(10), 20, artist = "burst")
        val found = ListeningInsights.compute(input(weekly + burst, periodStart = month, firstAt = covered - 100 * day)).find<Insight.Loyal>()!!
        assertEquals("loyal", found.artistId)
        assertEquals(5, found.weeks)
        assertEquals(6, found.ofWeeks)
        // A single week is too short a period to talk about loyalty.
        assertNull(ListeningInsights.compute(input(weekly.take(1) + burst)).find<Insight.Loyal>())
    }

    // Order

    @Test
    fun `findings take turns by kind, so no one kind fills the top`() {
        val listens = run("a", time(2, 9 * hour), 6) + (1..4).map { listen("b", time(it, 20 * hour)) } +
            liveWeek(skipped = 30, ended = 20) + (0 until 3).map { listen("late $it", time(it + 1, 2 * hour)) }
        val kinds = ListeningInsights.compute(input(listens)).insights.map { it::class }
        // Songs, habits, time; then the second of each. The longest session is left out, being the biggest day.
        assertEquals(
            listOf(
                Insight.SongOfTheDay::class, Insight.WhereFrom::class, Insight.BiggestDay::class,
                Insight.SongStreak::class, Insight.Skips::class, Insight.LateNights::class,
            ),
            kinds,
        )
    }
}
