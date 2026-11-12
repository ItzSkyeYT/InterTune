/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The stretcher's ways side by side, for the trial only: [Wsola]'s own (greedy: each frame within
 * the search of the map, natural continuation where it can, a jump a frame before each beat to land
 * it within a millisecond), with knobs, and other ways to splice. What the trial measures and
 * renders; the one that sounds best goes into [Wsola].
 */
class LabStretch(
    private val tail: ShortArray,
    private val channels: Int,
    private val sampleRate: Int,
    private val guide: FloatArray,
    private val factor: Int,
    private val anchors: DoubleArray,
    private val knobs: Knobs,
    private val phase: Long = 0,
    private val guideFrom: Long = 0,
) {
    data class Knobs(
        val name: String,
        val planned: Boolean = false,
        val searchS: Double = Wsola.SEARCH_S,
        val anchorS: Double = Wsola.ANCHOR_S,
        /** Splices keep the level: their crossfade scaled by how alike the two sides are. */
        val keepLevel: Boolean = false,
        /** Greedy: a drum hit in the overlap puts the jump off while the music stays within [hitSlackS] more of the map. */
        val avoidHits: Boolean = false,
        val hitSlackS: Double = 0.004,
        /** Planned: the most one splice moves the music, and how far it may stray from its share. */
        val spliceS: Double = 0.007,
        val flexS: Double = 0.003,
        /** Greedy: the anchor's jump may be made this many frames before the frame that lands it, at the best of them. */
        val anchorLead: Int = 0,
        /** Correlation measured on the full rate's both channels, weighted as the windows blend, instead of the guide's mono. */
        val fullRate: Boolean = false,
        /** A splice crossfades over this long a raised cosine, where its two sides are most alike, instead of the whole overlap; 0 for the whole. */
        val fadeS: Double = 0.0,
        /** Planned: the final choice among the guide's best few at the full rate, both channels. */
        val finalFull: Boolean = false,
        /** Planned with a short crossfade: the final choice by how alike the two sides are where the crossfade falls. */
        val fadeScore: Boolean = false,
    )

    private val frameLen = Wsola.FRAME
    private val hop = Wsola.HOP
    private val window = DoubleArray(frameLen) { 0.5 - 0.5 * cos(2.0 * PI * it / frameLen) }
    private val tailFrames = tail.size / channels
    private val search = (knobs.searchS * sampleRate).roundToInt()
    private val fadeLen = (knobs.fadeS * sampleRate).roundToInt()

    /** Where in the overlap a short crossfade from the half frame at [a] into the one at [b] is best centred, and how alike the two are there. */
    private fun bestFade(a: Long, b: Long): Pair<Int, Double> {
        var best = hop / 2
        var bestLike = Double.NEGATIVE_INFINITY
        var c = fadeLen / 2
        while (c <= hop - fadeLen / 2) {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in c - fadeLen / 2 until c + fadeLen / 2) for (ch in 0 until channels) {
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
            c += 32
        }
        return best to bestLike
    }
    private val anchorRadius = (knobs.anchorS * sampleRate).roundToInt()

    class Frames(val kFirst: Long, val positions: LongArray, val anchoredAt: BooleanArray, val likeness: DoubleArray, private val lab: LabStretch) {
        /** Where each splice's short crossfade is centred in its overlap, and how alike its two sides are there. */
        val fadeAt = IntArray(positions.size) { -1 }
        val fadeLike = DoubleArray(positions.size) { 1.0 }

        fun startOf(k: Long): Long = lab.start(k)

        fun frame(o: Long, into: DoubleArray) = lab.frameOf(this, o, into)
    }

    fun start(k: Long): Long = phase + k * hop

    private fun isIdentityFrame(map: FrameMap, k: Long): Boolean = (start(k) + frameLen).toDouble() <= map.inPlaceUntil

    fun frames(map: FrameMap, from: Long, to: Long): Frames {
        val kFirst = Math.floorDiv(from - phase, hop.toLong()) - 1
        val kLast = Math.floorDiv(max(from, to - 1) - phase, hop.toLong())
        var k0 = kFirst
        val inPlace = map.inPlaceUntil != Double.NEGATIVE_INFINITY
        if (inPlace) while (!isIdentityFrame(map, k0)) k0--
        val n = (kLast - kFirst + 1).toInt()
        val positions = LongArray(n)
        val anchoredAt = BooleanArray(n)
        val likeness = DoubleArray(n) { 1.0 }
        val all = HashMap<Long, Long>()
        val anchoredAll = HashSet<Long>()
        if (knobs.planned) planned(map, k0, kLast, inPlace, all, anchoredAll) else greedy(map, k0, kLast, inPlace, all, anchoredAll)
        for (k in kFirst..kLast) {
            val i = (k - kFirst).toInt()
            positions[i] = all[k]!!
            anchoredAt[i] = k in anchoredAll
            val prev = all[k - 1]
            if (prev != null && positions[i] != prev + hop) likeness[i] = alike(prev + hop, positions[i])
        }
        val f = Frames(kFirst, positions, anchoredAt, likeness, this)
        if (fadeLen > 0) for (i in 1 until n) {
            val prev = positions[i - 1]
            if (positions[i] == prev + hop) continue
            val (at, like) = bestFade(prev + hop, positions[i])
            f.fadeAt[i] = at
            f.fadeLike[i] = like
        }
        return f
    }

    // ---- greedy: Wsola's own, with knobs ----

    private fun greedy(map: FrameMap, k0: Long, kLast: Long, inPlace: Boolean, out: HashMap<Long, Long>, anchored: HashSet<Long>) {
        var previous = if (inPlace) start(k0) else (map.tailAt(start(k0) + frameLen / 2.0) - frameLen / 2.0).roundToLong()
        out[k0] = previous
        var continuing = 0
        val slack = (knobs.hitSlackS * sampleRate).roundToLong()
        for (k in k0 + 1..kLast + 3) {
            var isAnchored = false
            val p = when {
                isIdentityFrame(map, k) -> start(k)
                continuing > 0 -> previous + hop
                else -> {
                    val target = (map.tailAt(start(k) + frameLen / 2.0) - frameLen / 2.0).roundToLong()
                    val anchor = anchorIn(map.tailAt(start(k + 2).toDouble()), map.tailAt(start(k + 3).toDouble()))
                    val desired = anchor?.let { (start(k) + it - map.outAt(it)).roundToLong() }
                    val lo = if (desired == null) target - search else max(target - search, desired - anchorRadius)
                    val hi = if (desired == null) target + search else min(target + search, desired + anchorRadius)
                    val natural = previous + hop
                    if (desired != null && lo <= hi) {
                        isAnchored = true
                        bestOffset(desired.coerceIn(lo, hi), natural, lo, hi, desired)
                    } else if (knobs.avoidHits && natural >= target - search - slack && natural <= target + search + slack && hitIn(natural)) {
                        // A drum hit where the splice would fall: on as it is, a little further from the map.
                        natural
                    } else bestOffset(target, natural, target - search, target + search, null)
                }
            }
            continuing = if (isAnchored) 2 else max(0, continuing - 1)
            out[k] = p
            if (isAnchored) anchored += k
            previous = p
        }
    }

    // ---- planned splices ----

    private class Stop(val k: Long, val p: Long, val tolerance: Long, val anchored: Boolean)

    private fun planned(map: FrameMap, k0: Long, kLast: Long, inPlace: Boolean, out: HashMap<Long, Long>, anchoredSet: HashSet<Long>) {
        var k = k0
        var p = if (inPlace) start(k0) else (map.tailAt(start(k0) + frameLen / 2.0) - frameLen / 2.0).roundToLong()
        out[k] = p
        var free = k + 1
        while (k < kLast + 3) {
            if (isIdentityFrame(map, k + 1)) {
                k++
                p = start(k)
                out[k] = p
                free = k + 1
                continue
            }
            val stop = nextStop(map, k)
            val jumps = planSplices(k, p, max(free, k + 1), stop)
            for (h in k + 1..stop.k) {
                p += hop + (jumps[h] ?: 0L)
                out[h] = p
            }
            if (stop.anchored) anchoredSet += stop.k
            k = stop.k
            free = if (stop.anchored) k + 3 else k + 1
        }
    }

    private fun nextStop(map: FrameMap, k: Long): Stop {
        for (h in k + 1..k + 48) {
            val anchor = anchorIn(map.tailAt(start(h + 2).toDouble()), map.tailAt(start(h + 3).toDouble())) ?: continue
            val desired = (start(h) + anchor - map.outAt(anchor)).roundToLong()
            val target = (map.tailAt(start(h) + frameLen / 2.0) - frameLen / 2.0).roundToLong()
            if (abs(desired - target) > search) continue
            return Stop(h, desired, anchorRadius.toLong(), true)
        }
        val h = k + 24
        return Stop(h, (map.tailAt(start(h) + frameLen / 2.0) - frameLen / 2.0).roundToLong(), (0.0025 * sampleRate).roundToLong(), false)
    }

    private fun planSplices(k: Long, p: Long, first: Long, stop: Stop): Map<Long, Long> {
        val total = stop.p - (p + (stop.k - k) * hop)
        if (abs(total) <= stop.tolerance) return emptyMap()
        if (first > stop.k) return emptyMap()
        val count = (stop.k - first + 1).toInt()
        val splices = max(1, Math.round(abs(total).toDouble() / (knobs.spliceS * sampleRate)).toInt()).coerceAtMost(count)
        val flex = Math.round(knobs.flexS * sampleRate)
        val jumps = HashMap<Long, Long>()
        var left = total
        var taken = 0L
        var at = first
        for (i in 0 until splices) {
            val end = first + (count.toLong() * (i + 1)) / splices - 1
            val final = i == splices - 1
            val share = left.toDouble() / (splices - i)
            val lo = if (final) left - stop.tolerance else Math.round(share) - flex
            val hi = if (final) left + stop.tolerance else Math.round(share) + flex
            var bestH = at
            var bestJ = Math.round(share).coerceIn(lo, hi)
            var bestScore = Double.NEGATIVE_INFINITY
            val candidates = ArrayList<Triple<Double, Long, Long>>()
            for (h in at..end) {
                val natural = p + (h - k) * hop + taken
                val hit = hitIn(natural)
                var j = lo
                while (j <= hi) {
                    var score = scoreAt(natural, natural + j)
                    if (hit || hitIn(natural + j)) score -= 0.5
                    candidates += Triple(score, h, j)
                    if (score > bestScore) {
                        bestScore = score
                        bestH = h
                        bestJ = j
                    }
                    j += factor
                }
            }
            if (knobs.finalFull) {
                // The best few on the guide, judged again at the full rate on both channels.
                var top = Double.NEGATIVE_INFINITY
                for ((_, h, j) in candidates.sortedByDescending { it.first }.take(12)) {
                    val natural = p + (h - k) * hop + taken
                    var s = if (knobs.fadeScore && fadeLen > 0) bestFade(natural, natural + j).second else alike(natural, natural + j)
                    if (hitIn(natural) || hitIn(natural + j)) s -= 0.5
                    if (s > top) {
                        top = s
                        bestH = h
                        bestJ = j
                    }
                }
            }
            // At the full rate around the best.
            val natural = p + (bestH - k) * hop + taken
            var best = bestJ
            var bs = Double.NEGATIVE_INFINITY
            for (d in -factor..factor) {
                val jj = (bestJ + d).coerceIn(lo, hi)
                val s = if (knobs.fadeScore && fadeLen > 0) bestFade(natural, natural + jj).second else alike(natural, natural + jj)
                if (s > bs) {
                    bs = s
                    best = jj
                }
            }
            jumps[bestH] = best
            taken += best
            left -= best
            at = end + 1
        }
        return jumps
    }

    private fun scoreAt(natural: Long, candidate: Long): Double {
        val g = (frameLen - hop) / factor
        val nG = Math.floorDiv(natural - guideFrom, factor.toLong())
        val cG = Math.floorDiv(candidate - guideFrom, factor.toLong())
        return correlation(cG, nG, g) { guideAt(it) }
    }

    // ---- shared ----

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

    private fun bestOffset(target: Long, natural: Long, lo: Long, hi: Long, prefer: Long?): Long {
        val anchorRadius = max(1, this.anchorRadius)
        fun cost(p: Long): Double = if (prefer == null) 0.0 else {
            val d = (p - prefer).toDouble() / anchorRadius
            0.5 * d * d
        }
        val overlap = frameLen - hop
        if (knobs.fullRate) {
            // Every offset at the full rate, both channels, weighted as the two windows blend.
            var best = target.coerceIn(lo, hi)
            var bestScore = Double.NEGATIVE_INFINITY
            for (p in lo..hi) {
                val s = alike(natural, p) - cost(p)
                if (s > bestScore) {
                    bestScore = s
                    best = p
                }
            }
            return best
        }
        val g = overlap / factor
        val naturalG = Math.floorDiv(natural - guideFrom, factor.toLong())
        val loG = Math.floorDiv(lo - guideFrom, factor.toLong())
        val hiG = Math.floorDiv(hi - guideFrom, factor.toLong())
        var bestG = Math.floorDiv(target - guideFrom, factor.toLong())
        var bestScore = Double.NEGATIVE_INFINITY
        for (p in loG..hiG) {
            val score = correlation(p, naturalG, g) { guideAt(it) } - cost(p * factor + guideFrom)
            if (score > bestScore) {
                bestScore = score
                bestG = p
            }
        }
        val centre = (bestG * factor + guideFrom).coerceIn(lo, hi)
        var best = centre
        bestScore = Double.NEGATIVE_INFINITY
        for (d in -4..4) {
            val p = (centre + d).coerceIn(lo, hi)
            val score = correlation(p, natural, overlap) { monoAt(it) } - cost(p)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    /** How alike the half frame from [a] and the one from [b] are, both channels, weighted as the windows blend them. */
    fun alike(a: Long, b: Long): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until hop) {
            val w = window[i + hop] * window[i]
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

    private fun sample(t: Long, channel: Int): Double =
        if (t in 0 until tailFrames) tail[(t * channels + channel).toInt()].toDouble() else 0.0

    private val hitFlags: BooleanArray by lazy {
        val n = guide.size
        val e = DoubleArray(n)
        for (i in 1 until n) {
            val d = (guide[i] - guide[i - 1]).toDouble()
            e[i] = d * d
        }
        val out = BooleanArray(n)
        val block = 32
        val before = 4 * block
        for (i in before until n - block) {
            var now = 0.0
            for (j in i until i + block) now += e[j]
            if (now / block <= 1e-7) continue
            var then = 0.0
            for (j in i - before until i) then += e[j]
            out[i] = now / block > 4.0 * (then / before)
        }
        out
    }

    private fun hitIn(t: Long): Boolean {
        val g0 = Math.floorDiv(t - guideFrom, factor.toLong())
        val last = g0 + (frameLen - hop) / factor - 32
        val flags = hitFlags
        var g = max(0L, g0)
        while (g <= last && g < flags.size) {
            if (flags[g.toInt()]) return true
            g++
        }
        return false
    }

    fun frameOf(f: Frames, o: Long, into: DoubleArray) {
        val k2 = Math.floorDiv(o - phase, hop.toLong())
        val i2 = (o - start(k2)).toInt()
        val k1 = k2 - 1
        val i1 = i2 + hop
        val j1 = (k1 - f.kFirst).toInt()
        val j2 = (k2 - f.kFirst).toInt()
        val p1 = f.positions[j1]
        val p2 = f.positions[j2]
        val copy = p1 == start(k1) && p2 == start(k2)
        var w1 = window[i1]
        var w2 = window[i2]
        val fade = f.fadeAt[j2]
        if (fade >= 0 && p2 != p1 + hop) {
            // A splice's own short crossfade, centred where its two sides are most alike.
            val x = (i2 - (fade - fadeLen / 2)).toDouble() / fadeLen
            w2 = when {
                x <= 0 -> 0.0
                x >= 1 -> 1.0
                else -> 0.5 - 0.5 * cos(PI * x)
            }
            w1 = 1 - w2
            if (knobs.keepLevel) {
                val c = f.fadeLike[j2].coerceIn(0.0, 1.0)
                val g = 1.0 / sqrt(w1 * w1 + w2 * w2 + 2 * c * w1 * w2)
                for (ch in 0 until channels) into[ch] = (w1 * sample(p1 + i1, ch) + w2 * sample(p2 + i2, ch)) * g
                return
            }
            for (ch in 0 until channels) into[ch] = w1 * sample(p1 + i1, ch) + w2 * sample(p2 + i2, ch)
            return
        }
        // A splice's crossfade kept at the level of the music: scaled by how alike its two sides are.
        val gain = if (knobs.keepLevel && p2 != p1 + hop) {
            val c = f.likeness[j2].coerceIn(0.0, 1.0)
            1.0 / sqrt(w1 * w1 + w2 * w2 + 2 * c * w1 * w2)
        } else 1.0
        for (c in 0 until channels) {
            into[c] = if (copy) sample(o, c) else (w1 * sample(p1 + i1, c) + w2 * sample(p2 + i2, c)) * gain
        }
    }
}
