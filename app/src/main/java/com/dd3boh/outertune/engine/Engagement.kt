/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason

/**
 * How much one listen says the listener wanted the song, from 0 to 1.
 *
 * Every system read weights a play by how much of it was heard rather than by the fact of it:
 * YouTube trains its ranker on watch time because click ranking "promotes deceptive videos that
 * the user does not complete", Hu, Koren and Volinsky zero anything under half consumed, Spotify
 * counts a stream at thirty seconds. This is that idea reduced to one number per listen.
 *
 * Nothing under [minPositiveMs] is a positive at all, whatever the ratio: a quarter of all plays
 * end inside five seconds, and those are the player settling or a wrong tap. A natural end is a
 * full positive even when the ratio is low, because the ratio is against a duration the app
 * sometimes never learned, and the player's own "ended" is exact.
 */
object Engagement {

    /** Below this, a stop is not a positive: nobody has heard enough to have an opinion. */
    const val MIN_POSITIVE_MS = 30_000L

    /** Where the linear ramp reaches full weight. Deezer treats 80% as a full listen. */
    const val FULL_AT_RATIO = 0.8f

    enum class Curve {
        /** min(1, ratio / 0.8) once past the floor. Smooth, so a small change in listening is a small change in weight. Shipped default. */
        LINEAR,
        /** Flow's milestones: 0.35 from 15%, 0.70 from 50%, 1.0 from 90%. Kept selectable for the replay test. */
        MILESTONES,
    }

    fun weight(
        playedMs: Long,
        ratio: Float,
        endReason: Int,
        curve: Curve = Curve.LINEAR,
        minPositiveMs: Long = MIN_POSITIVE_MS,
    ): Float {
        if (endReason == EndReason.ENDED) return 1f
        if (playedMs < minPositiveMs) return 0f
        if (ratio < 0f) return 0.5f   // duration never learned: heard for a while, that is all we know
        return when (curve) {
            Curve.LINEAR -> (ratio / FULL_AT_RATIO).coerceIn(0f, 1f)
            Curve.MILESTONES -> when {
                ratio >= 0.90f -> 1.0f
                ratio >= 0.50f -> 0.70f
                ratio >= 0.15f -> 0.35f
                else -> 0f
            }
        }
    }

    /**
     * Whether a listen is a skip worth counting against the song: cut short by the listener, past
     * the floor, before the last tenth. Ended, replaced and stopped are not skips, and leaving in
     * the fade-out is not a verdict on the song.
     */
    fun isMeaningfulSkip(playedMs: Long, ratio: Float, endReason: Int, minPositiveMs: Long = MIN_POSITIVE_MS): Boolean =
        endReason == EndReason.SKIPPED && playedMs >= minPositiveMs && (ratio < 0f || ratio < 0.9f)
}
