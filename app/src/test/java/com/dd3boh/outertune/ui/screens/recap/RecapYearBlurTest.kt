/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dd3boh.outertune.recap.AutoFinds
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapMadeCover
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.Streak
import com.dd3boh.outertune.recap.TopArtist
import com.dd3boh.outertune.recap.TopSong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The year card's page on a Living blur of the top three covers (YearBlur): which colours it takes,
 * that the page's words read on it, the still the shared card gets, that only that page wears it,
 * and that it stands still with Remove animations on. Made-up covers only.
 */
class RecapYearBlurTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val magenta = argb(218, 25, 195)
    private val green = argb(36, 200, 12)
    private val white = argb(232, 232, 232)
    private val grey = argb(96, 96, 96)
    private val blue = argb(40, 80, 220)
    private val red = argb(200, 40, 40)

    @Test
    fun `bass, voice and top are the top three covers' colours, each its main one where that has colour enough, else its most vivid`() {
        assertArrayEquals(intArrayOf(magenta, blue, red), YearBlur.colours(listOf(intArrayOf(magenta, green, white), intArrayOf(grey, blue, white), intArrayOf(red, grey, white))))
        // One cover only: its other colours make up the rest.
        assertArrayEquals(intArrayOf(magenta, green, white), YearBlur.colours(listOf(intArrayOf(magenta, green, white))))
        // A cover that could not be read is passed over; with none at all the page keeps its own paper.
        assertArrayEquals(intArrayOf(blue, red, grey), YearBlur.colours(listOf(null, intArrayOf(grey, blue, white), intArrayOf(red, grey, white))))
        assertNull(YearBlur.colours(listOf(null, null, null)))
        assertNull(YearBlur.of(emptyList()))
        // A song from a file with no picture: the colours of the cover the Recap made it.
        val made = CoverColours.made(RecapMadeCover("Glue", "Bicep", "/music/glue.mp3"))
        assertEquals(made[0], YearBlur.colours(listOf(intArrayOf(magenta, green, white), made, intArrayOf(red, grey, white)))!![1])
    }

    @Test
    fun `the page's words read wherever the blur rests, grain and all, and the status bar's icons follow them`() {
        val hues = listOf(0xFF0000, 0xFF8800, 0xFFDD00, 0x33CC33, 0x00BBBB, 0x2255FF, 0x8833DD, 0xFF44AA)
        val palette = buildList {
            for (h in hues) for (k in listOf(1.0, 0.6, 0.3)) add(argb(((h shr 16 and 0xFF) * k).toInt(), ((h shr 8 and 0xFF) * k).toInt(), ((h and 0xFF) * k).toInt()))
            for (v in listOf(0, 40, 96, 128, 180, 232, 255)) add(argb(v, v, v))
        }
        for (a in palette.indices) {
            val three = intArrayOf(palette[a], palette[(a + 5) % palette.size], palette[(a + 11) % palette.size])
            val look = YearBlur.readable(three)
            val ink = look.paper.ink
            val name = three.joinToString { Integer.toHexString(it) }
            assertTrue(name, ink == RecapInk.Ink || ink == RecapInk.Cream)
            for (c in YearBlur.atRest(look.colours)) {
                val grained = Color(c.red * 0.935f, c.green * 0.935f, c.blue * 0.935f)
                assertTrue("$name: $c", CoverLook.contrast(ink, c) >= 4.5 && CoverLook.contrast(ink, grained) >= 4.5)
            }
            assertEquals(name, ink == RecapInk.Cream, look.paper.dark)
        }
    }

    @Test
    fun `the shared card's paper is a still of the blur, dimmed until its words read on it as they do on the blue`() {
        val colours = YearBlur.readable(intArrayOf(magenta, green, argb(255, 220, 60))).colours
        val still = YearBlur.still(colours)
        assertEquals(YearBlur.STILL_WIDE * YearBlur.STILL_TALL, still.size)
        val lightest = still.maxOf { Color(it).luminance() }
        assertTrue("$lightest", lightest <= RecapInk.Blue.luminance() + 0.002f)
        // The same picture every time, and a picture rather than one flat colour.
        assertArrayEquals(still, YearBlur.still(colours))
        assertTrue(still.distinct().size > 50)
        // The card shared is drawn on it; the one on the slide keeps its blue.
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("year != null -> drawYearCard(context, shown, words, covers, year)" in screen)
        assertTrue("yearCard = runCatching { drawYearCard(context, shown, words, covers) }.getOrNull()" in screen)
        val cards = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapCards.kt").readText()
        assertTrue("if (still == null) paper(canvas, RecapInk.Blue) else {" in cards)
    }

    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    private fun slides(): List<Slide> {
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
        return planSlides(RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", "cover:$it", 200) }, emptyMap()))
    }

    @Test
    fun `the year card's page wears the blur once the top three covers are read, and no other page does`() {
        val slides = slides()
        val card = slides.indexOfFirst { it.kind == SlideKind.CARD } + 1
        val mains = mapOf("cover:one" to intArrayOf(magenta, green, white), "cover:two" to intArrayOf(grey, blue, white), "cover:three" to intArrayOf(red, grey, white))
        var read = false
        val st = RecapStage(
            slides, still = false, startAt = 0, startStep = 0,
            colours = { model -> if (read) (model as? String)?.let { mains[it] } else null },
            yearCovers = listOf("cover:one", "cover:two", "cover:three"),
        ).apply { resize(1080f, 2340f) }
        assertNull(st.year)
        st.go(card, 0, How.INSTANT)
        assertEquals(paperOf(SlideKind.CARD).paper, st.current.paper.paper)
        read = true
        st.coversRead()
        val year = st.year!!
        assertArrayEquals(YearBlur.readable(intArrayOf(magenta, blue, red)).colours, year.colours)
        st.go(card - 1, 0, How.INSTANT)
        st.go(card, 0, How.ON)
        assertEquals(year.paper.paper, st.current.paper.paper)
        assertEquals(year.paper.ink, st.current.paper.ink)
        // The page after it, whichever it is (the note to next year's you since 22 Nov 2026, then Thank you), its own paper.
        st.go(card + 1, 0, How.ON)
        assertEquals(paperOf(slides[card].kind).paper, st.current.paper.paper)
    }

    @Test
    fun `with Remove animations on the year's blur stands still, it moves only while the story plays, and it lies under the shapes`() {
        val code = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapYearBlur.kt").readText()
        assertTrue("if (stage.still || !moving) {" in code)
        assertTrue("if (stage.still) motion.settle()" in code)
        // Drawn as the player's Living blur is: its discs, small and stretched, redrawn thirty times a second at most.
        assertTrue("discs.field(canvas.nativeCanvas, motion, size.width, size.height)" in code)
        assertTrue(".drawnSmall(stretch, 1f)" in code)
        assertTrue("motion.frame(now, pace, playing = true)" in code)
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapScreen.kt").readText()
        assertTrue("YearBlurPaper(stage, moving = !paused)" in screen)
        // Between the papers and the shapes: the card, its buttons, the shapes and the dots stay over it.
        assertTrue(screen.indexOf("Papers(stage)") < screen.indexOf("YearBlurPaper(stage") && screen.indexOf("YearBlurPaper(stage") < screen.indexOf("Shapes(stage)"))
    }
}
