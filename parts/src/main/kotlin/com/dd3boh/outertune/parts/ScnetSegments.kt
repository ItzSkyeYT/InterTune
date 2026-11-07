/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import com.dd3boh.outertune.parts.Scnet.SEGMENT
import kotlin.math.min

/**
 * How stemlib.split_scnet (stemlib.py:93-133) cuts a stretch of audio into 11 s segments for the
 * model: one every [step] = SEGMENT / overlap samples (overlap 1.33, picked by ear on 20 Oct 2026,
 * costs a third of the reference's 4), the stretch first padded at both ends by reflection, by
 * [border], when it is longer than twice that. A last segment that runs past the end is padded by
 * reflection when more than half of it is there, with zeros when less. Overlap 1 is refused:
 * segments then meet where both weights are zero, and stemlib's crop takes everything.
 */
class ScnetPlan(val frames: Int, val overlap: Double) {
    init {
        require(frames > 0) { "nothing to separate" }
        require(overlap > 1.0) { "overlap must be over 1: $overlap" }
    }

    val step: Int = (SEGMENT / overlap).toInt()
    val fade: Int = SEGMENT / 10
    val border: Int = SEGMENT - step
    val padded: Boolean = frames > 2 * border
    val paddedFrames: Int = if (padded) frames + 2 * border else frames

    /** How many segments: one starts at each multiple of [step] short of [paddedFrames]. */
    val count: Int = (paddedFrames + step - 1) / step

    fun start(i: Int): Int = i * step

    /** How much of segment [i] is the stretch (the rest is padding). */
    fun length(i: Int): Int = min(SEGMENT, paddedFrames - start(i))

    fun isLast(i: Int): Boolean = start(i) + step >= paddedFrames

    /** Where segment [i] starts in the stretch itself, before its padding: negative for the first. */
    fun startInStretch(i: Int): Int = start(i) - if (padded) border else 0

    /** Fills [left] and [right] ([SEGMENT] samples each) with segment [i] of the stretch, as the model gets it. */
    fun input(i: Int, srcLeft: FloatArray, srcRight: FloatArray, left: FloatArray, right: FloatArray) {
        require(srcLeft.size >= frames && srcRight.size >= frames)
        input(i, { channel, frame -> if (channel == 0) srcLeft[frame] else srcRight[frame] }, left, right)
    }

    /** The same from a stretch read frame by frame, so it need not be held whole (WindowSplitter). */
    fun input(i: Int, stretch: Stretch, left: FloatArray, right: FloatArray) {
        val s = start(i)
        val n = length(i)
        for (p in 0 until n) {
            var j = s + p
            if (padded) {
                j -= border
                if (j < 0) j = -j else if (j >= frames) j = 2 * (frames - 1) - j
            }
            left[p] = stretch.sample(0, j)
            right[p] = stretch.sample(1, j)
        }
        if (n < SEGMENT) {
            val reflect = n > SEGMENT / 2
            for (p in n until SEGMENT) {
                left[p] = if (reflect) left[2 * (n - 1) - p] else 0f
                right[p] = if (reflect) right[2 * (n - 1) - p] else 0f
            }
        }
    }

    /**
     * The weight of sample [p] of segment [i] when the parts are added back: rising over the first
     * tenth and falling over the last, as torch.linspace makes them in float32, flat at the two
     * ends of the stretch.
     */
    fun weight(i: Int, p: Int): Float = when {
        p < fade && i > 0 -> RISE[p]
        p >= SEGMENT - fade && !isLast(i) -> FALL[p - (SEGMENT - fade)]
        else -> 1f
    }

    companion object {
        /** torch.linspace(start, end, steps) in float32, as its scalar kernel computes it. */
        private fun linspace(start: Float, end: Float, steps: Int): FloatArray {
            val step = (end - start) / (steps - 1)
            val halfway = steps / 2
            return FloatArray(steps) { k -> if (k < halfway) start + step * k else end - step * (steps - k - 1) }
        }

        private val RISE = linspace(0f, 1f, SEGMENT / 10)
        private val FALL = linspace(1f, 0f, SEGMENT / 10)

        /**
         * The longest stretch at [overlap] that takes at most [segments] segments, so a run of the bench
         * can ask for a count. Some counts never happen: where the border padding starts, the count
         * jumps (at overlap 4 from 6 to 13; at 1.33 every count is there).
         */
        fun framesFor(segments: Int, overlap: Double): Int {
            require(segments >= 1)
            var lo = 1
            var hi = segments * SEGMENT + 2 * SEGMENT
            while (lo < hi) {
                val mid = lo + (hi - lo + 1) / 2
                if (ScnetPlan(mid, overlap).count <= segments) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}

/** A stretch of stereo audio read a sample at a time: [frame] from 0 of [channel] 0 (left) or 1 (right). */
fun interface Stretch {
    fun sample(channel: Int, frame: Int): Float
}

/**
 * The parts of a stretch, added back segment by segment with [ScnetPlan.weight] and divided by the
 * weights, in float32 as stemlib does it. Rows are stem by stem, each left then right ([Scnet.STEMS]).
 */
class ScnetSum(private val plan: ScnetPlan) {
    private val sums = Array(2 * Scnet.STEMS.size) { FloatArray(plan.paddedFrames) }
    private val weights = FloatArray(plan.paddedFrames)

    /** Adds segment [i]'s [parts]: 8 rows of [SEGMENT] samples, as [ScnetSegment.separate] gives them. */
    fun add(i: Int, parts: Array<FloatArray>) {
        val s = plan.start(i)
        val n = plan.length(i)
        for (p in 0 until n) {
            val w = plan.weight(i, p)
            weights[s + p] += w
            for (r in sums.indices) sums[r][s + p] += parts[r][p] * w
        }
    }

    /** The stretch's parts: 8 rows of [ScnetPlan.frames] samples. */
    fun result(): Array<FloatArray> {
        val from = if (plan.padded) plan.border else 0
        return Array(sums.size) { r ->
            FloatArray(plan.frames) { q -> sums[r][from + q] / maxOf(weights[from + q], 1e-8f) }
        }
    }
}

/**
 * One segment through SCNet: the STFT, the network, the inverse, timed apart so a phone's numbers
 * can be read for the model alone or with our Kotlin around it. Not thread-safe: one per thread,
 * with about 43 MB of arrays (the spectrogram, the transform's buffers).
 */
class ScnetSegment(private val model: SpecModel) {
    private val stft = ScnetStft()
    private val spec = FloatArray(Scnet.SPEC_SIZE)

    /** Nanoseconds the last [separate] spent in each step. */
    var forwardNanos = 0L
        private set
    var modelNanos = 0L
        private set
    var inverseNanos = 0L
        private set

    /** Separates one segment ([SEGMENT] samples a channel) into [parts]: 8 rows, stem by stem, left then right. */
    fun separate(left: FloatArray, right: FloatArray, parts: Array<FloatArray>) {
        require(parts.size == 2 * Scnet.STEMS.size && parts.all { it.size >= SEGMENT })
        val t0 = System.nanoTime()
        stft.forward(left, right, spec)
        val t1 = System.nanoTime()
        model.run(spec)
        val t2 = System.nanoTime()
        for (s in Scnet.STEMS.indices) {
            model.readStem(s, spec)
            stft.inverse(spec, 0, parts[2 * s], parts[2 * s + 1])
        }
        val t3 = System.nanoTime()
        forwardNanos = t1 - t0
        modelNanos = t2 - t1
        inverseNanos = t3 - t2
    }

    companion object {
        /** A stretch's parts, one segment after another on this thread: 8 rows of the stretch's length. */
        fun separate(left: FloatArray, right: FloatArray, overlap: Double, model: SpecModel): Array<FloatArray> {
            require(left.size == right.size)
            val plan = ScnetPlan(left.size, overlap)
            val segment = ScnetSegment(model)
            val inLeft = FloatArray(SEGMENT)
            val inRight = FloatArray(SEGMENT)
            val parts = Array(2 * Scnet.STEMS.size) { FloatArray(SEGMENT) }
            val sum = ScnetSum(plan)
            for (i in 0 until plan.count) {
                plan.input(i, left, right, inLeft, inRight)
                segment.separate(inLeft, inRight, parts)
                sum.add(i, parts)
            }
            return sum.result()
        }
    }
}
