/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason

/** An impression as the grader sees it. Features are null for cards the engine did not place. */
data class ImpressionRow(
    val id: Long,
    val songId: String,
    val slot: Int,
    val lane: Lane?,
    val features: DoubleArray?,
    val p: Double?,
    val visibleAt: Long,
    val tappedAt: Long?,
)

/** What became of a seen card. */
object Outcome {
    const val PENDING = 0
    const val PLAYED = 1
    const val ELSEWHERE = 2
    const val IGNORED = 3
    /** The listen it produced asked not to teach; graded so it is never looked at again, weighing nothing. */
    const val DROPPED = 6
}

data class Graded(val impressionId: Long, val outcome: Int, val y: Double, val u: Double)

/**
 * Wins are graded by engagement, never by the tap: a tap abandoned after twenty seconds grades 0,
 * a finished song 1. A card played from the row takes its listen's engagement at full weight; the
 * same song played from somewhere else within a day of being seen takes half the grade at half
 * the weight, so a coincidence is not a win; a card seen and not played within a day is an ignored
 * card at weight 0.3. A card that was tapped is never graded ignored, even when no listen arrives,
 * so a service killed mid-song cannot turn a win into a loss.
 */
object Grading {
    fun grade(
        impressions: List<ImpressionRow>,
        listens: List<ListenRow>,
        songs: Map<String, SongRow>,
        groups: VersionGroups,
        now: Long,
        p: EngineParams = EngineParams.DEFAULT,
    ): List<Graded> {
        val byImpression = listens.filter { it.impressionId != null }.associateBy { it.impressionId!! }
        val byGroup = HashMap<String, MutableList<ListenRow>>()
        for (l in listens) byGroup.getOrPut(groups.groupOf(l.songId)) { ArrayList() }.add(l)
        val window = p.justPlayedHours * 3_600_000L
        val out = ArrayList<Graded>()
        for (imp in impressions) {
            val tapped = imp.tappedAt
            if (tapped != null) {
                val listen = byImpression[imp.id] ?: continue          // still playing, or lost: never an ignored card
                if (listen.endReason == EndReason.OPEN) continue
                if (!listen.learn) { out += Graded(imp.id, Outcome.DROPPED, 0.0, 0.0); continue }
                val liked = songs[listen.songId]?.likedAt
                out += Graded(imp.id, Outcome.PLAYED, Signals.engagement(listen, liked, p), 1.0)
                continue
            }
            if (now - imp.visibleAt < window) continue                 // the day is not over
            val elsewhere = byGroup[groups.groupOf(imp.songId)].orEmpty().filter { l ->
                l.autoplayDepth == 0 && l.startedAt > imp.visibleAt && l.startedAt <= imp.visibleAt + window && l.endReason != EndReason.OPEN
            }
            if (elsewhere.isNotEmpty()) {
                if (elsewhere.none { it.learn }) { out += Graded(imp.id, Outcome.DROPPED, 0.0, 0.0); continue }
                val g = elsewhere.filter { it.learn }.maxOf { Signals.engagement(it, songs[it.songId]?.likedAt, p) }
                out += Graded(imp.id, Outcome.ELSEWHERE, 0.5 * g, 0.5)
            } else {
                out += Graded(imp.id, Outcome.IGNORED, 0.0, 0.3)
            }
        }
        return out
    }

    /** The features column as stored: comma-separated, four decimals. */
    fun parseFeatures(text: String?): DoubleArray? =
        text?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == Features.COUNT }?.toDoubleArray()
}
