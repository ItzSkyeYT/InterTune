/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.extensions.tabMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's clock moves twice a second while a song plays, and only what shows where the song
 * is gets composed again for it: the seek bar with its two times, and the mini player's line,
 * which asks for the position as it is drawn. The whole sheet used to be, on every screen, the
 * mini player, the strip of covers and every control with it. A JVM test cannot compose the
 * player, so the places that make it so are read from the source, comments taken out, as
 * PlayingIndicatorRestsTest does. What it comes to on the emulator is in the notes of the work
 * (experiments/features/perf-1023/PHASE3.md).
 */
class PlayerTicksTest {

    private fun source(path: String) = stripComments(File("src/main/java/com/dd3boh/outertune/$path").readText())

    private val player = source("ui/player/Player.kt")

    /** BottomSheetPlayer from where the position is first kept to the end of the function. */
    private val sheet = player
        .substringAfter("fun BottomSheetPlayer(", missingDelimiterValue = "")
        .substringAfter("var position by rememberSaveable(playbackState, songId) {", missingDelimiterValue = "")
        .substringBefore("\n}\n")

    @Test
    fun `the sheet never reads the position, it only hands on a way to ask for it`() {
        assertTrue("the player sheet could not be found", sheet.length > 10_000)
        assertTrue(sheet.contains("val shownPosition: () -> Long = { restoredPosition ?: position }"))
        // Every other mention is a write: the loop that moves it, a seek, or an argument named so.
        val reads = Regex("""\bposition\b(?!\s*=[^=])""").findAll(sheet)
            .map { sheet.substring((it.range.first - 40).coerceAtLeast(0), it.range.last + 1).trim() }
            .filterNot { it.endsWith("{ restoredPosition ?: position") }
            .toList()
        assertTrue("the sheet reads the position itself:\n" + reads.joinToString("\n"), reads.isEmpty())
        assertFalse(sheet.contains("sliderPosition ?: shownPosition"))
    }

    @Test
    fun `the seek bar reads it, and the mini player asks for it only to draw its line`() {
        val bar = player.substringAfter("private fun SeekBar(", missingDelimiterValue = "").substringBefore("\n}\n")
        assertTrue("SeekBar could not be found", bar.isNotEmpty())
        assertTrue(bar.contains("position: () -> Long,"))
        assertTrue(bar.contains("val shown = sliderPosition() ?: position()"))
        assertTrue("the sheet does not use it", sheet.contains("SeekBar(\n                        position = shownPosition,"))

        val mini = source("ui/player/MiniPlayer.kt")
        assertTrue(mini.contains("    position: () -> Long,\n    duration: Long,"))
        assertTrue(mini.contains("progress = { (position().toFloat() / duration).coerceIn(0f, 1f) },"))
        assertTrue(sheet.contains("MiniPlayer(\n                position = shownPosition,"))
    }

    @Test
    fun `the strip of covers keeps its list for as long as its songs stay`() {
        assertTrue(
            player.contains(
                "val mediaItems = remember(previousInStrip, mediaMetadata, nextInStrip) { listOfNotNull(previousInStrip, mediaMetadata, nextInStrip) }"
            )
        )
    }

    @Test
    fun `the tablet layout is worked out as before, and follows the setting as it changes`() {
        // A tablet on its side, whatever the setting, unless it is upright.
        assertTrue(tabMode(smallestWidthDp = 800, landscape = true, forced = null))
        assertTrue(tabMode(smallestWidthDp = 800, landscape = true, forced = false))
        assertFalse(tabMode(smallestWidthDp = 800, landscape = false, forced = true))
        // A phone only when the setting asks for it, and only on its side.
        assertFalse(tabMode(smallestWidthDp = 411, landscape = true, forced = null))
        assertFalse(tabMode(smallestWidthDp = 411, landscape = true, forced = false))
        assertTrue(tabMode(smallestWidthDp = 411, landscape = true, forced = true))
        assertFalse(tabMode(smallestWidthDp = 411, landscape = false, forced = true))
        assertEquals(true, tabMode(smallestWidthDp = 600, landscape = true, forced = null))

        for (path in listOf("ui/player/Player.kt", "ui/player/Thumbnail.kt")) {
            val file = source(path)
            assertFalse("$path asks the setting once, and not as it changes", file.contains("context.tabMode()"))
            assertTrue(path, file.contains("rememberTabMode()"))
        }
        assertTrue(player.contains("val forced by rememberNullablePreference(TabletUiKey)"))
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
