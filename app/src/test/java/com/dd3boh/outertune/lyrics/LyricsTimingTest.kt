/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Synced lyrics moved earlier or later per song: what is kept, and that the lyrics view follows the
 * move everywhere it reads the song's position against the lines.
 */
class LyricsTimingTest {

    @Test
    fun `a song never moved is where its lyrics say`() {
        assertEquals(0L, LyricsTiming.shiftOf("", "dQw4w9WgXcQ"))
        assertEquals(0L, LyricsTiming.shiftOf("abc=300", "dQw4w9WgXcQ"))
        assertEquals(0L, LyricsTiming.shiftOf("abc=300", null))
    }

    @Test
    fun `each song keeps its own move, and back to 0 forgets it`() {
        var raw = LyricsTiming.withShift("", "dQw4w9WgXcQ", -1_200L)
        raw = LyricsTiming.withShift(raw, "LSabcdefgh", 400L)
        assertEquals(-1_200L, LyricsTiming.shiftOf(raw, "dQw4w9WgXcQ"))
        assertEquals(400L, LyricsTiming.shiftOf(raw, "LSabcdefgh"))

        raw = LyricsTiming.withShift(raw, "dQw4w9WgXcQ", 0L)
        assertEquals(0L, LyricsTiming.shiftOf(raw, "dQw4w9WgXcQ"))
        assertEquals(400L, LyricsTiming.shiftOf(raw, "LSabcdefgh"))
        assertFalse("a song back at 0 takes no room", raw.contains("dQw4w9WgXcQ"))
        assertEquals("", LyricsTiming.withShift(raw, "LSabcdefgh", 0L))
    }

    @Test
    fun `a move is kept to the slider's steps and range`() {
        assertEquals(300L, LyricsTiming.snap(260L))
        assertEquals(-300L, LyricsTiming.snap(-340L))
        assertEquals(LyricsTiming.MOST_MS, LyricsTiming.snap(9_000L))
        assertEquals(-LyricsTiming.MOST_MS, LyricsTiming.snap(-9_000L))
        assertEquals(LyricsTiming.MOST_MS, LyricsTiming.shiftOf(LyricsTiming.withShift("", "a", 12_345L), "a"))
    }

    @Test
    fun `a damaged setting loses only what cannot be read`() {
        val raw = "a=100,,=5,b=,c=x,d=-200,broken"
        assertEquals(100L, LyricsTiming.shiftOf(raw, "a"))
        assertEquals(-200L, LyricsTiming.shiftOf(raw, "d"))
        assertEquals(0L, LyricsTiming.shiftOf(raw, "b"))
        assertEquals(0L, LyricsTiming.shiftOf(raw, "c"))
        assertEquals("a=100,d=-200,e=500", LyricsTiming.withShift(raw, "e", 500L))
    }

    @Test
    fun `lyrics moved later wait, lyrics moved earlier come sooner, and a tap seeks to the moved line`() {
        // Lyrics running 1.5 s behind the singing are moved 1.5 s earlier: at 10 s into the song
        // the view stands at 11.5 s of their timestamps, and a line stamped 11.5 s is sung at 10 s.
        assertEquals(11_500L, LyricsTiming.lyricsPosition(10_000L, -1_500L))
        assertEquals(10_000L, LyricsTiming.songPosition(11_500L, -1_500L))
        // Moved later, the lyrics stand at their start for the first moments, never before it:
        // the view reads positions as unsigned, and one below 0 took every line for sung.
        assertEquals(0L, LyricsTiming.lyricsPosition(300L, 800L))
        assertEquals(0L, LyricsTiming.songPosition(400L, -800L))
        assertEquals(1_200L, LyricsTiming.songPosition(400L, 800L))
    }

    @Test
    fun `the bar shows tenths of a second in the reader's own way`() {
        assertEquals("0.4", LyricsTiming.seconds(-400L, Locale.US))
        assertEquals("1,5", LyricsTiming.seconds(1_500L, Locale.FRANCE))
        assertEquals("5.0", LyricsTiming.seconds(5_000L, Locale.US))
    }

    @Test
    fun `the lyrics view follows the move wherever it holds the song against the lines`() {
        val view = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        // The loop is keyed on the lyrics alone, so it reads the move through an updated state.
        assertTrue(view.contains("val shiftNow by rememberUpdatedState(shiftMs)"))
        assertTrue("the highlight", view.contains("LyricsTiming.lyricsPosition(sliderPosition ?: playerConnection.player.currentPosition, shiftNow)"))
        assertTrue("a tap on a line", view.contains("seekTo(LyricsTiming.songPosition(item.start.toLong(), shiftNow))"))
        assertTrue("the video of picked lines", view.contains("LyricsTiming.songPosition(it.start.toLong(), shiftNow)"))
        assertFalse("no read of the position against the lines left unmoved",
            Regex("""findCurrentLineIndex\(lines, (sliderPosition|playerConnection)""").containsMatchIn(view))
        assertFalse(view.contains("seekTo(item.start.toLong())"))
        // A move shows at once while paused, where the loop waits for the music.
        assertTrue(view.contains("LaunchedEffect(shiftMs)"))
    }

    @Test
    fun `the setting is changed in one edit, and the menu offers the timing only for synced lyrics`() {
        val view = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue(view.contains("dataStore.edit { it[LyricsTimingKey] = LyricsTiming.withShift(it[LyricsTimingKey].orEmpty(), song, shiftMs) }"))
        assertTrue(view.contains("onAdjustTiming = if (isSynced) ({ startTiming(mediaMetadata.id) }) else null"))
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/LyricsMenu.kt").readText()
        assertTrue(menu.contains("if (onAdjustTiming != null) GridMenuItem("))
        assertTrue("other lyrics picked in Search start as they come", menu.contains("onOtherLyrics(searchMediaMetadata.id)"))
    }
}
