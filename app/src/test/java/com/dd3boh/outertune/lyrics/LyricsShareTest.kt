/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.lyrics.LyricsShare.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Copy and share from the lyrics view: the text that goes out, and the lines picked for a picture.
 */
class LyricsShareTest {

    private val verses = listOf(
        "", "The first line of a verse  ", "and the second", "", "", "  ",
        "A chorus line", "and another one", "", "",
    )

    @Test
    fun `the text is the lines as shown, one empty line between verses and none round them`() {
        assertEquals(
            "The first line of a verse\nand the second\n\nA chorus line\nand another one",
            LyricsShare.text(verses),
        )
        assertEquals("", LyricsShare.text(listOf("", " ")))
    }

    @Test
    fun `share sends the title and the artists over the lyrics`() {
        assertEquals(
            "A Song\nA Band, A Singer\n\nA line\n\nA chorus",
            LyricsShare.message("A Song", "A Band, A Singer", listOf("A line", "", "A chorus")),
        )
        assertEquals("Song\n\nLine", LyricsShare.message(" Song ", " ", listOf("Line")))
    }

    @Test
    fun `a tap picks a line and a second tap puts it back`() {
        val lines = listOf("one", "two", "three")
        val first = LyricsShare.toggle(emptyList(), 2, lines)
        assertEquals(Pick.Picked(listOf(2)), first)
        val second = LyricsShare.toggle((first as Pick.Picked).lines, 0, lines)
        assertEquals(Pick.Picked(listOf(2, 0)), second)
        assertEquals(Pick.Picked(listOf(0)), LyricsShare.toggle((second as Pick.Picked).lines, 2, lines))
    }

    @Test
    fun `an empty line is never picked`() {
        assertEquals(Pick.Empty, LyricsShare.toggle(emptyList(), 1, listOf("one", "  ", "three")))
        assertEquals(Pick.Empty, LyricsShare.toggle(emptyList(), 7, listOf("one")))
    }

    @Test
    fun `a picture takes five lines, and one more is refused, though one can still be put back`() {
        val lines = (1..9).map { "line $it" }
        val five = (0 until LyricsShare.MOST_LINES).toList()
        assertEquals(5, LyricsShare.MOST_LINES)
        assertEquals(Pick.Full, LyricsShare.toggle(five, 6, lines))
        assertEquals(Pick.Picked(listOf(0, 1, 2, 4)), LyricsShare.toggle(five, 3, lines))
    }

    @Test
    fun `the picture has the lines in the song's order, whatever order they were picked in`() {
        val lines = listOf("first", " second ", "", "fourth")
        assertEquals(listOf("first", "second", "fourth"), LyricsShare.pickedLines(lines, listOf(3, 0, 1)))
        assertEquals(listOf("first"), LyricsShare.pickedLines(lines, listOf(0, 0, 9, 2)))
    }

    @Test
    fun `the picture's file is named after the song, with nothing a file name chokes on`() {
        assertEquals("A Song.png", LyricsShare.fileName("A Song"))
        assertEquals("Left Right - A Song.png", LyricsShare.fileName("Left/Right - A Song"))
        assertEquals("Été 85.png", LyricsShare.fileName("Été 85?"))
        assertEquals("lyrics.png", LyricsShare.fileName("../.."))
        assertEquals("lyrics.png", LyricsShare.fileName("   "))
        assertTrue(LyricsShare.fileName("a".repeat(200)).length <= 64)
    }

    @Test
    fun `the menu keeps its old entries where they were, and the new ones follow them`() {
        // Read from the source, as other guards here do: the grid is built in code, not in a list.
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/LyricsMenu.kt").readText()
        val order = listOf("R.string.edit", "R.string.refetch", "R.string.search", "R.string.delete", "R.string.settings",
            "R.string.lyrics_copy", "R.string.lyrics_share", "R.string.lyrics_share_picture")
        val at = order.map { name -> menu.indexOf("title = $name") }
        assertTrue("every entry is in the menu: ${order.zip(at)}", at.all { it >= 0 })
        assertEquals("in this order: ${order.zip(at)}", at.sorted(), at)
    }
}
