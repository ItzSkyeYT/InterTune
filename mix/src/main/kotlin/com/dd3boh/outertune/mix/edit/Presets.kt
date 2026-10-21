/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlin.math.abs
import kotlin.math.min

/**
 * The presets as rules: each writes every lane's move from the change's length and a few facts
 * of the pair, as DESIGN.md 3.7 has them. A spec whose moves one of them writes exactly is that
 * preset; any other is Custom.
 *
 * Positions are in bars from the start of bar 1. A swap at u is A's exit and B's entry over the
 * beat before u, both a fade, so one lane hands over to the other in equal power: the bass swap.
 * On bands the parts' moves land where the editor colours them: the bass on the lows, the drums
 * on the highs (the hats, which is what is heard of a kit above 6 kHz), and the voice and the
 * melody on the mids.
 */
object Presets {

    /** What a rule may need of the pair beyond the change's length. */
    class Facts(
        /** Where B's own drums start, in bars of the change: Beat under hands A's drums over there. */
        val bDrumsBar: Double? = null,
    )

    /**
     * Every lane's move for [preset] on [lanes] at [lengthBars], [beat] being one of B's beats in
     * bars. Null for Auto, which is the planner's, and Custom, which no rule writes.
     */
    fun moves(preset: Preset, lanes: LaneSet, lengthBars: Double, beat: Double, facts: Facts = Facts()): Map<LaneId, Move>? {
        val l = lengthBars
        val w = Writer(lanes, beat)
        when (preset) {
            Preset.AUTO, Preset.CUSTOM -> return null
            Preset.FADE -> {
                w.inn(Lane.HIGHS, Lane.DRUMS, u = 0.0, len = l / 4, how = How.THIN)
                w.inn(Lane.MIDS, Lane.VOCALS, Lane.MELODY, u = 0.0, len = l / 2, how = How.FADE)
                w.out(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = l / 2, len = l / 2, how = How.FADE)
                w.swap(Lane.LOWS, Lane.BASS, at = l / 2)
            }
            Preset.RISE -> {
                w.inn(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = 0.0, len = l, how = How.MUFFLED)
                w.out(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = l / 2, len = l / 2, how = How.THIN)
                w.swap(Lane.LOWS, Lane.BASS, at = l - 1)
            }
            Preset.BLEND -> {
                w.inn(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = 0.0, len = l, how = How.FADE)
                w.out(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = 0.0, len = l, how = How.FADE)
                w.swap(Lane.LOWS, Lane.BASS, at = l / 2)
            }
            Preset.ECHO_OUT -> {
                w.inn(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY, u = 0.0, len = l / 2, how = How.FADE)
                w.swap(Lane.LOWS, Lane.BASS, at = l / 2)
                // The voice echoes away; on bands it lives in the mids. The rest fades with it.
                w.out(Lane.MIDS, Lane.VOCALS, u = l / 2, len = l / 4, how = How.ECHO)
                w.out(Lane.HIGHS, Lane.DRUMS, Lane.MELODY, u = l / 2, len = l / 4, how = How.FADE)
            }
            Preset.CUT -> {
                w.out(*lanes.lanes.toTypedArray(), u = l / 2, len = 0.0, how = How.CUT)
                w.inn(*lanes.lanes.toTypedArray(), u = l / 2, len = 0.0, how = How.CUT)
            }
            Preset.RIFF_RISE -> {
                // On bands the riff is the mids: B's rise muffled under A, which then muffles away.
                w.inn(Lane.MELODY, Lane.MIDS, u = 0.0, len = l / 2, how = How.MUFFLED)
                w.out(Lane.MELODY, Lane.MIDS, u = l / 2, len = l / 4, how = How.MUFFLE)
                w.out(Lane.DRUMS, Lane.HIGHS, Lane.BASS, Lane.LOWS, u = 3 * l / 4, len = 0.0, how = How.CUT)
                w.inn(Lane.DRUMS, Lane.HIGHS, u = 3 * l / 4, len = 0.0, how = How.CUT)
                w.out(Lane.VOCALS, u = l - 1, len = 1.0, how = How.ECHO)
                w.inn(Lane.BASS, Lane.LOWS, Lane.VOCALS, u = l, len = 0.0, how = How.CUT)
            }
            Preset.VOCALS_OVER -> {
                w.inn(Lane.DRUMS, Lane.HIGHS, u = 0.0, len = l / 4, how = How.THIN)
                w.out(Lane.DRUMS, Lane.HIGHS, u = l / 4, len = 0.0, how = How.CUT)
                w.swap(Lane.BASS, Lane.LOWS, at = l / 2)
                w.out(Lane.MELODY, u = l / 2, len = l / 4, how = How.MUFFLE)
                w.inn(Lane.MELODY, Lane.MIDS, u = 5 * l / 8, len = l / 8, how = How.FADE)
                w.out(Lane.VOCALS, Lane.MIDS, u = l - 1, len = 1.0, how = How.ECHO)
                w.inn(Lane.VOCALS, u = l, len = 0.0, how = How.CUT)
            }
            Preset.BEAT_UNDER -> {
                val drums = (facts.bDrumsBar ?: l / 2).coerceIn(1.0, l)
                w.out(Lane.MELODY, u = -1.0, len = 1.0, how = How.MUFFLE)
                w.out(Lane.VOCALS, Lane.MIDS, u = 0.0, len = 1.0, how = How.ECHO)
                w.out(Lane.BASS, Lane.LOWS, u = 0.0, len = 0.0, how = How.CUT)
                w.out(Lane.DRUMS, Lane.HIGHS, u = drums - 1, len = 1.0, how = How.THIN)
                w.inn(*lanes.lanes.toTypedArray(), u = 0.0, len = 0.0, how = How.FROM_START)
            }
            Preset.DRUM_SWAP -> {
                w.out(Lane.VOCALS, Lane.MELODY, Lane.MIDS, u = 0.0, len = l / 4, how = How.FADE)
                w.out(Lane.BASS, Lane.LOWS, u = l / 4, len = 0.0, how = How.CUT)
                w.inn(Lane.DRUMS, Lane.HIGHS, u = l / 4, len = l / 4, how = How.THIN)
                w.out(Lane.DRUMS, Lane.HIGHS, u = l / 2 - min(beat, l / 4), len = min(beat, l / 4), how = How.FADE)
                w.inn(Lane.BASS, Lane.LOWS, u = l / 2, len = 0.0, how = How.CUT)
                w.inn(Lane.MELODY, Lane.MIDS, u = l / 2, len = l / 4, how = How.FADE)
                w.inn(Lane.VOCALS, u = 3 * l / 4, len = 0.0, how = How.CUT)
            }
        }
        return w.moves.takeIf { m -> lanes.ids.all { it in m } }
    }

    /**
     * [spec] with [preset]'s moves, at its own length: lit, its curves and mutes cleared, since a
     * preset tapped replaces them. [spec] itself for Auto and Custom, which no rule writes.
     */
    fun apply(spec: TransitionSpec, preset: Preset, facts: Facts = Facts()): TransitionSpec {
        val moves = moves(preset, spec.lanes, spec.lengthBars, spec.beat, facts) ?: return spec
        return spec.copy(preset = preset, from = preset, moves = moves, curves = emptyMap(), muted = emptySet(), plain = false)
    }

    /**
     * The preset whose rule writes exactly [spec]'s moves at its length, with no curve and no mute:
     * the one the editor lights. Auto when [auto] is given and the moves are its; Custom otherwise.
     */
    fun match(spec: TransitionSpec, facts: Facts = Facts(), auto: TransitionSpec? = null): Preset {
        if (spec.curves.isNotEmpty() || spec.muted.isNotEmpty()) return Preset.CUSTOM
        if (auto != null && auto.lanes == spec.lanes && same(auto.moves, spec.moves)) return Preset.AUTO
        for (p in Preset.entries) {
            if (p.needsParts && spec.lanes != LaneSet.PARTS) continue
            val m = moves(p, spec.lanes, spec.lengthBars, spec.beat, facts) ?: continue
            if (same(m, spec.moves)) return p
        }
        return Preset.CUSTOM
    }

    /**
     * [spec] at a new length: a preset is written again at it, a custom spec keeps each move at the
     * same share of the change. Lengths in bars when [bars] is above 0, otherwise [seconds].
     */
    fun withLength(spec: TransitionSpec, bars: Int, seconds: Double = 0.0, facts: Facts = Facts()): TransitionSpec {
        val resized = spec.copy(bars = bars, seconds = if (bars > 0) 0.0 else seconds)
        moves(spec.preset, spec.lanes, resized.lengthBars, resized.beat, facts)?.let { return resized.copy(moves = it) }
        val k = resized.lengthBars / spec.lengthBars
        return resized.copy(
            moves = spec.moves.mapValues { (_, m) -> m.copy(u = m.u * k, len = m.len * k) },
            curves = spec.curves.mapValues { (_, c) ->
                LaneCurve(c.gain.map { it.copy(u = it.u * k) }, c.lowPassHz.map { it.copy(u = it.u * k) }, c.highPassHz.map { it.copy(u = it.u * k) })
            },
        )
    }

    /**
     * A change made on parts, to play on bands this time: each band takes the moves of the part
     * the editor colours it with, bass on the lows, drums on the highs, and on the mids A's voice
     * (it is what is heard leaving last) and B's melody (it is what comes in first). Its timing and
     * choices stay the listener's. A spec on bands is given back as it is.
     */
    fun onBands(spec: TransitionSpec): TransitionSpec {
        if (spec.lanes == LaneSet.BANDS) return spec
        val from = mapOf(
            LaneId(Side.A, Lane.LOWS) to LaneId(Side.A, Lane.BASS),
            LaneId(Side.A, Lane.HIGHS) to LaneId(Side.A, Lane.DRUMS),
            LaneId(Side.A, Lane.MIDS) to LaneId(Side.A, Lane.VOCALS),
            LaneId(Side.B, Lane.LOWS) to LaneId(Side.B, Lane.BASS),
            LaneId(Side.B, Lane.HIGHS) to LaneId(Side.B, Lane.DRUMS),
            LaneId(Side.B, Lane.MIDS) to LaneId(Side.B, Lane.MELODY),
        )
        return spec.copy(
            lanes = LaneSet.BANDS,
            moves = from.mapNotNull { (band, part) -> spec.moves[part]?.let { band to it } }.toMap(),
            curves = from.mapNotNull { (band, part) -> spec.curves[part]?.let { band to it } }.toMap(),
            muted = from.filter { (_, part) -> part in spec.muted }.keys,
        )
    }

    private fun same(a: Map<LaneId, Move>, b: Map<LaneId, Move>): Boolean =
        a.keys == b.keys && a.all { (id, m) -> b[id]?.let { n -> n.how == m.how && abs(n.u - m.u) < EPS && abs(n.len - m.len) < EPS } == true }

    private const val EPS = 1e-9

    /** Writes moves for whichever of the named lanes are in [lanes]: a band and its parts are named together. */
    private class Writer(private val lanes: LaneSet, private val beat: Double) {
        val moves = LinkedHashMap<LaneId, Move>()

        fun out(vararg named: Lane, u: Double, len: Double, how: How) = put(Side.A, named, Move(u, len, how))

        fun inn(vararg named: Lane, u: Double, len: Double, how: How) = put(Side.B, named, Move(u, len, how))

        /** A hands over to B on [named] over the beat before [at], or over half the time to it if that is shorter. */
        fun swap(vararg named: Lane, at: Double) {
            val len = min(beat, at / 2).coerceAtLeast(0.0)
            put(Side.A, named, Move(at - len, len, How.FADE))
            put(Side.B, named, Move(at - len, len, How.FADE))
        }

        private fun put(side: Side, named: Array<out Lane>, move: Move) {
            for (lane in named) if (lane in lanes.lanes) moves.putIfAbsent(LaneId(side, lane), move)
        }
    }
}
