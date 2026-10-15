/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bar shown while a lyrics video is made follows what is really done.
 *
 * Transformer's own count for the picture follows the still image fed in, not the frames drawn
 * over it, so it ran far ahead and then sat still while the drawing caught up (seen on his phone,
 * 15 Oct 2026). Reads the source, since the export needs a device.
 */
class LyricsVideoProgressTest {

    private val source = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsVideo.kt").readText()

    @Test
    fun `the picture's progress is the frames drawn`() {
        assertTrue(
            "drawPicture must measure its progress by the last frame drawn",
            Regex("""export\(context, item, into, progress, measure = \{ frames\.lastUs""").containsMatchIn(source)
        )
        assertTrue("Frames must remember the last frame's time", Regex("""lastUs = presentationTimeUs""").containsMatchIn(source))
    }

    @Test
    fun `the bar only goes forward`() {
        assertTrue(Regex("""if \(at > shown\)""").containsMatchIn(source))
        assertTrue(
            "every step reports through the forward-only wrapper",
            Regex("""cutSound\([^)]*\) \{ forward\(""").containsMatchIn(source) && Regex("""drawPicture\([^)]*\) \{ forward\(""").containsMatchIn(source)
        )
    }
}
