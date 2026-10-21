/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One song's music at hand: interleaved 16-bit frames from [startMs] of the song's own clock. */
class Copy(val pcm: ShortArray, val sampleRate: Int, val channels: Int, val startMs: Double = 0.0) {
    val frames: Int get() = pcm.size / channels
}

/**
 * A change as it will play, ready to be heard: [pcm] holds output frames [fromFrame] until
 * [toFrame], from [Preview.AROUND_BARS] before bar 1 to as many after the change, which the editor
 * loops. The samples are the ones the phone plays for the same spec and the same music, since they
 * come from the same render.
 */
class PreviewWindow(val render: ChangeRender, val pcm: ShortArray, val fromFrame: Long, val toFrame: Long) {
    val plan: LanePlan get() = render.plan
    val frames: Int get() = (toFrame - fromFrame).toInt()

    /** The bar position heard at frame [index] of [pcm], for the playhead and the now line. */
    fun uAt(index: Int): Double = plan.u((fromFrame + index).toDouble())

    /** The frame of [pcm] where bar position [u] is heard, for playing from a bar. */
    fun indexAt(u: Double): Int = (plan.out(u) - fromFrame).roundToInt().coerceIn(0, frames)

    /** In the songs' own clocks, in ms, what is heard at frame [index] of [pcm]: A's place, then B's. */
    fun aMsAt(index: Int): Double = plan.spec.aMsAt(uAt(index))
    fun bMsAt(index: Int): Double = plan.spec.bMsAt(uAt(index))
}

/**
 * The editor's way to hear a change: the same plan and the same renderer as playback, from the
 * two copies the editor read. Rendering an 8-bar change takes a fraction of a second on the PC;
 * the editor renders again when the spec changes, and its preview engine plays the window.
 */
object Preview {

    /** The bars around the change the window holds, either side, as the canvas shows them. */
    const val AROUND_BARS = 2.0

    /**
     * The window for [spec] from A's copy [a] and B's copy [b]. A change made on parts is heard on
     * bands until parts are there, as it would play. [aAnalysis], an analysis of A's copy, gives
     * the stretch A's own beats to land, where it was made already. Fails with why when the copies
     * cannot hold the change, or are not in one format.
     */
    fun render(
        spec: TransitionSpec, a: Copy, b: Copy, aAnalysis: RegionAnalysis? = null,
        attenuationA: Double = 1.0, attenuationB: Double = 1.0,
    ): Result<PreviewWindow> {
        if (a.sampleRate != b.sampleRate || a.channels != b.channels) {
            return Result.failure(IllegalArgumentException("the two copies are in different formats"))
        }
        val bands = Presets.onBands(spec)
        val rate = a.sampleRate
        val anchors = aAnalysis?.takeIf { it.beatsConfident && bands.beatMatched }?.beatFrames()
        val facts = ChangeFacts(
            sampleRate = rate,
            channels = a.channels,
            aBar1 = (bands.a.atMs - a.startMs) * rate / 1000.0,
            aFrames = a.frames.toLong(),
            bBar1 = (bands.b.atMs - b.startMs) * rate / 1000.0,
            bFrames = b.frames.toLong(),
            anchors = anchors,
        )
        val plan = when (val r = SpecPlan.plan(bands, facts)) {
            is SpecPlan.Result.Planned -> r.plan
            is SpecPlan.Result.Refused -> return Result.failure(IllegalStateException(r.why))
        }
        val render = LaneMixer(plan).render(a.pcm, b.pcm, attenuationA, attenuationB)
        val from = floor(plan.out(-AROUND_BARS)).toLong().coerceAtLeast(0)
        val to = maxOf(ceil(plan.out(bands.lengthBars + AROUND_BARS)).toLong(), plan.mixTo)
        return Result.success(PreviewWindow(render, render.mix(a.pcm, b.pcm, from, to), from, to))
    }
}
