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
 * over in output frames [outFrom] until [outTo], each join a crossfade of [seam] frames into the
 * frames that lead into the loop's first, so the jump back is the music going on, not a click.
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
 * B's music by output frame with its loop in front: inside the loop its bars over and over, with
 * B's own frames added where they have begun (a pickup before B's bar 1); elsewhere B's own frame,
 * silence outside its copy. The loop fades in over a join's length where it starts and out where
 * it ends, so it starts and stops on its bar lines without a click.
 */
internal class LoopedSource(
    private val b: ShortArray,
    private val channels: Int,
    private val frames: Long,
    private val bStart: Long,
    private val loop: PlannedLoop,
) : FrameSource {
    private val last = loop.times - 1

    override fun frame(t: Long, into: DoubleArray) {
        val j = t - bStart
        for (c in 0 until channels) into[c] = if (j in 0 until frames) b[(j * channels).toInt() + c].toDouble() else 0.0
        if (t < loop.outFrom || t >= loop.outTo) return
        val step = t - loop.outFrom
        val k = (step / loop.frames).toInt()
        val m = step % loop.frames
        val seam = loop.seam
        val src = loop.srcFrom + m
        var fadeIn = 1.0
        var crossTo = -1L
        var w = 0.0
        if (k == 0 && m < seam) fadeIn = (m + 0.5) / seam
        if (m >= loop.frames - seam) {
            w = (m - (loop.frames - seam) + 0.5) / seam
            // Into the frames that lead into the loop's first, for every pass but the last, which
            // fades out as B's own bar 1 comes in.
            if (k < last) crossTo = loop.srcFrom - (loop.frames - m)
        }
        for (c in 0 until channels) {
            var v = sample(src, c)
            if (w > 0.0) v = if (crossTo >= 0) v * (1 - w) + sample(crossTo, c) * w else v * (1 - w)
            into[c] += v * fadeIn
        }
    }

    private fun sample(frame: Long, c: Int): Double =
        if (frame in 0 until frames) b[(frame * channels).toInt() + c].toDouble() else 0.0
}
