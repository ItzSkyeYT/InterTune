/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import kotlin.math.abs

/**
 * The presets as the editor offers them: the engine's own rules (Presets in :mix), so a change
 * made here is written by the same rule playback and Auto use, and a preset whose rule writes
 * exactly what is on the page is the one lit. The part presets wait for parts: on bands they
 * write nothing here, and their chips are greyed.
 */
object EditorPresets {

    /** The order the chips are shown in, which never changes so no chip ever moves. */
    val ORDER: List<Preset> = Preset.entries

    /**
     * The moves [preset] writes for a change of [length] bars on [lanes]; null for Custom and
     * Auto, which no rule writes, and for a part preset on bands. [beat] is one beat in bars.
     * [kickU] is where B's own drums come in, for Beat under.
     */
    fun moves(preset: Preset, length: Double, lanes: LaneSet, beat: Double = 0.25, kickU: Double = 0.0): Map<LaneId, Move>? {
        if (preset.needsParts && lanes != LaneSet.PARTS) return null
        return Presets.moves(preset, lanes, length, beat, Presets.Facts(bDrumsBar = kickU))
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
