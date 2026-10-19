/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The outgoing song played faster or slower with its pitch kept: our own waveform-similarity
 * overlap-add, after Verhelst and Roelands (1993), driven by a [TimeMap].
 *
 * Output is built from Hann frames of [FRAME] samples at half overlap, laid on an absolute grid of
 * output frames. Each frame is taken from the tail within ±5 ms of where the map says it belongs,
 * at the offset whose start best matches the natural continuation of the frame before it, so the
 * waveforms join without a seam; never further than 5 ms, so a beat is never more than 5 ms from
 * where the map puts it. The search runs on the decimated guide the analysis already made, then is
 * refined over four frames either way at the full rate. Where the map is the identity, frames are
 * taken from exactly where they are and the tail is copied, sample for sample.
 *
 * Left alone, WSOLA keeps taking the natural continuation until its drift reaches the edge of the
 * search and only then jumps, so a beat lands up to 5 ms off, and mostly near that edge. [anchors]
 * are the tail frames that must land where the map says, the outgoing song's beats in a mix: a
 * frame is placed so the anchor lands within a millisecond of its mapped time, at the best match
 * there, and the frames after it continue from it exactly. The jump this needs falls 25 to 50 ms
 * before the beat, at the best waveform match, where the beat's onset covers it.
 */
class Wsola(
    private val tail: ShortArray,
    private val channels: Int,
    sampleRate: Int,
    /** The decimated mono copy of the tail the analysis made, and its decimation. */
    private val guide: FloatArray,
    private val factor: Int,
    /** Tail frames to land exactly where the map says, sorted. */
    private val anchors: DoubleArray = DoubleArray(0),
) {
    private val anchorRadius = (ANCHOR_S * sampleRate).roundToInt()
    private val tailFrames = tail.size / channels
    private val search = (SEARCH_S * sampleRate).roundToInt()
    private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * PI * it / FRAME) }

    /**
     * Output frames [from] to [to] of [map], interleaved, in full-scale units (a sample of the tail
     * keeps its 16-bit value). Tail frames before 0 or past the end read as silence.
     */
    fun render(map: TimeMap, from: Long, to: Long): DoubleArray {
        val length = (to - from).toInt()
        val out = DoubleArray(max(0, length) * channels)
        if (length <= 0) return out
        val frames = frames(map, from, to)
        val frame = DoubleArray(channels)
        for (o in from until to) {
            frames.frame(o, frame)
            for (c in 0 until channels) out[((o - from) * channels).toInt() + c] = frame[c]
        }
        return out
    }

    /**
     * Where every grid frame that output frames [from] to [to] are built from is taken in the
     * tail: a few numbers a hop, from which any output frame can then be made on demand, so a long
     * stretch is never held whole.
     */
    fun frames(map: TimeMap, from: Long, to: Long): Frames {
        // Each output frame lies in the first half of grid frame k2 and the second half of k2 - 1.
        val kFirst = Math.floorDiv(from, HOP.toLong()) - 1
        val kLast = Math.floorDiv(max(from, to - 1), HOP.toLong())
        // Each search continues the frame before it, so the chain starts from the last grid frame
        // the map leaves alone; it is the same chain whatever stretch is asked for.
        var k0 = kFirst
        while (!isIdentityFrame(map, k0)) k0--
        val positions = LongArray((kLast - kFirst + 1).toInt())
        var previous = k0 * HOP
        if (k0 >= kFirst) positions[(k0 - kFirst).toInt()] = previous
        var continuing = 0
        for (k in k0 + 1..kLast) {
            var anchored = false
            val p = when {
                isIdentityFrame(map, k) -> k * HOP
                // The two frames after an anchored one, the two that hold the anchor, continue it exactly.
                continuing > 0 -> previous + HOP
                else -> {
                    val target = (map.tailAt(k * HOP + FRAME / 2.0) - FRAME / 2.0).roundToLong()
                    // An anchor heard in the second half of the frame after this one: the jump
                    // that puts it on time is made here, a frame early, so that both frames that
                    // hold the anchor continue each other exactly and neither leaves an early
                    // ghost of it near the edge of its window.
                    val anchor = anchorIn(map.tailAt((k + 2) * HOP.toDouble()), map.tailAt((k + 3) * HOP.toDouble()))
                    val desired = anchor?.let { (k * HOP + it - map.outAt(it)).roundToLong() }
                    val lo = if (desired == null) target - search else max(target - search, desired - anchorRadius)
                    val hi = if (desired == null) target + search else kotlin.math.min(target + search, desired + anchorRadius)
                    if (desired != null && lo <= hi) {
                        anchored = true
                        bestOffset(desired.coerceIn(lo, hi), previous + HOP, lo, hi, desired)
                    } else bestOffset(target, previous + HOP, target - search, target + search, null)
                }
            }
            continuing = if (anchored) 2 else max(0, continuing - 1)
            if (k >= kFirst) positions[(k - kFirst).toInt()] = p
            previous = p
        }
        return Frames(kFirst, positions)
    }

    /** The positions [frames] chose, ready to make output frames from. */
    inner class Frames(private val kFirst: Long, private val positions: LongArray) {

        /** Output frame [o], one sample per channel into [into]. */
        fun frame(o: Long, into: DoubleArray) {
            val k2 = Math.floorDiv(o, HOP.toLong())
            val i2 = (o - k2 * HOP).toInt()
            val k1 = k2 - 1
            val i1 = i2 + HOP
            val j1 = (k1 - kFirst).toInt()
            val j2 = (k2 - kFirst).toInt()
            val p1 = positions[j1]
            val p2 = positions[j2]
            // Two frames taken from exactly where they are add up to the sample itself; adding
            // their windowed halves would only get there to within a rounding, which can tip a
            // sample's rounding later, so the tail is copied instead and joins the raw tail before
            // it to the bit. That holds wherever both frames are in place, not only where the map
            // is still the identity: the first frames of a ramp are often still in place too.
            val copy = p1 == k1 * HOP && p2 == k2 * HOP
            for (c in 0 until channels) {
                into[c] = if (copy) sample(o, c) else window[i1] * sample(p1 + i1, c) + window[i2] * sample(p2 + i2, c)
            }
        }
    }

    private fun sample(t: Long, channel: Int): Double =
        if (t in 0 until tailFrames) tail[(t * channels + channel).toInt()].toDouble() else 0.0

    /** Whether grid frame [k] lies wholly where the map is still the identity. */
    private fun isIdentityFrame(map: TimeMap, k: Long): Boolean = map.isIdentity || (k * HOP + FRAME).toDouble() <= map.rampStart

    /** The first anchor in tail frames [from, to), or null. */
    private fun anchorIn(from: Double, to: Double): Double? {
        if (anchors.isEmpty()) return null
        var lo = 0
        var hi = anchors.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (anchors[mid] < from) lo = mid + 1 else hi = mid
        }
        return if (lo < anchors.size && anchors[lo] < to) anchors[lo] else null
    }

    /**
     * The tail position between [lo] and [hi], searched around [target], whose next half frame best
     * matches the half frame at [natural], by normalised correlation: first on the guide, then at
     * the full rate.
     */
    private fun bestOffset(target: Long, natural: Long, lo: Long, hi: Long, prefer: Long?): Long {
        // Near an anchor, an offset pays for straying from where the anchor wants it, so where the
        // waveforms do not decide (silence between two clicks, a held note) the anchor's own
        // position wins.
        fun cost(p: Long): Double = if (prefer == null) 0.0 else {
            val d = (p - prefer).toDouble() / max(1, anchorRadius)
            ANCHOR_PULL * d * d
        }
        val overlap = FRAME - HOP
        val g = overlap / factor
        val naturalG = Math.floorDiv(natural, factor.toLong())
        val loG = Math.floorDiv(lo, factor.toLong())
        val hiG = Math.floorDiv(hi, factor.toLong())
        var bestG = Math.floorDiv(target, factor.toLong())
        var bestScore = Double.NEGATIVE_INFINITY
        for (p in loG..hiG) {
            val score = correlation(p, naturalG, g) { guideAt(it) } - cost(p * factor)
            if (score > bestScore) {
                bestScore = score
                bestG = p
            }
        }
        // At the full rate, around the guide's answer, never outside the window.
        val centre = (bestG * factor).coerceIn(lo, hi)
        var best = centre
        bestScore = Double.NEGATIVE_INFINITY
        for (d in -REFINE..REFINE) {
            val p = (centre + d).coerceIn(lo, hi)
            val score = correlation(p, natural, overlap) { monoAt(it) } - cost(p)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    private inline fun correlation(a: Long, b: Long, length: Int, value: (Long) -> Double): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until length) {
            val x = value(a + i)
            val y = value(b + i)
            ab += x * y
            aa += x * x
            bb += y * y
        }
        val norm = sqrt(aa * bb)
        return if (norm < 1e-12) 0.0 else ab / norm
    }

    private fun guideAt(i: Long): Double = if (i in guide.indices) guide[i.toInt()].toDouble() else 0.0

    private fun monoAt(t: Long): Double {
        if (t !in 0 until tailFrames) return 0.0
        var sum = 0.0
        val base = (t * channels).toInt()
        for (c in 0 until channels) sum += tail[base + c]
        return sum
    }

    companion object {
        const val FRAME = 2048
        const val HOP = FRAME / 2
        const val SEARCH_S = 0.005
        const val ANCHOR_S = 0.001
        private const val ANCHOR_PULL = 0.5
        private const val REFINE = 4
    }
}

/** Rounds and clamps to 16 bit, the one rounding every path in AutoMix shares. */
internal fun toPcm16(v: Double): Short {
    val r = floor(v + 0.5)
    return when {
        r > 32767.0 -> 32767
        r < -32768.0 -> -32768
        else -> r.toInt().toShort()
    }
}

/** A sample scaled by a song's attenuation, exactly itself when the attenuation is 1. */
internal fun attenuate(x: Short, attenuation: Double): Short = if (attenuation == 1.0) x else toPcm16(x * attenuation)
