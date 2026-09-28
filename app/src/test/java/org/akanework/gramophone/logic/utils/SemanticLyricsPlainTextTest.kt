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
 * flattened multi-verse lyrics into one run with no gap. The join that turns a line back into
 * text (see Lyrics.kt) already had its own bug, joining with ", " instead of "\n"; that one is
 * fixed separately, but this suite also checks the parser's own output joins back into plain text
 * cleanly, so the two fixes are not accidentally masking one another.
 */
class SemanticLyricsPlainTextTest {

    /** What Lyrics.kt does with [SemanticLyrics.unsyncedText] to build the line it displays. */
    private fun UnsyncedLyrics.rendered() = unsyncedText.joinToString("\n") { it.first }

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
            (lyrics as UnsyncedLyrics).rendered()
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
        assertEquals("Line one\nLine two\nLine three", (lyrics as UnsyncedLyrics).rendered())
    }

    @Test
    fun `a leading blank line is dropped rather than shown before the first line`() {
        val lyrics = parseLrc(
            "\nLine one\nLine two",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is UnsyncedLyrics)
        assertEquals("Line one\nLine two", (lyrics as UnsyncedLyrics).rendered())
    }

    @Test
    fun `no line after the first starts with a comma`() {
        val lyrics = parseLrc(
            "Line one\nLine two\n\nLine three",
            trimEnabled = false,
            multiLineEnabled = false
        ) as UnsyncedLyrics

        lyrics.rendered().split("\n").drop(1).forEach { line ->
            assertTrue("line started with a comma: $line", !line.startsWith(", "))
        }
    }
}
