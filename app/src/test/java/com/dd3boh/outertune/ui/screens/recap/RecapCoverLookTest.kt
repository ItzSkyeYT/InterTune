/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.MadeCoverPlan
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapMadeCover
import com.dd3boh.outertune.recap.RecapPalette
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The slides that reveal the year's favourites in their covers' colours (CoverLook): the words
 * always read, the paper is the cover's own colour, two pages in a row never share a paper, the
 * countdown keeps its ink, a made cover gives its own colours, and every other slide keeps its
 * paper. Made-up covers only.
 */
class RecapCoverLookTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun darker(c: Color) = Color(c.red * 0.935f, c.green * 0.935f, c.blue * 0.935f)
    private fun over(ink: Color, paper: Color) = Color(
        red = ink.red * ink.alpha + paper.red * (1 - ink.alpha),
        green = ink.green * ink.alpha + paper.green * (1 - ink.alpha),
        blue = ink.blue * ink.alpha + paper.blue * (1 - ink.alpha),
    )

    private fun lab(c: Color) = RecapPalette.oklab((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    private fun lab(c: Int) = RecapPalette.oklab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    private fun hue(l: DoubleArray) = Math.toDegrees(atan2(l[2], l[1]))

    /** Covers' main colours of every kind: hues at three lightnesses, dull ones, greys, black, white, the greys words read worst on. */
    private val palettes: List<IntArray> = buildList {
        val hues = listOf(0xFF0000, 0xFF8800, 0xFFDD00, 0x33CC33, 0x00BBBB, 0x2255FF, 0x8833DD, 0xFF44AA)
        for (h in hues) for (k in listOf(1.0, 0.6, 0.3)) {
            val c = argb((((h shr 16) and 0xFF) * k).toInt(), (((h shr 8) and 0xFF) * k).toInt(), ((h and 0xFF) * k).toInt())
            add(intArrayOf(c, argb(240, 240, 235), argb(40, 40, 40)))
            add(intArrayOf(c, hues[(hues.indexOf(h) + 3) % hues.size] or (0xFF shl 24), hues[(hues.indexOf(h) + 5) % hues.size] or (0xFF shl 24)))
        }
        for (v in listOf(0, 30, 96, 110, 118, 126, 140, 180, 232, 255)) add(intArrayOf(argb(v, v, v), argb(255 - v, 255 - v, 255 - v), argb(128, 128, 128)))
        add(intArrayOf(argb(120, 100, 90), argb(200, 170, 150), argb(60, 50, 40)))
        for ((paper, ink, accent) in RecapCoverFetcher.PAPERS) add(intArrayOf(paper.toArgb(), accent.toArgb(), ink.toArgb()))
    }

    @Test
    fun `the words read on every cover's paper, the quieter ones and the accent's too, grain and all`() {
        for (main in palettes) for (ink in listOf(null, RecapInk.Ink)) {
            val p = CoverLook.of(main, emptyList(), ink).paper
            val name = main.joinToString { Integer.toHexString(it) } + " ink $ink"
            assertTrue("$name: ${CoverLook.contrast(p.ink, p.paper)}", CoverLook.contrast(p.ink, p.paper) >= 4.5)
            assertTrue("$name on the grain", CoverLook.contrast(p.ink, darker(p.paper)) >= 4.5)
            assertTrue("$name quieter words", CoverLook.contrast(over(p.ink2, p.paper), p.paper) >= 4.5)
            assertTrue("$name quieter words on the grain", CoverLook.contrast(over(p.ink2, darker(p.paper)), darker(p.paper)) >= 4.5)
            assertTrue("$name: the words are the Recap's ink or its cream", p.ink == RecapInk.Ink || p.ink == RecapInk.Cream)
            assertTrue("$name: on the accent", CoverLook.contrast(p.onAccent, p.accent) >= 4.5)
            if (ink == null) {
                // The accent is words too on these slides: the artists' places, number one's plays.
                assertTrue("$name: the accent on the paper", CoverLook.contrast(p.accent, p.paper) >= 4.5)
                assertTrue("$name: the accent on the grain", CoverLook.contrast(p.accent, darker(p.paper)) >= 4.5)
            } else {
                assertEquals(name, RecapInk.Ink, p.ink)
            }
            // The status bar's icons follow the paper: dark ones where the words are the ink.
            assertEquals(name, p.ink == RecapInk.Cream, p.dark)
        }
    }

    @Test
    fun `the paper is the cover's own colour, only lighter or darker, and nothing is made up`() {
        for (main in palettes) {
            val look = CoverLook.of(main, emptyList())
            val paper = lab(look.paper.paper)
            val cover = lab(main[0])
            if (hypot(cover[1], cover[2]) < 0.06 || hypot(paper[1], paper[2]) < 0.04) continue
            var apart = abs(hue(paper) - hue(cover)) % 360
            if (apart > 180) apart = 360 - apart
            assertTrue("${Integer.toHexString(main[0])}: hue ${hue(cover)} became ${hue(paper)}", apart < 8)
            // No more colourful than the cover.
            assertTrue(hypot(paper[1], paper[2]) <= hypot(cover[1], cover[2]) + 0.01)
        }
        // A cover of greys gives greys: black, grey and white, no colour.
        val grey = CoverLook.of(intArrayOf(argb(96, 96, 96), argb(20, 20, 20), argb(232, 232, 232)), emptyList(), RecapInk.Ink)
        for (c in listOf(grey.paper.paper) + grey.shapes) assertTrue("$c", hypot(lab(c)[1], lab(c)[2]) < 0.01)
    }

    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    /** A year whose songs' covers are named "cover:<song>", the countdown's five from [album] when given. */
    private fun slides(album: String? = null): List<Slide> {
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
        val top = r.topSongs.map { it.songId }.toSet()
        return planSlides(RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", "cover:" + (if (album != null && it in top) album else it), 200) }, emptyMap()))
    }

    /** Every page's paper in the story's order, the start screen's first, with [colours] for the covers. */
    private fun papers(slides: List<Slide>, colours: (Any?) -> IntArray?): List<Pair<String, SlidePaper>> {
        val looks = CoverLook.plan(slides, colours)
        return listOf("start" to START_PAPER) + slides.flatMap { slide ->
            (0 until slide.steps).map { step -> "${slide.kind} $step" to (looks[pageOf(slides, slides.indexOf(slide) + 1, step)]?.paper ?: paperOf(slide.kind, step)) }
        }
    }

    @Test
    fun `two pages one after the other never share a paper, a whole countdown from one album included`() {
        val fixed = listOf(RecapInk.Lemon, RecapInk.Red, RecapInk.Cream, RecapInk.Ink, RecapInk.Mint, RecapInk.Lilac).map { it.toArgb() }
        val checked = palettes + fixed.map { intArrayOf(it, argb(30, 60, 200), argb(250, 250, 250)) }
        for (main in checked) for (album in listOf(null, "album")) {
            val pages = papers(slides(album)) { main }
            pages.zipWithNext().forEach { (a, b) ->
                assertTrue("${main.joinToString { Integer.toHexString(it) }}: ${a.first} and ${b.first} ${CoverLook.distance(a.second.paper, b.second.paper)}",
                    CoverLook.distance(a.second.paper, b.second.paper) >= CoverLook.TOO_CLOSE)
            }
        }
    }

    @Test
    fun `the countdown keeps its ink at every step, and the slides about no cover keep their own paper`() {
        val slides = slides()
        val pages = papers(slides) { intArrayOf(argb(20, 30, 120), argb(200, 40, 40), argb(240, 240, 240)) }
        for ((name, paper) in pages) {
            val kind = SlideKind.entries.firstOrNull { name.startsWith(it.name + " ") }
            when {
                kind == SlideKind.COUNTDOWN -> assertEquals(name, RecapInk.Ink, paper.ink)
                kind != null && kind !in CoverLook.KINDS -> assertEquals(name, paperOf(kind, name.substringAfter(' ').toInt()).paper, paper.paper)
            }
        }
        // Covers not read yet: every slide its own paper.
        assertTrue(CoverLook.plan(slides) { null }.isEmpty())
        // The covers asked for: the artist's, then each step of the countdown, then number one's.
        assertEquals(listOf("cover:one", "cover:five", "cover:four", "cover:three", "cover:two", "cover:one"), CoverLook.models(slides))
    }

    @Test
    fun `a song from a file with no picture has the colours of the cover the Recap made it`() {
        for (title in listOf("Ingrid Is A Hybrid", "Glue", "Delilah", "Sweden", "Levels", "Latch", "Baby", "x", "Solar System", "Nanana", "One More Time")) {
            val (paper, ink, accent) = RecapCoverFetcher.PAPERS[MadeCoverPlan.paper(title, RecapCoverFetcher.PAPERS.size)]
            val main = CoverColours.made(RecapMadeCover(title, "Someone", "/music/$title.mp3"))
            assertEquals(listOf(paper.toArgb(), accent.toArgb(), ink.toArgb()), main.toList())
        }
        // Its papers read as they are: on a slide with nothing round it, the page is the made cover's paper and words.
        for ((paper, ink, accent) in RecapCoverFetcher.PAPERS) {
            val look = CoverLook.of(intArrayOf(paper.toArgb(), accent.toArgb(), ink.toArgb()), emptyList())
            assertEquals("$paper", paper, look.paper.paper)
            assertEquals("$paper", ink, look.paper.ink)
        }
    }

    @Test
    fun `the shapes take the cover's two other colours for the design's loud ones, and keep the ink and the cream`() {
        val shapes = listOf(Color(0xFF2050D0), Color(0xFFE0C020))
        for (kind in CoverLook.KINDS) for (step in 0..3) {
            val sets = CoverLook.recolour(Choreography.of(kind, step), shapes)
            for (set in sets) for ((id, pose) in set.set) {
                for (c in pose.c) assertTrue("$kind $step $id $c", c in shapes || c == RecapInk.Ink || c == RecapInk.Cream || c == RecapInk.White)
                // A shape of two layers or more shows two colours.
                val loud = pose.c.filter { it in shapes }
                if (loud.size >= 2) assertNotEquals("$kind $step $id", loud[0], loud[1])
            }
        }
    }

    @Test
    fun `on the stage each step of the countdown comes in on its cover's paper, and its shapes turn to the cover's colours`() {
        val slides = slides()
        val main = intArrayOf(argb(20, 30, 120), argb(200, 40, 40), argb(240, 240, 240))
        val looks = CoverLook.plan(slides) { main }
        // Covers not read yet: the countdown comes up on its own paper.
        var read: IntArray? = null
        val st = RecapStage(slides, still = false, startAt = 0, startStep = 0, colours = { read }).apply { resize(1080f, 2340f) }
        val countdown = slides.indexOfFirst { it.kind == SlideKind.COUNTDOWN } + 1
        st.go(countdown, 0, How.ON)
        assertEquals(paperOf(SlideKind.COUNTDOWN, 0).paper, st.current.paper.paper)
        // Read: the next step wears its cover's colours, growing or sliding in over the last one's paper.
        read = main
        st.coversRead()
        repeat(80) { st.frame(16.0, false) }
        st.go(countdown, 1, How.ON)
        val first = looks.getValue(pageOf(slides, countdown, 1))
        assertEquals(WipeKind.STRIPS, st.wipe?.kind)
        assertEquals(paperOf(SlideKind.COUNTDOWN, 0).paper, st.wipe?.under)
        assertEquals(first.paper.paper, st.wipe?.over)
        assertEquals(first.paper.paper, st.current.paper.paper)
        repeat(80) { st.frame(16.0, false) }
        st.go(countdown, 2, How.ON)
        val next = looks.getValue(pageOf(slides, countdown, 2))
        assertEquals(first.paper.paper, st.wipe?.under)
        assertEquals(next.paper.paper, st.wipe?.over)
        // The shapes on show take the cover's colours, and the design's ink and cream; none keeps a loud colour of its own.
        repeat(80) { st.frame(16.0, false) }
        val shown = Choreography.of(SlideKind.COUNTDOWN, 2).last().set.filterValues { !it.hide }.keys
        val worn = shown.flatMap { st.actors[it.ordinal].last?.c.orEmpty() }
        assertTrue(worn.any { it in next.shapes })
        for (c in worn) assertTrue("$c", c in next.shapes || c == RecapInk.Ink || c == RecapInk.Cream || c == RecapInk.White)
        // Number one grows in with the cover's other colours as the papers at its edge.
        st.go(countdown + 1, 0, How.ON)
        assertEquals(WipeKind.GROW, st.wipe?.kind)
        assertEquals(looks.getValue(pageOf(slides, countdown + 1, 0)).shapes, st.wipe?.rims)
        // The slide before the countdown is about no cover: its own paper.
        st.go(countdown - 1, 0, How.BACK)
        assertEquals(paperOf(slides[countdown - 2].kind).paper, st.current.paper.paper)
    }
}
