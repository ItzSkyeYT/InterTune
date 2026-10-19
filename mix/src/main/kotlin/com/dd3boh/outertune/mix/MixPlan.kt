/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The five ways from one song into the next. */
enum class Style {
    /** Today's change: the outgoing song to its last sample, then the next. The fallback for everything. */
    GAPLESS,

    /** An equal-power blend from the outgoing song's true end back, with the next song's first sound at its start. */
    BLEND,

    /** A longer blend with the outgoing song filtered away under the incoming one: both ends loud. */
    SMOOTH,

    /** Bar on bar, tempo matched, with the bass swapped on the middle downbeat: both songs' beats and bars trusted. */
    BEAT,

    /** A one-beat change on a bar line: beats and bars trusted, tempos too far apart to blend. */
    CUT,
}

/**
 * Where each output frame takes the outgoing song from, in frames of its held tail.
 *
 * The identity up to [rampStart]; then the speed moves straight from 1 to [speed] over
 * [rampLength] output frames, and holds. The tail's position is the integral of that speed, so it
 * runs on without a jump: a parabola through the ramp and a straight line after it.
 */
class TimeMap(val rampStart: Double, val rampLength: Double, val speed: Double) {

    val isIdentity: Boolean get() = speed == 1.0

    /** The tail frame heard at output frame [out]. */
    fun tailAt(out: Double): Double {
        if (isIdentity || out <= rampStart) return out
        val u = out - rampStart
        if (u <= rampLength) return rampStart + u + (speed - 1.0) * u * u / (2.0 * rampLength)
        return rampStart + rampLength * (1.0 + speed) / 2.0 + (u - rampLength) * speed
    }

    /** The speed at output frame [out]. */
    fun speedAt(out: Double): Double {
        if (isIdentity || out <= rampStart) return 1.0
        val u = out - rampStart
        return if (u >= rampLength) speed else 1.0 + (speed - 1.0) * u / rampLength
    }

    /** The output frame at which tail frame [tail] is heard: the inverse of [tailAt]. */
    fun outAt(tail: Double): Double {
        if (isIdentity || tail <= rampStart) return tail
        val rampTail = rampLength * (1.0 + speed) / 2.0
        val v = tail - rampStart
        if (v >= rampTail) return rampStart + rampLength + (v - rampTail) / speed
        // Solve u + (s - 1) u² / 2R = v for the root that starts at zero.
        val a = (speed - 1.0) / (2.0 * rampLength)
        val u = if (kotlin.math.abs(a) < 1e-15) v else (-1.0 + sqrt(1.0 + 4.0 * a * v)) / (2.0 * a)
        return rampStart + u
    }

    companion object {
        val IDENTITY = TimeMap(Double.MAX_VALUE, 0.0, 1.0)
    }
}

/** How a gain moves across one stretch of output frames. */
enum class Shape {
    /** Straight from one value to the other. */
    LINEAR,

    /** The rising half of an equal-power pair: sin(πx/2). */
    SINE_RISE,

    /** The falling half: cos(πx/2). */
    COSINE_FALL,
}

/** A gain moving from [from] to [to] between output frames [start] and [end]. */
class Ramp(val start: Double, val end: Double, val from: Double, val to: Double, val shape: Shape) {
    fun at(frame: Double): Double {
        val x = ((frame - start) / (end - start)).coerceIn(0.0, 1.0)
        val w = when (shape) {
            Shape.LINEAR -> x
            Shape.SINE_RISE -> sin(PI / 2 * x)
            Shape.COSINE_FALL -> 1.0 - cos(PI / 2 * x)
        }
        return from + (to - from) * w
    }
}

/**
 * A gain over output frames: [initial] before the first ramp, each ramp over its own stretch, and
 * the last value held after it. Ramps must not overlap and must come in order.
 */
class Curve(val initial: Double, val ramps: List<Ramp>) {

    fun at(frame: Double): Double {
        var value = initial
        for (r in ramps) {
            if (frame < r.start) return value
            if (frame < r.end) return r.at(frame)
            value = r.to
        }
        return value
    }

    /** The value from the last ramp on. */
    val last: Double get() = ramps.lastOrNull()?.to ?: initial

    companion object {
        fun constant(value: Double) = Curve(value, emptyList())
    }
}

enum class FilterKind { LOW_PASS, HIGH_PASS }

/**
 * A filter put on one song for a while: engaged at [start] by a crossfade from dry to filtered
 * over [fadeIn] frames, its cutoff at [fromHz] until [sweepFrom], moving evenly in log frequency
 * to [toHz] at [sweepTo], and handed back to the dry signal over [fadeOut] frames ending at [end].
 * Outside [start, end) the song passes untouched, to the bit.
 */
class FilterMove(
    val kind: FilterKind,
    /** 2 or 4: one biquad, or two in a Butterworth pair. */
    val order: Int,
    val start: Long,
    val end: Long,
    val fadeIn: Long,
    val fadeOut: Long,
    val sweepFrom: Double,
    val sweepTo: Double,
    val fromHz: Double,
    val toHz: Double,
) {
    init {
        require(order == 2 || order == 4)
        require(end > start)
    }

    /** The cutoff at output frame [frame]. */
    fun cutoffAt(frame: Double): Double {
        if (sweepTo <= sweepFrom) return if (frame >= sweepTo) toHz else fromHz
        if (frame <= sweepFrom) return fromHz
        if (frame >= sweepTo) return toHz
        val x = (frame - sweepFrom) / (sweepTo - sweepFrom)
        return exp(ln(fromHz) + (ln(toHz) - ln(fromHz)) * x)
    }

    /** How much of the filtered signal is heard at [frame], 0 to 1. */
    fun wetAt(frame: Long): Double {
        if (frame < start || frame >= end) return 0.0
        var w = 1.0
        if (fadeIn > 0 && frame < start + fadeIn) w = min(w, (frame - start + 1).toDouble() / fadeIn)
        if (fadeOut > 0 && frame >= end - fadeOut) w = min(w, (end - frame).toDouble() / fadeOut)
        return w
    }
}

/**
 * A gain shared by both songs across the blend, so their sum stays under full scale: values every
 * [step] output frames from [start], straight between them, 1 everywhere outside.
 */
class Headroom(val start: Long, val step: Int, val values: FloatArray) {

    fun at(frame: Long): Double {
        if (values.isEmpty() || frame < start) return 1.0
        val position = (frame - start).toDouble() / step
        val i = position.toInt()
        if (i >= values.size - 1) return if (i >= values.size) 1.0 else values[i].toDouble()
        val f = position - i
        return values[i] * (1.0 - f) + values[i + 1] * f
    }

    /** The output frame after which it is 1 again. */
    val end: Long get() = start + values.size.toLong() * step

    /** The deepest dip, in dB. */
    val deepestDb: Double get() = values.minOrNull()?.let { 20 * kotlin.math.log10(it.toDouble()) } ?: 0.0

    companion object {
        val NONE = Headroom(0, 1, FloatArray(0))

        /** The sum of both songs, as a share of full scale, that the headroom holds the mix under. */
        const val CEILING = 0.95

        /** Knots every this many output frames. */
        const val STEP = 512

        /**
         * The headroom for [loads], the worst case of both songs' peaks at their gains on each knot
         * from [start]: on each knot [CEILING] over its load where the load is higher, then the
         * lowest of 400 ms around it and a 200 ms average of that, so it never rises above what any
         * frame needs and never moves faster than a fade.
         */
        fun solve(start: Long, loads: DoubleArray, sampleRate: Int): Headroom {
            val n = loads.size
            val needed = DoubleArray(n) { if (loads[it] <= CEILING) 1.0 else CEILING / loads[it] }
            if (needed.all { it >= 1.0 }) return NONE
            val radius = max(1, (0.2 * sampleRate / STEP).toInt())
            val lowest = DoubleArray(n) { i ->
                var m = 1.0
                for (j in max(0, i - radius)..min(n - 1, i + radius)) m = min(m, needed[j])
                m
            }
            val half = max(1, radius / 2)
            val values = FloatArray(n) { i ->
                var sum = 0.0
                for (j in i - half..i + half) sum += if (j in 0 until n) lowest[j] else 1.0
                (sum / (2 * half + 1)).toFloat()
            }
            return Headroom(start, STEP, values)
        }
    }
}

/**
 * One transition, worked out to the frame. Every time here is an output frame counted from the
 * first frame of the held tail; output and tail frames are the same until the outgoing song's
 * tempo starts to move. The incoming song's frame n is output at [bStart] + n.
 */
class MixPlan(
    val style: Style,
    /** Why this style, in a line for the logs. */
    val reason: String,
    val sampleRate: Int,
    val channels: Int,
    /** How long the held tail is. */
    val tailFrames: Long,
    /** The output frame of the incoming song's first frame, where the drain ends. */
    val bStart: Long,
    val aMap: TimeMap,
    /** The first output frame where the outgoing song differs from its raw held tail. */
    val aFirstProcessed: Long,
    /** From this output frame on, nothing of the outgoing song is heard. */
    val aGone: Long,
    val overlapStart: Double,
    val overlapEnd: Double,
    val gainA: Curve,
    val gainB: Curve,
    val filtersA: List<FilterMove>,
    val filtersB: List<FilterMove>,
    /** The headroom worked out from the analysis; the renderer refines it from the outgoing song's own rendered peaks. */
    val headroom: Headroom,
    /**
     * The incoming song's share of the load on each knot of [headroom]: its peak, 20% higher since
     * its live frames are not the ones measured, at its gain and attenuation.
     */
    val incomingLoad: DoubleArray,
    /** Each song's loudness normalisation, applied to its samples. */
    val attenuationA: Double,
    val attenuationB: Double,
    /** From this output frame on, the incoming song passes untouched but for its own attenuation. */
    val shapedUntil: Long,
    /** The incoming song's frames over which its live onsets are held against its analysis. */
    val lockFrom: Long,
    val lockTo: Long,
    /** The outgoing song's speed through the overlap. */
    val speed: Double,
    /** The overlap of a beat mix in the incoming song's own bars. */
    val bars: Int,
    /** The overlap of a beat mix in beats of the grids, which both songs share: four or eight to the bar. */
    val beats: Int = 0,
    /** The tail frames of the outgoing song's beats the stretch must land exactly where the time map puts them. */
    val anchors: DoubleArray = DoubleArray(0),
) {
    val isGapless: Boolean get() = style == Style.GAPLESS

    /** The first output frame where anything differs from today's gapless change. */
    val firstChange: Long get() = min(aFirstProcessed, bStart)

    /** The overlap's length in seconds. */
    val seconds: Double get() = (overlapEnd - overlapStart) / sampleRate

    /** The outgoing song's music never heard: from where it is gone to the end of its tail, in its own frames. */
    fun unheardTailFrames(trueEnd: Long): Long = (trueEnd - aMap.tailAt(aGone.toDouble()).toLong()).coerceAtLeast(0)

    fun describe(): String = when (style) {
        Style.GAPLESS -> "GAPLESS: $reason"
        Style.BEAT -> "BEAT $bars bars %.1f s, A at %.4f: %s".format(seconds, speed, reason)
        else -> "$style %.1f s: %s".format(seconds, reason)
    }

    companion object {
        /** Today's change, sample for sample: the whole tail, then the next song. */
        fun gapless(sampleRate: Int, channels: Int, tailFrames: Long, attenuationA: Double, attenuationB: Double, reason: String) = MixPlan(
            style = Style.GAPLESS,
            reason = reason,
            sampleRate = sampleRate,
            channels = channels,
            tailFrames = tailFrames,
            bStart = tailFrames,
            aMap = TimeMap.IDENTITY,
            aFirstProcessed = tailFrames,
            aGone = tailFrames,
            overlapStart = tailFrames.toDouble(),
            overlapEnd = tailFrames.toDouble(),
            gainA = Curve.constant(1.0),
            gainB = Curve.constant(1.0),
            filtersA = emptyList(),
            filtersB = emptyList(),
            headroom = Headroom.NONE,
            incomingLoad = DoubleArray(0),
            attenuationA = attenuationA,
            attenuationB = attenuationB,
            shapedUntil = tailFrames,
            lockFrom = 0,
            lockTo = 0,
            speed = 1.0,
            bars = 0,
        )
    }
}
