/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings the car shows are the phone's own: the same keys, and the same answer where
 * nothing was ever chosen. A switch that reads "on" in the car while the player takes it for
 * off is worse than no switch.
 */
class AutoSettingsTest {

    @Test
    fun `with nothing chosen the car shows what the player does`() {
        assertEquals(
            AutoSettings(more = true, quality = AudioQuality.AUTO, level = true, silence = false),
            AutoSettings.of(emptyPreferences()),
        )
    }

    @Test
    fun `what was chosen on the phone is what the car shows`() {
        val saved = preferencesOf(
            AutoLoadMoreKey to false,
            AudioQualityKey to AudioQuality.LOW.name,
            AudioNormalizationKey to false,
            SkipSilenceKey to true,
        )
        assertEquals(AutoSettings(more = false, quality = AudioQuality.LOW, level = false, silence = true), AutoSettings.of(saved))
    }

    @Test
    fun `a quality this version does not know reads as Auto`() {
        assertEquals(AudioQuality.AUTO, AutoSettings.of(preferencesOf(AudioQualityKey to "LOSSLESS")).quality)
    }

    @Test
    fun `the car offers every quality the phone does, in the phone's order`() {
        assertEquals(AudioQuality.entries.toList(), AutoSettings.QUALITIES)
    }

    @Test
    fun `a list of settings fits what a car shows of one`() {
        // A car cuts a list off, at six rows on most: a setting past that would be there and unreachable.
        assertTrue(AutoSettings.Line.entries.size <= 6)
    }

    @Test
    fun `the player takes the same answer where nothing was chosen`() {
        // Read from the source, as PlayBookCallSitesTest does: a JVM test cannot run the service.
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        for (line in listOf("it[SkipSilenceKey] ?: false", "it[AutoLoadMoreKey] ?: true", "it[AudioNormalizationKey] ?: true", "enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)")) {
            assertTrue("MusicService no longer reads \"$line\"", line in service)
        }
    }
}
