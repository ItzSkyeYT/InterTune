/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.lyrics.LyricsShare.Pick
import com.dd3boh.outertune.lyrics.LyricsVideoPlan.Timed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Lyrics as a video: the stretch of song it plays, the lines in it, and how they scroll, light up
 * and fade. Invented lines, as everywhere in the tests.
 */
class LyricsVideoPlanTest {

    /** A verse with a pause in it, a translated line, and a last line with no end of its own. */
    private val song = listOf(
        Timed("First line", 10_000, 13_999),
        Timed("Second line", 14_000, 17_999),
        Timed("", 18_000, 21_999),
        Timed("Third line", 22_000, 25_999),
        Timed("Troisième ligne", 22_000, 25_999, translated = true),
        Timed("Fourth line", 26_000, 29_999),
        Timed("Last line", 30_000, Long.MAX_VALUE),
    )

    @Test
    fun `a video plays every line from the first picked to the last, from just before the first`() {
        val clip = LyricsVideoPlan.clip(song, listOf(3, 0))!!
        assertEquals(10_000 - LyricsVideoPlan.LEAD_MS, clip.startMs)
        assertEquals("the pause is left out and the translation goes with its line", listOf("First line", "Second line", "Third line"), clip.lines.map { it.text })
        assertEquals("Troisième ligne", clip.lines[2].translation)
        assertEquals("times from the start of the video", listOf(600L, 4_600L, 12_600L), clip.lines.map { it.startMs })
        assertEquals("to the end of the last line, and a quick fade after", 25_999 + LyricsVideoPlan.FADE_OUT_MS, clip.endMs)
    }

    @Test
    fun `a translation picked brings its line, and a line picked brings its translation`() {
        assertEquals(listOf("Third line"), LyricsVideoPlan.clip(song, listOf(4))?.lines?.map { it.text })
        assertEquals("Troisième ligne", LyricsVideoPlan.clip(song, listOf(3))?.lines?.single()?.translation)
    }

    @Test
    fun `a last line with no end is held a few seconds`() {
        val clip = LyricsVideoPlan.clip(song, listOf(6))!!
        assertEquals(30_000 + LyricsVideoPlan.LAST_LINE_MS + LyricsVideoPlan.FADE_OUT_MS, clip.endMs)
        assertEquals("and the song's end comes first", 33_000L, LyricsVideoPlan.clip(song, listOf(6), songMs = 33_000)?.endMs)
    }

    @Test
    fun `a video starts at the song's start at the earliest`() {
        val early = listOf(Timed("Straight in", 200, 3_000))
        assertEquals(0L, LyricsVideoPlan.clip(early, listOf(0))?.startMs)
        assertEquals(200L, LyricsVideoPlan.clip(early, listOf(0))?.lines?.single()?.startMs)
    }

    @Test
    fun `a video takes a minute of the song at most`() {
        val long = List(30) { Timed("Line $it", 5_000L + it * 2_500L, 5_000L + it * 2_500L + 2_499) }
        assertTrue(LyricsVideoPlan.fits(long, listOf(0, 21)))
        val clip = LyricsVideoPlan.clip(long, (0..21).toList())!!
        assertTrue(clip.durationMs <= LyricsVideoPlan.LONGEST_MS)
        assertEquals("more than five lines", 22, clip.lines.size)
        assertFalse(LyricsVideoPlan.fits(long, listOf(0, 29)))
        assertNull(LyricsVideoPlan.clip(long, listOf(0, 29)))
    }

    @Test
    fun `nothing sung is no video`() {
        assertNull(LyricsVideoPlan.clip(song, listOf(2)))
        assertNull(LyricsVideoPlan.clip(song, emptyList()))
        assertNull(LyricsVideoPlan.clip(song, listOf(40)))
    }

    @Test
    fun `picking takes more than five lines where the rule says so`() {
        val lines = List(12) { "Line $it" }
        val many = (0 until 8).toList()
        assertEquals(Pick.Full, LyricsShare.toggle(many.take(5), 5, lines))
        assertEquals(Pick.Picked(many + 8), LyricsShare.toggle(many, 8, lines, fits = { it.size <= 10 }))
        assertEquals(Pick.Full, LyricsShare.toggle((0 until 10).toList(), 10, lines, fits = { it.size <= 10 }))
        assertEquals("taken back whatever the rule", Pick.Picked(many - 3), LyricsShare.toggle(many, 3, lines, fits = { false }))
    }

    @Test
    fun `the video's file is named after the song`() {
        assertEquals("A Song.mp4", LyricsShare.fileName("A Song", "mp4"))
        assertEquals("A Song.png", LyricsShare.fileName("A Song"))
    }

    // How the lines move

    private val clip = LyricsVideoPlan.clip(song, (0..6).toList())!!
    private val tops = floatArrayOf(0f, 100f, 200f, 360f, 460f)

    @Test
    fun `the first line stands in its place until it is sung, and each line is brought there in turn`() {
        assertEquals(0f, LyricsVideoPlan.scroll(clip, tops, 0), 0f)
        assertEquals(0f, LyricsVideoPlan.scroll(clip, tops, clip.lines[1].startMs - 1), 0f)
        val second = clip.lines[1].startMs
        assertEquals("on its way", 50f, LyricsVideoPlan.scroll(clip, tops, second + LyricsVideoPlan.SCROLL_MS / 2), 0.5f)
        assertEquals("and there", 100f, LyricsVideoPlan.scroll(clip, tops, second + LyricsVideoPlan.SCROLL_MS), 0f)
        assertEquals(460f, LyricsVideoPlan.scroll(clip, tops, clip.durationMs), 0f)
    }

    @Test
    fun `lines that come quicker than the scroll never make the column jump`() {
        val rap = LyricsVideoPlan.clip(List(12) { Timed("Quick $it", 1_000L + it * 200L, 1_000L + it * 200L + 199) }, (0..11).toList())!!
        val tops = FloatArray(12) { it * 100f }
        var before = LyricsVideoPlan.scroll(rap, tops, 0)
        var most = 0f
        for (ms in 1..rap.durationMs) {
            val now = LyricsVideoPlan.scroll(rap, tops, ms)
            most = maxOf(most, abs(now - before))
            assertTrue("never back", now >= before)
            before = now
        }
        assertTrue("at most $most px in a millisecond", most < 2f)
        assertEquals("and it gets to the last line", 1100f, before, 0.5f)
    }

    @Test
    fun `the line being sung is lit, and only that one`() {
        val second = clip.lines[1].startMs
        assertEquals(LyricsVideoPlan.DIM, LyricsVideoPlan.light(clip, 1, second - 1), 0f)
        assertEquals(1f, LyricsVideoPlan.light(clip, 1, second + LyricsVideoPlan.LIGHT_MS), 0f)
        assertEquals("the one before goes down as it comes up", LyricsVideoPlan.DIM, LyricsVideoPlan.light(clip, 0, second + LyricsVideoPlan.LIGHT_MS), 0f)
        assertEquals("the last stays lit to the end", 1f, LyricsVideoPlan.light(clip, clip.lines.lastIndex, clip.durationMs), 0f)
        assertEquals("and the first is lit from the first frame, before it is sung", 1f, LyricsVideoPlan.light(clip, 0, 0), 0f)
    }

    @Test
    fun `the sound comes up at once and both fade out quickly at the end, the picture from its first frame`() {
        val length = clip.durationMs
        assertEquals(0f, LyricsVideoPlan.sound(0.0, length), 0f)
        assertEquals(1f, LyricsVideoPlan.sound(LyricsVideoPlan.FADE_IN_MS.toDouble(), length), 0f)
        assertEquals(1f, LyricsVideoPlan.picture(0, length), 0f)
        assertEquals(1f, LyricsVideoPlan.sound((length - LyricsVideoPlan.FADE_OUT_MS).toDouble(), length), 0f)
        assertEquals(0.5f, LyricsVideoPlan.picture(length - LyricsVideoPlan.FADE_OUT_MS / 2, length), 0.01f)
        assertEquals(0f, LyricsVideoPlan.sound(length.toDouble(), length), 0f)
        assertEquals(0f, LyricsVideoPlan.picture(length, length), 0f)
    }

    @Test
    fun `a video is small enough to send, a minute under 10 MB`() {
        assertTrue(LyricsVideoPlan.bytes(LyricsVideoPlan.LONGEST_MS) < 10_000_000)
        assertTrue("half a minute under 5 MB", LyricsVideoPlan.bytes(30_000) < 5_000_000)
        assertEquals("the card's shape, smaller", 1080f / 1920f, LyricsVideoPlan.OUT_WIDTH.toFloat() / LyricsVideoPlan.OUT_HEIGHT, 0.0001f)
    }

    @Test
    fun `thirty frames a second`() {
        assertEquals(30, LyricsVideoPlan.frames(1_000))
        assertEquals(31, LyricsVideoPlan.frames(1_001))
        assertEquals(1_800, LyricsVideoPlan.frames(LyricsVideoPlan.LONGEST_MS))
    }
}
