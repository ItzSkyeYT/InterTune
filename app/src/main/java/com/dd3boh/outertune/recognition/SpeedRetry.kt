/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hearing a window again as if it played at another speed, for songs in reels and short videos.
 *
 * Those often play a song sped up or slowed down, pitch and all, and Shazam only names such a copy
 * when its catalogue holds an upload at about that speed. Measured on 18 Oct 2026 with three songs
 * (experiments/recognition/speed): every one named as it is; sped up by a quarter, one named, one
 * taken for a DJ remix, one not at all; the same windows put back to their own speed, all three
 * named, at the same second as the original. Put back from a third faster by a quarter, 8% off,
 * none of the three was named right, so a guess has to land within a few percent.
 *
 * So a Listen once window that Shazam knew nothing of is heard again at two of [RATES], two others
 * for the next window, rather than all of them at once: Shazam answers 429 after about a dozen
 * requests in quick succession, and a listen gives up after three windows anyway. The Instagram
 * reel he asked about (I'm Not Alone, slowed to 0.8) was named only from 0.8: from 0.85 and 0.75
 * Shazam named the song's radio edit at a skew of 0.037 and 0.034, from everything else nothing.
 */
internal object SpeedRetry {

    /**
     * How fast the room is guessed to be playing, against the recording, two to a window: a
     * quarter faster and a fifth slower first, the usual sped-up and slowed edits, then
     * nightcore's third faster and a gentler slowing, then the rest. A seventh faster comes late:
     * Shazam often names those as they are, from an upload at about that speed.
     */
    val RATES = doubleArrayOf(1.25, 0.8, 1.35, 0.85, 1.15, 0.75)

    /** The two rates to try after the [miss]th window in a listen that named nothing, counted from 0. */
    fun ratesFor(miss: Int): List<Double> = listOf(RATES[(2 * miss).mod(RATES.size)], RATES[(2 * miss + 1).mod(RATES.size)])

    /**
     * A guess a few percent off can still name the right song, at a skew that says by how much
     * (from 0.85 the reel was the radio edit at -0.037): the rate one step closer to the room's
     * own speed, to ask once more, or null when the match fits already or is too far off to trust.
     * A negative skew is a sample slower than the recording, put back from a lower rate.
     */
    fun closer(rate: Double, track: Recognised): Double? =
        track.timeSkew.takeIf { abs(it) >= MAX_SKEW && abs(it) <= NEAR_MISS }?.let { rate * (1 + it) }

    /**
     * Whether a match on a window put back from a guessed speed can be taken. Put back to its own
     * speed a window matches with both of Shazam's skews under 0.0003; a guess a few percent off
     * matched other things, a cover, a sped-up upload, once a different song altogether, with a
     * skew of 0.015 to 0.05. So a match that needed stretching to fit was a wrong guess. Not every
     * one: 6% off, Adele's Hello came back as a cover of it at a skew of 0.004, which passes. The
     * person still sees the answer before anything is added.
     */
    fun fits(track: Recognised): Boolean =
        abs(track.timeSkew) < MAX_SKEW && abs(track.frequencySkew) < MAX_SKEW

    private const val MAX_SKEW = 0.01

    /** Past this a match is not a near miss of the room's song but something else. */
    private const val NEAR_MISS = 0.08

    /**
     * [samples], heard at [rate] times the recording's speed, put back to the recording's own
     * speed and pitch, at most as long as [samples].
     *
     * Linear interpolation, which would be a poor resampler from the microphone's rate (it folds
     * whatever is above 8 kHz into the signature's bands, see MicrophoneListener), but these
     * samples are already 16 kHz: slowing them down folds nothing, and speeding them up by at most
     * a fifth moves only what lies above 6.8 kHz past the top, which folds back to above 6.5 kHz,
     * clear of the signature's highest band, which stops at 5.5 kHz.
     */
    fun putBack(samples: ShortArray, rate: Double): ShortArray {
        require(rate > 0.0)
        if (samples.isEmpty()) return samples
        val length = minOf(samples.size, (samples.size * rate).toInt())
        val last = samples.size - 1
        return ShortArray(length) { j ->
            val at = j / rate
            val i = at.toInt().coerceAtMost(last)
            val next = minOf(i + 1, last)
            val f = at - i
            (samples[i] + (samples[next] - samples[i]) * f).roundToInt().toShort()
        }
    }
}
