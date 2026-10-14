/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package org.akanework.gramophone.logic.utils

import org.akanework.gramophone.logic.utils.SemanticLyrics.UnsyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics with no timestamps at all fall through to the "recover only text information" branch of
 * [parseLrc]. It used to drop every blank line along with the rest of the structure, which
 * flattened multi-verse lyrics into one run with no gap. Lyrics.kt shows the parsed lines one under
 * another, so a blank one is the gap between verses on screen; this suite reads them joined back, one
 * to a line, with [SemanticLyrics.joinedUnsyncedText], so a regression in the parser shows up here.
 *
 * multiLineEnabled is exercised at both its values: false here throughout, and true (the app's own
 * default, see LyricsHelper.kt) on the main gap case, since that flag runs an extra merge pass over
 * the tokens before this code ever sees them.
 */
class SemanticLyricsPlainTextTest {

    @Test
    fun `a blank line between verses survives as a gap`() {
        val lyrics = parseLrc(
            "Line one\nLine two\n\nLine three\nLine four",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals(
            "Line one\nLine two\n\nLine three\nLine four",
            (lyrics as UnsyncedLyrics).joinedUnsyncedText()
        )
    }

    @Test
    fun `the gap still survives with multiline lrc merging on`() {
        val lyrics = parseLrc(
            "Line one\nLine two\n\nLine three\nLine four",
            trimEnabled = false,
            multiLineEnabled = true
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals(
            "Line one\nLine two\n\nLine three\nLine four",
            (lyrics as UnsyncedLyrics).joinedUnsyncedText()
        )
    }

    @Test
    fun `a CRLF blank line between verses also survives as a gap`() {
        val lyrics = parseLrc(
            "Line one\r\nLine two\r\n\r\nLine three\r\nLine four",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals(
            "Line one\nLine two\n\nLine three\nLine four",
            (lyrics as UnsyncedLyrics).joinedUnsyncedText()
        )
    }

    @Test
    fun `no blank source lines means no gap is invented`() {
        val lyrics = parseLrc(
            "Line one\nLine two\nLine three",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals("Line one\nLine two\nLine three", (lyrics as UnsyncedLyrics).joinedUnsyncedText())
    }

    @Test
    fun `a leading blank line is dropped rather than shown before the first line`() {
        // Two leading newlines, a genuine blank line before the first one, not one: with only one
        // newline there is nothing before it to pair up with, so the marker this fix adds is never
        // created in the first place and the case says nothing about the fix.
        val lyrics = parseLrc(
            "\n\nLine one\nLine two",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals("Line one\nLine two", (lyrics as UnsyncedLyrics).joinedUnsyncedText())
    }

    @Test
    fun `no line after the first starts with a comma`() {
        val lyrics = parseLrc(
            "Line one\nLine two\n\nLine three",
            trimEnabled = false,
            multiLineEnabled = false
        ) as UnsyncedLyrics

        lyrics.joinedUnsyncedText().split("\n").drop(1).forEach { line ->
            assertTrue("line started with a comma: $line", !line.startsWith(", "))
        }
    }
}
