/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Before the first line, as after a seek back into a song's opening, the lyrics go back to where
 * the first line will be sung, with no line lit. They used to stay on the line last shown.
 */
class LyricsOpeningTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `before the first line the view goes back to the start, lighting nothing`() {
        val branch = source.substringAfter("} else if (lastPreviewTime == 0L) {", missingDelimiterValue = "")
        assertTrue("no branch for the time before the first line", branch.isNotEmpty())
        val body = branch.substringBefore("\n        }\n")
        assertTrue(body.contains("deferredCurrentLineIndex = -1"))
        assertTrue(body.contains("lazyListState.scrollToItem(0,"))
        assertTrue(body.contains("lazyListState.glideToItem(0,"))
    }
}
