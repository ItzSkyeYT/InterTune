/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order section from Robert Bristow-Johnson's cookbook, in Direct Form I, with a state
 * per channel.
 *
 * Coefficients and state are kept in double: a 20 Hz pole at 48 kHz sits so close to the unit
 * circle that single precision turns it into noise. Direct Form I because it keeps past inputs and
 * outputs as its state, which lets the coefficients change under a running signal without the
 * transients other forms give, and a state that has decayed under 1e-20 is set to zero so a
 * filter left on silence does not slow the processor down with denormals.
 */
class Biquad(private val channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    fun set(kind: FilterKind, hz: Double, q: Double, sampleRate: Int) {
        val c = coefficients(kind, hz, q, sampleRate)
        set(c)
    }

    /** Coefficients as [coefficients] gives them: b0, b1, b2, a1, a2, already divided by a0. */
    fun set(c: DoubleArray) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]
    }

    fun process(x: Double, channel: Int): Double {
        val y = b0 * x + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = x
        y2[channel] = y1[channel]
        y1[channel] = if (abs(y) < 1e-20) 0.0 else y
        return y1[channel]
    }

    fun reset() {
        for (c in 0 until channels) {
            x1[c] = 0.0; x2[c] = 0.0; y1[c] = 0.0; y2[c] = 0.0
        }
    }

    companion object {
        /** The cookbook's coefficients, b0, b1, b2, a1, a2, each divided by a0. */
        fun coefficients(kind: FilterKind, hz: Double, q: Double, sampleRate: Int, into: DoubleArray = DoubleArray(5)): DoubleArray {
            val w0 = 2.0 * PI * hz / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            when (kind) {
                FilterKind.LOW_PASS -> {
                    into[0] = (1.0 - c) / 2.0 / a0
                    into[1] = (1.0 - c) / a0
                    into[2] = (1.0 - c) / 2.0 / a0
                }
                FilterKind.HIGH_PASS -> {
                    into[0] = (1.0 + c) / 2.0 / a0
                    into[1] = -(1.0 + c) / a0
                    into[2] = (1.0 + c) / 2.0 / a0
                }
            }
            into[3] = -2.0 * c / a0
            into[4] = (1.0 - alpha) / a0
            return into
        }

        /** The magnitude of the cookbook filter at [atHz], from its transfer function, for the tests. */
        fun magnitude(kind: FilterKind, hz: Double, q: Double, sampleRate: Int, atHz: Double): Double {
            val w0 = 2.0 * PI * hz / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val (b0, b1, b2) = when (kind) {
                FilterKind.LOW_PASS -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2)
                FilterKind.HIGH_PASS -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2)
            }
            val a0 = 1 + alpha
            val a1 = -2 * c
            val a2 = 1 - alpha
            val w = 2.0 * PI * atHz / sampleRate
            // |H(e^jw)| = |b0 + b1 e^-jw + b2 e^-2jw| / |a0 + a1 e^-jw + a2 e^-2jw|
            fun mag(p0: Double, p1: Double, p2: Double): Double {
                val re = p0 + p1 * cos(w) + p2 * cos(2 * w)
                val im = -p1 * sin(w) - p2 * sin(2 * w)
                return sqrt(re * re + im * im)
            }
            return mag(b0, b1, b2) / mag(a0, a1, a2)
        }

        /** The quality of each section: one Butterworth section, or the two of a 4th-order Butterworth. */
        fun qualities(order: Int): DoubleArray =
            if (order == 4) doubleArrayOf(0.541_196_100_146_197, 1.306_562_964_876_376_5) else doubleArrayOf(0.707_106_781_186_547_6)
    }
}

/**
 * A [FilterMove] carried out on a stream addressed by absolute output frame.
 *
 * The cutoff is worked out on every 32nd absolute frame (0.7 ms at 48 kHz), moving evenly in log
 * frequency, and the coefficients are drawn straight between those points, frame by frame. A
 * filter whose coefficients jump, however little, adds a zipper of steps at the update rate; drawn
 * straight they never jump, and the sweep is as smooth as one worked out on every frame at a 32nd
 * of the cost. Because the grid is absolute and the state runs from the move's first frame, the
 * result is the same whatever blocks the stream comes in, and outside the move's frames nothing
 * is touched, to the bit.
 */
class MovingFilter(
    val move: FilterMove,
    private val channels: Int,
    private val sampleRate: Int,
    private val updateFrames: Int = UPDATE_FRAMES,
) {

    private val qualities = Biquad.qualities(move.order)
    private val sections = Array(qualities.size) { Biquad(channels) }
    private val from = Array(qualities.size) { DoubleArray(5) }
    private val to = Array(qualities.size) { DoubleArray(5) }
    private val now = DoubleArray(5)
    private var loadedAt = Long.MIN_VALUE
    private var next = move.start

    /** Whether [frame] is one the filter touches. */
    fun touches(frame: Long): Boolean = frame >= move.start && frame < move.end

    /**
     * Filters the frame at absolute output frame [frame], one sample per channel in [samples].
     * Frames must come in order with none skipped inside the move.
     */
    fun process(frame: Long, samples: DoubleArray) {
        if (frame < move.start || frame >= move.end) return
        check(frame == next) { "filter fed frame $frame, expected $next" }
        next = frame + 1
        val offset = Math.floorMod(frame, updateFrames.toLong())
        val grid = frame - offset
        if (grid != loadedAt) {
            for (s in sections.indices) {
                Biquad.coefficients(move.kind, cutoff(grid), qualities[s], sampleRate, from[s])
                Biquad.coefficients(move.kind, cutoff(grid + updateFrames), qualities[s], sampleRate, to[s])
            }
            loadedAt = grid
        }
        val x = offset.toDouble() / updateFrames
        for (s in sections.indices) {
            val a = from[s]
            val b = to[s]
            for (i in 0 until 5) now[i] = a[i] + (b[i] - a[i]) * x
            sections[s].set(now)
        }
        val wet = move.wetAt(frame)
        for (c in 0 until channels) {
            val dry = samples[c]
            var y = dry
            for (s in sections) y = s.process(y, c)
            samples[c] = if (wet >= 1.0) y else dry * (1.0 - wet) + y * wet
        }
    }

    private fun cutoff(frame: Long): Double = move.cutoffAt(frame.toDouble()).coerceIn(10.0, 0.45 * sampleRate)

    companion object {
        const val UPDATE_FRAMES = 32
    }
}
