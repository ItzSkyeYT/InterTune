/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rank rule that decides whether a cached copy was fetched at a lower setting than the one now
 * in force, for playback (MusicService.shouldUpgradeCached) and downloads alike. A plain function,
 * so it is tested without a cache or a database.
 */
class QualityUpgradeRuleTest {

    @Test
    fun `a copy recorded at a lower tier than the current setting is stale`() {
        assertTrue(isStaleQualityTier(AudioQuality.LOW.name, AudioQuality.HIGH))
        assertTrue(isStaleQualityTier(AudioQuality.LOW.name, AudioQuality.AUTO))
        assertTrue(isStaleQualityTier(AudioQuality.AUTO.name, AudioQuality.MAX))
        assertTrue(isStaleQualityTier(AudioQuality.HIGH.name, AudioQuality.MAX))
    }

    @Test
    fun `auto and high share a rank, so moving between them is not an upgrade`() {
        assertFalse(isStaleQualityTier(AudioQuality.AUTO.name, AudioQuality.HIGH))
        assertFalse(isStaleQualityTier(AudioQuality.HIGH.name, AudioQuality.AUTO))
    }

    @Test
    fun `a copy at the same or a higher tier is left alone`() {
        assertFalse(isStaleQualityTier(AudioQuality.MAX.name, AudioQuality.MAX))
        assertFalse(isStaleQualityTier(AudioQuality.MAX.name, AudioQuality.LOW))
        assertFalse(isStaleQualityTier(AudioQuality.HIGH.name, AudioQuality.LOW))
    }

    @Test
    fun `a null or unrecognised tier is never treated as stale`() {
        assertFalse(isStaleQualityTier(null, AudioQuality.MAX))
        assertFalse(isStaleQualityTier("", AudioQuality.MAX))
        assertFalse(isStaleQualityTier("NOT_A_TIER", AudioQuality.MAX))
    }

    // ---- Auto on mobile data (9 Nov 2026, found while casting to Geogos's speaker): Auto takes a middle stream on
    // metered data and the best one elsewhere (AudioPick), and the copy was recorded as plain "AUTO" either way, so a
    // song first played on mobile data stayed at about 55 kbps (0.12) or 75 (0.13) on Wi-Fi for as long as it was cached.

    @Test
    fun `a copy is recorded with the network Auto fetched it on`() {
        assertEquals("AUTO_METERED", cacheTier(AudioQuality.AUTO, metered = true))
        assertEquals("AUTO_UNMETERED", cacheTier(AudioQuality.AUTO, metered = false))
        assertEquals("HIGH", cacheTier(AudioQuality.HIGH, metered = true))
        assertEquals("MAX", cacheTier(AudioQuality.MAX, metered = false))
        assertEquals("LOW", cacheTier(AudioQuality.LOW, metered = false))
    }

    @Test
    fun `a copy fetched under Auto on mobile data is fetched again on Wi-Fi, or under High anywhere`() {
        assertTrue(isStaleQualityTier("AUTO_METERED", AudioQuality.AUTO, metered = false))
        assertTrue(isStaleQualityTier("AUTO_METERED", AudioQuality.HIGH, metered = true))
        assertTrue(isStaleQualityTier("AUTO_METERED", AudioQuality.MAX, metered = true))
        assertFalse("not on mobile data again", isStaleQualityTier("AUTO_METERED", AudioQuality.AUTO, metered = true))
        assertFalse(isStaleQualityTier("AUTO_METERED", AudioQuality.LOW, metered = true))
        assertTrue("a Low copy is still raised by Auto on mobile data", isStaleQualityTier("LOW", AudioQuality.AUTO, metered = true))
    }

    @Test
    fun `a copy fetched under Auto on Wi-Fi is never fetched again under Auto or High, whatever its bitrate`() {
        for (metered in listOf(true, false)) {
            assertFalse(isStaleQualityTier("AUTO_UNMETERED", AudioQuality.AUTO, metered, storedBitrate = 48_000))
            assertFalse(isStaleQualityTier("AUTO_UNMETERED", AudioQuality.HIGH, metered, storedBitrate = 48_000))
        }
        assertTrue(isStaleQualityTier("AUTO_UNMETERED", AudioQuality.MAX, metered = false))
    }

    @Test
    fun `an old Auto copy is fetched again on Wi-Fi only when it is a small stream, once`() {
        // recorded before the network was: its bitrate tells which Auto took
        assertTrue(isStaleQualityTier("AUTO", AudioQuality.AUTO, metered = false, storedBitrate = 55_000))
        assertTrue(isStaleQualityTier("AUTO", AudioQuality.HIGH, metered = true, storedBitrate = 75_000))
        assertFalse(isStaleQualityTier("AUTO", AudioQuality.AUTO, metered = false, storedBitrate = 160_000))
        assertFalse("no bitrate known: left alone", isStaleQualityTier("AUTO", AudioQuality.AUTO, metered = false, storedBitrate = null))
        assertFalse("not on mobile data", isStaleQualityTier("AUTO", AudioQuality.AUTO, metered = true, storedBitrate = 55_000))
        // once fetched again it is recorded with its network, so a song whose best stream is small is not fetched at every play
        assertFalse(isStaleQualityTier(cacheTier(AudioQuality.AUTO, metered = false), AudioQuality.AUTO, metered = false, storedBitrate = 55_000))
    }

    private fun source(path: String) = java.io.File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `both places that record a copy record its network, and both that read one pass theirs`() {
        val service = source("playback/MusicService.kt")
        val downloads = source("playback/DownloadUtil.kt")
        for (code in listOf(service, downloads)) {
            assertFalse("qualityTier = audioQuality.name" in code || "qualityTier = audioQualityNow().name" in code)
            assertTrue(Regex("""qualityTier = cacheTier\(""").containsMatchIn(code))
            assertTrue(Regex("""isStaleQualityTier\(.*metered = metered\(\).*storedBitrate = stored\?\.bitrate""").containsMatchIn(code))
        }
    }
}

