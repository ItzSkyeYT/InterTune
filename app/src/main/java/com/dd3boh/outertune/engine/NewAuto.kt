/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * The Auto of the 0.14 engine, behind Unreleased.NEW_ENGINE: the row designed on 21 Oct 2026 and measured as
 * "MIX+ranker+cap3" (TrialArm), until 20 Nov two developer switches of the debug app. Familiar first, with the share
 * of new songs learned from what the listener plays and nudged by the slider, the familiar songs in FamiliarRanker's
 * order, three cards an artist. On 467 Last.fm listeners it held the session's first song 0.170 of the time against
 * today's Auto's 0.035, and 0.268 against 0.048 on his own history (experiments/features/recs-014/BASELINE.md).
 *
 * It stands in for Auto and nothing else: a lean the listener picks is built as it always was, Discover keeps its own
 * settings, and with the flag off Auto is today's blend, the fallback. Plain functions, so the JVM tests cover them.
 */
object NewAuto {
    /** Whether the row for the listener's [chosen] lean is the new Auto's, with the flag [on]. */
    fun applies(chosen: Lean, on: Boolean): Boolean = on && chosen == Lean.AUTO

    /**
     * The lean the row is built under: Familiar first for Auto under the new engine, which is also what the row's
     * build is logged under, so the judges can tell the two Autos apart. Home's chip and heading read Familiar first
     * as Auto (HomeScreen), so the listener still sees the Auto they chose.
     */
    fun lean(chosen: Lean, on: Boolean): Lean = if (applies(chosen, on)) Lean.FAMILIAR else chosen

    /** The engine's parameters for that row: [p] with the mix learned and nudged by [adventurousness] (0 to 100), or [p] as it is. */
    fun params(p: EngineParams, chosen: Lean, on: Boolean, adventurousness: Int): EngineParams =
        if (applies(chosen, on)) p.copy(mixLearned = true, familiarRanker = true, maxPerArtist = 3, mixNudge = MixShare.nudge(adventurousness))
        else p
}
