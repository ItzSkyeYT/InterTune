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
 * Follow the song off: each song's lyrics are placed on the line being sung once, when they come
 * up, and then stay where the listener leaves them, while the lit line keeps up. This reads the
 * source, as LyricsOpeningTest does.
 */
class LyricsFollowTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    /** The effect that scrolls the lines to the song, from its first early return to its end. */
    private val scrolling = source
        .substringAfter("LaunchedEffect(currentLineIndex, lastPreviewTime, picking, fingerDown, lyricsAutoScroll) {", missingDelimiterValue = "")
        .substringBefore("\n    }\n")

    @Test
    fun `each lyrics are placed once, not once for as long as the view is open`() {
        // The lines are one list for the view's whole life, refilled in place for each song, so a
        // placement remembered against that list was never forgotten: from the second song on,
        // the lyrics came up wherever the last ones had been left.
        assertTrue("the placement is not kept per lyrics", source.contains("var placedOnce by remember(lyricsModel) { mutableStateOf(false) }"))
        assertFalse(source.contains("var placedOnce by remember(lines)"))
    }

    @Test
    fun `left where it is, the view still lights the line being sung, during a seek too`() {
        assertTrue("the scrolling effect could not be found", scrolling.isNotEmpty())
        val staying = scrolling.substringAfter("if (!lyricsAutoScroll && placedOnce) {", missingDelimiterValue = "")
        assertTrue("no branch for lines that stay where they are", staying.isNotEmpty())
        // During a seek the lit line is deferredCurrentLineIndex, which only this effect moves.
        val body = staying.substringBefore("return@LaunchedEffect")
        assertTrue("the lit line stops while seeking", body.contains("deferredCurrentLineIndex = currentLineIndex"))
    }

    @Test
    fun `lyrics that come up before the first line are placed there once, then left`() {
        val opening = scrolling.substringAfter("} else if (lastPreviewTime == 0L) {", missingDelimiterValue = "")
        assertTrue("no branch for the time before the first line", opening.isNotEmpty())
        assertTrue("the opening is placed again after every scroll", opening.contains("placedOnce = true"))
    }
}
