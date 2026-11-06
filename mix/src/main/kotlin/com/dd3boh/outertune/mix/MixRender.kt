/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The outgoing song's part of a transition, rendered ahead of time: 16 bit, interleaved, output
 * frames [firstFrame] to [firstFrame] + [frames]. Through the time map, its filters, its gain, its
 * own attenuation and the shared [headroom], so all that is left to do live is to add it. The
 * incoming song is shaped with the same [headroom].
 */
class RenderedOutgoing(val firstFrame: Long, val frames: Int, val channels: Int, val samples: ShortArray, val headroom: Headroom) {

    val endFrame: Long get() = firstFrame + frames

    /** The sample of channel [channel] at output frame [frame], silence outside. */
    fun at(frame: Long, channel: Int): Short {
        val i = frame - firstFrame
        return if (i < 0 || i >= frames) 0 else samples[(i * channels + channel).toInt()]
    }
}

/**
 * The incoming song's part of a change, rendered from its copy: output frames [firstFrame] until
 * [endFrame], at the level [attenuation]. [copy] is the copy it was rendered from, which the stage
 * holds the song as it really plays against: they are the same decode, so they should agree to
 * the sample. [beatFrames] is one of its beats, what the outgoing song fades over if they do not.
 */
class RenderedIncoming(
    val firstFrame: Long,
    val frames: Int,
    val channels: Int,
    val samples: ShortArray,
    val copy: ShortArray,
    val attenuation: Double,
    val beatFrames: Long,
) {
    val endFrame: Long get() = firstFrame + frames
    val copyFrames: Int get() = copy.size / channels

    fun at(frame: Long, channel: Int): Short {
        val i = frame - firstFrame
        return if (i < 0 || i >= frames) 0 else samples[(i * channels + channel).toInt()]
    }
}

/**
 * Two songs' samples added as the stage adds them. Where only one of them sounds it is given out
 * as it is, so a song on its own is never bent; where both do, whatever the headroom left above
 * its ceiling is bent smoothly under full scale, as the prototype's mixer does.
 */
object ChangeSum {
    fun add(a: Double, b: Double): Short = when {
        a == 0.0 -> toPcm16(b)
        b == 0.0 -> toPcm16(a)
        else -> toPcm16(Mixer.limit(a + b))
    }

    /** Whether the two together would have gone past full scale but for the bend: expected never. */
    fun wouldClip(a: Double, b: Double): Boolean {
        if (a == 0.0 || b == 0.0) return false
        val r = Math.floor(a + b + 0.5)
        return r > 32767.0 || r < -32768.0
    }
}

/** The renderers. The same code renders the trials on the PC and plays on the phone. */
object MixRender {

    /**
     * The outgoing song's part of [plan], from its first processed frame to the frame it is gone,
     * from the held [tail] and the decimated [guide] the tail's analysis made. [anchors] are the
     * tail frames that must land exactly where the time map puts them: its beats, in a beat mix.
     */
    fun renderOutgoing(plan: MixPlan, tail: ShortArray, guide: FloatArray, guideFactor: Int, anchors: DoubleArray = DoubleArray(0)): RenderedOutgoing {
        val channels = plan.channels
        val from = plan.aFirstProcessed
        val to = max(from, plan.aGone)
        val frames = (to - from).toInt()
        if (frames == 0) return RenderedOutgoing(from, 0, channels, ShortArray(0), plan.headroom)
        val wsola = Wsola(tail, channels, plan.sampleRate, guide, guideFactor, anchors).frames(plan.aMap, from, to)

        // Twice through: once to find the outgoing song's peaks as rendered, after its filters and
        // its gain, which can be higher than the tail's own (a filter shifts phases and can raise
        // a peak by several percent), and once to render it under the headroom those peaks need.
        val room = plan.headroom
        val headroom = if (room.values.isEmpty()) room else {
            val peaks = DoubleArray(room.values.size)
            pass(plan, wsola, from, to) { o, frame ->
                val knot = ((o - room.start) / Headroom.STEP).toInt()
                if (knot in peaks.indices) for (c in 0 until channels) peaks[knot] = max(peaks[knot], kotlin.math.abs(frame[c]) / Pcm.FULL_SCALE)
            }
            // Each knot's load through the overlap: the outgoing song's highest peak within 25 ms of
            // it, plus the incoming one's share. Outside the overlap there is nothing to hold, as the
            // plan has it, so the rendered part never dips where the raw tail before it did not.
            val spread = kotlin.math.ceil(0.025 * plan.sampleRate / Headroom.STEP).toInt()
            val loads = DoubleArray(peaks.size) { i ->
                val o = room.start + i.toDouble() * Headroom.STEP
                if (o < plan.overlapStart || o > plan.overlapEnd) 0.0 else {
                    var p = 0.0
                    for (j in max(0, i - spread)..min(peaks.size - 1, i + spread)) p = max(p, peaks[j])
                    p + plan.incomingLoad.getOrElse(i) { 0.0 }
                }
            }
            Headroom.solve(room.start, loads, plan.sampleRate)
        }
        val out = ShortArray(frames * channels)
        pass(plan, wsola, from, to) { o, frame ->
            val h = headroom.at(o)
            val i = ((o - from) * channels).toInt()
            for (c in 0 until channels) out[i + c] = toPcm16(frame[c] * h)
        }
        return RenderedOutgoing(from, frames, channels, out, headroom)
    }

    /**
     * The outgoing song from [from] to [to] through the stretch, its filters, its gain and its
     * attenuation, frame by frame into [each]; everything but the headroom.
     */
    private inline fun pass(plan: MixPlan, wsola: Wsola.Frames, from: Long, to: Long, each: (Long, DoubleArray) -> Unit) {
        val filters = plan.filtersA.map { MovingFilter(it, plan.channels, plan.sampleRate) }
        val frame = DoubleArray(plan.channels)
        for (o in from until to) {
            wsola.frame(o, frame)
            for (f in filters) f.process(o, frame)
            // Where nothing moves yet, the gain is exactly the attenuation and the sample is still
            // the tail's own, so this rounds exactly as the raw path does and the two join.
            val gain = plan.gainA.at(o.toDouble()) * plan.attenuationA
            for (c in 0 until plan.channels) frame[c] *= gain
            each(o, frame)
        }
    }
}

/**
 * The incoming song shaped live, block by block: its filters, its gain, its attenuation and the
 * shared headroom, by absolute frame, so any block size gives the same result.
 */
class IncomingShaper(private val plan: MixPlan, private val headroom: Headroom = plan.headroom) {

    private val channels = plan.channels
    private val filters = plan.filtersB.map { MovingFilter(it, channels, plan.sampleRate) }
    private val frame = DoubleArray(channels)

    /**
     * Shapes [frames] frames of the incoming song from [input] at [offset] (frames), starting at the
     * song's own frame [bFrame], into [out] at [outOffset] (frames), in full-scale units. [levels]
     * holds the song's attenuation for each of those frames, where it may move while the song
     * plays; without it, the plan's.
     */
    @JvmOverloads
    fun shape(input: ShortArray, offset: Int, frames: Int, bFrame: Long, out: DoubleArray, outOffset: Int = 0, levels: DoubleArray? = null) {
        for (i in 0 until frames) {
            val o = plan.bStart + bFrame + i
            for (c in 0 until channels) frame[c] = input[(offset + i) * channels + c].toDouble()
            for (f in filters) f.process(o, frame)
            val gain = plan.gainB.at(o.toDouble()) * (levels?.get(i) ?: plan.attenuationB) * headroom.at(o)
            for (c in 0 until channels) out[(outOffset + i) * channels + c] = frame[c] * gain
        }
    }
}

/**
 * Adds the two songs and holds the sum to 16 bit. The headroom keeps the sum under [KNEE]; what
 * still goes past it, where the estimate fell short, is bent smoothly into the little room left
 * under full scale instead of being cut flat there, and every sample that would have gone past
 * full scale is counted.
 */
class Mixer {
    /** Samples that would have gone past full scale since this mixer was made: expected to stay at zero. */
    var clipped = 0L
        private set

    /**
     * Writes [frames] frames into [out] at [outOffset]: the shaped incoming song from [b] plus the
     * outgoing song's rendered part at output frames from [firstOutput], scaled by [aScale].
     */
    fun mix(a: RenderedOutgoing?, firstOutput: Long, b: DoubleArray, bOffset: Int, frames: Int, out: ShortArray, outOffset: Int, aScale: Double = 1.0) {
        val channels = a?.channels ?: (b.size / max(1, frames))
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                var v = (a?.at(firstOutput + i, c)?.toDouble() ?: 0.0) * aScale + b[(bOffset + i) * channels + c]
                if (v > KNEE || v < -KNEE) {
                    val r = Math.floor(v + 0.5)
                    if (r > 32767.0 || r < -32768.0) clipped++
                    v = limit(v)
                }
                out[(outOffset + i) * channels + c] = toPcm16(v)
            }
        }
    }

    companion object {
        /** Where the limit starts to bend the sum: the ceiling the headroom holds it under. */
        const val KNEE = Headroom.CEILING * Pcm.FULL_SCALE

        /**
         * [v] past [KNEE] bent into what is left under full scale, the way the volume boost's soft
         * clip does: continuous in value and slope at the knee, and never reaching full scale.
         */
        fun limit(v: Double): Double {
            val m = kotlin.math.abs(v)
            if (m <= KNEE) return v
            val range = 32767.0 - KNEE
            val over = m - KNEE
            val bent = KNEE + range * (over / (over + range))
            return if (v < 0) -bent else bent
        }
    }
}

/**
 * Whether the incoming song as it is heard lines up with the opening that was analysed: the two
 * onset curves over the same stretch, cross-correlated. A decode that came out a few milliseconds
 * off, or not the same stream at all, shows here before the beats can drift into a flam.
 */
object Lock {

    class Result(val offsetMs: Double, val match: Double)

    /**
     * The lag of [live] against [expected], both onset curves of [frameRate] frames a second over
     * the same stretch, searched up to [maxMs] either way, refined by a parabola; and the
     * normalised correlation at that lag, 1 for the same stream.
     */
    fun measure(expected: FloatArray, live: FloatArray, frameRate: Double, maxMs: Double = 60.0): Result {
        val n = min(expected.size, live.size)
        val maxLag = max(1, (maxMs / 1000.0 * frameRate).toInt())
        val scores = DoubleArray(2 * maxLag + 1)
        for (lag in -maxLag..maxLag) {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (t in 0 until n) {
                val u = t + lag
                if (u !in 0 until n) continue
                val x = expected[t].toDouble()
                val y = live[u].toDouble()
                ab += x * y
                aa += x * x
                bb += y * y
            }
            val norm = sqrt(aa * bb)
            scores[lag + maxLag] = if (norm < 1e-12) 0.0 else ab / norm
        }
        var best = maxLag
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        var lag = (best - maxLag).toDouble()
        if (best in 1 until scores.size - 1) {
            val a = scores[best - 1]
            val b = scores[best]
            val c = scores[best + 1]
            val d = a - 2 * b + c
            if (d < 0) lag += (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
        }
        return Result(lag / frameRate * 1000.0, scores[best])
    }
}
