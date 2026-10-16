/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.PlayOrigin
import kotlin.math.roundToInt

/**
 * How much of what someone plays is new to them, for Familiar first with the mix learned: the
 * row then holds about that share of new songs, and the rest are songs they love.
 *
 * Over the latest [EngineParams.mixWindowListens] listens heard well, the share whose song (by
 * version group) had never been heard well before. Listens started from the engine's own rows are
 * left out, so a row of familiar songs cannot talk itself into more of them, and a new install
 * starts at [EngineParams.mixPrior], which its own listens outweigh after a few dozen.
 *
 * Across 467 Last.fm listeners the share was a steady trait of each person: median 0.27, a
 * quarter of them under 0.15 and a quarter over 0.46, and the same whether every play was counted
 * or only the first of each session (experiments/features/recs-crowd, 16 Oct 2026).
 */
object MixShare {
    private val ownRows = setOf(PlayOrigin.QUICK_PICKS.code, PlayOrigin.DISCOVER.code)

    fun of(input: EngineInput, groups: VersionGroups, p: EngineParams = EngineParams.DEFAULT): Double {
        val heard = HashSet<String>()
        val counted = ArrayList<Boolean>()
        for (l in input.listens.sortedBy { it.startedAt }) {
            if (!l.learn) continue
            if (Signals.engagement(l, input.songs[l.songId]?.likedAt, p) < p.justPlayedEngagement) continue
            val isNew = heard.add(groups.groupOf(l.songId))
            if (l.origin !in ownRows) counted += isNew
        }
        val window = counted.takeLast(p.mixWindowListens)
        return (window.count { it } + p.mixPrior * p.mixPriorListens) / (window.size + p.mixPriorListens)
    }

    /** The new cards a row of [EngineParams.rowSize] holds at [share]. */
    fun newCards(share: Double, p: EngineParams = EngineParams.DEFAULT): Int =
        (share * p.rowSize).roundToInt().coerceIn(p.mixNewMin, p.mixNewMax)
}
