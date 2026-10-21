/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.ChangeSum
import com.dd3boh.outertune.mix.Curve
import com.dd3boh.outertune.mix.Decimator
import com.dd3boh.outertune.mix.FloatList
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.Pcm
import com.dd3boh.outertune.mix.RenderedIncoming
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.Wsola
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.toPcm16
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A's music by output frame: through the time map by the stretch, silence outside what it covers. */
internal class StretchSource(private val frames: Wsola.Frames, private val from: Long, private val to: Long, private val channels: Int) : FrameSource {
    override fun frame(t: Long, into: DoubleArray) {
        if (t < from || t >= to) {
            for (c in 0 until channels) into[c] = 0.0
            return
        }
        frames.frame(t, into)
    }
}

/**
 * One song's side of a change, frame after frame from [origin]: its music cut into bands, each
 * band shaped by its lane, and the lanes added back. Where every lane is at full and nothing
 * rings, the music itself is given out, not the sum of its bands, so it is the song's own
 * samples to the bit; where every lane is silent, silence. The bands are only cut where they
 * differ, from a tenth of a second before, which is long enough for the band filters to settle
 * from rest: a change of a few bars costs a fraction of one that cut the whole span.
 */
internal class SideRenderer(private val plan: LanePlan, private val side: Side, private val source: FrameSource, origin: Long) {
    private val channels = plan.channels
    private var split: BandSplit? = null
    private val settle = (Engine.SETTLE_S * plan.sampleRate).toLong()
    private val lanes = plan.spec.lanes.lanes
    private val voices = lanes.map { LaneVoice(LaneId(side, it), plan, channels) }
    private val bandOf = IntArray(lanes.size) {
        when (lanes[it]) {
            Lane.LOWS -> 0
            Lane.MIDS -> 1
            Lane.HIGHS -> 2
            else -> error("${lanes[it]} is not a band")
        }
    }
    private val stream = DoubleArray(channels)
    private val bands = Array(3) { DoubleArray(channels) }
    private var next = origin

    /** Output frame [o] of this side into [out], in full-scale units; frames come one after another from the origin. */
    fun next(o: Long, out: DoubleArray) {
        check(o == next) { "side $side asked for frame $o, expected $next" }
        next = o + 1
        var full = true
        var silent = true
        for (v in voices) {
            v.moveTo(o)
            if (!v.isFull) full = false
            if (!v.isSilent) silent = false
        }
        if (full || silent) {
            // Nothing to cut: the music as it is, or nothing of it. A split no longer needed is let go.
            split = null
            if (silent) for (c in 0 until channels) out[c] = 0.0 else source.frame(o, out)
            return
        }
        val s = split ?: BandSplit(source, channels, plan.sampleRate, o - settle).also { split = it }
        s.bands(o, stream, bands[0], bands[1], bands[2])
        for (c in 0 until channels) out[c] = 0.0
        val echo = if (side == Side.A) Engine.echoLevel(o, plan.echoFadeFrom, plan.aGone) else 1.0
        for (i in voices.indices) voices[i].process(bands[bandOf[i]], out, echo)
    }
}

/**
 * A change rendered ahead of time, both songs at their own level under the shared headroom, as
 * 16-bit frames: A from [LanePlan.mixFrom] to where it is gone, B from its first frame to
 * [LanePlan.mixTo]. Playing it is adding the two, which [mix] does exactly as the stage does.
 */
class ChangeRender(
    val plan: LanePlan,
    /** A's part: output frames [LanePlan.mixFrom] until [LanePlan.aGone]. */
    val outgoing: RenderedOutgoing,
    /** B's part: output frames [LanePlan.bStart] until [LanePlan.mixTo]. */
    val incoming: RenderedIncoming,
    val headroom: Headroom,
    /** The loudest the two came to together, where both can be heard, before the headroom, as a share of full scale. */
    val peakBefore: Double,
    /** The same after it, rounded as played. A song on its own plays at its own level and is not counted. */
    val peakAfter: Double,
    val attenuationA: Double,
    val attenuationB: Double,
    val renderMillis: Double,
) {
    /**
     * Output frames [from] until [to] as they are heard, interleaved 16-bit: A as it is before the
     * change, the two parts added through it, B as it is after it. The same samples the stage plays,
     * so the editor's preview is the change itself. [a] and [b] are the music the change was rendered
     * from, A's by output frame before its tempo moves and B's from its first frame.
     */
    fun mix(a: ShortArray, b: ShortArray, from: Long, to: Long): ShortArray {
        val channels = plan.channels
        val n = (to - from).toInt()
        val out = ShortArray(n * channels)
        val aFrames = a.size / channels
        val bFrames = b.size / channels
        for (i in 0 until n) {
            val o = from + i
            for (c in 0 until channels) {
                out[i * channels + c] = when {
                    // Before the change A plays as it is, on its own clock.
                    o < plan.mixFrom -> attenuate(if (o in 0 until aFrames) a[(o * channels).toInt() + c] else 0, attenuationA)
                    o >= plan.mixTo -> {
                        val j = o - plan.bStart
                        attenuate(if (j in 0 until bFrames) b[(j * channels).toInt() + c] else 0, attenuationB)
                    }
                    else -> ChangeSum.add(outgoing.at(o, c).toDouble(), incoming.at(o, c).toDouble())
                }
            }
        }
        return out
    }

    private fun attenuate(s: Short, attenuation: Double): Short = if (attenuation == 1.0) s else toPcm16(s * attenuation)

    /**
     * The plan the held end takes for this change, with [outgoing] and [incoming] beside it: every
     * frame it needs, and a style for the logs and for the planner's rule against two smooth blends
     * in a row.
     */
    fun heldPlan(): MixPlan {
        val spec = plan.spec
        val style = when {
            spec.from == Preset.CUT -> Style.CUT
            spec.from == Preset.RISE -> Style.SMOOTH
            spec.beatMatched -> Style.BEAT
            else -> Style.BLEND
        }
        return MixPlan(
            style = style,
            reason = plan.describe(),
            sampleRate = plan.sampleRate,
            channels = plan.channels,
            tailFrames = plan.facts.aFrames,
            bStart = plan.bStart,
            aMap = plan.aMap,
            aFirstProcessed = plan.mixFrom,
            aGone = outgoing.endFrame,
            overlapStart = plan.bothFrom.toDouble(),
            overlapEnd = plan.bothTo.toDouble(),
            gainA = Curve.constant(1.0),
            gainB = Curve.constant(1.0),
            filtersA = emptyList(),
            filtersB = emptyList(),
            headroom = headroom,
            incomingLoad = DoubleArray(0),
            attenuationA = attenuationA,
            attenuationB = attenuationB,
            shapedUntil = plan.mixTo,
            lockFrom = 0,
            lockTo = 0,
            speed = plan.speed,
            bars = spec.bars,
            beats = spec.bars * spec.b.beatsPerBar,
            anchors = plan.anchors,
        )
    }
}

/**
 * Renders a [LanePlan]: each song cut into its bands, each lane shaped, A stretched to B's beat
 * where the plan says, and both under one headroom worked out from what they really add up to.
 *
 * Twice through: once to find the peaks of the sum, once to render under the headroom they need.
 * The same input gives the same samples every time, which is what makes the second pass the
 * first one's, and the editor's preview the change the phone will play.
 */
class LaneMixer(private val plan: LanePlan) {

    /**
     * Renders the change from A's music [a] (the held end or the editor's copy of it) and B's copy
     * [b]. Each song is kept at its own [attenuationA] and [attenuationB], its loudness
     * normalisation as it plays.
     */
    fun render(a: ShortArray, b: ShortArray, attenuationA: Double, attenuationB: Double): ChangeRender {
        val started = System.nanoTime()
        val channels = plan.channels
        val rate = plan.sampleRate
        val aSource = aSource(a)
        val bSource = PcmSource(b, channels, plan.facts.bFrames.coerceAtMost((b.size / channels).toLong()), plan.bStart)
        val from = plan.mixFrom
        val to = plan.mixTo
        val aTo = min(plan.aGone, to)

        // The first pass: the peak of the sum on each knot of the headroom, counted only where both
        // songs are heard, since a song on its own plays at its own level, as it would without Mix.
        val step = Headroom.STEP
        val knots = ((to - from + step - 1) / step).toInt() + 1
        val peaks = DoubleArray(knots)
        var peakBefore = 0.0
        run {
            val sideA = SideRenderer(plan, Side.A, aSource, from)
            val sideB = SideRenderer(plan, Side.B, bSource, plan.bStart)
            val fa = DoubleArray(channels)
            val fb = DoubleArray(channels)
            var knot = -1
            var aHeard = false
            var bHeard = false
            var knotPeak = 0.0
            fun close() {
                if (knot >= 0 && aHeard && bHeard) peaks[knot] = knotPeak
                aHeard = false
                bHeard = false
                knotPeak = 0.0
            }
            for (o in from until to) {
                val k = ((o - from) / step).toInt()
                if (k != knot) {
                    close()
                    knot = k
                }
                if (o < aTo) sideA.next(o, fa) else fa.fill(0.0)
                if (o >= plan.bStart) sideB.next(o, fb) else fb.fill(0.0)
                for (c in 0 until channels) {
                    val va = fa[c] * attenuationA
                    val vb = fb[c] * attenuationB
                    if (abs(va) >= 1.0) aHeard = true
                    if (abs(vb) >= 1.0) bHeard = true
                    val s = abs(va + vb) / Pcm.FULL_SCALE
                    if (s > knotPeak) knotPeak = s
                    if (s > peakBefore && o >= plan.bothFrom && o < plan.bothTo) peakBefore = s
                }
            }
            close()
        }
        // Frames between two knots are drawn between their values, so each knot must hold the
        // stretch before it as well as its own.
        val loads = DoubleArray(knots) { i -> max(peaks[i], if (i > 0) peaks[i - 1] else 0.0) }
        val headroom = Headroom.solve(from, loads, rate)

        // The second pass, under the headroom.
        val aFrames = (aTo - from).toInt().coerceAtLeast(0)
        val bFrames = (to - plan.bStart).toInt().coerceAtLeast(0)
        val aOut = ShortArray(aFrames * channels)
        val bOut = ShortArray(bFrames * channels)
        var peakAfter = 0.0
        run {
            val sideA = SideRenderer(plan, Side.A, aSource, from)
            val sideB = SideRenderer(plan, Side.B, bSource, plan.bStart)
            val fa = DoubleArray(channels)
            val fb = DoubleArray(channels)
            for (o in from until to) {
                val h = headroom.at(o)
                if (o < aTo) {
                    sideA.next(o, fa)
                    val i = ((o - from) * channels).toInt()
                    for (c in 0 until channels) aOut[i + c] = toPcm16(fa[c] * attenuationA * h)
                }
                if (o >= plan.bStart) {
                    sideB.next(o, fb)
                    val i = ((o - plan.bStart) * channels).toInt()
                    for (c in 0 until channels) bOut[i + c] = toPcm16(fb[c] * attenuationB * h)
                }
                if (o >= plan.bothFrom && o < plan.bothTo) for (c in 0 until channels) {
                    val va = if (o < aTo) aOut[((o - from) * channels).toInt() + c].toInt() else 0
                    val vb = if (o >= plan.bStart) bOut[((o - plan.bStart) * channels).toInt() + c].toInt() else 0
                    val s = abs(va + vb) / Pcm.FULL_SCALE.toDouble()
                    if (s > peakAfter) peakAfter = s
                }
            }
        }
        return ChangeRender(
            plan = plan,
            outgoing = RenderedOutgoing(from, aFrames, channels, aOut, headroom),
            incoming = RenderedIncoming(plan.bStart, bFrames, channels, bOut, b, attenuationB, Math.round(plan.barFrames * plan.spec.beat)),
            headroom = headroom,
            peakBefore = peakBefore,
            peakAfter = peakAfter,
            attenuationA = attenuationA,
            attenuationB = attenuationB,
            renderMillis = (System.nanoTime() - started) / 1e6,
        )
    }

    /** A's music by output frame as the change takes it, before any lane is shaped: stretched where the plan stretches it. For a change's proof. */
    internal fun outgoingSource(a: ShortArray): FrameSource = aSource(a)

    private fun aSource(a: ShortArray): FrameSource {
        val channels = plan.channels
        val frames = plan.facts.aFrames.coerceAtMost((a.size / channels).toLong())
        if (plan.aMap.isIdentity) return PcmSource(a, channels, frames)
        // The stretch lands A's beats from its own guide, a decimated mono copy taken on the change's
        // grid, from bar 1: the analysis's own starts wherever the music at hand starts, and two
        // copies of A that start apart would be searched on other samples and joined elsewhere.
        val factor = Pcm.decimationFor(plan.sampleRate)
        val guideFrom = Math.floorMod(plan.gridOrigin, factor.toLong())
        val decimator = Decimator(plan.sampleRate, factor)
        val mono = FloatList(((frames - guideFrom) / factor + 1).toInt())
        val inverse = 1.0 / (Pcm.FULL_SCALE * channels)
        var i = (guideFrom * channels).toInt()
        val end = (frames * channels).toInt()
        while (i < end) {
            var sum = 0
            for (c in 0 until channels) sum += a[i + c]
            decimator.push(sum * inverse, mono)
            i += channels
        }
        // The stretch is laid out for every frame a band split may read: from the time it settles
        // before the change to its look ahead past it.
        val from = plan.mixFrom - (Engine.SETTLE_S * plan.sampleRate).toLong() - 1
        val to = plan.mixTo + BandSplit.CHUNK + BandSplit.LOOK
        val wsola = Wsola(a, channels, plan.sampleRate, mono.toArray(), factor, plan.anchors, phase = plan.gridOrigin, guideFrom = guideFrom)
            .frames(plan.aMap, from, to)
        return StretchSource(wsola, from, to, channels)
    }
}
