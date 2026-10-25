/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.recap.DayAmount
import com.dd3boh.outertune.recap.FirstSong
import com.dd3boh.outertune.recap.Recap
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.SongAmount
import com.dd3boh.outertune.recap.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Any phone: a slide or the start screen on a phone shorter than the one they are laid out for is
 * drawn smaller to fit, as the design is zoomed on a small phone, but only upright, never below a
 * floor, and never with large text made smaller than usual; and the hour's sun follows its dial
 * whatever size the dial is drawn. Made-up songs only.
 */
class RecapFitTest {
    @Test
    fun `a phone with room enough draws everything at its own size`() {
        assertEquals(1f, RecapFit.zoom(412.dp, 760.dp, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(448.dp, RecapFit.ROOM, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(393.dp, 851.dp, RecapFit.START, 1.3f))
    }

    @Test
    fun `a small phone held upright draws a slide and the start screen smaller to fit`() {
        // 360 x 640 dp: about 440 dp between the bars and the song, the whole 640 for the start screen.
        assertEquals(440f / 600f, RecapFit.zoom(360.dp, 440.dp, RecapFit.ROOM, 1f), 1e-4f)
        assertEquals(640f / 736f, RecapFit.zoom(360.dp, 640.dp, RecapFit.START, 1f), 1e-4f)
    }

    @Test
    fun `on its side nothing is made smaller, it scrolls`() {
        assertEquals(1f, RecapFit.zoom(997.dp, 300.dp, RecapFit.ROOM, 1f))
        assertEquals(1f, RecapFit.zoom(997.dp, 448.dp, RecapFit.START, 1.3f))
    }

    @Test
    fun `never below the floor, and large text never below its usual size`() {
        assertEquals(RecapFit.LEAST, RecapFit.zoom(320.dp, 330.dp, RecapFit.ROOM, 1f), 1e-4f)
        assertEquals(1f / 1.3f, RecapFit.zoom(360.dp, 440.dp, RecapFit.ROOM, 1.3f), 1e-4f)
        for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
            val z = RecapFit.zoom(360.dp, 400.dp, RecapFit.ROOM, scale)
            assertTrue("text at $scale drawn at ${scale * z}", scale * z >= 1f - 1e-4f || z == RecapFit.LEAST)
        }
    }

    private fun top(id: String, plays: Int) = TopSong(id, plays, plays * 180_000L, 100, 0, 3, 120, 2, 2, 120, mapOf(7 to plays))

    @Test
    fun `the hour's sun and its ring are sized from the dial they sit in`() {
        val r = Recap(
            year = 2026, firstYear = true, sinceDay = 100, lastDay = 223,
            firstSong = FirstSong("first", 100, 691), playedMs = 1L, plays = 900, songs = 10, artists = 5,
            msByDay = List(124) { 1L }, biggestDay = DayAmount(120, 1L), mostTime = SongAmount("long", 1L, 37),
            msByHour = List(24) { 1L }, peakHour = 18, peakSong = SongAmount("evening", 1L, 9), lateNights = null,
            streak = null, topSongs = listOf(top("one", 90), top("two", 87)), alsoPlayed = emptyList(), topArtists = emptyList(),
            newArtists = null, auto = null, recognition = null, rooms = null,
        )
        val ids = listOf("first", "long", "evening") + r.topSongs.map { it.songId }
        val shown = RecapShown(r, ids.associateWith { RecapSongShown(it, it.uppercase(), "Someone", null, 200) }, emptyMap())
        val slides = planSlides(shown)
        val stage = RecapStage(slides, still = false, startAt = 1, startStep = 0).apply { resize(1080f, 2340f) }
        val hour = slides.indexOfFirst { it.kind == SlideKind.HOUR } + 1
        stage.go(hour, 0, How.ON)
        // A dial drawn 500 px across, smaller than the design's 320 would be on this screen.
        stage.anchors.put(stage.current.visit, Spot.DIAL, Rect(290f, 900f, 790f, 1400f))
        repeat(3) { stage.frame(16.0, paused = false) }
        assertEquals(500f * 128f / 320f, stage.actors[ActorId.D.ordinal].s.target, 0.5f)
        assertEquals(500f * 300f / 320f, stage.actors[ActorId.R.ordinal].s.target, 0.5f)
        assertEquals(540f, stage.actors[ActorId.D.ordinal].x.target, 0.5f)
        assertEquals(1150f, stage.actors[ActorId.D.ordinal].y.target, 0.5f)
        // A piece not sitting on a part: the design's band of bars on the bars, the rest under them.
        stage.top = 120f
        stage.chrome = 170f
        val minutes = slides.indexOfFirst { it.kind == SlideKind.MINUTES } + 1
        stage.go(minutes, 0, How.ON)
        repeat(6) { stage.frame(16.0, paused = false) }
        assertEquals(120f + 170f + (248f - 48f) / (736f - 48f) * (2340f - 120f - 170f), stage.actors[ActorId.B.ordinal].y.target, 0.5f)
        assertEquals(380f / 414f * 1080f, stage.actors[ActorId.B.ordinal].x.target, 0.5f)
        assertEquals(120f + 85f, stage.pieceY(24f), 0.5f)
        // On a short screen, a piece the design puts under its bars stays under the bars here.
        stage.resize(2340f, 1080f)
        for (y in listOf(74f, 88f, 90f, 98f)) assertTrue("$y", stage.pieceY(y) > 120f + 170f)
        // The start screen's pieces go by the whole height, as its words do.
        stage.go(0, 0, How.INSTANT)
        assertEquals(116f / 736f * 1080f, stage.pieceY(116f), 0.5f)
    }
}
