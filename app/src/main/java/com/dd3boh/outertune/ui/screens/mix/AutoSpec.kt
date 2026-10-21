/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.aGoneAt
import kotlin.math.abs
import kotlin.math.max

/**
 * Auto in the editor: the planner's pick for the pair written as a spec, so the page can show it,
 * light its chip and start from it. Until the parts planner, its styles map to presets on bands
 * (DESIGN.md 3.7): Beat to Fade, Smooth to Rise, Blend to Blend, Cut to Cut, Gapless to a plain
 * change. Bar 1 is where the planner's overlap starts, in each song's own clock.
 */
object AutoSpec {

    /** What Auto plays, from [plan] made on A's window starting at [aWindowStartMs]; [fallback] without a plan. */
    fun of(plan: MixPlan?, facts: PairFacts, aWindowStartMs: Double): TransitionSpec {
        plan ?: return fallback(facts)
        val rate = plan.sampleRate.toDouble()
        fun aMsAtOutput(frame: Double) = aWindowStartMs + plan.aMap.tailAt(frame) / rate * 1000.0
        fun bMsAtOutput(frame: Double) = facts.b.windowStartMs + (frame - plan.bStart) / rate * 1000.0
        val base = fallback(facts)
        val spec = when (plan.style) {
            Style.GAPLESS -> base.copy(plain = true)
            Style.BEAT -> {
                val bars = TransitionSpec.LENGTHS_BARS.minBy { abs(it - plan.bars) }
                val gridA = facts.a.gridFor(facts.a.fix)
                val gridB = facts.b.gridFor(facts.b.fix)
                val aAt = aMsAtOutput(plan.overlapStart).let { at -> gridA?.let { it.beatMs(it.downbeatNear(at)) } ?: at }
                val bAt = bMsAtOutput(plan.overlapStart).let { at -> gridB?.let { it.beatMs(it.downbeatNear(at)) } ?: at }
                written(base.copy(a = base.a.copy(atMs = max(0.0, aAt)), b = base.b.copy(atMs = max(0.0, bAt)), bars = bars, seconds = 0.0, beatMatch = true), Preset.FADE)
            }
            // The cut is where A is gone; the Cut preset cuts in the middle of its change.
            Style.CUT -> cut(base, facts, aMsAtOutput(plan.aGone.toDouble()))
            // A blend a beat long is how the planner keeps two steady beats from sliding apart:
            // the editor's shortest blend is 4 s, so it is a cut on that beat here.
            Style.SMOOTH, Style.BLEND -> if (plan.seconds < MIN_BLEND_S) cut(base, facts, aMsAtOutput((plan.overlapStart + plan.overlapEnd) / 2)) else {
                val preset = if (plan.style == Style.SMOOTH) Preset.RISE else Preset.BLEND
                val seconds = TransitionSpec.LENGTHS_SECONDS.minBy { abs(it - plan.seconds) }
                // Rounded to a length the editor offers, the blend keeps the planner's end.
                val aAt = aMsAtOutput(plan.overlapEnd) - seconds * 1000
                val bAt = max(0.0, bMsAtOutput(plan.overlapEnd) - seconds * 1000)
                written(base.copy(a = base.a.copy(atMs = max(0.0, aAt)), b = base.b.copy(atMs = bAt), bars = 0, seconds = seconds, beatMatch = false), preset)
            }
        }
        val checked = spec.copy(preset = Preset.AUTO, from = Preset.AUTO)
        return if (checked.isValid && fits(checked, facts)) checked else fallback(facts)
    }

    /**
     * Auto where the planner has nothing to say: Fade over 8 bars ending on A's last downbeat
     * before its end, B from its first downbeat; or, with no bars to count, a 6 s blend into A's
     * end with B's first sound at its start.
     */
    fun fallback(facts: PairFacts): TransitionSpec {
        val gridA = facts.a.gridFor(facts.a.fix)
        val gridB = facts.b.gridFor(facts.b.fix)
        val b = if (gridB != null) {
            MixEditor.anchor(facts.b, firstDownbeat(gridB, facts.b.firstSoundMs), gridB.bpm)
        } else {
            MixEditor.anchor(facts.b, facts.b.firstSoundMs, null)
        }
        val endMs = facts.a.durationMs - END_MARGIN_MS
        for (bars in listOf(8, 4, 2)) {
            if (gridB == null) break
            val a0 = MixEditor.anchor(facts.a, 0.0, gridA?.bpm)
            val draft = written(
                TransitionSpec(a = a0, b = b, lanes = facts.lanes, bars = bars, preset = Preset.AUTO, moves = emptyMap()),
                Preset.FADE,
            )
            val perA = if (draft.beatMatched) draft.b.barMs * draft.tempoRatio() else draft.b.barMs
            var aAt = endMs - bars * perA
            if (gridA != null) {
                var k = gridA.downbeatNear(aAt)
                while (gridA.beatMs(k) + bars * perA > endMs && gridA.beatMs(k) > 0) k -= gridA.beatsPerBar
                aAt = gridA.beatMs(k)
            }
            val spec = draft.copy(a = draft.a.copy(atMs = max(0.0, aAt)), preset = Preset.AUTO, from = Preset.AUTO)
            if (fits(spec, facts)) return spec
        }
        val seconds = 6.0
        val a = MixEditor.anchor(facts.a, max(0.0, facts.a.durationMs - END_MARGIN_MS - seconds * 1000), gridA?.bpm)
        val timed = TransitionSpec(a = a, b = b.copy(bpm = gridB?.bpm ?: TransitionSpec.NOMINAL_BPM), lanes = facts.lanes, bars = 0, seconds = seconds, beatMatch = false, preset = Preset.AUTO, moves = emptyMap())
        return written(timed, Preset.BLEND).copy(preset = Preset.AUTO, from = Preset.AUTO)
    }

    /** The first downbeat at or after a song's first sound, or the one just before when that is a pickup. */
    fun firstDownbeat(grid: SongGrid, firstSoundMs: Double): Double {
        var k = grid.downbeatNear(firstSoundMs)
        while (grid.beatMs(k) < firstSoundMs - PICKUP_MS) k += grid.beatsPerBar
        while (grid.beatMs(k) < 0) k += grid.beatsPerBar
        return grid.beatMs(k)
    }

    /** A cut that leaves A at [aCutMs], B from its first downbeat (or first sound). */
    private fun cut(base: TransitionSpec, facts: PairFacts, aCutMs: Double): TransitionSpec {
        val timed = if (facts.barsMode) base.copy(bars = 2, seconds = 0.0) else base.copy(bars = 0, seconds = TransitionSpec.LENGTHS_SECONDS.first())
        val perA = timed.b.barMs
        val half = timed.lengthBars / 2
        val gridA = facts.a.gridFor(facts.a.fix)
        val aAt = (aCutMs - half * perA).let { at -> if (facts.barsMode && gridA != null) gridA.beatMs(gridA.downbeatNear(at)) else at }
        return written(timed.copy(a = timed.a.copy(atMs = max(0.0, aAt)), beatMatch = false), Preset.CUT)
    }

    private fun written(s: TransitionSpec, p: Preset): TransitionSpec =
        s.copy(moves = EditorPresets.moves(p, s.lengthBars, s.lanes, s.beat)!!, preset = p)

    /** Whether the spec can be heard whole with what may be read of the two songs. */
    private fun fits(s: TransitionSpec, facts: PairFacts): Boolean {
        val perA = if (s.beatMatched) s.b.barMs * s.tempoRatio() else s.b.barMs
        val around = if (s.bars == 0) MixEditor.SECONDS_AROUND_MS / s.b.barMs else 2.0
        val aFirst = s.a.atMs - around * perA
        val bLast = s.bMsAt(s.lengthBars + around)
        // A has to last until its last move, not to the change's end: a cut leaves it halfway.
        val gone = if (s.plain) 0.0 else s.aGoneAt()
        return aFirst >= facts.aReachStartMs - 0.5 && bLast <= facts.bReachEndMs + 0.5 && s.a.atMs + gone * perA <= facts.a.durationMs + 0.5
    }

    /** The editor's shortest blend; anything shorter from the planner is a cut. */
    private const val MIN_BLEND_S = 2.0

    /** A's end kept clear of the change: the last half second of a song is often its fade to nothing. */
    private const val END_MARGIN_MS = 500.0

    /** A first sound up to this before a downbeat is a pickup into it, not the downbeat. */
    private const val PICKUP_MS = 30.0
}
