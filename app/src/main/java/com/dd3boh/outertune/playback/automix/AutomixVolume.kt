/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import kotlin.math.min

/**
 * Where loudness normalisation is applied while AutoMix's stage is in the chain.
 *
 * Normally it rides player.volume with everything else at or below unity, and only gain above
 * unity goes to GainAudioProcessor (see the volume combine in MusicService). In a blend two songs
 * play at once, each with its own normalisation, which one volume cannot give: so while the stage
 * is in the chain it scales each song's samples by the song's own factor, from its first frame,
 * and the volume leaves normalisation out. The product is today's for every factor and every
 * volume, the boost above unity included, and AutomixVolumeSplitTest holds it to that.
 *
 * With n a song's factor (at most 1) and v the player volume (above 1 when boosted):
 * - player.volume is min(v, 1), times the sleep timer's fade, proximity and the fade between songs;
 * - the stage scales the song by n when v is at most 1, and by min(n v, 1) above it;
 * - GainAudioProcessor applies n v above 1, as it always has.
 */
object AutomixVolume {

    /** What goes on player.volume. */
    fun playerVolume(normalize: Float, volume: Float, sleepFade: Float, proximity: Float, transitionFade: Float, stageNormalises: Boolean): Float {
        val base = if (stageNormalises) min(volume, 1f) else min(normalize * volume, 1f)
        return base * sleepFade * proximity * transitionFade
    }

    /** What the stage scales a song's samples by, for a song whose factor is [normalize]. */
    fun songAttenuation(normalize: Float, volume: Float): Double =
        if (volume <= 1f) normalize.toDouble() else min(normalize.toDouble() * volume, 1.0)

    /** What GainAudioProcessor applies on top, unchanged by AutoMix: the part of n v above unity. */
    fun processorGain(normalize: Float, volume: Float): Float = maxOf(normalize * volume, 1f)
}
