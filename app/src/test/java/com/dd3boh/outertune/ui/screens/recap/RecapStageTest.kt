/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage the slides play on: the tunnel into the first slide, each later slide on its own sheet
 * of paper, a held story holding everything, confetti in its two places, and with Remove
 * animations on, cross-fades and no confetti. Made-up songs only.
 */
class RecapStageTest {
    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    private fun shown(): RecapShown {
        val r = Recap(
            year = 2026, firstYear = true, sinceDay = 100, lastDay = 223,
            firstSong = FirstSong("first", 100, 691), playedMs = 1L, plays = 9000, songs = 10, artists = 5,
            msByDay = List(124) { 1L }, biggestDay = DayAmount(120, 1L), mostTime = SongAmount("long", 1L, 37),
            msByHour = List(24) { 1L }, peakHour = 18, peakSong = SongAmount("evening", 1L, 9), lateNights = null,
            streak = Streak(64, 150, 213, "streaky", 15),
            topSongs = listOf(top("one", 90), top("two", 87), top("three", 66), top("four", 63), top("five", 62)),
            alsoPlayed = emptyList(), topArtists = listOf(TopArtist("UCa", 236, 1_000L, 4, "one", 90)), newArtists = null,
            auto = AutoFinds(null, 3, 1, 0, listOf(1, 0, 0), SongAmount("found", 1L, 62), 160), recognition = null, rooms = null,
        )
        val ids = listOf("first", "long", "evening", "streaky", "found") + r.topSongs.map { it.songId }
        return RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", null, 200) }, emptyMap())
    }

    private val slides = planSlides(shown())
    private fun at(kind: SlideKind) = slides.indexOfFirst { it.kind == kind } + 1

    private fun stage(still: Boolean = false) = RecapStage(slides, still, 0, 0).apply { resize(1080f, 2340f) }

    private fun RecapStage.run(ms: Int, paused: Boolean = false) {
        var t = 0
        while (t < ms) {
            frame(16.0, paused)
            t += 16
        }
    }

    @Test
    fun `Open Recap goes through the tunnel into the first slide, with no sheet over it`() {
        val st = stage()
        st.run(1_500)
        assertEquals(0, st.current.at)
        st.open()
        assertTrue(st.opening)
        st.run(RecapMotion.THROUGH_MS.toInt() + 64)
        assertFalse(st.opening)
        assertEquals(1, st.current.at)
        assertNull(st.wipe)
        assertNull(st.leaving)
    }

    @Test
    fun `each later slide comes in on its own sheet, and the one it covers goes once covered`() {
        val st = stage()
        st.go(1, 0, How.INSTANT)
        st.go(2, 0, How.ON)
        assertEquals(WipeKind.GROW, st.wipe?.kind)
        assertTrue(st.leaving?.covered == true)
        st.run(RecapMotion.WIPE_MS + 48)
        assertNull(st.wipe)
        assertNull(st.leaving)
        st.go(at(SlideKind.STREAK), 0, How.ON)
        assertEquals(WipeKind.STRIPS, st.wipe?.kind)
        // Back to the first slide from a later one: its sheet grows, as the tunnel is not there to come through.
        st.go(1, 0, How.BACK)
        assertEquals(WipeKind.GROW, st.wipe?.kind)
    }

    @Test
    fun `the countdown's steps each come in on a sheet of their own`() {
        val st = stage()
        val countdown = at(SlideKind.COUNTDOWN)
        st.go(countdown, 0, How.ON)
        assertEquals(WipeKind.GROW, st.wipe?.kind)
        st.run(1_000)
        st.go(countdown, 1, How.ON)
        assertEquals(WipeKind.STRIPS, st.wipe?.kind)
        assertEquals(paperOf(SlideKind.COUNTDOWN, 1).paper, st.current.paper.paper)
    }

    @Test
    fun `a held story holds its clock and everything that moves by it`() {
        val st = stage()
        st.go(at(SlideKind.MINUTES), 0, How.ON)
        st.run(500)
        val clock = st.clock.now
        val place = st.actors.first().x.at(clock)
        st.run(2_000, paused = true)
        assertEquals(clock, st.clock.now)
        assertEquals(place, st.actors.first().x.at(st.clock.now), 0.001f)
        st.run(100)
        assertTrue(st.clock.now > clock)
    }

    @Test
    fun `confetti flies on number one and on the thank-you page, and nowhere else`() {
        val st = stage()
        for (kind in SlideKind.entries.filter { it != SlideKind.NUMBER_ONE && it != SlideKind.THANKS && at(it) > 0 }) {
            st.go(at(kind), 0, How.ON)
            st.run(6_000)
            assertFalse("$kind", st.confetti.active)
        }
        st.go(at(SlideKind.NUMBER_ONE), 0, How.ON)
        st.run(1_400)
        assertFalse(st.confetti.active)
        st.run(200)
        assertTrue(st.confetti.active)
        st.go(at(SlideKind.CARD), 0, How.ON)
        assertFalse(st.confetti.active)
        st.go(at(SlideKind.THANKS), 0, How.ON)
        st.run(1_100)
        assertTrue(st.confetti.active)
        // Over in a few seconds; then the page rests.
        st.run(12_000)
        assertFalse(st.confetti.active)
    }

    @Test
    fun `confetti passes behind number one's words from the moment each begins to arrive`() {
        val anchors = RecapAnchors()
        anchors.origin = Offset(0f, 40f)
        anchors.words(7, "kick", 300L, listOf(Rect(72f, 240f, 500f, 290f)))
        anchors.words(7, "title/0", 1_900L, listOf(Rect(72f, 1000f, 400f, 1090f)))
        anchors.words(7, "found", 2_800L, listOf(Rect(72f, 1500f, 1010f, 1560f), Rect(72f, 1560f, 480f, 1620f)))
        // Each from when it begins to arrive, where it rests on the stage, a line of a text a box of its own.
        assertEquals(listOf(Rect(72f, 200f, 500f, 250f)), anchors.behind(7, 1_500L))
        assertEquals(2, anchors.behind(7, 2_000L).size)
        assertEquals(4, anchors.behind(7, 3_000L).size)
        assertTrue(Rect(72f, 1520f, 480f, 1580f) in anchors.behind(7, 3_000L))
        // Only on its own page, and forgotten with it.
        assertTrue(anchors.behind(8, 3_000L).isEmpty())
        anchors.forget(setOf(8))
        assertTrue(anchors.behind(7, 3_000L).isEmpty())
    }

    @Test
    fun `every line of number one's words is marked for the confetti to pass behind`() {
        val code = java.io.File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapSlides.kt").readText()
        val one = code.substringAfter("internal fun NumberOneSlide(").substringBefore("\n}\n")
        for (part in listOf("\"kick\"", "behind = \"title\"", "\"artists\"", "\"plays\"", "\"unit\"", "\"found\"")) {
            assertTrue("number one's $part", part in one)
        }
        val screen = java.io.File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("the confetti keeps clear of them", "anchors.behind(" in screen && "ClipOp.Difference" in screen)
    }

    @Test
    fun `a slide moves on seven seconds after everything on it has settled, the thank-you page never`() {
        val st = stage()
        assertTrue(RecapMotion.REST_MS in 6_000L..8_000L)
        st.go(at(SlideKind.MINUTES), 0, How.ON)
        val minutes = st.current
        // Its shapes' move alone: 1.1 s and a moment to come to rest.
        assertEquals(1_500L + RecapMotion.REST_MS, st.lengthOf(minutes))
        // Its parts say when they will have arrived: the latest counts.
        st.arrives(minutes.visit, 3_200L)
        st.arrives(minutes.visit, 2_000L)
        assertEquals(3_200L + RecapMotion.REST_MS, st.lengthOf(minutes))
        // Number one waits for its confetti to be over.
        st.go(at(SlideKind.NUMBER_ONE), 0, How.ON)
        assertTrue(st.lengthOf(st.current) >= 1_500L + RecapMotion.CONFETTI_OVER_MS + RecapMotion.REST_MS)
        st.go(at(SlideKind.THANKS), 0, How.ON)
        assertEquals(0L, st.lengthOf(st.current))
    }

    @Test
    fun `with Remove animations on, slides cross-fade, the shapes change in place, and nothing flies`() {
        val st = stage(still = true)
        st.run(200)
        st.open()
        st.run(400)
        assertEquals(1, st.current.at)
        st.go(2, 0, How.ON)
        assertEquals(WipeKind.FADE, st.wipe?.kind)
        st.go(at(SlideKind.NUMBER_ONE), 0, How.ON)
        assertNotNull(st.wipe)
        st.run(5_000)
        assertFalse(st.confetti.active)
        assertEquals(1f, st.shapesAlpha(st.clock.now), 1e-3f)
    }
}
