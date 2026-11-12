/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.playback.Normalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * Normalisation moved off the volume and onto each song's samples while AutoMix's stage is in the
 * chain: the same product for every factor and every volume, the boost above unity included, and
 * exactly today's volume while the stage is out of it.
 */
class AutomixVolumeSplitTest {

    private val factors = listOf(0.05f, 0.2238f, 0.4f, 0.7f, 0.95f, 1f)
    private val volumes = listOf(0f, 0.3f, 0.7f, 1f, 1.2f, 1.5f, 2f)

    @Test
    fun `with the stage out of the chain the volume is today's, to the bit`() {
        for (n in factors) for (v in volumes) for (sleep in listOf(1f, 0.4f)) {
            val today = min(n * v, 1f) * sleep * 0.8f * 0.9f
            assertEquals(today, AutomixVolume.playerVolume(n, v, sleep, 0.8f, 0.9f, stageNormalises = false))
        }
    }

    @Test
    fun `with the stage in the chain the product is the same for every factor and volume`() {
        for (n in factors) for (v in volumes) {
            val gain = AutomixVolume.processorGain(n, v)
            val today = AutomixVolume.playerVolume(n, v, 1f, 1f, 1f, stageNormalises = false) * gain
            val split = AutomixVolume.playerVolume(n, v, 1f, 1f, 1f, stageNormalises = true) * AutomixVolume.songAttenuation(n, v) * gain
            assertEquals("n $n, v $v", today.toDouble(), split, 1e-6)
        }
    }

    @Test
    fun `the stage never boosts a song, and the volume never carries normalisation with it`() {
        for (n in factors) for (v in volumes) {
            assertTrue(AutomixVolume.songAttenuation(n, v) <= 1.0)
            assertEquals(min(v, 1f), AutomixVolume.playerVolume(n, v, 1f, 1f, 1f, stageNormalises = true))
        }
    }

    @Test
    fun `a song's factor is the player's rule, out of MusicService unchanged`() {
        // Pulled down to 3 dB under YouTube's -14 LKFS target, never up.
        assertEquals(0.398f, Normalization.factor(5.0, isLocal = false, normalizeAudio = true), 0.001f)
        assertEquals(1f, Normalization.factor(-8.0, isLocal = false, normalizeAudio = true))
        // A local file and normalisation off are left alone; an unknown or impossible loudness is taken as loud.
        assertEquals(1f, Normalization.factor(5.0, isLocal = true, normalizeAudio = true))
        assertEquals(1f, Normalization.factor(5.0, isLocal = false, normalizeAudio = false))
        assertEquals(0.2239f, Normalization.factor(null, isLocal = false, normalizeAudio = true), 0.0005f)
        assertEquals(Normalization.factor(null, false, true), Normalization.factor(200.0, false, true))
        assertTrue(Normalization.isFinal(5.0, isLocal = false, normalizeAudio = true))
        assertFalse("its loudness will come with its stream", Normalization.isFinal(null, isLocal = false, normalizeAudio = true))
        assertTrue(Normalization.isFinal(null, isLocal = true, normalizeAudio = true))
    }
}
