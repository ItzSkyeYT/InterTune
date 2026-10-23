/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How often the player wakes while a song plays with the screen off.
 *
 * Measured on a Galaxy S25 Ultra on 23 Oct 2026 (experiments/features/battery-1023): ExoPlayer's
 * own thread was 8 to 10% of a core all through background playback, woken every 10 ms to find the
 * output still full. A JVM test cannot play, so the place that changes that is read from the
 * source, comments taken out.
 */
class PlayerWakesWhenNeededTest {

    private val service = stripComments(File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText())

    /** The player's builder, from `ExoPlayer.Builder(this)` to its own `.build()`, the one the listeners follow. */
    private fun builder(): String {
        val from = service.indexOf("exoPlayer = ExoPlayer.Builder(this)")
        assertTrue("MusicService no longer builds its player where this test looks", from >= 0)
        val to = Regex("""\.build\(\)\s*\.apply \{""").find(service, from)?.range?.first ?: -1
        assertTrue(to > from)
        return service.substring(from, to)
    }

    @Test
    fun `the player's thread wakes when its renderers can go on, not every 10 ms`() {
        assertTrue(
            "the player is built without dynamic scheduling: its thread wakes a hundred times a second while a song plays",
            ".experimentalSetDynamicSchedulingEnabled(true)" in builder(),
        )
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
