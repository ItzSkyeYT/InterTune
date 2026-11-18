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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * #165 (13 Nov 2026): word by word lyrics made with BetterLyrics' Unison, as TTML, lit the wrong part of
 * the line while the song was paused: a word not yet sung half lit, a word already sung not lit to its
 * end. The app's own way lit a share of each row's width counted in words, and handed the words to the
 * wrapped rows by counting spaces, so a word timed as two syllables ("bro" + "ken") shifted the rest and
 * dropped the line's last word. It now lights each word where it stands, from its own letters over its
 * own time (LyricsWords.place and LyricsWords.lit), as the looks being compared do.
 *
 * These are the reporter's three lines with their exact times, in words of no song (the lyrics themselves
 * are not ours to copy), the split word kept, laid out on the rows his pictures show, in made-up letter
 * widths: narrow and wide letters, as a real font has, since words of different widths are what the old
 * count got wrong. Where the lit part stops is asked at the moments of his pictures.
 */
class LyricsWordsTtmlTest {

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" ttp:timeBase="media" xml:lang="en" itunes:timing="Word">
          <head><metadata><ttm:agent xml:id="v1" type="person"/></metadata></head>
          <body dur="3:00.000"><div>
            <p begin="0:39.079" end="0:46.333" itunes:key="L1" ttm:agent="v1"><span begin="0:39.079" end="0:39.169">one</span> <span begin="0:39.255" end="0:39.440">two</span> <span begin="0:39.550" end="0:39.755">three,</span> <span begin="0:39.925" end="0:40.149">"four</span> <span begin="0:40.347" end="0:40.765">five</span> <span begin="0:41.038" end="0:41.526">six,</span> <span begin="0:41.764" end="0:42.996">seven</span> <span begin="0:43.299" end="0:43.529">eig</span><span begin="0:43.977" end="0:44.246">ht</span> <span begin="0:44.681" end="0:44.962">by</span> <span begin="0:45.388" end="0:45.685">ten</span> <span begin="0:46.070" end="0:46.333">end"</span></p>
            <p begin="0:50.593" end="0:53.134" itunes:key="L2" ttm:agent="v1"><span begin="0:50.593" end="0:50.693">one</span> <span begin="0:50.784" end="0:50.865">two</span> <span begin="0:50.985" end="0:51.072">three</span> <span begin="0:51.383" end="0:51.475">four</span> <span begin="0:51.746" end="0:51.870">five</span> <span begin="0:52.177" end="0:52.256">six</span> <span begin="0:52.646" end="0:52.726">seven</span> <span begin="0:52.835" end="0:53.134">eight</span></p>
            <p begin="2:12.427" end="2:17.726" itunes:key="L3" ttm:agent="v1"><span begin="2:12.427" end="2:12.521">one</span> <span begin="2:12.608" end="2:12.670">two</span> <span begin="2:12.762" end="2:13.068">three,</span> <span begin="2:13.252" end="2:13.383">"four</span> <span begin="2:13.716" end="2:14.097">five</span> <span begin="2:14.426" end="2:14.833">six</span> <span begin="2:15.099" end="2:15.770">seven,</span> <span begin="2:16.431" end="2:16.544">eight</span> <span begin="2:16.645" end="2:17.008">nine</span> <span begin="2:17.198" end="2:17.726">ten"</span></p>
          </div></body>
        </tt>
    """.trimIndent()

    private fun lines(): List<SemanticLyrics.LyricLine> {
        val parsed = parseTtml(null, ttml, KXmlParser())
        assertNotNull("the TTML is read", parsed)
        return (parsed as SemanticLyrics.SyncedLyrics).text.filter { it.text.isNotBlank() }
    }

    private fun line(): SemanticLyrics.LyricLine = lines()[0]

    private fun pieceOf(line: SemanticLyrics.LyricLine, pieces: List<LyricsWords.Piece>, word: String): LyricsWords.Piece =
        pieces.single { line.text.substring(it.from, it.to) == word }

    /** A word's width in a 50 pixel bold font, near enough: narrow letters and wide ones. */
    private fun advance(c: Char): Float = 50f * when (c) {
        ' ', 'i', 'l', 'j', '\'', ',', '.' -> 0.25f
        'f', 'r', 't', '"' -> 0.34f
        'm', 'w', 'M', 'W' -> 0.82f
        in 'A'..'Z' -> 0.66f
        else -> 0.55f
    }

    /**
     * [text] laid out as a screen does, in [advance]'s widths: each row after the first starts at
     * one of [breaks], the spaces before a break end the row above, and each row is set on the left
     * of [width], in its middle with [centred], or on its right and read from the right with [rtl].
     */
    private inner class Rows(
        val text: String,
        breaks: List<Int> = emptyList(),
        private val width: Float = 1_000f,
        private val centred: Boolean = false,
        private val rtl: Boolean = false,
    ) : WordRows {
        private val starts = listOf(0) + breaks
        override val length = text.length
        override val count = starts.size
        override fun rowOf(offset: Int) = starts.indexOfLast { it <= offset }.coerceAtLeast(0)
        override fun start(row: Int) = starts[row]
        override fun visibleEnd(row: Int): Int {
            var end = if (row + 1 < starts.size) starts[row + 1] else text.length
            while (end > start(row) && text[end - 1] == ' ') end--
            return end
        }
        private fun along(row: Int, offset: Int) = (start(row) until offset).sumOf { advance(text[it]).toDouble() }.toFloat()
        private fun rowWidth(row: Int) = along(row, visibleEnd(row))
        override fun left(row: Int) = when {
            centred -> (width - rowWidth(row)) / 2f
            rtl -> width - rowWidth(row)
            else -> 0f
        }
        override fun right(row: Int) = left(row) + rowWidth(row)
        override fun top(row: Int) = row * 60f
        override fun bottom(row: Int) = top(row) + 60f
        override fun rtl(row: Int) = rtl
        override fun x(offset: Int): Float {
            val row = rowOf(offset)
            return if (rtl) right(row) - along(row, offset) else left(row) + along(row, offset)
        }
    }

    /** One line of hand-made TTML, [p] its paragraph: text in it outside a timed span has no time of its own. */
    private fun partly(p: String): SemanticLyrics.LyricLine {
        val tt = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" ttp:timeBase="media" xml:lang="en" itunes:timing="Word"><head><metadata><ttm:agent xml:id="v1" type="person"/></metadata></head><body dur="0:10.000"><div>$p</div></body></tt>"""
        val parsed = parseTtml(null, tt, KXmlParser())
        assertNotNull("the TTML is read", parsed)
        return (parsed as SemanticLyrics.SyncedLyrics).text.single { it.text.isNotBlank() }
    }

    /** A line placed as the app's own way places it. */
    private inner class Placed(val line: SemanticLyrics.LyricLine, layOut: (String) -> Rows) {
        val rows = layOut(line.text)
        val pieces = LyricsWords.pieces(line.text, line.words)
        val boxes = LyricsWords.place(pieces, rows, past = 50f)

        fun box(word: String): WordBox = boxes.single { line.text.substring(pieces[it.piece].from, pieces[it.piece].to) == word }

        /** That all of [row] is drawn: its boxes reach its ends, with no gap between them, since nothing outside them is. */
        fun drawsWhole(row: Int) {
            val mine = boxes.filter { it.row == row }.sortedBy { it.left }
            assertTrue("row $row has something drawn", mine.isNotEmpty())
            assertTrue("row $row is drawn from its start", mine.minOf { it.clipLeft } <= rows.left(row))
            assertTrue("row $row is drawn to its end", mine.maxOf { it.clipRight } >= rows.right(row))
            mine.zipWithNext { a, b -> assertEquals("row $row is drawn with no gap", a.clipRight, b.clipLeft, 0.001f) }
        }

        /** Where the lit part of [row] ends at [atMs], read from the left: the far end of all that is lit, or null for none. */
        fun litEnd(row: Int, atMs: Long): Float? = boxes.filter { it.row == row }
            .mapNotNull { LyricsWords.lit(it, LyricsWords.sung(pieces[it.piece], atMs), pieces[it.piece].rtl)?.endInclusive }
            .maxOrNull()

        /** Whether the lit part of [row] at [atMs] stops between [sung], all of it lit, and [next], none of it. */
        fun stopsBetween(row: Int, atMs: Long, sung: String, next: String) {
            val end = litEnd(row, atMs)
            assertNotNull("something of row $row is lit at $atMs", end)
            assertTrue("\"$sung\" is lit to its end at $atMs: $end against ${box(sung).right}", end!! >= box(sung).right)
            assertTrue("nothing of \"$next\" is lit at $atMs: $end against ${box(next).left}", end <= box(next).left)
        }
    }

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

    @Test
    fun `the first picture, a word timed as two syllables on the second row: lit to the end of the word sung, nothing of by`() {
        val lines = lines()
        assertEquals(3, lines.size)
        // On two rows as in the picture, the second starting with the split word; set in the middle, the app's default.
        val placed = Placed(lines[0]) { Rows(it, breaks = listOf(it.indexOf("eight")), centred = true) }
        // Paused at 0:44.4, after "ken" (here "ht") and before "by".
        placed.stopsBetween(row = 1, atMs = 44_400, sung = "ht", next = "by")
        assertEquals("all of the first row is lit", placed.box("seven").clipRight, placed.litEnd(0, 44_400)!!, 0.001f)
        // The two syllables stand side by side on the row they are on, the first lit while the second waits.
        assertEquals(1, placed.box("eig").row)
        assertEquals(1, placed.box("ht").row)
        assertEquals(placed.box("eig").right, placed.box("ht").left, 0.001f)
        assertEquals(placed.box("eig").right, placed.litEnd(1, 43_800)!!, 0.001f)
        // The line's last word is on the second row and lit only as it is sung: the old count lost it.
        val end = placed.box("end\"")
        assertEquals(1, end.row)
        placed.stopsBetween(row = 1, atMs = 46_000, sung = "ten", next = "end\"")
        assertEquals((end.left + end.right) / 2f, placed.litEnd(1, (46_070L + 46_333L) / 2)!!, 0.5f)
        assertEquals(end.clipRight, placed.litEnd(1, 46_333)!!, 0.001f)
    }

    @Test
    fun `the second picture, one row: lit to the end of the word sung, nothing of way`() {
        val placed = Placed(lines()[1]) { Rows(it) }
        // Paused at 0:52.0, after "the" (here "five") and before "way" (here "six").
        placed.stopsBetween(row = 0, atMs = 52_000, sung = "five", next = "six")
        // And at every paused moment between two words of the line, the same.
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight")
        for (i in 0 until words.lastIndex) {
            val gap = (placed.pieces[i].endMs + placed.pieces[i + 1].startMs) / 2
            placed.stopsBetween(row = 0, atMs = gap, sung = words[i], next = words[i + 1])
        }
    }

    @Test
    fun `the third picture, two rows: Don't lit to its end, nothing of the next word, nothing of the second row`() {
        val placed = Placed(lines()[2]) { Rows(it, breaks = listOf(it.indexOf("nine"))) }
        // Paused at 2:13.5, after "Don't" (here "four) and before "be" (here "five").
        placed.stopsBetween(row = 0, atMs = 133_500, sung = "\"four", next = "five")
        assertNull("nothing of the second row is lit", placed.litEnd(1, 133_500))
        // The second row starts with its own first word, lit as it is sung.
        assertEquals(1, placed.box("nine").row)
        placed.stopsBetween(row = 1, atMs = 137_100, sung = "nine", next = "ten\"")
    }

    @Test
    fun `inside a word, the hard edge crosses its letters evenly over its time, and the line is all lit once sung`() {
        val placed = Placed(lines()[0]) { Rows(it, breaks = listOf(it.indexOf("eight"))) }
        val seven = placed.box("seven")
        val piece = placed.pieces[seven.piece]
        for (quarter in 1..3) {
            val at = piece.startMs + (piece.endMs - piece.startMs) * quarter / 4
            val expected = seven.left + (seven.right - seven.left) * quarter / 4f
            assertEquals("a quarter at a time", expected, placed.litEnd(0, at)!!, 0.5f)
        }
        // Before the line nothing is lit; once it is over, all of it.
        assertNull(placed.litEnd(0, 39_000))
        assertNull(placed.litEnd(1, 39_000))
        assertEquals(placed.box("end\"").clipRight, placed.litEnd(1, 46_400)!!, 0.001f)
    }

    @Test
    fun `text with no time of its own after the timed words is drawn and lit with them, never cut off`() {
        // Hand-made TTML with one timed word and "world (x2)" outside any span: while the line was sung only
        // "Hello w" was drawn, the rest gone until it ended.
        val placed = Placed(partly("""<p begin="0:01.000" end="0:04.000" ttm:agent="v1"><span begin="0:01.000" end="0:02.000">Hello</span> world (x2)</p>""")) { Rows(it) }
        assertEquals("Hello world (x2)", placed.line.text)
        assertEquals(1, placed.pieces.size)
        placed.drawsWhole(0)
        assertNull(placed.litEnd(0, 500))
        // While "Hello" is sung, none of the rest; once it is, all of it with it.
        assertTrue(placed.litEnd(0, 1_500)!! < placed.rows.x(placed.line.text.indexOf("world")))
        assertTrue(placed.litEnd(0, 2_000)!! >= placed.rows.right(0))
    }

    @Test
    fun `text with no time of its own before the first word of its row is lit as that word starts`() {
        val placed = Placed(partly("""<p begin="0:01.000" end="0:03.000" ttm:agent="v1">(x2) <span begin="0:01.000" end="0:02.000">Hello</span> <span begin="0:02.000" end="0:03.000">world</span></p>""")) {
            Rows(it, centred = true)
        }
        assertEquals("(x2) Hello world", placed.line.text)
        placed.drawsWhole(0)
        assertNull(placed.litEnd(0, 900))
        val hello = placed.box("Hello")
        val lit = LyricsWords.lit(hello, LyricsWords.sung(placed.pieces[hello.piece], 1_100), false)!!
        assertTrue("\"(x2)\" is lit with \"Hello\"", lit.start <= placed.rows.left(0))
        assertTrue("and \"Hello\" only as far as it is sung", lit.endInclusive < hello.right)
    }

    @Test
    fun `a row no word stands on is drawn whole and lit whole, as the word after it starts or once the word before it is sung`() {
        val placed = Placed(partly("""<p begin="0:01.000" end="0:04.000" ttm:agent="v1">oh la la <span begin="0:02.000" end="0:02.500">Hello</span> <span begin="0:03.000" end="0:03.500">there</span> na na na</p>""")) {
            Rows(it, breaks = listOf(it.indexOf("Hello"), it.indexOf("na na na")))
        }
        assertEquals("oh la la Hello there na na na", placed.line.text)
        for (row in 0..2) placed.drawsWhole(row)
        // Before "Hello", nothing; as it starts, all of the row before it, which used to wait for a word after it.
        assertNull(placed.litEnd(0, 1_500))
        assertTrue(placed.litEnd(0, 2_050)!! >= placed.rows.right(0))
        // The row after "there" waits for "there" to be sung, not for a word before it.
        assertNull(placed.litEnd(2, 3_200))
        assertTrue(placed.litEnd(2, 3_500)!! >= placed.rows.right(2))
    }

    @Test
    fun `a line read from the right is lit from the right`() {
        // Two words of a right to left script, timed one after the other.
        val text = "אבג דהו"
        val words = listOf(
            SemanticLyrics.Word(1_000UL..2_000UL, 0..2, true),
            SemanticLyrics.Word(2_000UL..3_000UL, 4..6, true),
        )
        val pieces = LyricsWords.pieces(text, words)
        val boxes = LyricsWords.place(pieces, Rows(text, rtl = true), past = 50f)
        val first = boxes.single { it.piece == 0 }
        val second = boxes.single { it.piece == 1 }
        assertTrue("the first word stands on the right", first.left > second.right)
        // Half way through the first word: lit from its right end to its middle.
        val lit = LyricsWords.lit(first, LyricsWords.sung(pieces[0], 1_500), true)!!
        assertEquals((first.left + first.right) / 2f, lit.start, 0.5f)
        assertTrue(lit.endInclusive >= first.right)
        assertNull("the second word waits", LyricsWords.lit(second, LyricsWords.sung(pieces[1], 1_500), true))
    }

    @Test
    fun `a word too long for its row is lit across both rows in turn`() {
        // One long word over two rows, the break inside it, and a short word after it.
        val text = "abcdefghij klm"
        val words = listOf(
            SemanticLyrics.Word(1_000UL..2_000UL, 0..9, false),
            SemanticLyrics.Word(2_500UL..3_000UL, 11..13, false),
        )
        val pieces = LyricsWords.pieces(text, words)
        val boxes = LyricsWords.place(pieces, Rows(text, breaks = listOf(5)), past = 50f)
        val (top, bottom) = boxes.filter { it.piece == 0 }.sortedBy { it.row }
        assertEquals(listOf(0, 1), listOf(top.row, bottom.row))
        // Its letters are lit evenly over its time, the first row's then the second's.
        val onTop = top.right - top.left
        val letters = onTop + (bottom.right - bottom.left)
        // A quarter sung: a quarter of its letters, all on the first row, nothing of the second's.
        val quarter = LyricsWords.sung(pieces[0], 1_250)
        assertTrue(letters / 4f < onTop)
        assertEquals(top.left + letters / 4f, LyricsWords.lit(top, quarter, false)!!.endInclusive, 0.5f)
        assertNull(LyricsWords.lit(bottom, quarter, false))
        // Three quarters: all of the first row's, and the rest on the second.
        val three = LyricsWords.sung(pieces[0], 1_750)
        assertTrue(letters * 3f / 4f > onTop)
        assertEquals(top.clipRight, LyricsWords.lit(top, three, false)!!.endInclusive, 0.001f)
        assertEquals(bottom.left + letters * 3f / 4f - onTop, LyricsWords.lit(bottom, three, false)!!.endInclusive, 0.5f)
    }
}
