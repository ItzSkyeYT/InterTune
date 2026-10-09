/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What plays music runs left to right in every language. In Arabic, Hebrew and Persian the three
 * places that have the buttons turned them round with the page: next stood where previous
 * stands, and a song's line filled from the right (seen on a Pixel 5, 9 Oct 2026).
 *
 * This reads the source: the order is a matter of what is drawn inside [PlaybackOrder], which a
 * test on the JVM cannot look at.
 */
class PlaybackOrderTest {

    private val player = File("src/main/java/com/dd3boh/outertune/ui/player")

    /** What [file] draws inside its PlaybackOrder blocks, each cut where its indentation ends. */
    private fun kept(file: String): List<String> {
        val lines = File(player, file).readLines()
        return lines.indices.filter { lines[it].trimEnd().endsWith("PlaybackOrder {") }.map { start ->
            val indent = lines[start].takeWhile { it == ' ' }
            val end = (start + 1 until lines.size).first { lines[it] == "$indent}" }
            lines.subList(start, end).joinToString("\n")
        }
    }

    @Test
    fun `the full player keeps its line, its two times and its five buttons in order`() {
        val block = kept("Player.kt").single()
        for (thing in listOf("Slider(", "makeTimeString(shownDuration)", "shuffleName(", "Icons.Rounded.SkipPrevious", "playPauseName(", "Icons.Rounded.SkipNext", "repeatName(")) {
            assertTrue(thing, thing in block)
        }
        // Previous is drawn before next, which is left of it once the order is left to right.
        assertTrue(block.indexOf("Icons.Rounded.SkipPrevious") < block.indexOf("Icons.Rounded.SkipNext"))
    }

    @Test
    fun `the arrow keys seek the way the line runs`() {
        // Right was back and left was on wherever the page ran from right to left, which fitted a
        // line that ran that way too.
        val source = File(player, "Player.kt").readText()
        assertTrue("Key.DirectionRight -> true" in source)
        assertTrue("Key.DirectionLeft -> false" in source)
        assertFalse(Regex("""Key\.Direction(Right|Left) -> sheetLayoutDirection""").containsMatchIn(source))
    }

    @Test
    fun `the mini player keeps its line and its three buttons in order`() {
        val blocks = kept("MiniPlayer.kt")
        assertEquals(2, blocks.size)
        assertTrue(blocks.any { "LinearProgressIndicator(" in it })
        val buttons = blocks.single { "skip_previous" in it }
        assertTrue("skip_next" in buttons)
        assertTrue(buttons.indexOf("skip_previous") < buttons.indexOf("togglePlayPause()"))
        assertTrue(buttons.indexOf("togglePlayPause()") < buttons.indexOf("skip_next"))
    }

    @Test
    fun `the queue keeps its five buttons in order`() {
        val block = kept("Queue.kt").single()
        for (thing in listOf("shuffleName(", "Icons.Rounded.SkipPrevious", "playPauseName(", "Icons.Rounded.SkipNext", "repeatName(")) {
            assertTrue(thing, thing in block)
        }
    }

    @Test
    fun `nothing else of the three is taken out of the page's direction`() {
        // The title, the cover's row and the lists turn with the language, as text does.
        assertEquals(1, Regex("""LayoutDirection\.Ltr\b""").findAll(File(player, "PlaybackOrder.kt").readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")).count())
        for (file in listOf("MiniPlayer.kt", "Queue.kt")) {
            assertFalse(file, "provides LayoutDirection.Ltr" in File(player, file).readText())
        }
    }
}
