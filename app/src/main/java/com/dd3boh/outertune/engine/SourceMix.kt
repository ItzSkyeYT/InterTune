/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.SimilarSource
import kotlin.math.pow

/** Which similar-song lists proposed a card, as bits. */
object Provenance {
    const val YOUTUBE = 1
    const val LASTFM = 2
    const val SOURCES = 3
    /** At least one song that proposed it has lists from both sources, so the two competed for it. */
    const val CONTESTED = 4

    /**
     * Only a card one source proposed, from a song both have a list for, says which source the
     * listener prefers: anywhere else the other source had no say, and a card both proposed
     * credits neither.
     */
    fun isEvidence(bits: Int) = bits and CONTESTED != 0 && (bits and SOURCES == YOUTUBE || bits and SOURCES == LASTFM)
}

/** One graded impression, as the share reads it. */
data class SourceEvidence(
    val songId: String,
    val team: Int,
    val lane: Int,
    val sources: Int,
    val visibleAt: Long,
    val outcome: Int,
    val y: Float?,
)

/**
 * How much of the similar-song places Last.fm gets in Both, from how the cards each source
 * proposed have fared. Recomputed at every build from the impressions and never stored as a
 * weight, so it can always be explained from what is on disk, and Reset weights leaves it alone.
 */
object SourceMix {
    const val FLOOR = 0.2
    const val HALF_LIFE_DAYS = 14.0
    const val PRIOR = 6.0
    const val POWER = 2.0
    const val WINDOW_MS = 120L * 86_400_000L

    data class Side(val cards: Double, val heard: Double, val expected: Double) {
        val per100 get() = if (cards > 0) 100 * heard / cards else 0.0
    }

    data class State(val share: Double, val lastFm: Side, val youTube: Side, val days: Int) {
        val compared get() = lastFm.cards > 0 && youTube.cards > 0
    }

    private class Kept(val cell: Int, val side: Int, val day: Long, val visibleAt: Long, val y: Double)

    fun share(rows: List<SourceEvidence>, tzOffsetMin: Int): State {
        // A card seen in several builds on one day is one decision by the listener, so it counts
        // once, at its best grade.
        val best = HashMap<Pair<Long, String>, Kept>()
        for (r in rows) {
            if (r.team != 1 && r.team != 4) continue
            if (r.lane != 1 && r.lane != 4) continue
            if (!Provenance.isEvidence(r.sources) || r.outcome !in 0..3) continue
            // A pending card is seen and not yet played, so a fresh play cannot lift its source
            // before the cards ignored beside it are graded.
            val y = if (r.outcome == Outcome.PENDING) 0.0 else (r.y ?: 0f).toDouble()
            val day = Math.floorDiv(r.visibleAt + tzOffsetMin * 60_000L, 86_400_000L)
            val k = Kept(r.team * 8 + r.lane, r.sources and Provenance.SOURCES, day, r.visibleAt, y)
            val had = best[day to r.songId]
            if (had == null || k.y > had.y || (k.y == had.y && k.visibleAt > had.visibleAt)) best[day to r.songId] = k
        }
        val none = Side(0.0, 0.0, 0.0)
        if (best.isEmpty()) return State(0.5, none, none, 0)
        // Age counts days with listening, so a week away does not wipe out what came before it.
        val age = best.values.map { it.day }.distinct().sortedDescending().withIndex().associate { it.value to it.index }
        fun w(k: Kept) = 0.5.pow(age[k.day]!! / HALF_LIFE_DAYS)
        // Each team and lane is its own baseline, so a source is not credited for landing in the
        // lane where plays come easier.
        val n = HashMap<Int, Double>()
        val yy = HashMap<Int, Double>()
        for (k in best.values) {
            n[k.cell] = (n[k.cell] ?: 0.0) + w(k)
            yy[k.cell] = (yy[k.cell] ?: 0.0) + w(k) * k.y
        }
        fun side(s: Int): Side {
            var c = 0.0; var h = 0.0; var e = 0.0
            for (k in best.values) if (k.side == s) {
                val wk = w(k)
                c += wk; h += wk * k.y; e += wk * yy[k.cell]!! / n[k.cell]!!
            }
            return Side(c, h, e)
        }
        val l = side(Provenance.LASTFM)
        val yt = side(Provenance.YOUTUBE)
        // The prior is in plays, not cards, so one good play on the first day cannot swing the
        // row, and with evidence on one side only both ratios are exactly one.
        val rhoL = (l.heard + PRIOR) / (l.expected + PRIOR)
        val rhoY = (yt.heard + PRIOR) / (yt.expected + PRIOR)
        val raw = rhoL.pow(POWER) / (rhoL.pow(POWER) + rhoY.pow(POWER))
        // The floor keeps the other source in the row, so it can win its places back.
        return State(raw.coerceIn(FLOOR, 1 - FLOOR), l, yt, age.size)
    }
}

object SimilarSources {
    /**
     * The mode the app acts on. A build without a Last.fm key has nothing to ask with, and while
     * the engine is held back in releases its similar songs are not shown, so both read as
     * YouTube only whatever was stored, and nothing is sent to Last.fm.
     */
    fun effective(stored: SimilarSource, hasKey: Boolean, engineOn: Boolean): SimilarSource =
        if (!hasKey || !engineOn) SimilarSource.YOUTUBE else stored

    /**
     * The mode as stored. Before the three-way choice there was a switch for Last.fm alone, and
     * a listener who turned it on chose Last.fm only, so that is what it still means; one who
     * turned it back off chose YouTube. Nobody who never touched either has been asked yet, and
     * until they are, nothing they play goes to Last.fm: YouTube only. The first-run questions
     * ask, and a yes is Both.
     */
    fun stored(value: String?, oldLastFmSwitch: Boolean?): SimilarSource =
        SimilarSource.entries.firstOrNull { it.name == value } ?: when (oldLastFmSwitch) {
            true -> SimilarSource.LASTFM
            false, null -> SimilarSource.YOUTUBE
        }

    /**
     * Whether How similar songs are split shows under the choice: only with Both, the one mode in
     * which the songs are shared out. With one source there is nothing to split, and a line saying
     * so read as a figure that had failed to fill in.
     */
    fun showsSplit(stored: SimilarSource, hasKey: Boolean, engineOn: Boolean): Boolean =
        effective(stored, hasKey, engineOn) == SimilarSource.BOTH

    /** Whether this install has chosen, either way, through the setting, the old switch or the question. */
    fun asked(value: String?, oldLastFmSwitch: Boolean?): Boolean = value != null || oldLastFmSwitch != null
}
