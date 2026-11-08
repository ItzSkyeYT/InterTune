/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A change of sample rate by windowed sinc, polyphase: the filter torchaudio.functional.resample
 * makes with the settings of the PC's tool (experiments/features/automix/parts/tools/
 * split_windows.py: a Kaiser window of beta 14.77 over 64 zero crossings, roll-off 0.9476,
 * librosa's "kaiser_best"), so the phone's parts and the PC's come through the same filter.
 *
 * torchaudio places each tap in float32, and that rounding is most of what sets its filter apart
 * from the exact one: on music the exact taps land 107 dB from its output. So each tap's place is
 * worked out here in float32, step by step as torchaudio does, and the window and the sinc in
 * float64 from there: 127 dB from its output, where its own taps summed in float64 reach 130 to
 * 133 dB (the rest is its float32 sums, and its float32 square root and sine, which come from MKL).
 *
 * No delay: output frame j is the input at time j / [to], input frame i is at i / [from], so a
 * sample stays where it was. The input is silence outside its length, and n frames give
 * ceil(n * to / from), as torchaudio's do. Up to 0.9476 of the lower rate's half (20.9 kHz at
 * 44.1 kHz) passes; what would fold back is held more than 90 dB down (ResamplerTest). Rates go by
 * their greatest common divisor: 48 kHz to 44.1 kHz is 160 frames to 147, with 147 sets of about
 * 147 taps.
 */
class Resampler(val from: Int, val to: Int) {

    /** Input frames and output frames per block: 160 and 147 from 48 to 44.1 kHz. */
    val down: Int
    val up: Int

    /** The rates are the same: frames go through as they are. */
    val identity: Boolean = from == to

    /** For each of the [up] phases, where its taps start in the input, from the block's first frame. */
    private val first: IntArray

    /** Where each phase's taps are in [taps]; phase p's are start[p] until start[p + 1]. */
    private val start: IntArray
    private val taps: DoubleArray

    /** The most taps any phase has: how much input one output frame reads. */
    val span: Int

    init {
        require(from > 0 && to > 0) { "rates must be positive: $from, $to" }
        val g = gcd(from, to)
        down = from / g
        up = to / g
        if (identity) {
            first = IntArray(1)
            start = intArrayOf(0, 1)
            taps = doubleArrayOf(1.0)
            span = 1
        } else {
            val base = min(down, up) * ROLLOFF
            val width = ceil(ZEROS * down / base).toInt()
            require(up.toLong() * (2L * width + down) <= MAX_TAPS) { "$from Hz to $to Hz takes too large a filter" }
            val scale = base / down
            val i0Beta = besselI0(BETA)
            // torchaudio's float32 steps: idx = arange(-width, width + down) / down, then
            // t = (arange(0, -up, -1) / up + idx) * base, each rounded to float32 on the way.
            val base32 = base.toFloat()
            val zeros32 = ZEROS.toFloat()
            first = IntArray(up)
            start = IntArray(up + 1)
            val all = DoubleArray(up * (2 * width + down))
            var n = 0
            var widest = 0
            for (p in 0 until up) {
                start[p] = n
                val q = (-p).toFloat() / up.toFloat()
                var firstK = -1
                for (k in 0 until 2 * width + down) {
                    val t = (q + (k - width).toFloat() / down.toFloat()) * base32
                    // At 64 zero crossings and past it the window is closed: torchaudio's taps there
                    // are about 1e-13, left out.
                    if (abs(t) >= zeros32) {
                        if (firstK >= 0) break
                        continue
                    }
                    if (firstK < 0) firstK = k
                    val u = t / zeros32
                    val inside = 1f - u * u
                    val window = besselI0(BETA * sqrt(inside.toDouble())) / i0Beta
                    val angle = (t * PI_32).toDouble()
                    val sinc = if (t == 0f) 1.0 else sin(angle) / angle
                    all[n++] = sinc * window * scale
                }
                first[p] = firstK - width
                widest = maxOf(widest, n - start[p])
            }
            start[up] = n
            taps = all.copyOf(n)
            span = widest
        }
    }

    /** Output frames from [frames] input frames: ceil(frames * to / from), as torchaudio's. */
    fun outputFrames(frames: Long): Long = (frames * up + down - 1) / down

    /** The first input frame output frame [j] reads. */
    fun firstInput(j: Long): Long = Math.floorDiv(j, up.toLong()) * down + first[Math.floorMod(j, up.toLong()).toInt()]

    /** One past the last input frame output frame [j] reads. */
    fun endInput(j: Long): Long {
        val p = Math.floorMod(j, up.toLong()).toInt()
        return Math.floorDiv(j, up.toLong()) * down + first[p] + (start[p + 1] - start[p])
    }

    /**
     * Output frames [from] until [from] + [count] of one channel into [out] at [at], from [input],
     * which holds that channel's input frames [inputStart] until [inputStart] + [inputCount].
     * Anything outside those is read as silence, so they must cover every frame of the input these
     * outputs read ([firstInput] of the first to [endInput] of the last) that is not silence.
     */
    fun resample(input: FloatArray, inputStart: Long, inputCount: Int, from: Long, count: Int, out: FloatArray, at: Int) {
        require(inputCount <= input.size && at + count <= out.size)
        var p = Math.floorMod(from, up.toLong()).toInt()
        var blockStart = Math.floorDiv(from, up.toLong()) * down
        for (o in at until at + count) {
            val i0 = blockStart + first[p] - inputStart
            val t0 = start[p]
            val n = start[p + 1] - t0
            var acc = 0.0
            if (i0 >= 0 && i0 + n <= inputCount) {
                // Four sums side by side: one chain of adds waits on each add before it.
                val i = i0.toInt()
                var a0 = 0.0
                var a1 = 0.0
                var a2 = 0.0
                var a3 = 0.0
                var k = 0
                while (k + 3 < n) {
                    a0 += taps[t0 + k] * input[i + k]
                    a1 += taps[t0 + k + 1] * input[i + k + 1]
                    a2 += taps[t0 + k + 2] * input[i + k + 2]
                    a3 += taps[t0 + k + 3] * input[i + k + 3]
                    k += 4
                }
                while (k < n) {
                    a0 += taps[t0 + k] * input[i + k]
                    k++
                }
                acc = (a0 + a1) + (a2 + a3)
            } else {
                for (k in 0 until n) {
                    val i = i0 + k
                    if (i >= 0 && i < inputCount) acc += taps[t0 + k] * input[i.toInt()]
                }
            }
            out[o] = acc.toFloat()
            if (++p == up) {
                p = 0
                blockStart += down
            }
        }
    }

    companion object {
        /** split_windows.py's settings for torchaudio: librosa's and resampy's "kaiser_best". */
        const val ZEROS = 64
        const val ROLLOFF = 0.9475937167399596
        const val BETA = 14.769656459379492

        /** Pi as torchaudio's float32 tensor holds it. */
        private val PI_32 = PI.toFloat()

        /** A filter of more taps than this is refused: rates with a small common divisor (44,101 Hz to 44,100). */
        private const val MAX_TAPS = 4_000_000L

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** The modified Bessel function of the first kind, of order 0, by its power series. */
        internal fun besselI0(x: Double): Double {
            val q = x * x / 4
            var term = 1.0
            var sum = 1.0
            var k = 1
            while (term > sum * 1e-17) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}

/**
 * A [Resampler] fed a stretch of [channels] channels piece by piece and in order, handing each
 * output frame to [sink] once the input it reads has come: the same numbers as one call over the
 * whole stretch, holding only a few thousand frames. [length] is the whole input's, for its end:
 * after it, silence, and [Resampler.outputFrames] of it come out in all.
 */
class ResamplerStream(
    private val resampler: Resampler,
    private val channels: Int,
    private val length: Long,
    private val sink: Sink,
) {
    /** Output frames [from] until [from] + [count]: channel c's in values[c], from index 0. */
    fun interface Sink {
        fun frames(from: Long, count: Int, values: Array<FloatArray>)
    }

    private val capacity = resampler.span + resampler.down + CHUNK
    private val buffer = Array(channels) { FloatArray(capacity) }

    /** The input frame buffer[c][0] holds, and how many are there. */
    private var bufferStart = 0L
    private var filled = 0
    private var received = 0L
    private var next = 0L
    private val outputs = resampler.outputFrames(length)
    private val out = Array(channels) { FloatArray(OUT_BLOCK) }

    /** Takes the next [count] input frames, channel c's from values[c][at]. */
    fun push(values: Array<FloatArray>, at: Int, count: Int) {
        require(received + count <= length) { "more input than the stretch holds" }
        var done = 0
        while (done < count) {
            if (filled == capacity) compact()
            val n = min(count - done, capacity - filled)
            for (c in 0 until channels) values[c].copyInto(buffer[c], filled, at + done, at + done + n)
            filled += n
            received += n
            done += n
            drain()
        }
    }

    /** After the whole stretch: the frames that read past its end. */
    fun finish() {
        require(received == length) { "the stretch is not all in: $received of $length" }
        drain()
    }

    /** Drops the input no output still to come reads, to make room. */
    private fun compact() {
        val keep = (if (next < outputs) resampler.firstInput(next) else received).coerceIn(bufferStart, bufferStart + filled)
        val drop = (keep - bufferStart).toInt()
        check(drop > 0) { "no room in the resampler's buffer" }
        for (c in 0 until channels) buffer[c].copyInto(buffer[c], 0, drop, filled)
        filled -= drop
        bufferStart = keep
    }

    private fun drain() {
        val complete = received == length
        while (next < outputs) {
            var n = 0
            while (n < OUT_BLOCK && next + n < outputs && (complete || resampler.endInput(next + n) <= received)) n++
            if (n == 0) return
            for (c in 0 until channels) resampler.resample(buffer[c], bufferStart, filled, next, n, out[c], 0)
            sink.frames(next, n, out)
            next += n
        }
    }

    private companion object {
        const val CHUNK = 8192
        const val OUT_BLOCK = 4096
    }
}
