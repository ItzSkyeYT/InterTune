/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Skip silence skips gaps, not quiet music (31 Oct 2026: with media3's defaults a quiet piano opening played 4% fast). */
class SkipSilenceOnlySkipsSilenceTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `every audio chain uses the quiet skipper, never media3's defaults`() {
        assertFalse("SilenceSkippingAudioProcessor() with media3's defaults", "SilenceSkippingAudioProcessor()," in service)
        assertEquals(2, Regex("""quietSilenceSkipper\(\),""").findAll(service).count())
    }

    @Test
    fun `only real silence counts, about -42 dBFS for half a second`() {
        assertEquals(256.toShort(), MusicService.SKIP_SILENCE_THRESHOLD)
        assertEquals(500_000L, MusicService.SKIP_SILENCE_MINIMUM_US)
        assertTrue(MusicService.SKIP_SILENCE_THRESHOLD < 1024)
    }
}
