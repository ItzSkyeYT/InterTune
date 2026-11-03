/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A loop of B's bars as a change plays it: [frames] frames of B's copy from [srcFrom], over and
 * over in output frames [outFrom] until [outTo]. Each new pass starts with a crossfade of [seam]
 * frames out of what follows the looped bars in B (its next bar line) into the loop's first: two
 * bar lines of the same song meet there, so the jump back is the music going on, not a click.
 */
class PlannedLoop(val srcFrom: Long, val frames: Long, val outFrom: Long, val outTo: Long, val seam: Int) {
    val times: Int get() = ((outTo - outFrom) / frames).toInt()

    /** The output frames where one pass of the loop ends and the next begins. */
    fun joins(): LongArray = LongArray((times - 1).coerceAtLeast(0)) { outFrom + (it + 1) * frames }
}

object Loops {
    /** How long each join of a loop is crossfaded over: well under a beat, long enough not to click. */
    const val SEAM_MS = 5.0

    fun seamFrames(rate: Int): Int = max(1, (SEAM_MS * rate / 1000).roundToInt())

    /** What a pass of a loop comes out of at each join. */
    enum class Join {
        /** What follows the looped bars in the song (its next bar line): the music going on. */
        AFTER,

        /** What leads into the looped bars in the song: as the song itself comes into them. */
        BEFORE,

        /** Nothing: the pass ends and the next begins each over half a join, where neither of the others is as loud as the loop's start. */
        DIP,
    }

    /** How far, in level, the music a join comes out of may be from the loop's first frames: twice, or half. */
    private const val LEVEL_REACH = 2.0

    /**
     * The join for a loop of [frames] frames from [src] in [music]: the side of the looped bars whose
     * level nearest matches the loop's own first frames, within [LEVEL_REACH]; a dip where neither
     * does. Starboy's first bar is quiet and its second starts loud: out of the second at each join,
     * every pass would start with a burst of it.
     */
    fun joinFor(music: ShortArray, channels: Int, total: Long, src: Long, frames: Long, rate: Int): Join {
        val w = max(1, rate / 100)
        fun rms(from: Long): Double {
            var sum = 0.0
            var n = 0
            for (f in from until from + w) {
                if (f < 0 || f >= total) continue
                for (c in 0 until channels) {
                    val v = music[(f * channels).toInt() + c].toDouble()
                    sum += v * v
                }
                n += channels
            }
            return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
        }
        val start = rms(src)
        val after = rms(src + frames)
        val before = rms(src - w)
        fun off(v: Double) = if (start <= 1.0 || v <= 1.0) (if (start <= 1.0 && v <= 1.0) 1.0 else Double.MAX_VALUE) else kotlin.math.abs(kotlin.math.ln(v / start))
        val reach = kotlin.math.ln(LEVEL_REACH)
        val best = listOf(Join.AFTER to off(after), Join.BEFORE to off(before)).filter { src - w >= 0 || it.first != Join.BEFORE }.minByOrNull { it.second }
        return if (best != null && best.second <= reach) best.first else Join.DIP
    }
}

/**
 * B's music by output frame with its loop in front: inside the loop its bars over and over, each
 * join made as [Loops.joinFor] chose for these bars; after it B's own frames, which the last pass
 * hands over to the same way; before it, B's own frames, silence outside its copy. The first pass
 * fades in over a join's length, out of B's own frames where they have begun.
 */
internal class LoopedSource(
    private val b: ShortArray,
    private val channels: Int,
    private val frames: Long,
    private val bStart: Long,
    private val loop: PlannedLoop,
    rate: Int,
) : FrameSource {
    private val own = DoubleArray(channels)
    private val join = Loops.joinFor(b, channels, frames, loop.srcFrom, loop.frames, rate)
    private val seam = loop.seam
    private val end = loop.srcFrom + loop.frames

    override fun frame(t: Long, into: DoubleArray) {
        ownFrame(t, own)
        if (t < loop.outFrom || t >= loop.outTo + seam) {
            for (c in 0 until channels) into[c] = own[c]
            return
        }
        if (t >= loop.outTo) {
            // Into B's own bar 1, after the last pass.
            val i = t - loop.outTo
            val w = (i + 0.5) / seam
            for (c in 0 until channels) {
                into[c] = when (join) {
                    Loops.Join.AFTER -> own[c] * w + sample(end + i, c) * (1 - w)
                    Loops.Join.BEFORE -> own[c]
                    Loops.Join.DIP -> own[c] * w
                }
            }
            return
        }
        val step = t - loop.outFrom
        val k = step / loop.frames
        val m = step % loop.frames
        val last = k == (loop.times - 1).toLong()
        for (c in 0 until channels) {
            var v = sample(loop.srcFrom + m, c)
            if (m < seam) {
                // A pass begins: the first out of B's own frames, the others as the join says.
                val w = (m + 0.5) / seam
                v = when {
                    k == 0L -> v * w + own[c] * (1 - w)
                    join == Loops.Join.AFTER -> v * w + sample(end + m, c) * (1 - w)
                    join == Loops.Join.DIP -> v * w
                    else -> v
                }
            } else if (m >= loop.frames - seam) {
                // A pass ends: into what leads into the bars (or into B's own lead-in to its bar 1
                // after the last), or fading for a dip.
                val i = m - (loop.frames - seam)
                val w = (i + 0.5) / seam
                v = when (join) {
                    Loops.Join.BEFORE -> v * (1 - w) + (if (last) own[c] else sample(loop.srcFrom - seam + i, c)) * w
                    Loops.Join.DIP -> v * (1 - w)
                    Loops.Join.AFTER -> v
                }
            }
            into[c] = v
        }
    }

    private fun ownFrame(t: Long, into: DoubleArray) {
        val j = t - bStart
        for (c in 0 until channels) into[c] = if (j in 0 until frames) b[(j * channels).toInt() + c].toDouble() else 0.0
    }

    private fun sample(frame: Long, c: Int): Double =
        if (frame in 0 until frames) b[(frame * channels).toInt() + c].toDouble() else 0.0
}

/**
 * A loop of A's bars as a change plays it: [frames] frames of A's music from [srcFrom], played
 * [times] more times right after the first, each pass joined as B's loop is, out of what follows the
 * looped bars into their first; then A goes on from the end of the bars. A's frames after them come
 * [times] x [frames] later.
 */
class PlannedOutroLoop(val srcFrom: Long, val frames: Long, val times: Int, val seam: Int) {
    val end: Long get() = srcFrom + frames
    val shift: Long get() = frames * times

    /** Where a frame of A's music is heard in A as looped. */
    fun virtual(frame: Double): Double = if (frame >= end) frame + shift else frame

    /** A's music with the loop in it: the bars [times] more times, each join made as [Loops.joinFor] chose for them. */
    fun extend(a: ShortArray, channels: Int, aFrames: Long, rate: Int): ShortArray {
        val n = aFrames.coerceAtMost((a.size / channels).toLong())
        val join = Loops.joinFor(a, channels, n, srcFrom, frames, rate)
        val out = ShortArray(((n + shift) * channels).toInt())
        val endI = end.toInt()
        System.arraycopy(a, 0, out, 0, endI * channels)
        fun at(f: Long, c: Int): Double = if (f in 0 until n) a[(f * channels).toInt() + c].toDouble() else 0.0
        for (k in 0 until times) {
            val passAt = end + k * frames
            for (m in 0 until frames.toInt()) {
                for (c in 0 until channels) {
                    var v = at(srcFrom + m, c)
                    if (m < seam) {
                        val w = (m + 0.5) / seam
                        v = when (join) {
                            Loops.Join.AFTER -> v * w + at(end + m, c) * (1 - w)
                            Loops.Join.DIP -> v * w
                            Loops.Join.BEFORE -> v
                        }
                    }
                    out[((passAt + m) * channels).toInt() + c] = com.dd3boh.outertune.mix.toPcm16(v)
                }
            }
            // The end of what comes before each pass (the bars as first played, or the pass before).
            if (join != Loops.Join.AFTER) for (i in 0 until seam) {
                val w = (i + 0.5) / seam
                val f = passAt - seam + i
                for (c in 0 until channels) {
                    val v = out[(f * channels).toInt() + c].toDouble()
                    out[(f * channels).toInt() + c] = com.dd3boh.outertune.mix.toPcm16(
                        if (join == Loops.Join.BEFORE) v * (1 - w) + at(srcFrom - seam + i, c) * w else v * (1 - w),
                    )
                }
            }
        }
        val rest = (n - end).toInt()
        if (rest > 0) System.arraycopy(a, endI * channels, out, ((end + shift) * channels).toInt(), rest * channels)
        return out
    }
}
