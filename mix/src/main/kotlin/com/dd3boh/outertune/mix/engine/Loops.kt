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
}

/**
 * B's music by output frame with its loop in front: inside the loop its bars over and over;
 * after it, B's own frames, crossfaded in over a join's length out of what follows the looped bars,
 * as every pass starts; before it, B's own frames, silence outside its copy. The loop fades in over
 * a join's length where it starts, under the outgoing song.
 */
internal class LoopedSource(
    private val b: ShortArray,
    private val channels: Int,
    private val frames: Long,
    private val bStart: Long,
    private val loop: PlannedLoop,
) : FrameSource {
    private val own = DoubleArray(channels)

    override fun frame(t: Long, into: DoubleArray) {
        val seam = loop.seam
        val after = loop.srcFrom + loop.frames
        if (t >= loop.outTo && t < loop.outTo + seam) {
            // Into B's own bar 1, out of what follows the loop's last pass in B.
            ownFrame(t, own)
            val i = t - loop.outTo
            val w = (i + 0.5) / seam
            for (c in 0 until channels) into[c] = own[c] * w + sample(after + i, c) * (1 - w)
            return
        }
        if (t < loop.outFrom || t >= loop.outTo) {
            ownFrame(t, into)
            return
        }
        val step = t - loop.outFrom
        val k = step / loop.frames
        val m = step % loop.frames
        if (m >= seam) {
            for (c in 0 until channels) into[c] = sample(loop.srcFrom + m, c)
            return
        }
        val w = (m + 0.5) / seam
        // The first pass comes out of B's own frames where they have begun (a long intro before
        // its bar 1), else out of nothing; every other comes out of B's next bar line.
        if (k == 0L) ownFrame(t, own)
        for (c in 0 until channels) {
            val v = sample(loop.srcFrom + m, c)
            into[c] = v * w + (if (k == 0L) own[c] else sample(after + m, c)) * (1 - w)
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

    /** A's music with the loop in it: the bars [times] more times, each pass crossfaded in over [seam] frames. */
    fun extend(a: ShortArray, channels: Int, aFrames: Long): ShortArray {
        val n = aFrames.coerceAtMost((a.size / channels).toLong())
        val out = ShortArray(((n + shift) * channels).toInt())
        val endI = end.toInt()
        System.arraycopy(a, 0, out, 0, endI * channels)
        for (k in 0 until times) {
            val at = (end + k * frames).toInt()
            for (m in 0 until frames.toInt()) {
                for (c in 0 until channels) {
                    val v = a[(srcFrom.toInt() + m) * channels + c].toDouble()
                    out[(at + m) * channels + c] = if (m < seam) {
                        // Out of what follows the bars (A's own next bar line) into their first.
                        val w = (m + 0.5) / seam
                        val follow = if (endI + m < n) a[(endI + m) * channels + c].toDouble() else 0.0
                        com.dd3boh.outertune.mix.toPcm16(v * w + follow * (1 - w))
                    } else v.toInt().toShort()
                }
            }
        }
        val rest = (n - end).toInt()
        if (rest > 0) System.arraycopy(a, endI * channels, out, ((end + shift) * channels).toInt(), rest * channels)
        return out
    }
}
