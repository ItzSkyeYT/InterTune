/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.BatteryMode
import com.dd3boh.outertune.constants.BatteryMode.BALANCED
import com.dd3boh.outertune.constants.BatteryMode.FULL
import com.dd3boh.outertune.constants.BatteryMode.SAVER
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.SpatialAudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each choice of Battery use turns down, and that none of them takes a setting away.
 *
 * His answers of 24 Oct 2026: Balanced is everyone's default, Saver turns the sound effects off
 * and streams at Low quality on mobile data. The rest is as experiments/features/battery-1023/MODES.md
 * has it. A mode is a rule applied where a setting is used: the person's own value goes in and
 * the value to use comes out, so what was set is never written over, and Full and Balanced give
 * every setting back exactly as it was chosen.
 */
class BatteryUseTest {

    private val modes = BatteryMode.entries

    @Test
    fun `a mode never chosen is Balanced, on a new install and on one that updated`() {
        // An update has no key for it either: nothing is written, so it reads as the default.
        assertEquals(BALANCED, BatteryUse.modeOf(null, offered = true))
        assertEquals(BALANCED, BatteryUse.DEFAULT)
        // A name this build does not know, from a backup made by a newer one, is the default too.
        assertEquals(BALANCED, BatteryUse.modeOf("TURBO", offered = true))
        for (mode in modes) assertEquals(mode, BatteryUse.modeOf(mode.name, offered = true))
    }

    @Test
    fun `a build that does not offer the choice runs as Full, whatever is stored`() {
        for (stored in listOf(null, "BALANCED", "SAVER", "FULL")) {
            assertEquals(FULL, BatteryUse.modeOf(stored, offered = false))
        }
        for (mode in modes) assertEquals(FULL, BatteryUse.inForce(mode, offered = false))
        for (mode in modes) assertEquals(mode, BatteryUse.inForce(mode, offered = true))
    }

    @Test
    fun `Full is the app as it was before the choice`() {
        assertEquals(30 * 60L, BatteryUse.syncEverySeconds(FULL))
        assertTrue(BatteryUse.syncAllowed(FULL, metered = true))
        assertEquals(5 * 60 * 1000L, BatteryUse.tasteKeptMs(FULL))
        assertEquals(BatteryUse.Bars.SCREEN_RATE, BatteryUse.bars(FULL))
        assertFalse("no offload unless the developer's switch asks for it", BatteryUse.offload(FULL, developerSwitch = false, needsAppPath = false))
        assertTrue(BatteryUse.offload(FULL, developerSwitch = true, needsAppPath = false))
        assertEquals(0, BatteryUse.songCacheMb(null, FULL))
    }

    @Test
    fun `Balanced syncs every two hours, keeps the taste half an hour and steps the bars thirty times a second`() {
        assertEquals(2 * 60 * 60L, BatteryUse.syncEverySeconds(BALANCED))
        assertTrue(BatteryUse.syncAllowed(BALANCED, metered = true))
        assertEquals(30 * 60 * 1000L, BatteryUse.tasteKeptMs(BALANCED))
        assertEquals(BatteryUse.Bars.STEPPED, BatteryUse.bars(BALANCED))
        assertEquals("thirty steps a second", 33L, BatteryUse.BARS_STEP_MS)
        assertFalse(BatteryUse.offload(BALANCED, developerSwitch = false, needsAppPath = false))
        assertFalse(BatteryUse.holdsEffects(BALANCED))
    }

    @Test
    fun `Saver syncs on Wi-Fi only, every six hours, and keeps the taste an hour`() {
        assertEquals(6 * 60 * 60L, BatteryUse.syncEverySeconds(SAVER))
        assertFalse("not on mobile data", BatteryUse.syncAllowed(SAVER, metered = true))
        assertTrue(BatteryUse.syncAllowed(SAVER, metered = false))
        assertEquals(60 * 60 * 1000L, BatteryUse.tasteKeptMs(SAVER))
        assertEquals(BatteryUse.Bars.STILL, BatteryUse.bars(SAVER))
    }

    @Test
    fun `Saver turns the sound effects off`() {
        assertTrue(BatteryUse.holdsEffects(SAVER))
        for (chosen in SpatialAudioMode.entries) assertEquals(SpatialAudioMode.OFF, BatteryUse.spatialAudio(chosen, SAVER))
        assertFalse(BatteryUse.equalizer(true, SAVER))
        assertFalse(BatteryUse.fade(true, SAVER))
        assertFalse("its scan runs while music plays", BatteryUse.proximity(true, SAVER))
        assertEquals("no boost past 100%", 1f, BatteryUse.volume(2.5f, SAVER))
        assertEquals("below 100% the volume is as set", 0.4f, BatteryUse.volume(0.4f, SAVER))
    }

    @Test
    fun `Saver asks for the low-power path whenever nothing needs the app's own`() {
        assertTrue(BatteryUse.offload(SAVER, developerSwitch = false, needsAppPath = false))
        // Skip silence, another speed, a sleep timer counting down: the song stays on the app's path.
        for (mode in modes) for (switch in listOf(true, false)) {
            assertFalse("$mode, switch $switch", BatteryUse.offload(mode, switch, needsAppPath = true))
        }
    }

    @Test
    fun `Saver streams at Low on mobile data and as chosen on Wi-Fi`() {
        for (chosen in AudioQuality.entries) {
            assertEquals(AudioQuality.LOW, BatteryUse.quality(chosen, metered = true, SAVER))
            assertEquals(chosen, BatteryUse.quality(chosen, metered = false, SAVER))
        }
        // Auto already takes the smallest stream on mobile data, so only High and Highest change.
        assertEquals(setOf(AudioQuality.HIGH, AudioQuality.MAX), AudioQuality.entries.filter { BatteryUse.lowersQuality(it, SAVER) }.toSet())
        assertTrue(AudioQuality.entries.none { BatteryUse.lowersQuality(it, FULL) || BatteryUse.lowersQuality(it, BALANCED) })
    }

    @Test
    fun `Saver downloads on Wi-Fi only and draws Blur in place of Living blur`() {
        assertTrue(BatteryUse.downloadsOnWifiOnly(false, SAVER))
        val blur = PlayerBackgroundStyle.BLUR
        assertEquals(blur, BatteryUse.playerBackground(PlayerBackgroundStyle.LIVING, SAVER, blur))
        // Where the phone has no blur, the fallback it is given: the gradient.
        assertEquals(PlayerBackgroundStyle.GRADIENT, BatteryUse.playerBackground(PlayerBackgroundStyle.LIVING, SAVER, PlayerBackgroundStyle.GRADIENT))
        for (chosen in PlayerBackgroundStyle.entries - PlayerBackgroundStyle.LIVING) {
            assertEquals(chosen, BatteryUse.playerBackground(chosen, SAVER, blur))
        }
    }

    @Test
    fun `Full and Balanced give every setting back exactly as it was chosen`() {
        // So leaving Saver brings back every effect, quality and style as it was set: nothing of it
        // was written over.
        for (mode in listOf(FULL, BALANCED)) {
            for (chosen in SpatialAudioMode.entries) assertEquals(chosen, BatteryUse.spatialAudio(chosen, mode))
            for (chosen in listOf(true, false)) {
                assertEquals(chosen, BatteryUse.equalizer(chosen, mode))
                assertEquals(chosen, BatteryUse.fade(chosen, mode))
                assertEquals(chosen, BatteryUse.proximity(chosen, mode))
                assertEquals(chosen, BatteryUse.downloadsOnWifiOnly(chosen, mode))
            }
            for (volume in listOf(0f, 0.5f, 1f, 2.5f, 4f)) assertEquals(volume, BatteryUse.volume(volume, mode))
            for (chosen in AudioQuality.entries) for (metered in listOf(true, false)) {
                assertEquals(chosen, BatteryUse.quality(chosen, metered, mode))
            }
            for (chosen in PlayerBackgroundStyle.entries) assertEquals(chosen, BatteryUse.playerBackground(chosen, mode, PlayerBackgroundStyle.BLUR))
            for (stored in listOf(null, 0, 128, 512, 4096, -1)) assertEquals(stored ?: 0, BatteryUse.songCacheMb(stored, mode))
        }
    }

    @Test
    fun `a setting set by hand is kept in every mode`() {
        // Download on Wi-Fi only, turned on by hand, stays on whatever the mode.
        for (mode in modes) assertTrue(BatteryUse.downloadsOnWifiOnly(true, mode))
        // A song cache size picked by hand wins over Saver's, Off and Unlimited included: Saver
        // only fills the gap where none was ever picked.
        for (stored in listOf(0, 128, 1024, -1)) assertEquals(stored, BatteryUse.songCacheMb(stored, SAVER))
        assertEquals(BatteryUse.SAVER_SONG_CACHE_MB, BatteryUse.songCacheMb(null, SAVER))
        assertEquals("one of the sizes the setting offers", 512, BatteryUse.SAVER_SONG_CACHE_MB)
        // The developer's Enable offload stays on in every mode.
        for (mode in modes) assertTrue(BatteryUse.offload(mode, developerSwitch = true, needsAppPath = false))
        // An effect switched off by hand stays off.
        for (mode in modes) {
            assertFalse(BatteryUse.equalizer(false, mode))
            assertFalse(BatteryUse.fade(false, mode))
            assertEquals(SpatialAudioMode.OFF, BatteryUse.spatialAudio(SpatialAudioMode.OFF, mode))
        }
    }

    @Test
    fun `each mode does less than the one before it`() {
        assertTrue(BatteryUse.syncEverySeconds(FULL) < BatteryUse.syncEverySeconds(BALANCED))
        assertTrue(BatteryUse.syncEverySeconds(BALANCED) < BatteryUse.syncEverySeconds(SAVER))
        assertTrue(BatteryUse.tasteKeptMs(FULL) < BatteryUse.tasteKeptMs(BALANCED))
        assertTrue(BatteryUse.tasteKeptMs(BALANCED) < BatteryUse.tasteKeptMs(SAVER))
        assertEquals(listOf(FULL, BALANCED, SAVER), modes)
    }
}
