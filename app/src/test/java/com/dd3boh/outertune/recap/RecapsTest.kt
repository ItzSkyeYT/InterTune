/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.stats.StatsBounds
import com.dd3boh.outertune.stats.StatsSongBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Recap's numbers, on a small made-up history: no real listening, no real songs. Each test
 * builds only what it needs and reads back what the slides would say.
 */
class RecapsTest {
    private val minute = Recaps.MINUTE_MS

    /** 2 December 2026, noon UTC: the reveal day. */
    private val now = at(LocalDate.of(2026, 12, 2), 12)
    private var nextId = 1L
    private val credits = ArrayList<RecapCredit>()
    private val firstCredits = HashMap<String, String>()

    private fun at(date: LocalDate, hour: Int, min: Int = 0) = date.atTime(hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun day(month: Int, d: Int, year: Int = 2026) = LocalDate.of(year, month, d)

    /** [song] credited to [artists] in that order. */
    private fun credit(song: String, vararg artists: String) {
        artists.forEachIndexed { i, a -> credits += RecapCredit(song, a, i) }
        artists.firstOrNull()?.let { firstCredits[song] = it }
    }

    private fun listen(
        song: String,
        start: Long,
        playedMs: Long = 3 * minute,
        offsetMin: Int = 0,
        endReason: Int = EndReason.ENDED,
        origin: Int = PlayOrigin.SEARCH.code,
        session: Long = start,
        counted: Boolean = true,
        continues: Long? = null,
        chip: Int = ContextChip.AUTO,
        id: Long = nextId++,
    ) = RecapListen(id, song, start, start + playedMs, offsetMin, playedMs, endReason, origin, session, counted, continues, chip)

    /** A skip of twenty seconds: listened to, never a play. */
    private fun skip(song: String, start: Long) = listen(song, start, 20_000, endReason = EndReason.SKIPPED, counted = false)

    /** [n] plays of [song] back to back from [from], in one session. */
    private fun run(song: String, from: Long, n: Int, playedMs: Long = 3 * minute, origin: Int = PlayOrigin.SEARCH.code) =
        (0 until n).map { listen(song, from + it * playedMs, playedMs, origin = origin, session = from) }

    /** Filler: [n] different songs once each, credited to no one, so that nothing else stands out. */
    private fun variety(n: Int, from: Long, tag: String = "filler") =
        (0 until n).map { listen("$tag $it", from + it * 3 * minute, session = from) }

    private fun input(
        listens: List<RecapListen>,
        before: List<StatsSongBefore> = emptyList(),
        liked: Set<String> = emptySet(),
        heard: List<RecapHeard> = emptyList(),
        rooms: List<RecapRoom> = emptyList(),
        firstAt: Long? = (listens.map { it.startedAt } + before.map { it.firstAt }).minOrNull(),
        firstLiveAt: Long? = firstAt,
        now: Long = this.now,
    ) = RecapInput(2026, now, 0, listens, credits.toList(), firstCredits.toMap(), before, liked, StatsBounds(firstAt, firstLiveAt), heard, rooms)

    private fun recap(listens: List<RecapListen>) = Recaps.compute(input(listens))!!

    // ---- whether there is a Recap at all

    @Test
    fun `a year with too little in it has no Recap`() {
        assertNull(Recaps.compute(input(variety(9, at(day(9, 1), 10)))))
        // Ten plays, but twenty minutes of music.
        val short = (0 until 10).map { listen("s$it", at(day(9, 1), 10) + it * 2 * minute, 2 * minute) }
        assertNull(Recaps.compute(input(short)))
        assertNotNull(Recaps.compute(input(variety(10, at(day(9, 1), 10)))))
    }

    // ---- the first play, the minutes, the hour and the longest streak

    private fun aSummer(): List<RecapListen> = buildList {
        add(skip("warmup", at(day(6, 14), 11, 20)))
        add(listen("first", at(day(6, 14), 11, 31)))
        for (d in 13..17) {
            add(listen("streaky", at(day(8, d), 18, 10), 4 * minute))
            add(listen("other $d", at(day(8, d), 18, 14)))
        }
        add(listen("lone", at(day(9, 1), 9)))
        add(listen("lone", at(day(9, 3), 9)))
    }

    @Test
    fun `it begins with the first play since the first day, in a first year`() {
        val r = recap(aSummer())
        assertTrue(r.firstYear)
        assertEquals(day(6, 14).toEpochDay(), r.sinceDay)
        // The skip at 11:20 starts the Recap's days but is not its first song.
        assertEquals(FirstSong("first", day(6, 14).toEpochDay(), 11 * 60 + 31), r.firstSong)
        assertEquals(day(12, 2).toEpochDay(), r.lastDay)
        assertEquals(172, r.days)
    }

    @Test
    fun `minutes count every second heard, skips too, and each day is listed`() {
        val r = recap(aSummer())
        assertEquals(20_000 + 3 * minute + 5 * 7 * minute + 6 * minute, r.playedMs)
        assertEquals(13, r.plays)
        assertEquals(r.days, r.msByDay.size)
        assertEquals(20_000 + 3 * minute, r.msByDay[0])
        assertEquals(r.playedMs, r.msByDay.sum())
        // Five days of seven minutes: the latest of them is the biggest.
        assertEquals(DayAmount(day(8, 17).toEpochDay(), 7 * minute), r.biggestDay)
        // The song heard longest, of those played three times or more.
        assertEquals(SongAmount("streaky", 20 * minute, 5), r.mostTime)
    }

    @Test
    fun `the listening hour is where the most time went, and its song the one most played in it`() {
        val r = recap(aSummer())
        assertEquals(18, r.peakHour)
        assertEquals(35 * minute, r.msByHour[18])
        assertEquals(SongAmount("streaky", 20 * minute, 5), r.peakSong)
        assertNull(r.lateNights)
    }

    @Test
    fun `a listen running past the hour counts in the next one too`() {
        val long = listen("mix", at(day(7, 1), 20, 30), 60 * minute)
        val r = recap(listOf(long) + variety(10, at(day(7, 2), 9)))
        assertEquals(30 * minute, r.msByHour[20])
        assertEquals(30 * minute, r.msByHour[21])
    }

    @Test
    fun `the longest streak is the longest run of days with music, and its song the one there most days`() {
        val r = recap(aSummer())
        assertEquals(Streak(5, day(8, 13).toEpochDay(), day(8, 17).toEpochDay(), "streaky", 5), r.streak)
    }

    @Test
    fun `of two streaks as long, the later one`() {
        val listens = variety(10, at(day(6, 1), 10)) +
            listOf(listen("a", at(day(7, 1), 9)), listen("a", at(day(7, 2), 9))) +
            listOf(listen("b", at(day(8, 1), 9)), listen("b", at(day(8, 2), 9)))
        assertEquals(Streak(2, day(8, 1).toEpochDay(), day(8, 2).toEpochDay(), "b", 2), recap(listens).streak)
    }

    @Test
    fun `nights past midnight need two, and the latest start is kept`() {
        val two = listOf(listen("owl", at(day(9, 2), 1)), listen("owl", at(day(9, 5), 3, 3)), listen("owl", at(day(9, 5), 0, 40)))
        assertEquals(LateNights(2, 3 * 60 + 3), recap(two + variety(10, at(day(9, 1), 12))).lateNights)
        assertNull(recap(two.take(1) + variety(10, at(day(9, 1), 12))).lateNights)
    }

    // ---- top songs and top artists

    private fun aChart(): List<RecapListen> {
        credit("a1", "UCalpha")
        credit("a2", "UCalpha", "UCbeta")
        credit("b1", "UCbeta")
        credit("c1", "UCgamma")
        credit("loc", "LA_local")
        credit("d1", "UCdelta")
        credit("g1", "UCdelta")
        credit("e1", "UCepsilon")
        credit("f1", "UCzeta")
        val from = at(day(9, 1), 8)
        var t = from
        fun many(song: String, n: Int, playedMs: Long = 3 * minute) = (0 until n).map { _ ->
            listen(song, t, playedMs, session = from).also { t += 30 * minute }
        }
        return many("a1", 6) + many("a2", 5) + many("b1", 4) + many("c1", 3, 10 * minute) + many("g1", 3) +
            many("loc", 7) + many("d1", 2) + many("e1", 2) + many("f1", 1)
    }

    @Test
    fun `top songs go by plays, then by time`() {
        val r = recap(aChart())
        // c1 and g1 have three plays each: c1's are longer.
        assertEquals(listOf("loc", "a1", "a2", "b1", "c1"), r.topSongs.map { it.songId })
        assertEquals(listOf(7, 6, 5, 4, 3), r.topSongs.map { it.plays })
        assertEquals(30 * minute, r.topSongs[4].ms)
        // Then the rest, in the same order, for covers that give nothing away.
        assertEquals(listOf("g1", "d1", "e1", "f1"), r.alsoPlayed)
    }

    @Test
    fun `the ranking is the tops and the rest in one order, the most played first`() {
        val r = recap(aChart())
        assertEquals(listOf("loc", "a1", "a2", "b1", "c1", "g1", "d1", "e1", "f1"), r.ranking)
        // Its first five are the tops and the songs after them are the ones the opening screen shows.
        assertEquals(r.topSongs.map { it.songId }, r.ranking.take(Recaps.TOP))
        assertEquals(r.alsoPlayed, r.ranking.drop(Recaps.TOP).take(Recaps.ALSO_PLAYED))
    }

    @Test
    fun `sixty songs give a ranking of fifty, the most played of them`() {
        // s1 once, s2 twice, and so on up to s60 sixty times, each run on a day of its own.
        val listens = (1..60).flatMap { n -> run("s$n", at(day(3, 1), 8) + n * 400 * minute, n) }
        val r = recap(listens)
        assertEquals(50, Recaps.PLAYLIST)
        assertEquals((60 downTo 11).map { "s$it" }, r.ranking)
        assertEquals(r.songs, 60)
    }

    @Test
    fun `a song only skipped, or heard before the year, is not in the ranking`() {
        val listens = variety(10, at(day(9, 1), 9), "kept") +
            skip("skipped", at(day(9, 2), 9)) +
            listen("last year", at(day(12, 31, 2025), 20))
        val r = recap(listens)
        assertEquals(10, r.ranking.size)
        assertTrue("skipped" !in r.ranking && "last year" !in r.ranking)
    }

    @Test
    fun `top artists count every credit, YouTube artists only, as the Stats page's row does`() {
        val r = recap(aChart())
        // a2 is a play for both of its artists; the local artist's seven plays are left out.
        assertEquals(listOf("UCalpha", "UCbeta", "UCdelta", "UCgamma", "UCepsilon"), r.topArtists.map { it.artistId })
        assertEquals(listOf(11, 9, 5, 3, 2), r.topArtists.map { it.plays })
        val alpha = r.topArtists[0]
        assertEquals(2, alpha.songs)
        assertEquals("a1", alpha.topSongId)
        assertEquals(6, alpha.topSongPlays)
        assertEquals("a2", r.topArtists[1].topSongId)
        assertEquals(33 * minute, alpha.ms)
        // Artists with a play by first credit, the local one among them, as the Stats page counts them.
        assertEquals(7, r.artists)
        assertEquals(9, r.songs)
    }

    @Test
    fun `the number one's own story, found, best day, in a row and by month`() {
        val hit = listOf(
            listen("hit", at(day(7, 1), 23, 54)),
        ) + run("hit", at(day(7, 4), 20), 5) + listOf(
            listen("hit", at(day(7, 5), 9)),
            listen("hit", at(day(8, 2), 10), session = 7),
            listen("break", at(day(8, 2), 10, 3), session = 7),
            listen("hit", at(day(8, 2), 10, 6), session = 7),
            listen("hit", at(day(8, 2), 10, 9), session = 7),
        )
        val r = recap(hit + variety(3, at(day(9, 9), 9)))
        val top = r.topSongs.first()
        assertEquals("hit", top.songId)
        assertEquals(10, top.plays)
        assertEquals(day(7, 1).toEpochDay(), top.firstHeardDay)
        assertEquals(23 * 60 + 54, top.firstHeardMinute)
        assertEquals(4, top.days)
        assertEquals(day(7, 4).toEpochDay(), top.bestDay)
        assertEquals(5, top.bestDayPlays)
        assertEquals(5, top.inARow)
        assertEquals(day(7, 4).toEpochDay(), top.inARowDay)
        assertEquals(mapOf(7 to 7, 8 to 3), top.byMonth)
    }

    @Test
    fun `a song first heard last year was found then`() {
        val before = listOf(StatsSongBefore("old", at(day(11, 3, 2025), 7, 15), at(day(11, 3, 2025), 7, 15), 1))
        val r = Recaps.compute(input(run("old", at(day(3, 1), 9), 12), before = before))!!
        assertFalse(r.firstYear)
        assertEquals(day(1, 1).toEpochDay(), r.sinceDay)
        assertEquals(day(11, 3, 2025).toEpochDay(), r.topSongs.first().firstHeardDay)
        assertEquals(7 * 60 + 15, r.topSongs.first().firstHeardMinute)
    }

    @Test
    fun `a paused play that was taken up again is one play`() {
        val first = listen("paused", at(day(9, 2), 9), endReason = EndReason.STOPPED)
        val rest = listen("paused", at(day(9, 2), 11), continues = first.id)
        val r = recap(listOf(first, rest) + variety(10, at(day(9, 3), 9)))
        assertEquals(11, r.plays)
        assertEquals(1, r.topSongs.first { it.songId == "paused" }.plays)
    }

    // ---- days are local, and the year is the phone's

    @Test
    fun `a listen's day is its own local day, at its own offset`() {
        // 23:30 UTC on the 31st of December 2025 is half past midnight on New Year's Day in Paris.
        val newYear = listen("first of the year", at(day(12, 31, 2025), 23, 30), offsetMin = 60)
        val r = recap(listOf(newYear) + variety(10, at(day(2, 1), 9)))
        assertEquals("first of the year", r.firstSong.songId)
        assertEquals(day(1, 1).toEpochDay(), r.firstSong.day)
        assertEquals(30, r.firstSong.minute)
    }

    @Test
    fun `a play on the last evening of the year before is not in the Recap, and the year is not a first`() {
        // 00:30 UTC on New Year's Day is still the 31st in Martinique.
        val old = listen("old", at(day(1, 1), 0, 30), offsetMin = -240, origin = PlayOrigin.QUICK_PICKS.code)
        val again = (0 until 3).map { listen("old", at(day(3, it + 1), 9), origin = PlayOrigin.QUICK_PICKS.code) }
        val r = recap(listOf(old) + again + variety(10, at(day(2, 1), 9)))
        assertFalse(r.firstYear)
        assertEquals(day(1, 1).toEpochDay(), r.sinceDay)
        assertEquals(13, r.plays)
        // Heard before the year: no find of Auto's.
        assertNull(r.auto)
    }

    @Test
    fun `once the year is over it ends on the 31st`() {
        val january = at(day(1, 10, 2027), 9)
        val r = Recaps.compute(input(variety(12, at(day(12, 30), 9)) + listen("next year", january), now = january))!!
        assertEquals(day(12, 31).toEpochDay(), r.lastDay)
        assertEquals(12, r.plays)
    }

    // ---- new artists and what Auto found

    @Test
    fun `in a first year a new artist is one found after the first month, and the best find the most played of them`() {
        credit("early", "UCearly")
        credit("found", "UCfound")
        credit("later", "UClater")
        val listens = run("early", at(day(6, 1), 9), 4) +
            run("found", at(day(7, 15), 9), 6) +
            run("later", at(day(8, 1), 9), 2)
        val r = recap(listens)
        assertEquals(NewArtists(2, afterFirstMonth = true, bestFind = BestFind("UCfound", day(7, 15).toEpochDay(), 6, "found")), r.newArtists)
    }

    @Test
    fun `after a first year a new artist is one never heard before, and a find needs five plays`() {
        credit("oldsong", "UCold")
        credit("fresh", "UCfresh")
        credit("shy", "UCshy")
        val before = listOf(StatsSongBefore("oldsong", at(day(5, 1, 2025), 9), at(day(5, 1, 2025), 9), 3))
        val listens = run("oldsong", at(day(3, 1), 9), 5) + run("fresh", at(day(4, 1), 9), 6) + run("shy", at(day(5, 1), 9), 2)
        val r = Recaps.compute(input(listens, before = before))!!
        assertEquals(NewArtists(2, afterFirstMonth = false, bestFind = BestFind("UCfresh", day(4, 1).toEpochDay(), 6, "fresh")), r.newArtists)
        val few = Recaps.compute(input(run("oldsong", at(day(3, 1), 9), 10) + run("shy", at(day(5, 1), 9), 2), before = before))!!
        assertEquals(NewArtists(1, afterFirstMonth = false, bestFind = null), few.newArtists)
    }

    @Test
    fun `a first year of a month or less cannot tell a find`() {
        credit("x", "UCx")
        val r = Recaps.compute(input(run("x", at(day(11, 10), 9), 12)))!!
        assertNull(r.newArtists)
    }

    @Test
    fun `Auto's finds are the songs first heard from Quick picks on Auto, in the order they came`() {
        val qp = PlayOrigin.QUICK_PICKS.code
        val before = listOf(StatsSongBefore("known", at(day(10, 1, 2025), 9), at(day(10, 1, 2025), 9), 2))
        val listens = buildList {
            add(listen("searched", at(day(9, 10), 9)))
            add(listen("searched", at(day(9, 10), 10), origin = qp))
            add(listen("auto1", at(day(9, 12), 9), origin = qp))
            add(listen("fav", at(day(9, 12), 10), origin = qp, chip = ContextChip.FAVOURITES))
            add(listen("auto2", at(day(9, 13), 9), origin = qp))
            add(listen("auto4", at(day(9, 14), 9), origin = PlayOrigin.WIDGET.code))
            add(listen("known", at(day(9, 15), 9), origin = qp))
            addAll(run("auto1", at(day(9, 20), 9), 4))
            addAll(run("auto4", at(day(9, 21), 9), 2))
            addAll(run("fav", at(day(9, 22), 9), 5))
        }
        val r = Recaps.compute(
            input(listens, before = before, liked = setOf("auto1", "fav"), firstLiveAt = at(day(9, 11), 0))
        )!!
        val auto = r.auto!!
        assertEquals(3, auto.songs)
        assertEquals(listOf(AutoFinds.MARK_KEPT + AutoFinds.MARK_LIKED, 0, AutoFinds.MARK_KEPT), auto.marks)
        assertEquals(2, auto.kept)
        assertEquals(1, auto.liked)
        assertEquals(SongAmount("auto1", 15 * minute, 5), auto.stuck)
        assertEquals(day(9, 12).toEpochDay(), auto.stuckFirstDay)
        // The log is older than where plays began to say where they came from.
        assertEquals(day(9, 11).toEpochDay(), auto.sinceDay)
    }

    @Test
    fun `no find of Auto's was kept, so there is nothing to say about Auto`() {
        val qp = PlayOrigin.QUICK_PICKS.code
        val listens = listOf(listen("once", at(day(9, 12), 9), origin = qp)) + variety(10, at(day(9, 13), 9))
        assertNull(recap(listens).auto)
    }

    // ---- recognition and rooms

    @Test
    fun `songs named by ear come from recognition's history, this year's, the latest first`() {
        val heard = listOf(
            RecapHeard(at(day(9, 24), 10, 5), "rec2", "Two", "Someone"),
            RecapHeard(at(day(9, 24), 10), "rec1", "One", "Someone"),
            RecapHeard(at(day(9, 24), 11), null, "Unplaced", "Nobody"),
            RecapHeard(at(day(12, 24, 2025), 10), "old", "Old", "Someone"),
        )
        val r = Recaps.compute(input(variety(10, at(day(9, 1), 9)), heard = heard))!!
        assertEquals(Recognitions(3, day(9, 24).toEpochDay(), day(9, 24).toEpochDay(), listOf("rec2", "rec1")), r.recognition)
    }

    @Test
    fun `with the history cleared, the plays started from a recognition stand for it`() {
        val rec = PlayOrigin.RECOGNISED.code
        val listens = listOf(
            listen("r1", at(day(10, 2), 9), origin = rec),
            listen("r2", at(day(10, 5), 9), origin = rec),
            listen("r1", at(day(10, 6), 9), origin = rec),
        ) + variety(10, at(day(9, 1), 9))
        assertEquals(Recognitions(2, day(10, 2).toEpochDay(), day(10, 5).toEpochDay(), listOf("r2", "r1")), recap(listens).recognition)
        assertNull(recap(variety(10, at(day(9, 1), 9))).recognition)
    }

    @Test
    fun `rooms, when there were any this year, with how many people`() {
        assertNull(recap(variety(10, at(day(9, 1), 9))).rooms)
        val rooms = listOf(
            RecapRoom(at(day(10, 1), 20), setOf("a", "b")),
            RecapRoom(at(day(10, 8), 20), setOf("a")),
            RecapRoom(at(day(11, 1), 20), setOf("c")),
            RecapRoom(at(day(11, 1, 2025), 20), setOf("d")),
        )
        assertEquals(Rooms(3, 3), Recaps.compute(input(variety(10, at(day(9, 1), 9)), rooms = rooms))!!.rooms)
    }
}
