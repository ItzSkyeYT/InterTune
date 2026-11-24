/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.CameBack
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.DayPlays
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.MostSkipped
import com.dd3boh.outertune.recap.NightAndMorning
import com.dd3boh.outertune.recap.OnRepeat
import com.dd3boh.outertune.recap.PartOfDay
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.Season
import com.dd3boh.outertune.recap.Seasons
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the four slides of 24 Nov 2026 go in the story, the song under each, their papers beside every
 * slide that can come next to them, the seasons' names on each side of the equator, and their words.
 * Made-up songs only.
 */
class RecapMomentsPlanTest {
    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    private val seasons = Seasons(listOf(Season(2, 120, "summer song", 30), Season(3, 160, "autumn song", 41)))
    private val week = OnRepeat("binge", 180, 186, 41, 98, listOf(3, 6, 8, 7, 6, 5, 6))
    private val night = NightAndMorning(PartOfDay(40, 9, "owl", 12), PartOfDay(80, 30, "lark", 15))
    private val back = CameBack("again", 120, 220, 8, 9, listOf(DayPlays(110, 4), DayPlays(120, 4), DayPlays(220, 5), DayPlays(225, 4)))

    private fun recap(withMoments: Boolean = true) = Recap(
        year = 2026, firstYear = true, sinceDay = 100, lastDay = 300,
        firstSong = FirstSong("first", 100, 691), playedMs = 1L, plays = 9000, songs = 10, artists = 5,
        msByDay = List(201) { 1L }, biggestDay = DayAmount(120, 1L), mostTime = SongAmount("long", 1L, 37),
        msByHour = List(24) { 1L }, peakHour = 18, peakSong = SongAmount("evening", 1L, 9), lateNights = null,
        streak = Streak(64, 150, 213, "streaky", 15), topSongs = listOf(top("one", 90), top("two", 87), top("three", 66)), alsoPlayed = emptyList(),
        topArtists = listOf(TopArtist("UCa", 236, 1_000L, 4, "one", 90)), newArtists = null,
        auto = AutoFinds(null, 3, 1, 0, listOf(1, 0, 0), SongAmount("found", 1L, 62), 160), recognition = null, rooms = null,
        mostSkipped = MostSkipped("skippy", 23, 4),
        seasons = seasons.takeIf { withMoments }, onRepeat = week.takeIf { withMoments }, nightAndMorning = night.takeIf { withMoments },
        cameBack = back.takeIf { withMoments },
    )

    private fun shown(r: Recap): RecapShown {
        val ids = listOf("first", "long", "evening", "streaky", "found", "skippy", "summer song", "autumn song", "binge", "owl", "lark", "again") +
            r.topSongs.map { it.songId }
        return RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", null, 200) }, emptyMap())
    }

    @Test
    fun `the four go where the story turns to what they tell, and nothing of the slides before moves`() {
        assertTrue("the unit tests are a debug build, where the four are on", Unreleased.RECAP_MOMENTS)
        val kinds = planSlides(shown(recap())).map { it.kind }
        assertEquals(
            listOf(
                SlideKind.SINCE, SlideKind.MINUTES, SlideKind.SEASONS, SlideKind.HOUR, SlideKind.NIGHT, SlideKind.STREAK, SlideKind.WEEK,
                SlideKind.ARTISTS, SlideKind.ONLY_HERE, SlideKind.SKIPPED, SlideKind.CAME_BACK, SlideKind.COUNTDOWN, SlideKind.NUMBER_ONE,
                SlideKind.CARD, SlideKind.NOTE_WRITE, SlideKind.THANKS,
            ),
            kinds,
        )
        // Without them, the story it was.
        val before = planSlides(shown(recap(withMoments = false))).map { it.kind }
        assertEquals(kinds.filter { it !in setOf(SlideKind.SEASONS, SlideKind.NIGHT, SlideKind.WEEK, SlideKind.CAME_BACK) }, before)
    }

    @Test
    fun `each is left out where the year has nothing for it`() {
        for (missing in listOf("seasons", "week", "night", "back")) {
            val r = recap().let {
                when (missing) {
                    "seasons" -> it.copy(seasons = null)
                    "week" -> it.copy(onRepeat = null)
                    "night" -> it.copy(nightAndMorning = null)
                    else -> it.copy(cameBack = null)
                }
            }
            val kinds = planSlides(shown(r)).map { it.kind }
            assertEquals(missing, 15, kinds.size)
        }
    }

    @Test
    fun `the song under each is the one it is about`() {
        val slides = planSlides(shown(recap()))
        fun song(kind: SlideKind) = slides.single { it.kind == kind }.song(0)?.id
        assertEquals("the busiest season's", "autumn song", song(SlideKind.SEASONS))
        assertEquals("the night's", "owl", song(SlideKind.NIGHT))
        assertEquals("binge", song(SlideKind.WEEK))
        assertEquals("again", song(SlideKind.CAME_BACK))
        val morningOnly = planSlides(shown(recap().copy(nightAndMorning = NightAndMorning(null, PartOfDay(80, 30, "lark", 15)))))
        assertEquals("the morning's where there is no night", "lark", morningOnly.single { it.kind == SlideKind.NIGHT }.song(0)?.id)
    }

    @Test
    fun `no slide shares a paper with one that can come next to it, whichever slides a year has`() {
        // Every slide that can be missing, missing or not, and Only on InterTune, where-from or neither.
        val optional = listOf(
            SlideKind.NOTE_READ, SlideKind.SEASONS, SlideKind.NIGHT, SlideKind.STREAK, SlideKind.WEEK, SlideKind.ARTISTS, SlideKind.SKIPPED,
            SlideKind.CAME_BACK, SlideKind.COUNTDOWN,
        )
        val story = listOf(
            SlideKind.SINCE, SlideKind.NOTE_READ, SlideKind.MINUTES, SlideKind.SEASONS, SlideKind.HOUR, SlideKind.NIGHT, SlideKind.STREAK,
            SlideKind.WEEK, SlideKind.ARTISTS, SlideKind.ONLY_HERE, SlideKind.SKIPPED, SlideKind.CAME_BACK, SlideKind.COUNTDOWN,
            SlideKind.NUMBER_ONE, SlideKind.CARD, SlideKind.NOTE_WRITE, SlideKind.THANKS,
        )
        var checked = 0
        for (mask in 0 until (1 shl optional.size)) for (only in listOf(SlideKind.ONLY_HERE, SlideKind.WHERE_FROM, null)) {
            val left = optional.filterIndexed { i, _ -> mask and (1 shl i) == 0 }.toSet()
            val kinds = story.mapNotNull { k ->
                when {
                    k in left -> null
                    k == SlideKind.ONLY_HERE -> only
                    else -> k
                }
            }
            // The countdown's first step after the slide before it, its last before number one.
            val papers = listOf(START_PAPER.paper) + kinds.flatMap { k ->
                if (k == SlideKind.COUNTDOWN) listOf(paperOf(k, 0).paper, paperOf(k, COUNTDOWN_COLOURS.lastIndex).paper) else listOf(paperOf(k).paper)
            }
            papers.zipWithNext().forEachIndexed { i, (a, b) -> assertNotEquals("$kinds, at $i", a, b) }
            checked++
        }
        assertEquals(1536, checked)
    }

    @Test
    fun `December to February is winter in the north and summer in the south, by the time zone's country`() {
        assertEquals(RecapSeasonNames.Name.WINTER, RecapSeasonNames.of(0, south = false))
        assertEquals(RecapSeasonNames.Name.SUMMER, RecapSeasonNames.of(0, south = true))
        assertEquals(RecapSeasonNames.Name.SPRING, RecapSeasonNames.of(1, south = false))
        assertEquals(RecapSeasonNames.Name.AUTUMN, RecapSeasonNames.of(1, south = true))
        assertEquals(RecapSeasonNames.Name.SUMMER, RecapSeasonNames.of(2, south = false))
        assertEquals(RecapSeasonNames.Name.WINTER, RecapSeasonNames.of(2, south = true))
        assertEquals(RecapSeasonNames.Name.AUTUMN, RecapSeasonNames.of(3, south = false))
        assertEquals(RecapSeasonNames.Name.SPRING, RecapSeasonNames.of(3, south = true))
        assertEquals(listOf(12 to 2, 3 to 5, 6 to 8, 9 to 11), (0..3).map { RecapSeasonNames.months(it) })
        for (south in listOf("AU", "nz", "BR", "AR", "ZA", "CL")) assertTrue(south, RecapSeasonNames.south(south))
        for (north in listOf("FR", "US", "IN", "GB", "001", null)) assertFalse("$north", RecapSeasonNames.south(north))
    }

    @Test
    fun `the words of the four are in English and French, held from translators, with no long dash`() {
        val res = File("src/main/res")
        val english = File(res, "values/strings-ot.xml").readText()
        val french = File(res, "values-fr/strings-ot.xml").readText()
        val names = listOf(
            "recap_seasons_kick", "recap_season_winter", "recap_season_spring", "recap_season_summer", "recap_season_autumn", "recap_season_line",
            "recap_night_kick", "recap_morning_kick", "recap_night_title", "recap_morning_title", "recap_part_hours", "recap_night_line",
            "recap_morning_line", "recap_morning_note", "recap_week_kick", "recap_week_times", "recap_week_line", "recap_back_kick",
            "recap_back_stamp", "recap_back_days", "recap_back_line",
        )
        for (name in names) {
            val en = Regex("""<(string|plurals) name="$name"([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(english)
            val fr = Regex("""<(string|plurals) name="$name"([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(french)
            assertTrue("$name in English", en != null)
            assertTrue("$name in French", fr != null)
            assertTrue("$name is held from translators", "translatable=\"false\"" in en!!.groupValues[2])
            for (text in listOf(en.groupValues[3], fr!!.groupValues[3])) assertFalse("$name: $text", '—' in text || '–' in text)
        }
    }
}
