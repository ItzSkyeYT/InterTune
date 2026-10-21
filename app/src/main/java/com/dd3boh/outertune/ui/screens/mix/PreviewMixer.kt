/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.laneAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The preview's sound until the engine's LaneMixer plays the same copies the change will play:
 * the two windows played into each other, each song at the power mean of its lanes' gains at that
 * moment, so every move is heard as a level, but no filter, no echo and no band apart. A
 * beat-matched A is read faster or slower, its pitch moved with it (1.8% on the first test pair),
 * which keeps its beats on B's.
 *
 * Pure, so the PC renders what the phone plays (MixEditorTrial).
 */
class PreviewMixer(private val a: PcmWindow, private val b: PcmWindow) {

    /** A song alone with a click on each beat and a higher one on each 1 (Fix the beat), or null. */
    @Volatile var click: Side? = null

    /** One lane alone, its own level kept (Listen alone), or null. */
    @Volatile var solo: LaneId? = null

    /** The frame [frame] adds to, in full-scale units. */
    var outL = 0.0
    var outR = 0.0

    /** Each song's level at bar [u] into [into]: A's, then B's. */
    fun levels(spec: TransitionSpec, u: Double, into: DoubleArray) {
        val alone = solo
        if (alone != null) {
            val g = spec.laneAt(alone, u).gain
            into[0] = if (alone.side == Side.A) g else 0.0
            into[1] = if (alone.side == Side.B) g else 0.0
            return
        }
        into[0] = powerMean(spec, Side.A, u)
        into[1] = powerMean(spec, Side.B, u)
    }

    /** Adds the change's frame at bar [u], the songs at [gainA] and [gainB], to [outL] and [outR]. */
    fun frame(spec: TransitionSpec, u: Double, gainA: Double, gainB: Double) {
        val side = click
        if (side != null) return clickFrame(spec, side, u)
        if (spec.plain) {
            // Today's change: A to its end, then B from its first frame.
            val aMs = spec.aMsAt(u)
            val aEnd = a.info.durationMs
            if (aMs < aEnd) return add(a, aMs, 1.0)
            val perA = if (spec.beatMatched) spec.b.barMs * spec.tempoRatio() else spec.b.barMs
            val endU = (aEnd - spec.a.atMs) / perA
            return add(b, (u - endU) * spec.b.barMs, 1.0)
        }
        if (gainA > 0.0) add(a, spec.aMsAt(u), gainA)
        val bMs = spec.bMsAt(u)
        if (gainB > 0.0 && bMs >= spec.bStartMs) add(b, bMs, gainB)
    }

    /**
     * [frames] frames of the change from bar [from], at [rate] frames a second, into [out] as
     * interleaved stereo 16-bit, the levels drawn straight across every [block] frames as the
     * preview plays them. For the trial on the PC.
     */
    fun render(spec: TransitionSpec, from: Double, rate: Int, frames: Int, out: ShortArray, block: Int = 512) {
        val step = 1000.0 / rate / spec.b.barMs
        val start = DoubleArray(2)
        val end = DoubleArray(2)
        var u = from
        var f = 0
        while (f < frames) {
            val n = minOf(block, frames - f)
            levels(spec, u, start)
            levels(spec, u + n * step, end)
            for (i in 0 until n) {
                val t = i.toDouble() / n
                outL = 0.0
                outR = 0.0
                frame(spec, u, start[0] + (end[0] - start[0]) * t, start[1] + (end[1] - start[1]) * t)
                out[2 * (f + i)] = pcm(outL)
                out[2 * (f + i) + 1] = pcm(outR)
                u += step
            }
            f += n
        }
    }

    private fun powerMean(spec: TransitionSpec, side: Side, u: Double): Double {
        if (spec.plain) return 1.0
        var sum = 0.0
        val lanes = spec.lanes.lanes
        for (lane in lanes) {
            val g = spec.laneAt(LaneId(side, lane), u).gain
            sum += g * g
        }
        return sqrt(sum / lanes.size)
    }

    /** Adds a song's sample at [ms] of its own clock, read between two frames, scaled by [gain]. */
    private fun add(w: PcmWindow, ms: Double, gain: Double) {
        val x = (ms - w.info.startMs) / 1000.0 * w.info.sampleRate
        val i = floor(x).toInt()
        if (i < 0 || i + 1 >= w.frames) return
        val t = x - i
        outL += (w.at(i, 0) * (1 - t) + w.at(i + 1, 0) * t) * gain
        outR += (w.at(i, 1) * (1 - t) + w.at(i + 1, 1) * t) * gain
    }

    private fun clickFrame(spec: TransitionSpec, side: Side, u: Double) {
        val anchor = if (side == Side.A) spec.a else spec.b
        val ms = if (side == Side.A) spec.aMsAt(u) else spec.bMsAt(u)
        add(if (side == Side.A) a else b, ms, 0.7)
        val beatMs = anchor.barMs / anchor.beatsPerBar
        val beats = (ms - anchor.atMs) / beatMs
        val k = floor(beats).toInt()
        val since = (beats - k) * beatMs
        if (since < CLICK_MS) {
            val hz = if (Math.floorMod(k, anchor.beatsPerBar) == 0) 1_760.0 else 1_320.0
            val c = sin(2 * PI * hz * since / 1000.0) * (1 - since / CLICK_MS) * 9_000.0
            outL += c
            outR += c
        }
    }

    companion object {
        private const val CLICK_MS = 25.0
        private const val KNEE = 24_000.0

        /**
         * A sample as 16 bits, rounded off softly above three quarters of full scale: two songs
         * mastered to full scale overlap above it, and the preview has no headroom of its own.
         */
        fun pcm(x: Double): Short {
            val a = abs(x)
            val y = if (a <= KNEE) a else KNEE + (32_767 - KNEE) * tanh((a - KNEE) / (32_767 - KNEE))
            return (if (x < 0) -y else y).roundToInt().toShort()
        }
    }
}
