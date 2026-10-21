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
 * Lyrics timing opens only on the song playing. The lyrics menu keeps the song it was opened on,
 * and Lyrics timing tapped after the next song began opened nothing, then popped up by itself when
 * the old song came back. This reads the source, as LyricsOpeningTest does.
 */
class LyricsTimingSongTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `the bar opens only on the song playing when it is asked for`() {
        val start = source.substringAfter("fun startTiming(song: String) {", missingDelimiterValue = "").substringBefore("\n    }\n")
        assertTrue("startTiming could not be found", start.isNotEmpty())
        val check = start.indexOf("if (song != playerConnection.mediaMetadata.value?.id) return")
        assertTrue(start, check >= 0)
        assertTrue(start, check < start.indexOf("timing.open = true"))
    }
}
