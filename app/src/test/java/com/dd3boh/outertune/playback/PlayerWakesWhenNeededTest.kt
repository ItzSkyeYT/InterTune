/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the player keeps awake, and how often it wakes, while a song plays with the screen off.
 *
 * Measured on a Galaxy S25 Ultra on 23 Oct 2026 (experiments/features/battery-1023): ExoPlayer's
 * own thread was 8 to 10% of a core all through background playback, woken every 10 ms to find the
 * output still full; a streamed song came down a megabyte at a time, waking the mobile radio every
 * 30 to 50 s of music; and Wi-Fi was kept out of power save for as long as music played, cached
 * songs included. A JVM test cannot play, so the places that change that are read from the
 * source, comments taken out, and the rule for the Wi-Fi lock is tested as it is.
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

    @Test
    fun `a streamed song comes down in one burst, not a megabyte every half minute`() {
        val built = builder()
        assertTrue("the player keeps media3's default load control: 50 s ahead, read a megabyte at a time", ".setLoadControl(" in built)
        // Up to five minutes ahead, again when 30 s are left; playing from 1 s buffered, 2 s after a stall.
        assertTrue(Regex("""DefaultLoadControl\.Builder\(\)\s*\.setBufferDurationsMs\(30_000, 300_000, 1_000, 2_000\)""").containsMatchIn(built))
        // The byte target stays media3's own for audio (12.5 MiB, five minutes at 256 kbps), and
        // stays the limit: time is not put before size.
        assertTrue("setTargetBufferBytes" !in built && "setPrioritizeTimeOverSizeThresholds" !in built)
    }

    @Test
    fun `Wi-Fi is kept at full power only while the player loads`() {
        assertEquals(C.WAKE_MODE_NETWORK, PlayerWakeMode.forLoading(isLoading = true))
        assertEquals(C.WAKE_MODE_LOCAL, PlayerWakeMode.forLoading(isLoading = false))
        // Not loading still keeps the processor awake while the music plays: only Wi-Fi is let go.
        assertNotEquals(C.WAKE_MODE_NONE, PlayerWakeMode.forLoading(isLoading = false))
    }

    @Test
    fun `the service follows the phone's player as it starts and stops loading`() {
        // Built with the network's mode, so the first load has its lock before the first change is heard.
        assertTrue(".setWakeMode(C.WAKE_MODE_NETWORK)" in builder())
        val hook = service.indexOf("override fun onIsLoadingChanged(isLoading: Boolean)")
        assertTrue("MusicService does not hear the player start and stop loading", hook >= 0)
        val body = service.substring(hook, service.indexOf('}', hook))
        // The phone's player, whichever player called: while casting the service hears the receiver's.
        assertTrue(body.contains("exoPlayer.setWakeMode(PlayerWakeMode.forLoading(exoPlayer.isLoading))"))
        assertEquals("the wake mode is set in two places only", 2, Regex("""setWakeMode\(""").findAll(service).count())
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
