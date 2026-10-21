/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A song's beats as the editor uses them: a straight line of beats in ms of the song's own clock,
 * which is what the analysis fits (BeatGrid's line), with the beat index of one downbeat.
 */
data class SongGrid(
    /** Beat 0, in ms of the song. */
    val t0Ms: Double,
    val periodMs: Double,
    /** A beat index that is a 1: downbeats are every [beatsPerBar] beats from it. */
    val phase: Int = 0,
    val beatsPerBar: Int = 4,
) {
    init {
        require(periodMs > 0 && periodMs.isFinite()) { "a beat of $periodMs ms" }
    }

    val bpm: Double get() = 60_000.0 / periodMs
    val barMs: Double get() = periodMs * beatsPerBar

    fun beatMs(k: Int): Double = t0Ms + k * periodMs

    fun beatNear(ms: Double): Int = ((ms - t0Ms) / periodMs).roundToInt()

    fun isDownbeat(k: Int): Boolean = Math.floorMod(k - phase, beatsPerBar) == 0

    /** The downbeat nearest [ms], as a beat index; a tie goes to the earlier one. */
    fun downbeatNear(ms: Double): Int {
        val exact = (ms - t0Ms) / periodMs
        val below = Math.floorDiv(Math.floor(exact).toInt() - phase, beatsPerBar) * beatsPerBar + phase
        val above = below + beatsPerBar
        return if (exact - below <= above - exact) below else above
    }
}

/**
 * The listener's fix laid on a grid. A new tempo keeps the downbeat nearest [focusMs] where it
 * was, since that is where the change is and where the listener heard the beat; the 1 moves by
 * whole beats and the nudge moves the whole line. The editor's audio side may fit the line again
 * on the song's own onsets instead (EditorAudio), which this stands in for in tests.
 */
fun applyFix(base: SongGrid, fix: BeatFix, focusMs: Double): SongGrid {
    var grid = base
    fix.bpm?.let { bpm ->
        val pivot = base.beatMs(base.downbeatNear(focusMs))
        grid = SongGrid(pivot, 60_000.0 / bpm, phase = 0, beatsPerBar = base.beatsPerBar)
    }
    return grid.copy(t0Ms = grid.t0Ms + fix.nudgeMs, phase = grid.phase + fix.downbeatShift)
}

/** What the editor knows of one song of the pair. Every time is in ms of the song's own clock. */
class SongFacts(
    val id: String,
    val title: String,
    val durationMs: Double,
    /** What of the song was read for the editor. */
    val windowStartMs: Double,
    val windowEndMs: Double,
    /** Its beats as the analysis found them, with no fix; null where its bars could not be trusted. */
    val grid: SongGrid?,
    /** Where its music starts, for the song coming in. */
    val firstSoundMs: Double = 0.0,
    /** The fix kept for the song when the editor opened (BeatFixStore). */
    val fix: BeatFix = BeatFix.NONE,
    /** Where a new tempo is pinned: near the change. */
    val focusMs: Double = windowStartMs,
    /** The copy the grid was read from, and the analysis version, for the anchors (Anchor.source). */
    val source: String = "",
    val analysis: Int = 0,
    /** Lays a fix on the grid; the audio side fits it on the song's onsets. */
    private val refit: (SongGrid, BeatFix, Double) -> SongGrid = ::applyFix,
) {
    /** The grid with [fix] on it, or null where there is no trusted grid. */
    fun gridFor(fix: BeatFix): SongGrid? = grid?.let { if (fix.isNone) it else refit(it, fix, focusMs) }
}

/**
 * Both songs, and the rules of reach: A may be used from its last [aReachMs], B for [bReachMs]
 * from where it starts. [active] says whether a song's lane has music at a time, for the hints
 * ("Two basslines for 1 bar"); it is the band's level against the song's own.
 */
class PairFacts(
    val a: SongFacts,
    val b: SongFacts,
    val lanes: LaneSet = LaneSet.BANDS,
    val aReachMs: Double = REACH_A_MS,
    val bReachMs: Double = REACH_B_MS,
    /** Question 2 of the design, unanswered: B always starts at its first second until it is yes. */
    val laterStartAllowed: Boolean = false,
    val active: (Side, Lane, Double) -> Boolean = { _, _, _ -> true },
) {
    /** The earliest moment of A that may be heard in a change. */
    val aReachStartMs: Double get() = max(a.windowStartMs, a.durationMs - aReachMs)

    /** The latest moment of B a change may reach. */
    val bReachEndMs: Double get() = min(b.windowEndMs, bReachMs)

    /** Whether the change is counted in B's bars: only with B's bars trusted. */
    val barsMode: Boolean get() = b.grid != null

    companion object {
        /** The design's reach: A's last 72 s, B's first 60 s (DESIGN.md 3.2). */
        const val REACH_A_MS = 72_000.0
        const val REACH_B_MS = 60_000.0
    }
}

/** Equal within a hair, for positions worked out two ways. */
internal fun near(x: Double, y: Double, tolerance: Double = 1e-6): Boolean = abs(x - y) <= tolerance
