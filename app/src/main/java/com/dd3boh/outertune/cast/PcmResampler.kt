/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Changes the rate of 16-bit audio, for a renderer that does not list the rate a song decodes at
 * (Opus, always 48 kHz, to a speaker that takes only 44.1).
 *
 * Each output frame is the input around its exact place, weighted by a sinc cut off at half the
 * lower of the two rates and shaped by a Kaiser window: 32 zero crossings on each side at the
 * lower rate, beta 9, so about 90 dB down past the band. Between 44.1 and 48 kHz that keeps the
 * sound flat to 20 kHz and puts anything folded back above it. Linear interpolation (Media3's
 * Sonic) is 3 dB down at 15 kHz. The weights are worked out once for each place an output frame
 * can fall between two input frames ([phases]); 147 or 160 places between 44.1 and 48 kHz.
 *
 * Places are global: output frame n sits at input frame n x [inRate] / [outRate], worked out in
 * whole numbers. A resampling started anywhere ([start]) therefore gives the very frames a
 * resampling from the start gives, given the same input around them, which is what lets a range
 * request start in the middle of a song. Input before the first frame given, and after [finish],
 * is silence.
 */
class PcmResampler(val inRate: Int, val outRate: Int, val channels: Int) {
    private val up: Int
    private val down: Int

    /** How many places between two input frames have weights of their own. */
    val phases: Int

    /** Input frames used on each side of an output frame's place. */
    val half: Int
    private val taps: Int
    private val weights: FloatArray

    /** Input kept for the frames still to come, interleaved, starting at global frame [inputStart]. */
    private var input = FloatArray(INITIAL_FRAMES * channels)
    private var inputStart = 0L
    private var inputFrames = 0
    private var firstFrame = 0L
    private var endFrame = Long.MAX_VALUE

    /** The next output frame, global. */
    var nextOutput = 0L
        private set

    init {
        require(inRate > 0 && outRate > 0 && channels > 0) { "Bad rates or channels" }
        val divisor = gcd(inRate, outRate)
        up = outRate / divisor
        down = inRate / divisor
        phases = min(up, MAX_PHASES)
        val scale = min(1.0, outRate.toDouble() / inRate)
        half = ceil(ZERO_CROSSINGS / scale).toInt()
        taps = 2 * half
        val cutoff = 0.5 * scale
        val windowNorm = besselI0(BETA)
        weights = FloatArray(phases * taps)
        val row = DoubleArray(taps)
        for (phase in 0 until phases) {
            val fraction = phase.toDouble() / phases
            var sum = 0.0
            for (j in 0 until taps) {
                // Input frame floor(place) + k, at distance place - frame = fraction - k.
                val distance = fraction - (j - half + 1)
                val x = 2 * cutoff * distance
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = distance / half
                val window = if (r <= -1.0 || r >= 1.0) 0.0 else besselI0(BETA * sqrt(1 - r * r)) / windowNorm
                row[j] = sinc * window
                sum += row[j]
            }
            // Each place's weights add up to one, so silence stays silence and a constant stays put.
            for (j in 0 until taps) weights[phase * taps + j] = (row[j] / sum).toFloat()
        }
    }

    /**
     * Begins again: output frame [firstOutput] is the next one given, and the first input frame
     * pushed after this is global input frame [firstInput]. Anything held is dropped.
     */
    fun start(firstInput: Long, firstOutput: Long) {
        require(firstOutput >= 0) { "Output starts at 0 or later" }
        firstFrame = firstInput
        inputStart = firstInput
        inputFrames = 0
        endFrame = Long.MAX_VALUE
        nextOutput = firstOutput
    }

    /** Adds [frames] frames of [samples] from sample [offset], interleaved, after the ones before. */
    fun push(samples: ShortArray, offset: Int, frames: Int) {
        check(endFrame == Long.MAX_VALUE) { "Pushed after the end" }
        dropUsed()
        val needed = (inputFrames + frames) * channels
        if (needed > input.size) input = input.copyOf(maxOf(needed, input.size * 2))
        var at = inputFrames * channels
        for (index in offset until offset + frames * channels) input[at++] = samples[index].toFloat()
        inputFrames += frames
    }

    /** No more input: what follows the last frame is silence, and the frames it reaches can be given. */
    fun finish() {
        if (endFrame == Long.MAX_VALUE) endFrame = inputStart + inputFrames
    }

    /**
     * Writes up to [maxFrames] output frames into [out] from sample [offset], interleaved, as many as
     * the input so far allows; 0 when more input is needed, or, after [finish], when the output has
     * gone past everything the input can reach.
     */
    fun pull(out: ShortArray, offset: Int, maxFrames: Int): Int {
        var produced = 0
        val available = inputStart + inputFrames
        while (produced < maxFrames) {
            val place = nextOutput * down
            val base = place / up
            val first = base - half + 1
            if (first >= endFrame) break
            if (base + half >= available && endFrame == Long.MAX_VALUE) break
            val phase = ((place % up) * phases / up).toInt()
            val row = phase * taps
            // The weights that fall on real input; the rest fall on silence.
            val from = maxOf(first, firstFrame, inputStart)
            val until = minOf(first + taps, endFrame, available)
            val at = offset + produced * channels
            for (channel in 0 until channels) {
                var sum = 0f
                var frame = from
                var weight = row + (from - first).toInt()
                var sample = ((from - inputStart) * channels + channel).toInt()
                while (frame < until) {
                    sum += weights[weight] * input[sample]
                    frame++
                    weight++
                    sample += channels
                }
                out[at + channel] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            produced++
            nextOutput++
        }
        return produced
    }

    /** Drops input no output frame still to come can reach. */
    private fun dropUsed() {
        val needed = nextOutput * down / up - half + 1
        val drop = minOf(needed - inputStart, inputFrames.toLong()).toInt()
        if (drop <= 0) return
        System.arraycopy(input, drop * channels, input, 0, (inputFrames - drop) * channels)
        inputStart += drop
        inputFrames -= drop
    }

    private companion object {
        const val ZERO_CROSSINGS = 32
        const val BETA = 9.0
        const val MAX_PHASES = 1024
        const val INITIAL_FRAMES = 8192

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** The modified Bessel function of the first kind, order 0, by its series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val quarter = x * x / 4
            var k = 1
            while (term > sum * 1e-12) {
                term *= quarter / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
