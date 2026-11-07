/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A song played faster or slower with its pitch kept: waveform-similarity overlap-add, after
 * Verhelst and Roelands (1993), driven by a [FrameMap].
 *
 * Output is laid on an absolute grid of grid frames of [FRAME] samples at half overlap. Where the
 * map is the identity the tail is copied, sample for sample. Elsewhere each grid frame goes on from
 * the one before it exactly (the tail as it is, again sample for sample), and the speed comes from
 * a few splices: between two of [anchors], the song's beats, the music has to lose or gain what the
 * map asks of that beat, and it does so in as few splices as keep it within [SPLICE_S] / 2 of the
 * map, each one in its own share of the beat, at the grid frame and the jump whose two sides are
 * most alike (searched on the decimated guide the analysis already made, the best few again at the
 * full rate on both channels), where no drum hit falls if it can be helped; the last one lands the
 * next beat within [ANCHOR_S] of where the map puts it. A splice crossfades over [FADE_S] where its
 * two sides are most alike, its level kept by how alike they are. Where the song has no beat to
 * land, every [SOFT_HOPS] grid frames stand in for one, within [SOFT_S] of the map.
 *
 * Measured on his reels' songs (experiments/features/automix/stretch, 7 Nov 2026): the way before
 * took every frame within ±5 ms of the map and landed each beat with a jump a frame before it,
 * searched within a millisecond. At 3% it spliced 20 times a second, half of the splices joining
 * unlike sides over a 21 ms crossfade, which was heard as tearing and phasing; this one splices 4
 * times a second at 3% and twice at 1%, and does a third of the harm to the spectrum (a sixth in
 * the mids, an eighth in the highs).
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
    /**
     * Where grid frame 0 starts, in output frames. The prototype's grid starts at frame 0 of the
     * held end; the change on bands starts it on bar 1, the same music in any copy, so a copy of
     * the song that starts elsewhere is stretched with the same splices.
     */
    private val phase: Long = 0,
    /** The tail frame guide sample 0 was taken at: the guide is every [factor]-th frame from there. */
    private val guideFrom: Long = 0,
) {
    private val anchorRadius = Math.round(ANCHOR_S * sampleRate)
    private val spliceFrames = SPLICE_S * sampleRate
    private val flex = Math.round(FLEX_S * sampleRate)
    private val softRadius = Math.round(SOFT_S * sampleRate)
    private val fadeFrames = max(2, (FADE_S * sampleRate).roundToInt() / 2 * 2)
    private val drift = DRIFT_S * sampleRate
    private val tailFrames = tail.size / channels
    private val search = (SEARCH_S * sampleRate).roundToInt()
    private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * PI * it / FRAME) }

    /**
     * Output frames [from] to [to] of [map], interleaved, in full-scale units (a sample of the tail
     * keeps its 16-bit value). Tail frames before 0 or past the end read as silence.
     */
    fun render(map: FrameMap, from: Long, to: Long): DoubleArray {
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
     * tail, and where its splice crossfades: a few numbers a grid frame, from which any output
     * frame can then be made on demand, so a long stretch is never held whole.
     */
    fun frames(map: FrameMap, from: Long, to: Long): Frames {
        // Each output frame lies in the first half of grid frame k2 and the second half of k2 - 1.
        val kFirst = Math.floorDiv(from - phase, HOP.toLong()) - 1
        val kLast = Math.floorDiv(max(from, to - 1) - phase, HOP.toLong())
        // The chain starts from the last grid frame the map leaves alone, and plans whole stretches
        // from beat to beat whatever end is asked for, so it is the same chain whatever stretch of
        // it is asked for. A song stretched from its first frame heard starts where the map puts it.
        var k0 = kFirst
        val inPlace = map.inPlaceUntil != Double.NEGATIVE_INFINITY
        if (inPlace) while (!isIdentityFrame(map, k0)) k0--
        val n = (kLast - kFirst + 1).toInt()
        val positions = LongArray(n)
        var before = Long.MIN_VALUE
        fun put(k: Long, p: Long) {
            if (k == kFirst - 1) before = p
            if (k in kFirst..kLast) positions[(k - kFirst).toInt()] = p
        }
        var k = k0
        var p = if (inPlace) start(k0) else (map.tailAt(start(k0) + FRAME / 2.0) - FRAME / 2.0).roundToLong()
        put(k, p)
        // The first grid frame a splice may fall on: never on the two that hold a beat just landed.
        var free = k + 1
        while (k < kLast) {
            if (isIdentityFrame(map, k + 1)) {
                k++
                p = start(k)
                put(k, p)
                free = k + 1
                continue
            }
            val stop = nextStop(map, k)
            val jumps = planSplices(map, k, p, max(free, k + 1), stop)
            for (h in k + 1..stop.k) {
                p += HOP + (jumps[h] ?: 0L)
                put(h, p)
            }
            k = stop.k
            free = if (stop.anchored) k + 3 else k + 1
        }
        // Each splice's crossfade, where its two sides are most alike, and how alike they are there.
        val fadeAt = IntArray(n) { -1 }
        val fadeLike = DoubleArray(n)
        for (i in 0 until n) {
            val prev = if (i == 0) before else positions[i - 1]
            if (prev == Long.MIN_VALUE || positions[i] == prev + HOP) continue
            val (at, like) = bestFade(prev + HOP, positions[i])
            fadeAt[i] = at
            fadeLike[i] = like
        }
        return Frames(kFirst, positions, fadeAt, fadeLike)
    }

    /** Where a stretch between splices ends: grid frame [k] taken from [p], within [tolerance] frames. */
    private class Stop(val k: Long, val p: Long, val tolerance: Long, val anchored: Boolean)

    /**
     * The next stretch's end after grid frame [k]: the grid frame that lands the next anchor, heard
     * in the second half of the grid frame after it, so both that hold it go on from it exactly;
     * with none within [ANCHOR_HOPS], the map's own place [SOFT_HOPS] on.
     */
    private fun nextStop(map: FrameMap, k: Long): Stop {
        for (h in k + 1..k + ANCHOR_HOPS) {
            val anchor = anchorIn(map.tailAt(start(h + 2).toDouble()), map.tailAt(start(h + 3).toDouble())) ?: continue
            val desired = (start(h) + anchor - map.outAt(anchor)).roundToLong()
            val target = (map.tailAt(start(h) + FRAME / 2.0) - FRAME / 2.0).roundToLong()
            // An anchor the map does not put near where the music is, is not one to land.
            if (abs(desired - target) > search) continue
            return Stop(h, desired, anchorRadius, true)
        }
        val h = k + SOFT_HOPS
        return Stop(h, (map.tailAt(start(h) + FRAME / 2.0) - FRAME / 2.0).roundToLong(), softRadius, false)
    }

    /**
     * The splices from grid frame [k], taken from [p], to [stop], as jumps by the grid frame they
     * fall on, none before [first]: as many as keep each under [SPLICE_S], each in its own share of
     * the stretch, at the grid frame and jump whose two sides are most alike and hold no drum hit,
     * and which keep the music within [DRIFT_S] of the straight way from where the stretch starts
     * against the map to where it ends.
     */
    private fun planSplices(map: FrameMap, k: Long, p: Long, first: Long, stop: Stop): Map<Long, Long> {
        val total = stop.p - (p + (stop.k - k) * HOP)
        if (abs(total) <= stop.tolerance || first > stop.k) return emptyMap()
        fun target(h: Long) = map.tailAt(start(h) + FRAME / 2.0) - FRAME / 2.0
        val startOff = p - target(k)
        val endOff = stop.p - target(stop.k)
        // How far the music at grid frame h, taken from [at], is from the straight way.
        fun off(h: Long, at: Long): Double = at - target(h) - (startOff + (endOff - startOff) * (h - k) / (stop.k - k).toDouble())
        val count = (stop.k - first + 1).toInt()
        val splices = max(1, Math.round(abs(total) / spliceFrames).toInt()).coerceAtMost(count)
        val jumps = HashMap<Long, Long>()
        var left = total
        var taken = 0L
        var at = first
        val candidates = ArrayList<Candidate>()
        for (i in 0 until splices) {
            val end = first + (count.toLong() * (i + 1)) / splices - 1
            val last = i == splices - 1
            val share = left.toDouble() / (splices - i)
            val lo = if (last) left - stop.tolerance else Math.round(share) - flex
            val hi = if (last) left + stop.tolerance else Math.round(share) + flex
            // On the guide, every grid frame of this share and every jump, a step of the guide apart.
            candidates.clear()
            for (h in at..end) {
                val natural = p + (h - k) * HOP + taken
                val hit = hitIn(natural)
                val before = off(h, natural)
                var j = lo
                while (j <= hi) {
                    var score = guideLikeness(natural, natural + j)
                    if (hit || hitIn(natural + j)) score -= HIT_COST
                    // Too far from the map on either side of the splice: only where nothing else is.
                    if (abs(before) > drift || abs(before + j) > drift) score -= DRIFT_COST
                    candidates += Candidate(h, j, score)
                    j += factor
                }
            }
            // The best few again at the full rate, both channels, then the jump to the sample around the best.
            candidates.sortByDescending { it.score }
            var best = candidates.firstOrNull() ?: Candidate(at, Math.round(share).coerceIn(lo, hi), 0.0)
            var bestScore = Double.NEGATIVE_INFINITY
            for (c in candidates.take(FINAL_CANDIDATES)) {
                val natural = p + (c.h - k) * HOP + taken
                var score = alike(natural, natural + c.j)
                if (hitIn(natural) || hitIn(natural + c.j)) score -= HIT_COST
                val before = off(c.h, natural)
                if (abs(before) > drift || abs(before + c.j) > drift) score -= DRIFT_COST
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            val natural = p + (best.h - k) * HOP + taken
            var jump = best.j
            var jumpScore = Double.NEGATIVE_INFINITY
            for (d in -factor..factor) {
                val j = (best.j + d).coerceIn(lo, hi)
                val score = alike(natural, natural + j)
                if (score > jumpScore) {
                    jumpScore = score
                    jump = j
                }
            }
            jumps[best.h] = jump
            taken += jump
            left -= jump
            at = end + 1
        }
        return jumps
    }

    private class Candidate(val h: Long, val j: Long, val score: Double)

    /** How alike the half frames of the tail from [a] and from [b] are on the guide: their normalised correlation. */
    private fun guideLikeness(a: Long, b: Long): Double {
        val n = (FRAME - HOP) / factor
        val aG = Math.floorDiv(a - guideFrom, factor.toLong())
        val bG = Math.floorDiv(b - guideFrom, factor.toLong())
        return correlation(bG, aG, n) { guideAt(it) }
    }

    /**
     * How alike the half frames of the tail from [a] and from [b] are at the full rate, both
     * channels, weighted as the two windows of an overlap blend them: 1 for the same music.
     */
    private fun alike(a: Long, b: Long): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until HOP) {
            val w = window[i + HOP] * window[i]
            for (c in 0 until channels) {
                val x = sample(a + i, c)
                val y = sample(b + i, c)
                ab += w * x * y
                aa += w * x * x
                bb += w * y * y
            }
        }
        val norm = sqrt(aa * bb)
        return if (norm < 1e-9) 1.0 else ab / norm
    }

    /**
     * Where in the overlap a crossfade of [fadeFrames] from the tail at [a] into the tail at [b]
     * is best centred, a step of 32 frames apart, and how alike the two are over it there.
     */
    private fun bestFade(a: Long, b: Long): Pair<Int, Double> {
        var best = HOP / 2
        var bestLike = Double.NEGATIVE_INFINITY
        val half = fadeFrames / 2
        var c = half
        while (c <= HOP - half) {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in c - half until c + half) for (ch in 0 until channels) {
                val x = sample(a + i, ch)
                val y = sample(b + i, ch)
                ab += x * y
                aa += x * x
                bb += y * y
            }
            val norm = sqrt(aa * bb)
            val like = if (norm < 1e-9) 1.0 else ab / norm
            if (like > bestLike) {
                bestLike = like
                best = c
            }
            c += FADE_STEP
        }
        return best to bestLike
    }

    /** The positions [frames] chose and their splices' crossfades, ready to make output frames from. */
    inner class Frames(internal val kFirst: Long, internal val positions: LongArray, private val fadeAt: IntArray, internal val fadeLike: DoubleArray) {

        /** The output frame grid frame [k] starts on. */
        internal fun startOf(k: Long): Long = start(k)

        /** Output frame [o], one sample per channel into [into]. */
        fun frame(o: Long, into: DoubleArray) {
            val k2 = Math.floorDiv(o - phase, HOP.toLong())
            val i2 = (o - start(k2)).toInt()
            val j2 = (k2 - kFirst).toInt()
            val p2 = positions[j2]
            val fade = fadeAt[j2]
            if (fade < 0) {
                // The tail as it is: where the map leaves it alone, and where a grid frame goes on
                // from the one before it, whose second half is the same samples.
                for (c in 0 until channels) into[c] = sample(p2 + i2, c)
                return
            }
            // A splice: the grid frame before it goes on until the crossfade, which is centred where
            // the two sides are most alike, and its level kept by how alike they are there.
            val p1 = positions[j2 - 1]
            val x = (i2 - (fade - fadeFrames / 2) + 0.5) / fadeFrames
            val w2 = when {
                x <= 0.0 -> 0.0
                x >= 1.0 -> 1.0
                else -> 0.5 - 0.5 * cos(PI * x)
            }
            val w1 = 1.0 - w2
            val like = fadeLike[j2].coerceIn(0.0, 1.0)
            val gain = 1.0 / sqrt(w1 * w1 + w2 * w2 + 2 * like * w1 * w2)
            for (c in 0 until channels) into[c] = (w1 * sample(p1 + HOP + i2, c) + w2 * sample(p2 + i2, c)) * gain
        }
    }

    private fun sample(t: Long, channel: Int): Double =
        if (t in 0 until tailFrames) tail[(t * channels + channel).toInt()].toDouble() else 0.0

    /** Whether grid frame [k] lies wholly where the map is still the identity. */
    private fun isIdentityFrame(map: FrameMap, k: Long): Boolean = (start(k) + FRAME).toDouble() <= map.inPlaceUntil

    /** The output frame grid frame [k] starts on. */
    private fun start(k: Long): Long = phase + k * HOP

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

    /**
     * Whether a drum hit falls in the half frame of the tail from [t]: on the guide's first
     * difference, which leaves the bass out, a stretch of [HIT_BLOCK] samples [HIT_RISE] times as
     * loud as the four before it, and loud enough to be heard.
     */
    private fun hitIn(t: Long): Boolean {
        val g0 = Math.floorDiv(t - guideFrom, factor.toLong())
        val last = g0 + (FRAME - HOP) / factor - HIT_BLOCK
        val flags = hits
        var g = max(0L, g0)
        while (g <= last && g < flags.size) {
            if (flags[g.toInt()]) return true
            g++
        }
        return false
    }

    /**
     * Where a drum hit starts on the guide, sample by sample, each worked out from the samples
     * around it only, so any copy of the song finds the same hits at the same music.
     */
    private val hits: BooleanArray by lazy {
        val n = guide.size
        val out = BooleanArray(n)
        val back = 4 * HIT_BLOCK
        if (n < back + HIT_BLOCK + 1) return@lazy out
        fun e(i: Int): Double {
            if (i < 1) return 0.0
            val d = (guide[i] - guide[i - 1]).toDouble()
            return d * d
        }
        var now = 0.0
        var then = 0.0
        for (i in back until n - HIT_BLOCK) {
            if ((i - back) % RESUM == 0) {
                // Summed afresh now and then, so the running sums never drift.
                now = 0.0
                for (j in i until i + HIT_BLOCK) now += e(j)
                then = 0.0
                for (j in i - back until i) then += e(j)
            }
            out[i] = now / HIT_BLOCK > HIT_FLOOR && now / HIT_BLOCK > HIT_RISE * (then / back)
            now += e(i + HIT_BLOCK) - e(i)
            then += e(i) - e(i - back)
        }
        out
    }

    companion object {
        const val FRAME = 2048
        const val HOP = FRAME / 2

        /** How far from the map an anchor may sit and still be landed. */
        const val SEARCH_S = 0.005

        /** How near where the map puts it a beat lands. */
        const val ANCHOR_S = 0.001

        /** The most one splice moves the music: enough splices that between two beats it keeps within half of this of the map. */
        const val SPLICE_S = 0.007

        /** How far a splice that is not a beat's last may take more or less than its share, to find two alike sides. */
        const val FLEX_S = 0.003

        /** How near the map a stretch with no beat to land ends. */
        const val SOFT_S = 0.0025

        /** A splice's crossfade, a raised cosine. */
        const val FADE_S = 0.008

        /** How far the music may stray from the straight way between two beats, either side of a splice. */
        const val DRIFT_S = 0.0045

        /** How far ahead the next beat is looked for, and where a stretch with none ends, in grid frames. */
        const val ANCHOR_HOPS = 48
        const val SOFT_HOPS = 24

        /** How many of the guide's best splices are judged again at the full rate. */
        private const val FINAL_CANDIDATES = 12
        private const val FADE_STEP = 32

        /** What a drum hit on either side of a splice costs it, against two sides alike to 1. */
        private const val HIT_COST = 0.5
        private const val DRIFT_COST = 0.5
        private const val HIT_BLOCK = 32
        private const val HIT_RISE = 4.0
        private const val HIT_FLOOR = 1e-7
        private const val RESUM = 4096
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
