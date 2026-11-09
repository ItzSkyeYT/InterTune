/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One song's music at hand: interleaved 16-bit frames from [startMs] of the song's own clock, the
 * first [frames] of [pcm] (all of it unless said: a held end is a ring only partly written).
 */
class Copy(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val startMs: Double = 0.0,
    val frames: Int = pcm.size / channels,
    /** The song's parts over the same frames, in [LaneSet.PARTS]'s order, where the parts store has them. */
    val parts: List<java.nio.ShortBuffer>? = null,
)

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
     * the parts where both copies have them and the plan reads them as they are; on bands
     * otherwise, as it would play. [aAnalysis], an analysis of A's copy, gives the stretch A's own
     * beats to land, where it was made already. Fails with why when the copies cannot hold the
     * change, or are not in one format.
     */
    fun render(
        spec: TransitionSpec, a: Copy, b: Copy, aAnalysis: RegionAnalysis? = null,
        attenuationA: Double = 1.0, attenuationB: Double = 1.0,
    ): Result<PreviewWindow> {
        val p = planned(spec, a, b, aAnalysis).getOrElse { return Result.failure(it) }
        val render = LaneMixer(p.plan).render(a.pcm, b.pcm, attenuationA, attenuationB, p.aParts, p.bParts)
        return Result.success(PreviewWindow(render, render.mix(a.pcm, b.pcm, p.from, p.to), p.from, p.to))
    }

    /**
     * The same window as [render], planned now and worked out as it is asked for, from any frame
     * ([PreviewStream.from]): what an edit is heard from at once, where the playhead is. The
     * costly part of its setup, the stretch, is made here, once for every stream of it.
     */
    fun stream(
        spec: TransitionSpec, a: Copy, b: Copy, aAnalysis: RegionAnalysis? = null,
        attenuationA: Double = 1.0, attenuationB: Double = 1.0,
    ): Result<PreviewStream> {
        val p = planned(spec, a, b, aAnalysis).getOrElse { return Result.failure(it) }
        val sources = LaneMixer(p.plan).sources(a.pcm, b.pcm, p.aParts, p.bParts)
        return Result.success(PreviewStream(p.plan, p.from, p.to) { first -> ChangeStream(p.plan, a.pcm, b.pcm, attenuationA, attenuationB, sources, first) })
    }

    /** A change planned for the editor's copies: on parts or on bands, and the window it is heard over. */
    private class Planned(val plan: LanePlan, val aParts: List<java.nio.ShortBuffer>?, val bParts: List<java.nio.ShortBuffer>?, lengthBars: Double) {
        val from = floor(plan.out(-AROUND_BARS)).toLong().coerceAtLeast(0)
        val to = maxOf(ceil(plan.out(lengthBars + AROUND_BARS)).toLong(), plan.mixTo)
    }

    private fun planned(spec: TransitionSpec, a: Copy, b: Copy, aAnalysis: RegionAnalysis?): Result<Planned> {
        if (a.sampleRate != b.sampleRate || a.channels != b.channels) {
            return Result.failure(IllegalArgumentException("the two copies are in different formats"))
        }
        if (spec.lanes == LaneSet.PARTS && a.parts != null && b.parts != null) {
            onParts(spec, a, b)?.let { return Result.success(Planned(it, a.parts, b.parts, spec.lengthBars)) }
        }
        val bands = Presets.onBands(spec)
        val rate = a.sampleRate
        val anchors = aAnalysis?.takeIf { it.beatsConfident && bands.beatMatched && it.followsTempo(bands.a.bpm) }?.beatFrames()
        val facts = ChangeFacts(
            sampleRate = rate,
            channels = a.channels,
            aBar1 = (bands.a.atMs - a.startMs) * rate / 1000.0,
            aFrames = a.frames.toLong(),
            bBar1 = (bands.b.atMs - b.startMs) * rate / 1000.0,
            bFrames = b.frames.toLong(),
            anchors = anchors,
        )
        return when (val r = SpecPlan.plan(bands, facts)) {
            is SpecPlan.Result.Planned -> Result.success(Planned(r.plan, null, null, bands.lengthBars))
            is SpecPlan.Result.Refused -> Result.failure(IllegalStateException(r.why))
        }
    }

    /** [spec] planned on the two copies' parts, or null where the plan does not read parts (a stretch, a loop). */
    private fun onParts(spec: TransitionSpec, a: Copy, b: Copy): LanePlan? {
        val rate = a.sampleRate
        val facts = ChangeFacts(
            sampleRate = rate,
            channels = a.channels,
            aBar1 = (spec.a.atMs - a.startMs) * rate / 1000.0,
            aFrames = a.frames.toLong(),
            bBar1 = (spec.b.atMs - b.startMs) * rate / 1000.0,
            bFrames = b.frames.toLong(),
            parts = true,
        )
        return (SpecPlan.plan(spec, facts) as? SpecPlan.Result.Planned)?.plan
    }
}

/**
 * A change's window as [Preview.render] has it, worked out as it is asked for: output frames
 * [fromFrame] until [toFrame], from any of them ([from]), the same samples a whole render gives once
 * past the stream's settling ([ChangeStream]).
 */
class PreviewStream internal constructor(val plan: LanePlan, val fromFrame: Long, val toFrame: Long, private val make: (Long) -> ChangeStream) {
    val frames: Int get() = (toFrame - fromFrame).toInt()

    /** Whether an echo rings in it: streamed from inside the change, its earliest repeats are heard short. */
    val echoes: Boolean get() = plan.spec.lanes.ids.any { plan.echoes(it) }

    /** The bar position heard at frame [index] of the window. */
    fun uAt(index: Int): Double = plan.u((fromFrame + index).toDouble())

    /** The frame of the window where bar position [u] is heard. */
    fun indexAt(u: Double): Int = (plan.out(u) - fromFrame).roundToInt().coerceIn(0, frames)

    /** The window's frames from its frame [index] on. */
    fun from(index: Int): ChangeStream = make(fromFrame + index)
}
