/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * A row to build in a trial, by name: a lean, or MIX (Familiar first with the mix learned), then
 * any changes joined by +: fresh<N> the fresh hours, cap<N> the cards one artist may hold,
 * decay<N> Familiar first's decay in tenths, floor<N> the learned mix's fewest new cards,
 * window<N> the listens it reads, ranker Familiar first ordered by FamiliarRanker, newall the
 * mix's new songs from one list (mixNewFromAll). FAMILIAR+cap4 is Familiar first with four cards
 * an artist; MIX+newall+ranker the learned mix with both.
 */
object TrialArm {
    fun lean(name: String): Lean = name.substringBefore('+').let { if (it == "MIX") Lean.FAMILIAR else Lean.valueOf(it) }

    fun params(name: String): EngineParams {
        val parts = name.split('+')
        return parts.drop(1).fold(if (parts[0] == "MIX") EngineParams.DEFAULT.copy(mixLearned = true) else EngineParams.DEFAULT) { p, change ->
            if (change == "ranker") return@fold p.copy(familiarRanker = true)
            if (change == "newall") return@fold p.copy(mixNewFromAll = true)
            val n = change.dropWhile { it.isLetter() }.toInt()
            when (change.takeWhile { it.isLetter() }) {
                "fresh" -> p.copy(engineFreshHours = n)
                "cap" -> p.copy(maxPerArtist = n)
                "decay" -> p.copy(familiarDecay = n / 10.0)
                "floor" -> p.copy(mixNewMin = n)
                "window" -> p.copy(mixWindowListens = n)
                else -> error("unknown change $change in arm $name")
            }
        }
    }
}
