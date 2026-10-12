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
 * The bars on the playing song hold still while the open player covers their page.
 *
 * Each step of the bars is a frame drawn for the whole window. With the player open over a page
 * that shows the playing song, the page kept taking those frames: measured on a Pixel 5 in the
 * release build of 0.11.1, 88 frames a second and half a processor core, for bars under the
 * player. A JVM test cannot draw, so the three places that make it work are read from the
 * source, comments taken out.
 */
class PlayingIndicatorRestsTest {

    private fun source(path: String) = stripComments(File("src/main/java/com/dd3boh/outertune/$path").readText())

    @Test
    fun `the bars ask whether they are hidden before they move`() {
        val bars = source("ui/component/PlayingIndicator.kt")
        val asks = bars.indexOf("snapshotFlow { hidden() }")
        val rests = bars.indexOf("if (covered) return@collectLatest")
        val moves = bars.indexOf("animateTo(")
        assertTrue("PlayingIndicator does not watch LocalHiddenByPlayer", "LocalHiddenByPlayer.current" in bars && asks >= 0)
        assertTrue("PlayingIndicator does not rest while hidden", rests > asks)
        assertTrue("PlayingIndicator moves before it has asked", moves > rests)
    }

    @Test
    fun `the pages are told, by the player fully open`() {
        val main = source("MainActivity.kt")
        assertTrue(
            "MainActivity does not say when the player is fully open",
            Regex("""val pagesHiddenByPlayer = remember\(playerBottomSheetState\) \{\s*\{ playerBottomSheetState\.isExpanded \}\s*\}""")
                .containsMatchIn(main),
        )
        val told = main.indexOf("CompositionLocalProvider(LocalHiddenByPlayer provides pagesHiddenByPlayer)")
        assertTrue("MainActivity does not tell the pages", told >= 0)
        assertTrue("it is not the nav host that is told", main.indexOf("navHost()", told) in told..told + 200)
    }

    @Test
    fun `the player's own sheet is never told it is hidden`() {
        // Its queue shows the playing song too, over everything: those bars keep moving.
        assertFalse("LocalHiddenByPlayer" in source("ui/player/Player.kt"))
        assertFalse("LocalHiddenByPlayer" in source("ui/player/Queue.kt"))
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
