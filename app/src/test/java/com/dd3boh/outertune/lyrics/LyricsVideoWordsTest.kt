/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.lyrics.LyricsVideoPlan.DIM
import com.dd3boh.outertune.lyrics.LyricsVideoPlan.Timed
import com.dd3boh.outertune.lyrics.LyricsVideoPlan.UNSUNG
import com.dd3boh.outertune.lyrics.LyricsVideoPlan.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A lyrics video lit word by word (his idea, 4 Nov 2026): the words go into the video with their
 * places in the line and their times, and each is lit across as it is sung. Invented lines.
 */
class LyricsVideoWordsTest {

    /** "Hold the line" sung at 10.0, 10.4 and 10.8 s, with a space before it the video trims. */
    private val sung = Timed(
        " Hold the line", 10_000, 11_999,
        words = listOf(Word(1, 5, 10_000, 10_399), Word(6, 9, 10_400, 10_799), Word(10, 14, 10_800, 11_999)),
    )
    private val next = Timed("Love isn't always on time", 12_000, 14_999)
    private val clip = LyricsVideoPlan.clip(listOf(sung, next), listOf(0, 1))!!

    @Test
    fun `the words go into the video at their places in the trimmed line, timed from its start`() {
        val words = clip.lines[0].words
        assertEquals("Hold the line", clip.lines[0].text)
        assertEquals(listOf("Hold", "the", "line"), words.map { clip.lines[0].text.substring(it.from, it.to) })
        assertEquals("times from the video's start", listOf(600L, 1_000L, 1_400L), words.map { it.startMs })
        assertTrue("a line without word times has none", clip.lines[1].words.isEmpty())
    }

    @Test
    fun `a word is lit across as it is sung, and one timed as a moment still sweeps`() {
        val hold = clip.lines[0].words[0]
        assertEquals(0f, LyricsVideoPlan.wordLit(hold, 599), 0f)
        assertEquals(0.5f, LyricsVideoPlan.wordLit(hold, 600 + 200), 0.01f)
        assertEquals(1f, LyricsVideoPlan.wordLit(hold, 1_000), 0f)
        val blink = Word(0, 1, 1_000, 1_000)
        assertEquals("half way through the shortest sweep", 0.5f, LyricsVideoPlan.wordLit(blink, 1_000 + LyricsVideoPlan.SHORTEST_WORD_MS / 2), 0.01f)
    }

    @Test
    fun `the line being sung shows its words to come brighter than the other lines, and its sung ones full`() {
        // The first line is up from the first frame, as a whole line is: what a chat shows of the video.
        assertEquals(UNSUNG, LyricsVideoPlan.unsung(clip, 0, 0), 0.0001f)
        assertEquals(1f, LyricsVideoPlan.lit(clip, 0, 1_000), 0.0001f)
        assertEquals("the next line is not sung yet", DIM, LyricsVideoPlan.unsung(clip, 1, 1_000), 0f)
        // Once the next line starts, the whole of the first goes back down to the others' level.
        val nextStart = clip.lines[1].startMs
        assertEquals(DIM, LyricsVideoPlan.unsung(clip, 0, nextStart + LyricsVideoPlan.LIGHT_MS), 0f)
        assertEquals(DIM, LyricsVideoPlan.lit(clip, 0, nextStart + LyricsVideoPlan.LIGHT_MS), 0f)
        assertEquals("and the next comes up to the unsung level", UNSUNG, LyricsVideoPlan.unsung(clip, 1, nextStart + LyricsVideoPlan.LIGHT_MS), 0.0001f)
        assertTrue("the words to come stand out from the other lines and from the sung ones", DIM < UNSUNG && UNSUNG < 1f)
    }

    @Test
    fun `a word with no letters left after the trim is left out`() {
        val spaced = Timed("  Go", 0, 999, words = listOf(Word(0, 2, 0, 400), Word(2, 4, 400, 999)))
        val words = LyricsVideoPlan.clip(listOf(spaced), listOf(0))!!.lines[0].words
        assertEquals(1, words.size)
        assertEquals(0 until 2, words[0].from until words[0].to)
    }

    @Test
    fun `the lyrics view gives the video word times only with word by word on and the flag`() {
        val view = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue("words = if (!Unreleased.LYRICS_VIDEO_WORDS || !lyricsFancy) emptyList()" in view)
        assertTrue("an inclusive range made exclusive", "word.charRange.first, word.charRange.last + 1" in view)
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        assertTrue("held back to debug and preview builds", "val LYRICS_VIDEO_WORDS = BuildConfig.PREVIEW_FEATURES" in flags)
    }
}
