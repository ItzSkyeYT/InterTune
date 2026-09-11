/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin

/**
 * What one listen says, in three numbers: engagement `g` (how much of it was heard), skip `k`
 * (how pointedly it was cut off) and intent `m` (how much the listener chose it), combined into
 * the listen's value `v = (g - 0.5 k) m`, or 0 when the listener asked for it not to teach.
 */
object Signals {
    fun engagement(l: ListenRow, likedAt: Long?, p: EngineParams = EngineParams.DEFAULT): Double {
        if (likedAt != null && likedAt >= l.startedAt && likedAt <= l.endedAt + p.likeCountsWithinMs) return 1.0
        if (l.playedMs < p.minPositiveMs) return 0.0
        if (l.durationMs <= 0) return ((l.playedMs - p.minPositiveMs) / p.unknownLengthFullAtMs.toDouble()).coerceIn(0.0, p.unknownLengthCap)
        val r = (l.playedMs.toDouble() / l.durationMs).coerceAtMost(1.0)
        return ((r - p.rampFrom) / (p.rampTo - p.rampFrom)).coerceIn(0.0, 1.0)
    }

    /**
     * A skip past the floor and before the fade-out, weighted so that a skip near the middle of
     * the song counts most and one just past the floor barely counts.
     */
    fun skip(l: ListenRow, p: EngineParams = EngineParams.DEFAULT): Double {
        if (l.endReason != EndReason.SKIPPED || l.playedMs < p.minPositiveMs || l.durationMs <= 0) return 0.0
        val r = (l.playedMs.toDouble() / l.durationMs).coerceAtMost(1.0)
        if (r >= p.skipIgnoredFrom) return 0.0
        val r30 = (p.minPositiveMs.toDouble() / l.durationMs).coerceAtMost(0.5)
        val ramp = ((r - r30) / maxOf(0.5 - r30, 0.1)).coerceIn(0.0, 1.0)
        return (1 - r) * ramp
    }

    /** True when an autoplayed song sits inside something the listener chose, rather than a radio. */
    private fun insideChosenContainer(origin: Int): Boolean = when (PlayOrigin.fromCode(origin)) {
        PlayOrigin.PLAYLIST, PlayOrigin.ALBUM, PlayOrigin.ARTIST, PlayOrigin.LIBRARY, PlayOrigin.LOCAL_FILES,
        PlayOrigin.QUEUE, PlayOrigin.HISTORY, PlayOrigin.STATS -> true
        else -> false
    }

    fun intent(l: ListenRow, p: EngineParams = EngineParams.DEFAULT): Double {
        if (l.autoplayDepth <= 0) return when (PlayOrigin.fromCode(l.origin)) {
            PlayOrigin.SEARCH, PlayOrigin.RECOGNISED -> p.intentSearch
            PlayOrigin.LIBRARY, PlayOrigin.LOCAL_FILES, PlayOrigin.PLAYLIST, PlayOrigin.ALBUM, PlayOrigin.ARTIST,
            PlayOrigin.RADIO, PlayOrigin.QUEUE, PlayOrigin.HISTORY, PlayOrigin.STATS, PlayOrigin.MENU -> p.intentChosen
            else -> p.intentRow
        }
        if (insideChosenContainer(l.origin)) return p.intentInsideContainer
        return maxOf(p.intentRadioFloor, p.intentRadioBase * Math.pow(p.intentRadioDecay, (l.autoplayDepth - 1).toDouble()))
    }

    fun value(l: ListenRow, likedAt: Long?, p: EngineParams = EngineParams.DEFAULT): Double =
        if (!l.learn) 0.0 else (engagement(l, likedAt, p) - p.skipWeight * skip(l, p)) * intent(l, p)

    /** ACT-R's recency term: hours since, plus one, to the power of minus the decay; never a negative age. */
    fun recency(nowMs: Long, thenMs: Long, decay: Double = EngineParams.DEFAULT.activationDecay): Double =
        Math.pow(ageHours(nowMs, thenMs) + 1.0, -decay)
}
