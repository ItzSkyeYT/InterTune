/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlin.math.min
import kotlin.math.pow

/**
 * A song's loudness normalisation: the factor its sound is multiplied by, never above 1.
 *
 * Out of MusicService so that AutoMix can work out the factor of the song that comes next before
 * it plays, by the very same rule: the two songs of a blend each keep their own (AutomixVolume).
 */
object Normalization {

    /**
     * The factor for a song whose stored loudness is [loudnessDb] (null when none is stored),
     * whether it is a local file, and whether normalisation is on.
     */
    fun factor(loudnessDb: Double?, isLocal: Boolean, normalizeAudio: Boolean): Float {
        // Reject impossible values rather than trusting the column. The observed range over 3178
        // real rows is -16.5 to +12.6, so this only ever catches corruption.
        val loudness = loudnessDb
            ?.takeIf { it.isFinite() && it in -60.0..40.0 }
            ?.toFloat()

        return when {
            // Off is the only case allowed to leave the signal at unity.
            !normalizeAudio -> 1f

            // A local file has no YouTube loudness and never will. The repair scan excludes local
            // rows deliberately, and nothing else writes the column for them, so falling through
            // to the presumed value would attenuate every local file by 13 dB permanently with no
            // way to undo it from inside the app. Levelling local files properly needs ReplayGain
            // or R128 tags read at scan time, which does not exist yet, so until then they are
            // left alone.
            isLocal -> 1f

            // loudnessDb is how far above YouTube's -14 LKFS target this track sits. Everything
            // above the reference is pulled down to it; everything below is left alone, because
            // setVolume cannot exceed 1 and so cannot boost.
            //
            // The reference is set low on purpose. Normalising to YouTube's own target left nearly
            // half the library untouched and still varying; going lower trades absolute loudness,
            // which the device volume can recover, for consistency, which it cannot.
            loudness != null ->
                min(10f.pow(-(loudness - MusicService.NORMALIZATION_REFERENCE_DB) / 20), 1f)

            // Loudness unknown. This used to be 1f, meaning no attenuation, which is the LOUDEST
            // possible answer to "I do not know". Since every track that does have a value is
            // pulled down 6 to 10 dB, an unknown one stood out by up to 16 dB. That is the entire
            // "some songs are much louder" complaint.
            //
            // Assume it is loud instead. Being wrong quietly is recoverable with the volume
            // rocker; being wrong loudly is not.
            else -> min(
                10f.pow(-(MusicService.UNKNOWN_LOUDNESS_DB - MusicService.NORMALIZATION_REFERENCE_DB) / 20),
                1f
            )
        }
    }

    /** Whether [factor] is the song's own for good, not the guess made while its loudness is unknown. */
    fun isFinal(loudnessDb: Double?, isLocal: Boolean, normalizeAudio: Boolean): Boolean =
        !normalizeAudio || isLocal || (loudnessDb != null && loudnessDb.isFinite() && loudnessDb in -60.0..40.0)
}
