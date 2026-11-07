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
import com.dd3boh.outertune.mix.FrameMap
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.Pcm
import com.dd3boh.outertune.mix.RenderedIncoming
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.Wsola
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.toPcm16
import kotlin.math.abs
import kotlin.math.floor
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
 * B's copy by output frame when it comes in after its first frame: its frame n at [origin] + n,
 * silence before its frame [entry], which it starts on over a few milliseconds of a raised cosine
 * so that the middle of a song never starts with a click.
 */
internal class EntrySource(
    private val pcm: ShortArray,
    private val channels: Int,
    private val frames: Long,
    private val origin: Long,
    private val entry: Long,
    private val fadeFrames: Int,
) : FrameSource {
    override fun frame(t: Long, into: DoubleArray) {
        val i = t - origin
        if (i < entry || i >= frames) {
            for (c in 0 until channels) into[c] = 0.0
            return
        }
        val k = i - entry
        val g = if (k >= fadeFrames) 1.0 else 0.5 - 0.5 * kotlin.math.cos(Math.PI * (k + 1) / (fadeFrames + 1))
        val base = (i * channels).toInt()
        for (c in 0 until channels) into[c] = pcm[base + c] * g
    }
}

/**
 * B stretched by the beat matcher, by output frame: silence before it is heard, then the stretch
 * along its map, rising over a few milliseconds where B comes in after its first frame, then its
 * own samples from where it is back at its own speed, crossed over to in [BStretch.switchFrames],
 * so the change ends on B's own frames, the ones the song as it plays goes on with.
 */
internal class GlideSource(
    private val stretched: Wsola.Frames,
    private val pcm: ShortArray,
    private val channels: Int,
    private val frames: Long,
    private val s: BStretch,
    private val fadeFrames: Int,
) : FrameSource {
    private val own = DoubleArray(channels)
    private val rising = s.map.t0 > 0

    override fun frame(t: Long, into: DoubleArray) {
        if (t < s.heardFrom) {
            for (c in 0 until channels) into[c] = 0.0
            return
        }
        if (t >= s.switchAt + s.switchFrames) {
            ownAt(s.switchFrame + (t - s.switchAt), into)
            return
        }
        stretched.frame(t, into)
        val k = t - s.heardFrom
        if (rising && k < fadeFrames) {
            val g = 0.5 - 0.5 * kotlin.math.cos(Math.PI * (k + 1) / (fadeFrames + 1))
            for (c in 0 until channels) into[c] *= g
        }
        if (t >= s.switchAt) {
            val w = (t - s.switchAt + 1).toDouble() / (s.switchFrames + 1)
            ownAt(s.switchFrame + (t - s.switchAt), own)
            for (c in 0 until channels) into[c] = into[c] * (1 - w) + own[c] * w
        }
    }

    private fun ownAt(i: Long, into: DoubleArray) {
        if (i < 0 || i >= frames) {
            for (c in 0 until channels) into[c] = 0.0
            return
        }
        val base = (i * channels).toInt()
        for (c in 0 until channels) into[c] = pcm[base + c].toDouble()
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
internal class SideRenderer(
    private val plan: LanePlan,
    private val side: Side,
    private val source: FrameSource,
    origin: Long,
    /** The song's parts in [LaneSet.PARTS]'s order, read as [source] reads the song, for a change made on parts. */
    private val parts: List<FrameSource>? = null,
) {
    private val channels = plan.channels
    private var split: BandSplit? = null
    private val settle = (Engine.SETTLE_S * plan.sampleRate).toLong()
    private val lanes = plan.spec.lanes.lanes
    private val voices = lanes.map { LaneVoice(LaneId(side, it), plan, channels) }
    private val bandOf = IntArray(lanes.size) {
        if (parts != null) {
            LaneSet.PARTS.lanes.indexOf(lanes[it]).also { k -> require(k >= 0) { "${lanes[it]} is not a part" } }
        } else when (lanes[it]) {
            Lane.LOWS -> 0
            Lane.MIDS -> 1
            Lane.HIGHS -> 2
            else -> error("${lanes[it]} is not a band")
        }
    }
    private val stream = DoubleArray(channels)
    private val bands = Array(if (parts != null) parts.size else 3) { DoubleArray(channels) }
    private var next = origin

    init {
        require(parts == null || parts.size == LaneSet.PARTS.lanes.size) { "four parts, not ${parts?.size}" }
    }

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
        if (parts != null) {
            // The parts as they are: no filter to settle, and the four add up to the song.
            for (k in parts.indices) parts[k].frame(o, bands[k])
        } else {
            val s = split ?: BandSplit(source, channels, plan.sampleRate, o - settle).also { split = it }
            s.bands(o, stream, bands[0], bands[1], bands[2])
        }
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
     * from, A's by output frame before its tempo moves and B's from its first frame, of which
     * nothing before its entry is heard.
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
                        val j = o - plan.bOrigin
                        attenuate(if (j in plan.bEntry until bFrames) b[(j * channels).toInt() + c] else 0, attenuationB)
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
    @JvmOverloads
    fun heldPlan(checkFrames: Long = 0): MixPlan {
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
            // The incoming song's first frames handed back after the song change, where asked: for
            // the check that a later entry came in on the frame the copy has there.
            lockFrom = 0,
            lockTo = checkFrames,
            speed = plan.speed,
            bars = spec.bars,
            beats = spec.bars * spec.b.beatsPerBar,
            anchors = plan.anchors,
            alignedUntil = plan.outro?.let { floor(plan.aMap.outAt(it.end.toDouble())).toLong() - it.seam } ?: Long.MAX_VALUE,
            bEntry = plan.bEntry,
            // Where B's part is B's own music, the song as it plays: after its way back to its own
            // tempo where it is adjusted, after its loop where it has one, at once otherwise.
            incomingOwnFrom = plan.bStretch?.let { it.switchAt + it.switchFrames } ?: plan.loop?.let { it.outTo + it.seam } ?: Long.MIN_VALUE,
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
    @JvmOverloads
    fun render(
        a: ShortArray,
        b: ShortArray,
        attenuationA: Double,
        attenuationB: Double,
        /** A's parts in [LaneSet.PARTS]'s order, each frame for frame with [a]: for a change made on parts. */
        aParts: List<java.nio.ShortBuffer>? = null,
        /** B's, frame for frame with [b]. */
        bParts: List<java.nio.ShortBuffer>? = null,
    ): ChangeRender {
        val started = System.nanoTime()
        val channels = plan.channels
        val rate = plan.sampleRate
        val aSource = aSource(a)
        val bFrames0 = plan.facts.bFrames.coerceAtMost((b.size / channels).toLong())
        fun bSourceOf(music: ShortArray): FrameSource = when {
            plan.bStretch != null -> stretchedB(music, bFrames0)
            plan.loop != null -> LoopedSource(music, channels, bFrames0, plan.bStart, plan.loop, rate)
            // B from a later bar: none of it before, and no click where it starts.
            plan.bEntry > 0 -> EntrySource(music, channels, bFrames0, plan.bOrigin, plan.bEntry, ENTRY_FADE_MS * rate / 1000)
            else -> PcmSource(music, channels, bFrames0, plan.bStart)
        }
        val bSource = bSourceOf(b)
        // A change made on parts: each part read the way its song is, which the plan keeps to
        // reading as it is (no stretch, no loop: see SpecPlan).
        val onParts = plan.spec.lanes == LaneSet.PARTS
        val aPartSources = if (!onParts) null else {
            val partsOfA = requireNotNull(aParts) { "a change made on parts needs A's parts" }
            check(plan.aMap.isIdentity && plan.outro == null) { "parts are read as they are" }
            val aFrames = plan.facts.aFrames.coerceAtMost((a.size / channels).toLong())
            partsOfA.map { BufferSource(it, channels, aFrames) }
        }
        val bPartSources = if (!onParts) null else {
            val partsOfB = requireNotNull(bParts) { "a change made on parts needs B's parts" }
            check(plan.bStretch == null && plan.loop == null) { "parts are read as they are" }
            // As B's own copy is read: from its first frame at bStart, or from its entry with the same rise.
            partsOfB.map {
                if (plan.bEntry > 0) BufferSource(it, channels, bFrames0, plan.bOrigin, plan.bEntry, ENTRY_FADE_MS * rate / 1000)
                else BufferSource(it, channels, bFrames0, plan.bStart)
            }
        }
        val from = plan.mixFrom
        val to = plan.mixTo
        val aTo = min(plan.aGone, to)
        // B's side from its loop where it has one: what of it comes before B's own first frame is
        // played with A's part, which is all the held end plays until then.
        val bFrom = plan.bFrom
        val outEnd = max(aTo, min(plan.bStart, to))

        // The first pass: the peak of the sum on each knot of the headroom, counted only where both
        // songs are heard, since a song on its own plays at its own level, as it would without Mix.
        val step = Headroom.STEP
        val knots = ((to - from + step - 1) / step).toInt() + 1
        val peaks = DoubleArray(knots)
        var peakBefore = 0.0
        run {
            val sideA = SideRenderer(plan, Side.A, aSource, from, aPartSources)
            val sideB = SideRenderer(plan, Side.B, bSource, bFrom, bPartSources)
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
                if (o >= bFrom) sideB.next(o, fb) else fb.fill(0.0)
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
        val aFrames = (outEnd - from).toInt().coerceAtLeast(0)
        val bFrames = (to - plan.bStart).toInt().coerceAtLeast(0)
        val aOut = ShortArray(aFrames * channels)
        val bOut = ShortArray(bFrames * channels)
        var peakAfter = 0.0
        run {
            val sideA = SideRenderer(plan, Side.A, aSource, from, aPartSources)
            val sideB = SideRenderer(plan, Side.B, bSource, bFrom, bPartSources)
            val fa = DoubleArray(channels)
            val fb = DoubleArray(channels)
            for (o in from until to) {
                val h = headroom.at(o)
                if (o < aTo) sideA.next(o, fa) else fa.fill(0.0)
                if (o >= bFrom) sideB.next(o, fb) else fb.fill(0.0)
                if (o < outEnd) {
                    // A's part, with B's loop in it until B's own first frame.
                    val i = ((o - from) * channels).toInt()
                    val withB = o < plan.bStart
                    for (c in 0 until channels) aOut[i + c] = toPcm16((fa[c] * attenuationA + (if (withB) fb[c] * attenuationB else 0.0)) * h)
                }
                if (o >= plan.bStart) {
                    val i = ((o - plan.bStart) * channels).toInt()
                    for (c in 0 until channels) bOut[i + c] = toPcm16(fb[c] * attenuationB * h)
                }
                if (o >= plan.bothFrom && o < plan.bothTo) for (c in 0 until channels) {
                    val va = if (o < outEnd) aOut[((o - from) * channels).toInt() + c].toInt() else 0
                    val vb = if (o >= plan.bStart) bOut[((o - plan.bStart) * channels).toInt() + c].toInt() else 0
                    val s = abs(va + vb) / Pcm.FULL_SCALE.toDouble()
                    if (s > peakAfter) peakAfter = s
                }
            }
        }
        return ChangeRender(
            plan = plan,
            outgoing = RenderedOutgoing(from, aFrames, channels, aOut, headroom),
            incoming = RenderedIncoming(plan.bStart, bFrames, channels, bOut, b, attenuationB, Math.round(plan.barFrames * plan.spec.beat), entry = plan.bEntry),
            headroom = headroom,
            peakBefore = peakBefore,
            peakAfter = peakAfter,
            attenuationA = attenuationA,
            attenuationB = attenuationB,
            renderMillis = (System.nanoTime() - started) / 1e6,
        )
    }

    companion object {
        /** How long B takes to start when it comes in after its first frame: no click, nothing heard as a fade. */
        const val ENTRY_FADE_MS = 5
    }

    /** B at A's tempo along its map, laid on its own beats, back to its own samples where it is back at its own speed. */
    private fun stretchedB(b: ShortArray, frames: Long): FrameSource {
        val s = plan.bStretch!!
        val channels = plan.channels
        // What is stretched: B as played, its loop's passes laid in where it has one, from its frame [lo].
        val (music, lo) = s.loop?.let { asPlayed(b, frames, s, it) } ?: (b to 0L)
        val musicFrames = (music.size / channels).toLong()
        val factor = Pcm.decimationFor(plan.sampleRate)
        val decimator = Decimator(plan.sampleRate, factor)
        val mono = FloatList((musicFrames / factor + 1).toInt())
        val inverse = 1.0 / (Pcm.FULL_SCALE * channels)
        var i = 0
        val end = (musicFrames * channels).toInt()
        while (i < end) {
            var sum = 0
            for (c in 0 until channels) sum += music[i + c]
            decimator.push(sum * inverse, mono)
            i += channels
        }
        // Laid out for every frame a band split may read before B is heard, up to where its own samples have taken over.
        val from = s.heardFrom - (Engine.SETTLE_S * plan.sampleRate).toLong() - 1
        val to = s.switchAt + s.switchFrames + 1
        val map = if (lo == 0L) s.map else Shifted(s.map, -lo)
        val anchors = if (lo == 0L) s.anchors else DoubleArray(s.anchors.size) { s.anchors[it] - lo }
        val wsola = Wsola(music, channels, plan.sampleRate, mono.toArray(), factor, anchors, phase = plan.gridOrigin).frames(map, from, to)
        return GlideSource(wsola, b, channels, frames, s, ENTRY_FADE_MS * plan.sampleRate / 1000)
    }

    /**
     * B as played with its loop in front, for the stretch: its frames, the loop's passes laid in
     * just before its own bar 1 and joined as with A adjusted ([LoopedSource]), from its frame
     * returned beside them (before its first where the loop starts before it) up to a little past
     * where its own samples take over, which is all the stretch reads.
     */
    internal fun asPlayed(b: ShortArray, frames: Long, s: BStretch, loop: PlannedLoop): Pair<ShortArray, Long> {
        val channels = plan.channels
        val lo = min(0L, loop.outFrom)
        val hi = max(loop.outTo + loop.seam, min(frames, s.switchFrame + s.switchFrames + 2L * Wsola.FRAME))
        val source = LoopedSource(b, channels, frames, 0L, loop, plan.sampleRate)
        val out = ShortArray(((hi - lo) * channels).toInt())
        val frame = DoubleArray(channels)
        for (v in lo until hi) {
            source.frame(v, frame)
            val i = ((v - lo) * channels).toInt()
            for (c in 0 until channels) out[i + c] = toPcm16(frame[c])
        }
        return out to lo
    }

    /** [map] with every frame it gives moved by [by]: B as played, counted from the first frame of a copy of it. */
    private class Shifted(private val map: FrameMap, private val by: Long) : FrameMap {
        override fun tailAt(out: Double): Double = map.tailAt(out) + by
        override fun outAt(tail: Double): Double = map.outAt(tail - by)
        override val inPlaceUntil: Double get() = map.inPlaceUntil
    }

    /** A's music by output frame as the change takes it, before any lane is shaped: stretched where the plan stretches it. For a change's proof. */
    internal fun outgoingSource(a: ShortArray): FrameSource = aSource(a)

    /** B's music by output frame as the change takes it, before any lane is shaped. For a change's proof. */
    internal fun incomingSource(b: ShortArray): FrameSource {
        val frames = plan.facts.bFrames.coerceAtMost((b.size / plan.channels).toLong())
        return if (plan.bStretch != null) stretchedB(b, frames) else error("only B adjusted")
    }

    private fun aSource(held: ShortArray): FrameSource {
        val channels = plan.channels
        // A as heard: with a loop of its bars, the music with the loop in it.
        val a = plan.outro?.extend(held, channels, plan.facts.aFrames, plan.sampleRate) ?: held
        val frames = (plan.facts.aFrames + (plan.outro?.shift ?: 0L)).coerceAtMost((a.size / channels).toLong())
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
