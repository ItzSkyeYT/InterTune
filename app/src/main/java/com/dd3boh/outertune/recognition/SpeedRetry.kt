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
 * So a Listen once window that Shazam knew nothing of is heard again at one of [RATES], a different
 * one for each window, rather than all of them at once: Shazam answers 429 after about a dozen
 * requests in quick succession, and a listen gives up after three windows anyway.
 */
internal object SpeedRetry {

    /**
     * How fast the room is guessed to be playing, against the recording: a quarter faster first,
     * the usual sped-up edit, then the usual slowed one, then nightcore's third faster. A seventh
     * faster comes last: Shazam often names those as they are, from an upload at about that speed.
     */
    val RATES = doubleArrayOf(1.25, 0.85, 1.35, 1.15)

    /** The rate to try after the [miss]th window in a listen that named nothing, counted from 0. */
    fun rateFor(miss: Int): Double = RATES[miss.mod(RATES.size)]

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
