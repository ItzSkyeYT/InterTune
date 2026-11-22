/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.FromRow
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.MostSkipped
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapPiece
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.Recognitions
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import com.dd3boh.outertune.recap.WhereFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which slides a year gets, in what order, for how long, and the song under each: the mock's
 * story, with a slide the year has nothing for left out. Made-up songs only.
 */
class RecapPlanTest {
    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    private fun recap(
        topSongs: List<TopSong> = listOf(top("one", 90), top("two", 87), top("three", 66), top("four", 63), top("five", 62)),
        streak: Streak? = Streak(64, 150, 213, "streaky", 15),
        artists: List<TopArtist> = listOf(TopArtist("UCa", 236, 1_000L, 4, "one", 90)),
        auto: AutoFinds? = AutoFinds(null, 3, 1, 0, listOf(1, 0, 0), SongAmount("found", 1L, 62), 160),
        recognition: Recognitions? = null,
    ) = Recap(
        year = 2026, firstYear = true, sinceDay = 100, lastDay = 223,
        firstSong = FirstSong("first", 100, 691), playedMs = 1L, plays = 9000, songs = 10, artists = 5,
        msByDay = List(124) { 1L }, biggestDay = DayAmount(120, 1L), mostTime = SongAmount("long", 1L, 37),
        msByHour = List(24) { 1L }, peakHour = 18, peakSong = SongAmount("evening", 1L, 9), lateNights = null,
        streak = streak, topSongs = topSongs, alsoPlayed = emptyList(), topArtists = artists, newArtists = null,
        auto = auto, recognition = recognition, rooms = null,
    )

    private fun shown(r: Recap): RecapShown {
        val ids = listOf("first", "long", "evening", "streaky", "found", "heard") + r.topSongs.map { it.songId }
        return RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", null, 200) }, emptyMap())
    }

    private fun plan(r: Recap) = planSlides(shown(r))

    @Test
    fun `with nothing only InterTune does, where the songs came from takes its place, its first source's song under it`() {
        val f = WhereFrom(null, listOf(FromRow(WhereFrom.Source.LIBRARY, 30, "found", 9), FromRow(WhereFrom.Source.SEARCH, 10, "first", 2)))
        val slides = plan(recap(auto = null).copy(whereFrom = f))
        val kinds = slides.map { it.kind }
        assertFalse(SlideKind.ONLY_HERE in kinds)
        assertEquals(kinds.indexOf(SlideKind.ARTISTS) + 1, kinds.indexOf(SlideKind.WHERE_FROM))
        assertEquals("found", slides.single { it.kind == SlideKind.WHERE_FROM }.song(0)?.id)
        // Something only InterTune does: that, and no where-from.
        val with = plan(recap().copy(whereFrom = f)).map { it.kind }
        assertTrue(SlideKind.ONLY_HERE in with)
        assertFalse(SlideKind.WHERE_FROM in with)
        // Nothing for either: neither, the story one slide shorter.
        val neither = plan(recap(auto = null)).map { it.kind }
        assertFalse(SlideKind.ONLY_HERE in neither || SlideKind.WHERE_FROM in neither)
    }

    @Test
    fun `the song skipped most comes after what only InterTune has and before the countdown, its own song under it`() {
        val r = recap().copy(mostSkipped = MostSkipped("skippy", 23, 4))
        val ids = listOf("first", "long", "evening", "streaky", "found", "skippy") + r.topSongs.map { it.songId }
        val slides = planSlides(RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", null, 200) }, emptyMap()))
        val kinds = slides.map { it.kind }
        assertEquals(kinds.indexOf(SlideKind.ONLY_HERE) + 1, kinds.indexOf(SlideKind.SKIPPED))
        assertEquals(kinds.indexOf(SlideKind.SKIPPED) + 1, kinds.indexOf(SlideKind.COUNTDOWN))
        val skipped = slides.single { it.kind == SlideKind.SKIPPED }
        assertEquals("skippy", skipped.song(0)?.id)
        assertEquals(10_000L, skipped.ms)
    }

    @Test
    fun `a year with no song skipped three times has no such slide`() {
        assertEquals(false, plan(recap()).any { it.kind == SlideKind.SKIPPED })
    }

    @Test
    fun `a full year has the mock's ten slides in its order, each with its song`() {
        val slides = plan(recap())
        assertEquals(
            listOf(
                SlideKind.SINCE, SlideKind.MINUTES, SlideKind.HOUR, SlideKind.STREAK, SlideKind.ARTISTS,
                SlideKind.ONLY_HERE, SlideKind.COUNTDOWN, SlideKind.NUMBER_ONE, SlideKind.CARD, SlideKind.THANKS,
            ),
            slides.map { it.kind },
        )
        assertEquals(listOf("first", "long", "evening", "streaky", "one", "found"), slides.take(6).map { it.song(0)?.id })
        assertEquals(listOf("one", "one", "first"), slides.takeLast(3).map { it.song(0)?.id })
        // As long as the mock keeps each up; the thank-you page stays until it is left.
        assertEquals(listOf(9_000L, 10_000L, 10_000L, 10_000L, 11_000L, 12_000L, 6_500L, 12_000L, 12_000L, 0L), slides.map { it.ms })
    }

    @Test
    fun `the countdown goes from the fifth song to the second, a step each`() {
        val countdown = plan(recap()).single { it.kind == SlideKind.COUNTDOWN }
        assertEquals(4, countdown.steps)
        assertEquals(listOf("five", "four", "three", "two"), (0 until countdown.steps).map { countdown.song(it)?.id })
    }

    @Test
    fun `the thank-you page is in the app's own colours, not a cover's`() {
        assertNull(plan(recap()).last().cover(0))
    }

    @Test
    fun `a slide the year has nothing for is left out`() {
        val bare = plan(recap(topSongs = listOf(top("one", 12)), streak = null, artists = emptyList(), auto = null))
        assertEquals(
            listOf(SlideKind.SINCE, SlideKind.MINUTES, SlideKind.HOUR, SlideKind.NUMBER_ONE, SlideKind.CARD, SlideKind.THANKS),
            bare.map { it.kind },
        )
        // Two songs: the countdown has number two alone.
        val two = plan(recap(topSongs = listOf(top("one", 12), top("two", 10))))
        assertEquals(1, two.single { it.kind == SlideKind.COUNTDOWN }.steps)
    }

    @Test
    fun `only on InterTune stands on recognition alone, with a song it named`() {
        val slides = plan(recap(auto = null, recognition = Recognitions(2, 200, 200, listOf("heard"))))
        assertEquals("heard", slides.single { it.kind == SlideKind.ONLY_HERE }.song(0)?.id)
    }

    @Test
    fun `the sound goes through the story page by page, the countdown's steps each a page`() {
        val slides = plan(recap())
        val pages = storyPages(slides)
        assertEquals(
            listOf("first", "long", "evening", "streaky", "one", "found", "five", "four", "three", "two", "one", "one", "first"),
            pages.map { it.song?.id },
        )
        assertEquals(RecapPiece(66_666L, 9_000L), pages.first().piece)
        // Slide 7, the countdown, begins on page 6; its last step is page 9; number one is page 10.
        assertEquals(0, pageOf(slides, 1, 0))
        assertEquals(6, pageOf(slides, 7, 0))
        assertEquals(9, pageOf(slides, 7, 3))
        assertEquals(10, pageOf(slides, 8, 0))
        assertEquals(pages.lastIndex, pageOf(slides, slides.size, 0))
    }

    @Test
    fun `the piece under a slide is a third of the way in, as long as the slide`() {
        val since = plan(recap()).first()
        assertEquals(RecapPiece(66_666L, 9_000L), since.piece(0))
    }
}
