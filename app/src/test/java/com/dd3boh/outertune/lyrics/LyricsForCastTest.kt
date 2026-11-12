/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.cast.CastLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What of a song's lyrics goes to the TV (LyricsForCast). */
class LyricsForCastTest {
    private fun line(startMs: Long, text: String, translated: Boolean = false) =
        SemanticLyrics.LyricLine(text, startMs.toULong(), (startMs + 1_000).toULong(), null, null, translated)

    @Test
    fun `synced lines go with their starts, trimmed, the song's shift kept`() {
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(line(1_000, " First line "), line(4_500, "Second line")))
        assertEquals(
            CastLyrics("vid", listOf(CastLyrics.Line(1_000, "First line"), CastLyrics.Line(4_500, "Second line")), shiftMs = -250),
            LyricsForCast.of("vid", lyrics, shiftMs = -250),
        )
    }

    @Test
    fun `translations stay on the phone, and a pause in the singing stays a blank line`() {
        val lyrics = SemanticLyrics.SyncedLyrics(listOf(line(0, "Hola"), line(0, "Hello", translated = true), line(3_000, ""), line(6_000, "Adiós")))
        assertEquals(listOf("Hola", "", "Adiós"), LyricsForCast.of("vid", lyrics, 0)!!.lines.map { it.text })
    }

    @Test
    fun `nothing to show without synced words`() {
        assertNull(LyricsForCast.of("vid", null, 0))
        assertNull(LyricsForCast.of("vid", SemanticLyrics.UnsyncedLyrics(listOf("No times" to null)), 0))
        assertNull(LyricsForCast.of("vid", SemanticLyrics.SyncedLyrics(listOf(line(0, " "), line(2_000, ""))), 0))
    }
}
