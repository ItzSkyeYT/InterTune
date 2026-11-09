/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.ChangeSum
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.Pcm
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.toPcm16
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A change's window worked out in order from any output frame: the samples [LaneMixer.render] and
 * then [ChangeRender.mix] give for those frames, from where the editor's playhead is instead of from
 * the change's start (his ask of 9 Nov 2026: "as soon as a change is made it should be hearable by
 * the player and playhead"). The whole render works the change out twice before anything is heard;
 * this hears it after a fraction of a second.
 *
 * The render's two passes run side by side here: the first, for the peaks the shared headroom is
 * made from, ahead; the second, for the samples, behind it by what the headroom looks at around a
 * frame ([Headroom.solve]: the lowest over 400 ms, then a 200 ms average), worked out knot by knot
 * as the render works them out. Both begin [WARM_S] before the first frame asked for, so the band
 * split, a lane's filter and its gain glide are where a render from the change's start has them;
 * from the change's start, nothing differs at all. Only an echo ringing from before that is heard
 * short of its earliest repeats, until a whole render takes over.
 */
class ChangeStream internal constructor(
    private val plan: LanePlan,
    private val a: ShortArray,
    private val b: ShortArray,
    private val attenuationA: Double,
    private val attenuationB: Double,
    private val sources: LaneMixer.Sources,
    /** The first output frame wanted. */
    first: Long,
) {
    private val channels = plan.channels
    private val from = plan.mixFrom
    private val to = plan.mixTo
    private val aTo = min(plan.aGone, to)
    private val bFrom = plan.bFrom
    private val outEnd = max(aTo, min(plan.bStart, to))
    private val aCopyFrames = a.size / channels
    private val bCopyFrames = b.size / channels

    private val step = Headroom.STEP
    private val knots = ((to - from + step - 1) / step).toInt() + 1
    private val radius = max(1, (0.2 * plan.sampleRate / step).toInt())
    private val half = max(1, radius / 2)
    private val peaks = DoubleArray(max(0, knots))
    private val lowest = DoubleArray(max(0, knots))
    private val lowestKnown = BooleanArray(max(0, knots))
    private val values = FloatArray(max(0, knots))
    private val valueKnown = BooleanArray(max(0, knots))

    /** The next output frame [next] gives. */
    var position: Long = first
        private set

    // The first pass: the peaks, from a whole knot that settles before the first knot the headroom needs.
    private val warm = (WARM_S * plan.sampleRate).toLong()
    private var p1: Long
    private var peaksKnown: Int
    private var knot = -1
    private var aHeard = false
    private var bHeard = false
    private var knotPeak = 0.0
    private var a1: SideRenderer? = null
    private var b1: SideRenderer? = null
    private val fa1 = DoubleArray(channels)
    private val fb1 = DoubleArray(channels)

    // The second pass: the samples.
    private var p2: Long
    private var a2: SideRenderer? = null
    private var b2: SideRenderer? = null
    private val fa = DoubleArray(channels)
    private val fb = DoubleArray(channels)

    init {
        val firstKnot = if (first <= from) 0 else ((first - from) / step).toInt()
        // The headroom at a knot reads the peaks from this many knots before it.
        val needFrom = max(0, firstKnot - half - radius - 1)
        val warmKnots = ((warm + step - 1) / step).toInt()
        val start = if (first <= from) 0 else max(0, needFrom - warmKnots)
        p1 = from + start.toLong() * step
        peaksKnown = start
        p2 = if (first <= from) from else max(from, first - warm)
    }

    /** Writes the next [frames] output frames into [out] from frame [offset], interleaved, and moves on. */
    fun next(out: ShortArray, offset: Int, frames: Int) {
        for (i in 0 until frames) {
            frame(position, out, (offset + i) * channels)
            position++
        }
    }

    private fun frame(o: Long, out: ShortArray, at: Int) {
        when {
            o < from -> for (c in 0 until channels) out[at + c] = attenuate(if (o in 0 until aCopyFrames) a[(o * channels).toInt() + c] else 0, attenuationA)
            o >= to -> {
                val j = o - plan.bOrigin
                for (c in 0 until channels) out[at + c] = attenuate(if (j in plan.bEntry until bCopyFrames) b[(j * channels).toInt() + c] else 0, attenuationB)
            }
            else -> {
                // The second pass up to this frame, what it works out before the first frame wanted only settling it.
                while (p2 < o) {
                    side(p2, fa, fb, second = true)
                    p2++
                }
                side(o, fa, fb, second = true)
                p2 = o + 1
                val h = headroomAt(o)
                for (c in 0 until channels) {
                    val outgoing = if (o < outEnd) toPcm16((fa[c] * attenuationA + (if (o < plan.bStart) fb[c] * attenuationB else 0.0)) * h) else 0
                    val incoming = if (o >= plan.bStart) toPcm16(fb[c] * attenuationB * h) else 0
                    out[at + c] = ChangeSum.add(outgoing.toDouble(), incoming.toDouble())
                }
            }
        }
    }

    /** Each side's frame [o] of a pass, its renderer made on its first frame. */
    private fun side(o: Long, ia: DoubleArray, ib: DoubleArray, second: Boolean) {
        if (o < aTo) {
            val r = (if (second) a2 else a1) ?: SideRenderer(plan, Side.A, sources.a, o, sources.aParts).also { if (second) a2 = it else a1 = it }
            r.next(o, ia)
        } else ia.fill(0.0)
        if (o >= bFrom) {
            val r = (if (second) b2 else b1) ?: SideRenderer(plan, Side.B, sources.b, o, sources.bParts).also { if (second) b2 = it else b1 = it }
            r.next(o, ib)
        } else ib.fill(0.0)
    }

    /** The first pass on until every knot through [k] has its peak. */
    private fun peaksThrough(k: Int) {
        val wanted = min(k, knots - 1)
        while (peaksKnown <= wanted) {
            if (p1 >= to) {
                close()
                peaksKnown = knots
                return
            }
            val o = p1
            val kk = ((o - from) / step).toInt()
            if (kk != knot) {
                close()
                knot = kk
                peaksKnown = kk
            }
            side(o, fa1, fb1, second = false)
            for (c in 0 until channels) {
                val va = fa1[c] * attenuationA
                val vb = fb1[c] * attenuationB
                if (abs(va) >= 1.0) aHeard = true
                if (abs(vb) >= 1.0) bHeard = true
                val s = abs(va + vb) / Pcm.FULL_SCALE
                if (s > knotPeak) knotPeak = s
            }
            p1++
        }
    }

    private fun close() {
        if (knot >= 0 && aHeard && bHeard) peaks[knot] = knotPeak
        aHeard = false
        bHeard = false
        knotPeak = 0.0
    }

    // ---- the headroom, as Headroom.solve and Headroom.at work it out, knot by knot

    private fun needed(i: Int): Double {
        val load = max(peaks[i], if (i > 0) peaks[i - 1] else 0.0)
        return if (load <= Headroom.CEILING) 1.0 else Headroom.CEILING / load
    }

    private fun lowestAt(j: Int): Double {
        if (!lowestKnown[j]) {
            peaksThrough(j + radius)
            var m = 1.0
            for (i in max(0, j - radius)..min(knots - 1, j + radius)) m = min(m, needed(i))
            lowest[j] = m
            lowestKnown[j] = true
        }
        return lowest[j]
    }

    private fun valueAt(k: Int): Float {
        if (!valueKnown[k]) {
            var sum = 0.0
            for (j in k - half..k + half) sum += if (j in 0 until knots) lowestAt(j) else 1.0
            values[k] = (sum / (2 * half + 1)).toFloat()
            valueKnown[k] = true
        }
        return values[k]
    }

    private fun headroomAt(frame: Long): Double {
        if (knots <= 0 || frame < from) return 1.0
        val position = (frame - from).toDouble() / step
        val i = position.toInt()
        if (i >= knots - 1) return if (i >= knots) 1.0 else valueAt(i).toDouble()
        val a = valueAt(i)
        val b = valueAt(i + 1)
        if (a == 1f && b == 1f) return 1.0
        val f = position - i
        return a * (1.0 - f) + b * f
    }

    private fun attenuate(s: Short, attenuation: Double): Short = if (attenuation == 1.0) s else toPcm16(s * attenuation)

    companion object {
        /** How long before the first frame asked for both passes begin, for everything with a memory to settle. */
        const val WARM_S = 0.5
    }
}
