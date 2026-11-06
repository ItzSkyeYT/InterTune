/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.WholeSongGrid
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * A change far from the end, forced for trying the engine on a device: the debug app only, and
 * only while the file [FILE] is in its files folder, as `{"exitLeftS": 70, "entryS": 30, "bars": 8}`.
 * Every pair then changes the reels' way: A at full to its line [exitLeftS] before its end, cut
 * there, B in from its bar nearest [entryS] on the box's first bar, [bars] bars before the line,
 * the basses swapped on the middle bar line. Played as a change saved for the pair would be.
 */
data class EntryTrial(val exitLeftS: Double, val entryS: Double, val bars: Int) {

    /** The change for [aId] into [bId], laid on each song's grid where it has one, else on a nominal 120 BPM. */
    fun spec(aId: String, bId: String, aDurationMs: Long, gridA: WholeSongGrid?, gridB: WholeSongGrid?): TransitionSpec? {
        if (aDurationMs <= 0 || bars <= 0) return null
        val aBpm = gridA?.bpm ?: NOMINAL_BPM
        val aBeats = gridA?.beatsPerBar ?: 4
        val line = downbeatNear(gridA, aDurationMs - exitLeftS * 1000)
        val start = line - bars * aBeats * 60_000.0 / aBpm
        // An entry of 0 or less plays B from its first frame, its bar near 2 s on the box's first bar.
        val entry = if (entryS <= 0) 0.0 else downbeatNear(gridB, entryS * 1000)
        val bAnchor = if (entryS <= 0) downbeatNear(gridB, 2_000.0) else entry
        if (start <= 0 || bAnchor <= 0) return null
        val half = bars / 2.0
        val moves = mapOf(
            LaneId(Side.A, Lane.LOWS) to Move(half, 0.0, How.CUT),
            LaneId(Side.A, Lane.MIDS) to Move(bars.toDouble(), 0.0, How.CUT),
            LaneId(Side.A, Lane.HIGHS) to Move(bars.toDouble(), 0.0, How.CUT),
            LaneId(Side.B, Lane.LOWS) to Move(half, 0.0, How.CUT),
            LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
            LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
        )
        return TransitionSpec(
            a = Anchor(aId, start, aBpm, aBeats),
            b = Anchor(bId, bAnchor, gridB?.bpm ?: NOMINAL_BPM, gridB?.beatsPerBar ?: 4),
            lanes = LaneSet.BANDS, bars = bars, beatMatch = true, preset = Preset.CUSTOM, moves = moves,
            bStartMs = entry,
        ).takeIf { it.isValid }
    }

    private fun downbeatNear(grid: WholeSongGrid?, ms: Double): Double {
        if (grid == null || !grid.beatsSure) return ms
        val k = grid.kNear(ms)
        val bar = (k - grid.beatsPerBar..k + grid.beatsPerBar).filter { grid.isDownbeat(it) }.minByOrNull { abs(it - k) } ?: k
        return grid.msOf(bar)
    }

    companion object {
        const val FILE = "mix-entry-trial.json"
        private const val NOMINAL_BPM = 120.0

        fun read(file: File): EntryTrial? = runCatching {
            if (!file.isFile) return null
            val o = JSONObject(file.readText())
            EntryTrial(o.getDouble("exitLeftS"), o.getDouble("entryS"), o.optInt("bars", 8))
        }.getOrNull()
    }
}
