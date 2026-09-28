/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.AudioQuality
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
}
