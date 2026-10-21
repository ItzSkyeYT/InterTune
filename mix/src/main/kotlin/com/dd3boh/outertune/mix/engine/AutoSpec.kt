/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.abs

/**
 * Auto as a spec: the planner chooses where and how long, and the change is written as the
 * preset its style maps to, on bands (DESIGN.md 3.7): Beat to Fade, Smooth to Rise, Blend to
 * Blend, Cut to Cut. Gapless stays the plain change. The spec is placed to the frame from the
 * planner's own numbers, so the beats meet exactly where the planner lined them up.
 */
object AutoSpec {

    /** Auto's spec and the facts that place it in the held end and the incoming song's copy. */
    class Made(val spec: TransitionSpec, val facts: ChangeFacts)

    /**
     * The spec for [plan], from songs [aId] into [bId]. [tailStartMs] is where the held end starts
     * in A, for the spec's anchor only; [bFrames] how much of B's copy there is; [earliest] the
     * first output frame anything may change on. Null for a gapless plan.
     */
    fun of(plan: MixPlan, tail: RegionAnalysis, head: RegionAnalysis, aId: String, bId: String, tailStartMs: Double, bFrames: Long, earliest: Long): Made? {
        val rate = plan.sampleRate
        val start = plan.overlapStart
        val end = plan.overlapEnd
        fun ms(frames: Double) = frames * 1000.0 / rate
        return when (plan.style) {
            Style.GAPLESS -> null
            Style.BEAT -> {
                val bpb = plan.beats / plan.bars
                val periodB = (end - start) / plan.beats
                val bpmB = 60.0 * rate / periodB
                val aBar1 = plan.aMap.tailAt(start)
                val bBar1 = start - plan.bStart
                val spec = TransitionSpec(
                    a = Anchor(aId, tailStartMs + ms(aBar1), bpmB / plan.speed, bpb),
                    b = Anchor(bId, ms(bBar1), bpmB, bpb),
                    lanes = LaneSet.BANDS,
                    bars = plan.bars,
                    beatMatch = true,
                    preset = Preset.AUTO,
                    from = Preset.FADE,
                    moves = Presets.moves(Preset.FADE, LaneSet.BANDS, plan.bars.toDouble(), 1.0 / bpb)!!,
                )
                Made(spec, ChangeFacts(rate, plan.channels, aBar1, plan.tailFrames, bBar1, bFrames, earliest, plan.aMap, plan.anchors))
            }
            Style.CUT -> {
                // Bar 1 is the line A leaves on and B's first downbeat lands on; both songs cross
                // over the beat before it, as the planner has it.
                val bars = tail.barsConfident && head.barsConfident && tail.bpm != null && head.bpm != null
                val bpb = if (bars) head.downbeats!!.beatsPerBar else 4
                val bpmB = if (bars) head.bpm!! else TransitionSpec.NOMINAL_BPM
                val bpmA = if (bars) tail.bpm!! else TransitionSpec.NOMINAL_BPM
                val barFrames = bpb * 60.0 / bpmB * rate
                val len = (end - start) / barFrames
                val aBar1 = end
                val bBar1 = end - plan.bStart
                val lanes = LaneSet.BANDS
                val moves = lanes.ids.associateWith { Move(-len, len, How.FADE) }
                val spec = TransitionSpec(
                    a = Anchor(aId, tailStartMs + ms(aBar1), bpmA, bpb),
                    b = Anchor(bId, ms(bBar1), bpmB, bpb),
                    lanes = lanes,
                    bars = if (bars) 2 else 0,
                    seconds = if (bars) 0.0 else TransitionSpec.LENGTHS_SECONDS.first(),
                    beatMatch = false,
                    preset = Preset.AUTO,
                    from = Preset.CUT,
                    moves = moves,
                )
                Made(spec, ChangeFacts(rate, plan.channels, aBar1, plan.tailFrames, bBar1, bFrames, earliest))
            }
            Style.BLEND, Style.SMOOTH -> {
                val preset = if (plan.style == Style.SMOOTH) Preset.RISE else Preset.BLEND
                val seconds = (end - start) / rate
                val aBar1 = start
                val bBar1 = start - plan.bStart
                val a = Anchor(aId, tailStartMs + ms(aBar1), TransitionSpec.NOMINAL_BPM)
                val b = Anchor(bId, ms(bBar1), TransitionSpec.NOMINAL_BPM)
                val lengthBars = seconds * 1000.0 / b.barMs
                val spec = TransitionSpec(
                    a = a,
                    b = b,
                    lanes = LaneSet.BANDS,
                    bars = 0,
                    seconds = seconds,
                    beatMatch = false,
                    preset = Preset.AUTO,
                    from = preset,
                    moves = Presets.moves(preset, LaneSet.BANDS, lengthBars, 1.0 / b.beatsPerBar)!!,
                )
                Made(spec, ChangeFacts(rate, plan.channels, aBar1, plan.tailFrames, bBar1, bFrames, earliest))
            }
        }
    }

    /** What the pill and the log call Auto's choice: "Fade · 8 bars", "Blend · 6 s". */
    fun label(spec: TransitionSpec): String {
        val name = spec.from.key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        if (spec.bars > 0) return "$name · ${spec.bars} bars"
        val tenths = Math.round(spec.seconds * 10)
        return if (tenths % 10 == 0L) "$name · ${tenths / 10} s" else "$name · ${tenths / 10}.${tenths % 10} s"
    }
}

/**
 * Where a saved change's anchors fall in the music at hand on the phone. Each anchor is kept in
 * its song's own clock in ms, and the held end's place in its song is known only to a buffer, so
 * each anchor is put on the nearest beat the analysis of the very frames that will play finds,
 * if one is within half a beat: the editor put it on a beat of the same analysis of the same
 * music, and a beat found again is exact where a clock is not.
 */
object Placing {

    /**
     * The facts for saved [spec] in A's music at hand, its frame 0 at [aStartMs] of A and analysed
     * as [tail], and B's copy from its first frame, analysed as [head].
     */
    fun facts(
        spec: TransitionSpec, sampleRate: Int, channels: Int,
        aStartMs: Double, aFrames: Long, tail: RegionAnalysis?,
        bFrames: Long, head: RegionAnalysis?,
        earliest: Long,
    ): ChangeFacts {
        val aBeat = 60.0 / spec.a.bpm * sampleRate
        val bBeat = 60.0 / spec.b.bpm * sampleRate
        val aBar1 = snap((spec.a.atMs - aStartMs) * sampleRate / 1000.0, tail, aBeat)
        val bBar1 = snap(spec.b.atMs * sampleRate / 1000.0, head, bBeat)
        // The stretch lands A's own beats as the analysis heard them, where it trusts them.
        val anchors = tail?.takeIf { it.beatsConfident && spec.beatMatched }?.beatFrames()
        return ChangeFacts(sampleRate, channels, aBar1, aFrames, bBar1, bFrames, earliest, anchors = anchors)
    }

    private fun snap(frame: Double, r: RegionAnalysis?, beat: Double): Double {
        if (r == null || !r.beatsConfident) return frame
        val k = r.beatNear(frame)
        val at = r.beatFrame(k)
        return if (abs(at - frame) <= beat / 2) at else frame
    }
}
