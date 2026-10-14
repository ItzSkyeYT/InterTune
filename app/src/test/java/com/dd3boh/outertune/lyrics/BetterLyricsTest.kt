/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Better Lyrics: what is asked of it, what is taken from its answer, and that the lookup knows TTML. */
class BetterLyricsTest {

    // Invented words, nobody's song.
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.000" end="00:03.000">""" +
        """<span begin="00:01.000" end="00:01.800">Paper</span> <span begin="00:01.800" end="00:03.000">boats</span></p></div></body></tt>"""

    @Test
    fun `it is asked by title, artist, length and album`() {
        val url = BetterLyrics.address("Paper Boats", "The Puddles", 201, "Rain")
        assertEquals("lyrics-api.boidu.dev", url.host)
        assertEquals("/getLyrics", url.encodedPath)
        assertEquals("Paper Boats", url.queryParameter("s"))
        assertEquals("The Puddles", url.queryParameter("a"))
        assertEquals("201", url.queryParameter("d"))
        assertEquals("Rain", url.queryParameter("al"))
    }

    @Test
    fun `an unknown length or album is left out`() {
        val url = BetterLyrics.address("Paper Boats", "The Puddles", -1, " ")
        assertNull(url.queryParameter("d"))
        assertNull(url.queryParameter("al"))
    }

    @Test
    fun `the TTML is taken from the answer, and nothing from an empty or odd one`() {
        assertEquals(ttml, BetterLyrics.ttmlOf("""{"ttml": ${"\""}${ttml.replace("\"", "\\\"")}${"\""}}"""))
        assertNull(BetterLyrics.ttmlOf("""{"ttml": "   "}"""))
        assertNull(BetterLyrics.ttmlOf("""{"error": "not found"}"""))
        assertNull(BetterLyrics.ttmlOf("<html>busy</html>"))
    }

    @Test
    fun `TTML counts as timed, and LRC and plain words are not taken for it`() {
        assertTrue(LyricsMatch.isTtml(ttml))
        assertTrue(LyricsMatch.isTtml("<?xml version=\"1.0\"?>\n" + ttml))
        assertTrue(LyricsMatch.isSynced(ttml))
        assertFalse(LyricsMatch.isTtml("[00:01.00]Paper boats"))
        assertFalse(LyricsMatch.isTtml("Paper boats along the gutter"))
        assertFalse("a TTML file with no timings is plain words", LyricsMatch.isSynced("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p>Paper boats</p></body></tt>"))
    }
}
