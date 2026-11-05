/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The lyrics scroll up under the status bar: nothing shows there, and the fade starts under it (seen on the Pixel 5, 5 Nov 2026). */
class LyricsTopEdgeTest {
    @Test
    fun `the lines fade in under the status bar, not behind the clock`() {
        val view = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        assertTrue("topClear = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()" in view)
        val edge = File("src/main/java/com/dd3boh/outertune/ui/utils/FadingEdge.kt").readText()
        assertTrue("startY = topClear.toPx()" in edge)
        assertTrue("endY = (topClear + top).toPx()" in edge)
    }
}
