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
 * left out of that, so a row of familiar songs cannot talk itself into more of them, and a new
 * install starts at [EngineParams.mixPrior], which its own listens outweigh after a few dozen.
 *
 * The row's own cards count too, per card shown, which is what keeps them from feeding the row:
 * over the impression window, how often a new card shown was played and heard well against how
 * often a familiar one was. Played as often, the row says half and half; never played, it says
 * none. That reading joins the share with the weight of the plays it rests on, once at least
 * [MIN_SHOWN] cards of each kind have been shown, so someone who plays the new cards gets more of
 * them, whatever they play elsewhere. Without cards logged as passed over (impressions off) the
 * row's taps say nothing.
 *
 * Across 467 Last.fm listeners the share was a steady trait of each person: median 0.27, a
 * quarter of them under 0.15 and a quarter over 0.46, and the same whether every play was counted
 * or only the first of each session (experiments/features/recs-crowd, 16 Oct 2026).
 */
object MixShare {
    private val ownRows = setOf(PlayOrigin.QUICK_PICKS.code, PlayOrigin.DISCOVER.code)
    /** Cards of each kind the row must have shown before its taps count. */
    const val MIN_SHOWN = 20

    fun of(input: EngineInput, groups: VersionGroups, p: EngineParams = EngineParams.DEFAULT): Double {
        val heard = HashMap<String, Long>()   // version group to its first listen heard well
        val counted = ArrayList<Boolean>()
        val windowStart = input.now - p.impressionWindowDays * 86_400_000L
        var tappedNew = 0; var tappedFamiliar = 0
        for (l in input.listens.sortedBy { it.startedAt }) {
            if (!l.learn) continue
            if (Signals.engagement(l, input.songs[l.songId]?.likedAt, p) < p.justPlayedEngagement) continue
            val g = groups.groupOf(l.songId)
            val isNew = g !in heard
            if (isNew) heard[g] = l.startedAt
            if (l.origin !in ownRows) counted += isNew
            else if (l.startedAt >= windowStart) { if (isNew) tappedNew++ else tappedFamiliar++ }
        }
        val window = counted.takeLast(p.mixWindowListens)
        var newCount = window.count { it }.toDouble()
        var n = window.size.toDouble()

        // Cards seen and passed over, each new or familiar as it was when it was on screen.
        var seenNew = 0; var seenFamiliar = 0
        for (c in input.seen) {
            if (c.seenAt < windowStart) continue
            val first = heard[groups.groupOf(c.songId)]
            if (first == null || first > c.seenAt) seenNew++ else seenFamiliar++
        }
        val shownNew = seenNew + tappedNew
        val shownFamiliar = seenFamiliar + tappedFamiliar
        // Only where the row's cards were logged as they were passed over: without them a tap cannot
        // be set against what was shown, and impressions can be turned off.
        if (seenNew + seenFamiliar >= MIN_SHOWN && shownNew >= MIN_SHOWN && shownFamiliar >= MIN_SHOWN) {
            val rateNew = tappedNew.toDouble() / shownNew
            val rateFamiliar = tappedFamiliar.toDouble() / shownFamiliar
            val taps = (tappedNew + tappedFamiliar).toDouble()
            if (rateNew + rateFamiliar > 0) {
                newCount += taps * rateNew / (rateNew + rateFamiliar)
                n += taps
            }
        }
        return (newCount + p.mixPrior * p.mixPriorListens) / (n + p.mixPriorListens)
    }

    /** The new cards a row of [EngineParams.rowSize] holds at [share]. */
    fun newCards(share: Double, p: EngineParams = EngineParams.DEFAULT): Int =
        (share * p.rowSize).roundToInt().coerceIn(p.mixNewMin, p.mixNewMax)
}
