/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * New lyrics while the music is paused: Next on a paused player brings up the next song's lines,
 * and the loop that finds the line being sung waits for the music before it reads anything. The
 * last song's line number stayed, and a touch on the lines read past the end of the shorter new
 * ones and closed the app. This reads the source, as LyricsOpeningTest does.
 */
class LyricsNewLinesTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `the line being sung is found as soon as the new lines are in, before the wait for the music`() {
        val loop = source.substringAfter("LaunchedEffect(lyricsModel, lifecycleOwner) {", missingDelimiterValue = "")
            .substringBefore("\n    }\n")
        assertTrue("the loop could not be found", loop.isNotEmpty())
        val before = loop.substringBefore("repeatOnLifecycle(")
        assertTrue(before, before.contains("currentLineIndex = findCurrentLineIndex(lines, "))
    }

    @Test
    fun `the offset of the line scrolled to never reads past the lines`() {
        val offset = source.substringAfter("fun calculateOffset() = with(density) {", missingDelimiterValue = "")
            .substringBefore("\n        }\n")
        assertTrue("calculateOffset could not be found", offset.isNotEmpty())
        assertFalse(offset, "lines[currentLineIndex]" in offset)
        assertTrue(offset, "lines.getOrNull(currentLineIndex)" in offset)
    }
}
