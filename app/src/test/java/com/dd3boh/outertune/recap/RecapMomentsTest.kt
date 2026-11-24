/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.stats.StatsBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The four slides of 24 Nov 2026 (RecapMoments) on made-up listening, worked out through the Recap
 * itself (Recaps.compute), so that a play and its local day and hour are the Recap's: your seasons,
 * the week you couldn't stop, your 2 AM songs and the morning's, and the one you came back to. No
 * real listening, no real songs. Each test builds only what it needs.
 */
class RecapMomentsTest {
    private val minute = Recaps.MINUTE_MS

    /** 2 December 2026, noon UTC: the reveal day. */
    private val now = at(day(12, 2), 12)
    private var nextId = 1L

    private fun at(date: LocalDate, hour: Int, min: Int = 0) = date.atTime(hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun day(month: Int, d: Int, year: Int = 2026): LocalDate = LocalDate.of(year, month, d)
    private fun epoch(month: Int, d: Int) = day(month, d).toEpochDay()

    private fun listen(song: String, start: Long, playedMs: Long = 3 * minute, offsetMin: Int = 0) =
        RecapListen(nextId++, song, start, start + playedMs, offsetMin, playedMs, EndReason.ENDED, PlayOrigin.SEARCH.code, start, true, null)

    /** [n] plays of [song] one after another from [from]. */
    private fun run(song: String, from: Long, n: Int, playedMs: Long = 3 * minute, offsetMin: Int = 0) =
        (0 until n).map { listen(song, from + it * playedMs, playedMs, offsetMin) }

    /** [n] songs once each from [from], none of them standing out. */
    private fun variety(n: Int, from: Long, tag: String) = (0 until n).map { listen("$tag $it", from + it * 3 * minute) }

    private fun recap(listens: List<RecapListen>, now: Long = this.now): Recap {
        val first = listens.minOf { it.startedAt }
        return Recaps.compute(RecapInput(2026, now, 0, listens, emptyList(), emptyMap(), emptyList(), emptySet(), StatsBounds(first, first)))!!
    }

    // ---- your seasons

    @Test
    fun `one song a season, in the order of the year, for the seasons with enough plays`() {
        val log = run("snow", at(day(1, 10), 9), 6) + variety(24, at(day(1, 11), 9), "jan") +
            run("bloom", at(day(4, 10), 9), 5) + variety(25, at(day(4, 11), 9), "apr") +
            run("sun", at(day(7, 10), 9), 8) + variety(32, at(day(7, 11), 9), "jul") +
            // Twenty-nine plays: autumn says nothing.
            run("leaf", at(day(10, 10), 9), 9) + variety(20, at(day(10, 11), 9), "oct")
        val s = recap(log).seasons!!
        assertEquals(listOf(Season(0, 30, "snow", 6), Season(1, 30, "bloom", 5), Season(2, 40, "sun", 8)), s.seasons)
        assertEquals("the busiest season's song plays under the slide", "sun", s.busiest.songId)
    }

    @Test
    fun `a calendar year's December goes with its winter`() {
        val log = run("frost", at(day(2, 3), 9), 3) + variety(17, at(day(2, 4), 9), "feb") +
            run("frost", at(day(12, 1), 9), 4) + variety(6, at(day(12, 1), 11), "dec") +
            run("rain", at(day(9, 3), 9), 4) + variety(26, at(day(9, 4), 9), "sep")
        val s = recap(log).seasons!!
        assertEquals(listOf(Season(0, 30, "frost", 7), Season(3, 30, "rain", 4)), s.seasons)
        assertEquals(0, RecapMoments.seasonOf(epoch(12, 31)))
        assertEquals(1, RecapMoments.seasonOf(epoch(3, 1)))
        assertEquals(2, RecapMoments.seasonOf(epoch(8, 31)))
        assertEquals(3, RecapMoments.seasonOf(epoch(11, 30)))
    }

    @Test
    fun `a year that starts in June has summer and autumn at most`() {
        val log = run("june", at(day(6, 14), 9), 5) + variety(40, at(day(7, 2), 9), "summer") +
            run("october", at(day(10, 3), 9), 6) + variety(30, at(day(10, 4), 9), "autumn")
        val r = recap(log)
        assertEquals(epoch(6, 14), r.sinceDay)
        assertEquals(listOf(2, 3), r.seasons!!.seasons.map { it.season })
        assertEquals(listOf("june", "october"), r.seasons!!.seasons.map { it.songId })
    }

    @Test
    fun `a song played three times in a season is its song, twice is not, and one season alone makes no slide`() {
        val summer = run("three", at(day(7, 1), 9), 3) + variety(27, at(day(7, 2), 9), "jul")
        val autumn = run("two", at(day(10, 1), 9), 2) + variety(28, at(day(10, 2), 9), "oct")
        assertNull("autumn has no song of its own: one season is left", recap(summer + autumn).seasons)
        val spring = run("four", at(day(4, 1), 9), 4) + variety(26, at(day(4, 2), 9), "apr")
        assertEquals(listOf(Season(1, 30, "four", 4), Season(2, 30, "three", 3)), recap(spring + summer + autumn).seasons!!.seasons)
    }

    @Test
    fun `two songs tied in a season go by the time heard in it, then by plays in the whole year`() {
        val summer = run("short", at(day(7, 1), 9), 4) + run("long", at(day(7, 1), 12), 4, playedMs = 4 * minute) +
            run("again", at(day(7, 3), 9), 2) + variety(20, at(day(7, 2), 9), "jul")
        val autumn = run("once", at(day(10, 1), 9), 4) + run("again", at(day(10, 1), 12), 4) + variety(22, at(day(10, 2), 9), "oct")
        val s = recap(summer + autumn).seasons!!.seasons
        assertEquals("heard longer", "long", s[0].songId)
        assertEquals("as long in autumn, more plays in the year", "again", s[1].songId)
    }

    @Test
    fun `each season takes a song of its own where it has one, the busiest first, or else its own top song again`() {
        val summer = run("hit", at(day(7, 1), 9), 10) + run("s2", at(day(7, 1), 12), 5) + variety(35, at(day(7, 2), 9), "jul")
        val autumn = run("hit", at(day(10, 1), 9), 8) + run("a2", at(day(10, 1), 12), 4) + variety(28, at(day(10, 2), 9), "oct")
        val spring = run("hit", at(day(4, 1), 9), 3) + variety(27, at(day(4, 2), 9), "apr")
        assertEquals(listOf("hit", "hit", "a2"), recap(spring + summer + autumn).seasons!!.seasons.map { it.songId })
    }

    @Test
    fun `a play's season is that of its own local day`() {
        // From 23:30 UTC on 31 May, half past midnight on 1 June in Paris: summer, not spring.
        val spring = run("may", at(day(5, 2), 9), 4) + variety(26, at(day(5, 3), 9), "may")
        val late = run("late", at(day(5, 31), 23, 30), 5, offsetMin = 60) +
            (0 until 25).map { listen("night $it", at(day(5, 31), 23, 30) + 15 * minute + it * 3 * minute, offsetMin = 60) }
        val s = recap(spring + late).seasons!!.seasons
        assertEquals(listOf(1, 2), s.map { it.season })
        assertEquals("late", s[1].songId)
        assertNull("in London the same plays are still May's", recap(spring + late.map { it.copy(tzOffsetMin = 0) }).seasons)
    }

    // ---- the week you couldn't stop

    @Test
    fun `the week one song took over, its days and how much of everything it was`() {
        val perDay = listOf(3, 6, 8, 7, 6, 5, 6)
        val log = perDay.flatMapIndexed { i, n -> run("pitiful", at(day(3, 3 + i), 18), n) + variety(5, at(day(3, 3 + i), 8), "d$i") } +
            run("pitiful", at(day(6, 1), 9), 4) + variety(30, at(day(5, 1), 9), "may")
        val week = recap(log).onRepeat!!
        assertEquals(OnRepeat("pitiful", epoch(3, 3), epoch(3, 9), 41, 76, perDay), week)
    }

    @Test
    fun `ten plays in seven days and a fifth of everything played then, or no such week`() {
        val few = run("nine", at(day(3, 3), 18), 9) + variety(10, at(day(3, 4), 9), "few")
        assertNull("nine plays", recap(few).onRepeat)
        val busy = run("busy", at(day(3, 3), 18), 12) + variety(49, at(day(3, 4), 9), "busy")
        assertNull("twelve of sixty-one", recap(busy).onRepeat)
        val enough = run("enough", at(day(3, 3), 18), 12) + variety(48, at(day(3, 4), 9), "enough")
        assertEquals("twelve of sixty, a fifth", 12, recap(enough).onRepeat?.plays)
    }

    @Test
    fun `of two weeks as full, the one where the song took more of it, then the later one`() {
        val march = run("a", at(day(3, 2), 18), 12) + variety(20, at(day(3, 3), 9), "mar")
        val april = run("b", at(day(4, 6), 18), 12) + variety(30, at(day(4, 7), 9), "apr")
        assertEquals("twelve of thirty-two over twelve of forty-two", "a", recap(march + april).onRepeat?.songId)
        val even = run("b", at(day(4, 6), 18), 12) + variety(20, at(day(4, 7), 9), "apr")
        assertEquals("as much of each: the later", "b", recap(march + even).onRepeat?.songId)
    }

    @Test
    fun `a week runs across two months, and one at the year's end stops on the Recap's last day`() {
        val across = run("leap", at(day(2, 26), 18), 6) + run("leap", at(day(3, 2), 18), 6) + variety(10, at(day(1, 5), 9), "jan")
        val w = recap(across).onRepeat!!
        assertEquals(epoch(2, 26) to epoch(3, 4), w.firstDay to w.lastDay)
        assertEquals(listOf(6, 0, 0, 0, 6, 0, 0), w.byDay)
        val end = (0 until 4).flatMap { run("last", at(day(11, 29).plusDays(it.toLong()), 9), 3) } + variety(10, at(day(1, 5), 9), "jan")
        val cut = recap(end).onRepeat!!
        assertEquals(epoch(11, 29) to epoch(12, 2), cut.firstDay to cut.lastDay)
        assertEquals(listOf(3, 3, 3, 3), cut.byDay)
    }

    // ---- your 2 AM songs, and the morning's

    /** [n] plays starting at [hour]:[min] local time on each of [days] days from [from], [song] taking the first [own] of them each time. */
    private fun daily(from: LocalDate, days: Int, hour: Int, min: Int, song: String, own: Int, n: Int, offsetMin: Int = 0, tag: String = song) =
        (0 until days).flatMap { d ->
            val start = at(from.plusDays(d.toLong()), hour, min) - offsetMin * minute
            (0 until n).map { i -> listen(if (i < own) song else "$tag other $d $i", start + i * 3 * minute, offsetMin = offsetMin) }
        }

    @Test
    fun `after midnight and first thing in the morning, by each play's local time`() {
        // 01:30 in Athens is 23:30 UTC the evening before: the night's.
        val night = daily(day(9, 2), 4, 1, 30, "owl", own = 3, n = 5, offsetMin = 120)
        val morning = daily(day(9, 2), 5, 7, 0, "lark", own = 3, n = 4)
        val evening = daily(day(9, 2), 5, 19, 0, "evening", own = 4, n = 4)
        val n = recap(night + morning + evening).nightAndMorning!!
        assertEquals(PartOfDay(20, 4, "owl", 12), n.night)
        assertEquals(PartOfDay(20, 5, "lark", 15), n.morning)
        assertNull("the same plays in London are evening ones", recap(night.map { it.copy(tzOffsetMin = 0) } + morning).nightAndMorning?.night)
    }

    @Test
    fun `the day begins at 04 00, a play at 03 59 is the night's and one at 04 00 the morning's`() {
        val late = (0 until 5).flatMap { d -> (0 until 3).map { listen("owl", at(day(9, 2 + d), 3, 59)) } }
        val early = (0 until 5).flatMap { d -> (0 until 3).map { listen("lark", at(day(9, 2 + d), 4, 0)) } }
        val n = recap(late + early).nightAndMorning!!
        assertEquals(PartOfDay(15, 5, "owl", 15), n.night)
        assertEquals(PartOfDay(15, 5, "lark", 15), n.morning)
        // And the morning ends at 10:00.
        val ten = (0 until 5).flatMap { d -> (0 until 3).map { listen("late riser", at(day(9, 2 + d), 10, 0)) } }
        assertNull(recap(late + ten).nightAndMorning?.morning)
    }

    @Test
    fun `only the part that had enough, and nothing where neither had`() {
        val night = daily(day(9, 2), 4, 1, 0, "owl", own = 3, n = 5)
        val thinMorning = daily(day(9, 2), 2, 7, 0, "lark", own = 3, n = 5)
        val morning = daily(day(9, 2), 5, 7, 0, "lark", own = 3, n = 4)
        assertEquals(NightAndMorning(PartOfDay(20, 4, "owl", 12), null), recap(night + thinMorning).nightAndMorning)
        assertEquals(NightAndMorning(null, PartOfDay(20, 5, "lark", 15)), recap(morning + variety(10, at(day(9, 2), 18), "eve")).nightAndMorning)
        assertNull(recap(variety(30, at(day(9, 2), 18), "eve") + thinMorning).nightAndMorning)
    }

    @Test
    fun `one sleepless night is not your 2 AM songs, three nights are`() {
        val twoNights = daily(day(9, 2), 2, 1, 0, "owl", own = 5, n = 15)
        assertNull(recap(twoNights).nightAndMorning)
        val threeNights = daily(day(9, 2), 3, 1, 0, "owl", own = 2, n = 5)
        assertEquals(PartOfDay(15, 3, "owl", 6), recap(threeNights).nightAndMorning?.night)
    }

    @Test
    fun `the morning takes a song of its own where it has one`() {
        val night = daily(day(9, 2), 5, 1, 0, "same", own = 2, n = 4)
        val morning = daily(day(9, 2), 5, 7, 0, "same", own = 2, n = 4, tag = "m") + run("other", at(day(9, 10), 8), 4)
        assertEquals("other", recap(night + morning).nightAndMorning?.morning?.songId)
        val onlySame = daily(day(9, 2), 5, 7, 0, "same", own = 2, n = 4, tag = "m")
        assertEquals("same", recap(night + onlySame).nightAndMorning?.morning?.songId)
    }

    // ---- the one you came back to

    /** Two plays of [song] on each of [days]. */
    private fun twice(song: String, vararg days: LocalDate) = days.flatMap { run(song, at(it, 20), 2) }

    private val filler get() = variety(10, at(day(1, 5), 9), "jan")

    @Test
    fun `a song loved, gone for months and found again`() {
        val log = twice("flame", day(6, 5), day(6, 10), day(6, 15)) + twice("flame", day(9, 20), day(9, 22), day(9, 25)) + filler
        val back = recap(log).cameBack!!
        assertEquals("flame", back.songId)
        assertEquals(epoch(6, 15) to epoch(9, 20), back.lastBefore to back.backOn)
        assertEquals(96, back.awayDays)
        assertEquals(6 to 6, back.before to back.after)
        assertEquals(
            listOf(epoch(6, 5), epoch(6, 10), epoch(6, 15), epoch(9, 20), epoch(9, 22), epoch(9, 25)).map { DayPlays(it, 2) },
            back.byDay,
        )
    }

    @Test
    fun `sixty days away is dropping it, fifty-nine is not`() {
        val before = twice("x", day(3, 1), day(3, 2), day(3, 3))
        // 3 March to 2 May: 59 days in between; to 3 May, 60.
        assertNull(recap(before + twice("x", day(5, 2), day(5, 3), day(5, 4)) + filler).cameBack)
        assertEquals(60, recap(before + twice("x", day(5, 3), day(5, 4), day(5, 5)) + filler).cameBack?.awayDays)
    }

    @Test
    fun `five plays within thirty days on each side, or it was never loved or never found`() {
        val after = twice("x", day(9, 1), day(9, 2), day(9, 3))
        val four = run("x", at(day(5, 1), 20), 4)
        assertNull("four before", recap(four + after + filler).cameBack)
        // Five, but a month and a day apart.
        val spread = run("x", at(day(4, 1), 20), 1) + run("x", at(day(5, 2), 20), 4)
        assertNull("five over thirty-one days", recap(spread + after + filler).cameBack)
        val within = run("x", at(day(4, 3), 20), 1) + run("x", at(day(5, 2), 20), 4)
        assertNotNull("five within thirty days", recap(within + after + filler).cameBack)
    }

    @Test
    fun `of two songs found again, the one away longest, then the one played most`() {
        val a = twice("a", day(3, 1), day(3, 2), day(3, 3)) + twice("a", day(5, 12), day(5, 13), day(5, 14))
        val b = twice("b", day(3, 1), day(3, 2), day(3, 3)) + twice("b", day(6, 1), day(6, 2), day(6, 3))
        assertEquals("b", recap(a + b + filler).cameBack?.songId)
        val c = twice("c", day(2, 28), day(3, 1), day(3, 2), day(3, 3)) + twice("c", day(6, 1), day(6, 2), day(6, 3))
        assertEquals("as long away, more plays", "c", recap(b + c + filler).cameBack?.songId)
    }

    @Test
    fun `in a first year begun in June, a song from its first weeks found again in the autumn`() {
        val log = twice("first weeks", day(6, 3), day(6, 8), day(6, 12)) + variety(30, at(day(7, 1), 9), "july") +
            twice("first weeks", day(10, 1), day(10, 5), day(10, 9))
        val r = recap(log)
        assertEquals(epoch(6, 3), r.sinceDay)
        assertEquals(epoch(6, 12) to epoch(10, 1), r.cameBack?.let { it.lastBefore to it.backOn })
    }

    @Test
    fun `the songs the four slides show are named for the reader`() {
        // In the evenings: neither night nor morning.
        val r = recap(
            run("snow", at(day(1, 10), 18), 6) + variety(24, at(day(1, 11), 18), "jan") +
                run("sun", at(day(7, 10), 18), 8) + variety(32, at(day(7, 11), 18), "jul"),
        )
        assertEquals(listOf("snow", "sun"), RecapMoments.songs(r))
        assertEquals(emptyList<String>(), RecapMoments.songs(r.copy(seasons = null)))
    }
}
