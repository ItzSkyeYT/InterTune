/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The presets as rules (DESIGN.md 3.7): each writes every move of a change from its length, so a
 * preset whose rule writes exactly what is on the page is the one lit, and a change of length
 * writes it again at the new length. The rules are the 19 to 21 Oct mock's, line for line, so a
 * change made here plays as it did there.
 *
 * Kept in the editor until the engine has its own (Presets in :mix): the two must stay one rule,
 * and this file goes when that one lands.
 */
object EditorPresets {

    /** The order the chips are shown in, which never changes so no chip ever moves. */
    val ORDER: List<Preset> = Preset.entries

    /**
     * The moves [preset] writes for a change of [length] bars on [lanes]; null for Custom and
     * Auto, which no rule writes. [beat] is one beat in bars. [kickU] is where B's own drums come
     * in, for Beat under.
     */
    fun moves(preset: Preset, length: Double, lanes: LaneSet, beat: Double = 0.25, kickU: Double = 0.0): Map<LaneId, Move>? {
        val l = length
        val m = LinkedHashMap<LaneId, Move>()
        val parts = lanes == LaneSet.PARTS
        val low = if (parts) Lane.BASS else Lane.LOWS
        val rest = if (parts) listOf(Lane.VOCALS, Lane.DRUMS, Lane.MELODY) else listOf(Lane.HIGHS, Lane.MIDS)
        fun a(lane: Lane) = LaneId(Side.A, lane)
        fun b(lane: Lane) = LaneId(Side.B, lane)
        fun swap(lane: Lane, u: Double) {
            m[a(lane)] = Move(u - beat, beat, How.FADE)
            m[b(lane)] = Move(u - beat, beat, How.FADE)
        }
        fun all(side: Side, of: List<Lane>, move: Move) = of.forEach { m[LaneId(side, it)] = move }
        when (preset) {
            Preset.CUSTOM, Preset.AUTO -> return null
            Preset.FADE -> {
                if (parts) {
                    m[b(Lane.DRUMS)] = Move(0.0, l / 4, How.THIN)
                    all(Side.B, listOf(Lane.VOCALS, Lane.MELODY), Move(0.0, l / 2, How.FADE))
                } else {
                    m[b(Lane.HIGHS)] = Move(0.0, l / 4, How.THIN)
                    m[b(Lane.MIDS)] = Move(0.0, l / 2, How.FADE)
                }
                all(Side.A, rest, Move(l / 2, l / 2, How.FADE))
                swap(low, l / 2)
            }
            Preset.RISE -> {
                all(Side.B, rest, Move(0.0, l, How.MUFFLED))
                all(Side.A, rest, Move(l / 2, l / 2, How.THIN))
                swap(low, l - 1)
            }
            Preset.BLEND -> {
                all(Side.B, rest, Move(0.0, l, How.FADE))
                all(Side.A, rest, Move(0.0, l, How.FADE))
                swap(low, l / 2)
            }
            Preset.ECHO_OUT -> {
                all(Side.B, rest, Move(0.0, l / 2, How.FADE))
                swap(low, l / 2)
                if (parts) {
                    m[a(Lane.VOCALS)] = Move(l / 2, l / 4, How.ECHO)
                    all(Side.A, listOf(Lane.DRUMS, Lane.MELODY), Move(l / 2, l / 4, How.FADE))
                } else {
                    m[a(Lane.MIDS)] = Move(l / 2, l / 4, How.ECHO)
                    m[a(Lane.HIGHS)] = Move(l / 2, l / 4, How.FADE)
                }
            }
            Preset.CUT -> {
                all(Side.A, lanes.lanes, Move(l / 2, 0.0, How.CUT))
                all(Side.B, lanes.lanes, Move(l / 2, 0.0, How.CUT))
            }
            Preset.VOCALS_OVER -> {
                if (!parts) return null
                m[b(Lane.DRUMS)] = Move(0.0, l / 4, How.THIN)
                m[a(Lane.DRUMS)] = Move(l / 4, 0.0, How.CUT)
                swap(Lane.BASS, l / 2)
                m[a(Lane.MELODY)] = Move(l / 2, l / 4, How.MUFFLE)
                m[b(Lane.MELODY)] = Move(5 * l / 8, l / 8, How.FADE)
                m[a(Lane.VOCALS)] = Move(l - 1, 1.0, How.ECHO)
                m[b(Lane.VOCALS)] = Move(l, 0.0, How.CUT)
            }
            Preset.BEAT_UNDER -> {
                if (!parts) return null
                val drumsAt = ((kickU * 4).roundToInt() / 4.0).coerceIn(0.0, l - 1)
                m[a(Lane.MELODY)] = Move(-1.0, 1.0, How.MUFFLE)
                m[a(Lane.VOCALS)] = Move(0.0, beat, How.ECHO)
                m[a(Lane.BASS)] = Move(0.0, beat, How.FADE)
                m[a(Lane.DRUMS)] = Move(drumsAt, 1.0, How.THIN)
                all(Side.B, LaneSet.PARTS.lanes, Move(0.0, 0.0, How.FROM_START))
            }
            Preset.DRUM_SWAP -> {
                if (!parts) return null
                m[a(Lane.VOCALS)] = Move(0.0, l / 4, How.FADE)
                m[a(Lane.MELODY)] = Move(0.0, l / 4, How.MUFFLE)
                m[a(Lane.BASS)] = Move(l / 4, beat, How.FADE)
                m[b(Lane.DRUMS)] = Move(l / 4, l / 4, How.THIN)
                m[a(Lane.DRUMS)] = Move(l / 2, 0.0, How.CUT)
                m[b(Lane.BASS)] = Move(l / 2, beat, How.FADE)
                m[b(Lane.MELODY)] = Move(l / 2, l / 4, How.FADE)
                m[b(Lane.VOCALS)] = Move(3 * l / 4, 0.0, How.CUT)
            }
            Preset.RIFF_RISE -> {
                if (parts) {
                    m[b(Lane.MELODY)] = Move(0.0, l / 2, How.MUFFLED)
                    m[a(Lane.MELODY)] = Move(l / 2, l / 4, How.MUFFLE)
                    m[a(Lane.DRUMS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[a(Lane.BASS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[b(Lane.DRUMS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[a(Lane.VOCALS)] = Move(l - 1, 1.0, How.ECHO)
                    m[b(Lane.BASS)] = Move(l, beat, How.FADE)
                    m[b(Lane.VOCALS)] = Move(l, 0.0, How.CUT)
                } else {
                    m[b(Lane.MIDS)] = Move(0.0, l / 2, How.MUFFLED)
                    m[a(Lane.MIDS)] = Move(l / 2, l / 4, How.MUFFLE)
                    m[a(Lane.LOWS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[a(Lane.HIGHS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[b(Lane.HIGHS)] = Move(3 * l / 4, 0.0, How.CUT)
                    m[b(Lane.LOWS)] = Move(l, beat, How.FADE)
                }
            }
        }
        return m
    }

    /**
     * Whether two sets of moves say the same thing: the same how on every lane, the same place
     * and the same length, except where the how has neither (a cut has no length, Keep and From
     * the start no place).
     */
    fun sameMoves(a: Map<LaneId, Move>, b: Map<LaneId, Move>): Boolean {
        if (a.keys != b.keys) return false
        for ((id, y) in b) {
            val x = a[id] ?: return false
            if (x.how != y.how) return false
            if (y.how == How.FROM_START || y.how == How.KEEP) continue
            if (abs(x.u - y.u) > 1e-6) return false
            if (y.how != How.CUT && abs(x.len - y.len) > 1e-6) return false
        }
        return true
    }
}
