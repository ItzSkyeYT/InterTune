/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's error screen was handed a retry and never drew it (audit of 2 Nov 2026, 2.1): its
 * only buttons were Show more and Copy error details, and nothing said the play button would try
 * again. It has a Retry button now, which plays the song again from where it stopped.
 */
class PlaybackErrorRetryTest {
    private val src = File("src/main/java/com/dd3boh/outertune/ui/player")

    @Test
    fun `the error screen draws its retry`() {
        val screen = File(src, "ThumbnailPlaybackError.kt").readText()
        assertTrue(Regex("onClick\\s*=\\s*retry\\b").containsMatchIn(screen))
        assertTrue(screen.contains("R.string.retry"))
    }

    @Test
    fun `and the retry it is handed plays, since a stop on an error leaves the player paused`() {
        val player = File(src, "Thumbnail.kt").readText()
        val passed = player.substringAfter("ThumbnailPlaybackError(").substringBefore(")\n")
        assertFalse(passed, passed.contains("::prepare"))
        assertTrue(passed, passed.contains("playSafe"))
    }
}
