/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseTtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * #165 (13 Nov 2026): word by word lyrics made with BetterLyrics' Unison, as TTML, lit the wrong part of
 * the line while the song was paused: a word not yet sung half lit, a word already sung not lit to its
 * end. The app's own way lights a share of the line's width counted in words, and hands the words to
 * the wrapped lines by counting spaces, so a word timed as two syllables ("bro" + "ken") shifts the
 * rest and drops the line's last word. The looks of LyricsWords light each word where it stands, from
 * its own letters over its own time. This is the reporter's line with its exact times, in words of no
 * song (the lyrics themselves are not ours to copy), the split word kept.
 */
class LyricsWordsTtmlTest {

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" ttp:timeBase="media" xml:lang="en" itunes:timing="Word">
          <head><metadata><ttm:agent xml:id="v1" type="person"/></metadata></head>
          <body dur="1:00.000"><div>
            <p begin="0:39.079" end="0:46.333" itunes:key="L1" ttm:agent="v1"><span begin="0:39.079" end="0:39.169">one</span> <span begin="0:39.255" end="0:39.440">two</span> <span begin="0:39.550" end="0:39.755">three,</span> <span begin="0:39.925" end="0:40.149">"four</span> <span begin="0:40.347" end="0:40.765">five</span> <span begin="0:41.038" end="0:41.526">six,</span> <span begin="0:41.764" end="0:42.996">seven</span> <span begin="0:43.299" end="0:43.529">eig</span><span begin="0:43.977" end="0:44.246">ht</span> <span begin="0:44.681" end="0:44.962">by</span> <span begin="0:45.388" end="0:45.685">ten</span> <span begin="0:46.070" end="0:46.333">end"</span></p>
          </div></body>
        </tt>
    """.trimIndent()

    private fun line(): SemanticLyrics.LyricLine {
        val parsed = parseTtml(null, ttml, KXmlParser())
        assertNotNull("the TTML is read", parsed)
        return (parsed as SemanticLyrics.SyncedLyrics).text.single { it.text.isNotBlank() }
    }

    private fun pieceOf(line: SemanticLyrics.LyricLine, pieces: List<LyricsWords.Piece>, word: String): LyricsWords.Piece =
        pieces.single { line.text.substring(it.from, it.to) == word }

    @Test
    fun `every timed word of the line is a piece, the split one as two and the last one too`() {
        val line = line()
        val pieces = LyricsWords.pieces(line.text, line.words)
        assertEquals("one two three, \"four five six, seven eight by ten end\"", line.text)
        assertEquals(12, pieces.size)
        assertEquals(listOf("eig", "ht"), pieces.map { line.text.substring(it.from, it.to) }.filter { it == "eig" || it == "ht" })
        assertEquals("end\"", line.text.substring(pieces.last().from, pieces.last().to))
    }

    @Test
    fun `paused between two words, the one sung is lit to its end and the next not at all`() {
        val line = line()
        val pieces = LyricsWords.pieces(line.text, line.words)
        // The reporter's first picture: after "ken" (here "ht"), before "by": "by" was most of the way lit.
        val at = 44_400L
        assertEquals(1f, LyricsWords.sung(pieceOf(line, pieces, "eig"), at))
        assertEquals(1f, LyricsWords.sung(pieceOf(line, pieces, "ht"), at))
        assertEquals(0f, LyricsWords.sung(pieceOf(line, pieces, "by"), at))
        // The third: after "Don't" (here "four), before the next word: "Don't" was not lit to its end.
        val later = 40_200L
        assertEquals(1f, LyricsWords.sung(pieceOf(line, pieces, "\"four"), later))
        assertEquals(0f, LyricsWords.sung(pieceOf(line, pieces, "five"), later))
    }

    @Test
    fun `inside a word, its own letters are lit over its own time`() {
        val line = line()
        val pieces = LyricsWords.pieces(line.text, line.words)
        val seven = pieceOf(line, pieces, "seven")
        val half = (seven.startMs + seven.endMs) / 2
        assertTrue(LyricsWords.sung(seven, half) in 0.45f..0.55f)
    }
}
