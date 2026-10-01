/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.playback.ListenProgress

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
    /**
     * The listen it produced asked not to teach, or failed; graded so it is never looked at again,
     * weighing nothing.
     */
    const val DROPPED = 6
    /**
     * Tapped, but no listen of it was ever found. Settled a day after the tap, weighing nothing:
     * never an ignored card, which is the promise below, and no longer pending, where it was read
     * again on every run for good.
     */
    const val LOST = 7
    /**
     * Tapped, and its play failed: waiting, until a resume could no longer link to the play, for
     * one that carries it on. Not graded, so it is read again on every run, but it decides nothing
     * meanwhile. A plain pending card counts as seen and not heard in the share of each similar
     * songs source ([SourceMix]); this one is left out of it, as it is left out of the learning
     * until it is graded.
     */
    const val WAITING = 8
}

/** What became of a card, or with [Outcome.WAITING], that it is not graded yet. */
data class Graded(val impressionId: Long, val outcome: Int, val y: Double, val u: Double, val listenId: Long? = null)

/**
 * Wins are graded by engagement, never by the tap: a tap abandoned after twenty seconds grades 0,
 * a finished song 1. A card played from the row takes its listen's engagement at full weight; the
 * same song played from somewhere else within a day of being seen takes half the grade at half
 * the weight, so a coincidence is not a win; a card seen and not played within a day is an ignored
 * card at weight 0.3. A card that was tapped is never graded ignored, even when no listen arrives,
 * so a service killed mid-song cannot turn a win into a loss.
 *
 * A play that failed decides nothing (see [EngineListens]): a card whose play died is settled at no
 * weight, like one whose play asked not to teach, and a failed play elsewhere neither wins the card
 * nor lets it be graded ignored.
 *
 * A card's play is followed through the rows that carry it on (continuesListenId, see
 * [ListenProgress.continues]): a play stopped or failed and picked up again where it stood is one
 * play, graded once by all of it. So a card whose play failed and was resumed is graded by the whole
 * play and how it ended. One that failed waits out the time a resume can still come in
 * ([Outcome.WAITING]), and is settled at no weight only once none did.
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
        // Each row that was carried on, and the earliest row carrying it on.
        val resumedBy = HashMap<Long, ListenRow>()
        for (l in listens) {
            val from = l.continuesListenId ?: continue
            if (resumedBy[from].let { it == null || l.startedAt < it.startedAt }) resumedBy[from] = l
        }
        val window = p.justPlayedHours * 3_600_000L
        val out = ArrayList<Graded>()
        for (imp in impressions) {
            val tapped = imp.tappedAt
            if (tapped != null) {
                // The link is made when the listen opens, from the tap's moment carried through the
                // player, and some ways of starting a song lose that moment. On his phone on 25 Sep,
                // 73 tapped cards more than two days old had no listen linked, 19 of them with the
                // same song starting within a minute of the tap. So that song's own play, started
                // just after the tap from a queue and not claimed by another card, stands in.
                val listen = byImpression[imp.id]
                    ?: byGroup[groups.groupOf(imp.songId)].orEmpty()
                        .filter { it.impressionId == null && it.autoplayDepth == 0 && it.startedAt in (tapped - TAP_BEFORE_MS)..(tapped + TAP_AFTER_MS) }
                        .minByOrNull { kotlin.math.abs(it.startedAt - tapped) }
                if (listen == null) {
                    // Still starting, or lost: never an ignored card. Settled once the day is over.
                    if (now - tapped >= window) out += Graded(imp.id, Outcome.LOST, 0.0, 0.0)
                    continue
                }
                val play = asOnePlay(listen, resumedBy)
                if (play.endReason == EndReason.OPEN) continue
                if (!play.learn) { out += Graded(imp.id, Outcome.DROPPED, 0.0, 0.0); continue }
                if (EngineListens.failed(play)) {
                    // Until a resume can no longer come in, the play may yet be carried on.
                    if (now - play.endedAt <= ListenProgress.RESUME_WINDOW_MS) {
                        out += Graded(imp.id, Outcome.WAITING, 0.0, 0.0)
                        continue
                    }
                    out += Graded(imp.id, Outcome.DROPPED, 0.0, 0.0)
                    continue
                }
                val liked = songs[play.songId]?.likedAt
                out += Graded(imp.id, Outcome.PLAYED, Signals.engagement(play, liked, p), 1.0, listen.id.takeIf { it > 0 })
                continue
            }
            if (now - imp.visibleAt < window) continue                 // the day is not over
            val elsewhere = byGroup[groups.groupOf(imp.songId)].orEmpty().filter { l ->
                l.autoplayDepth == 0 && l.startedAt > imp.visibleAt && l.startedAt <= imp.visibleAt + window && l.endReason != EndReason.OPEN
            }
            if (elsewhere.isNotEmpty()) {
                val teaching = elsewhere.filter { it.learn && !EngineListens.failed(it) }
                if (teaching.isEmpty()) { out += Graded(imp.id, Outcome.DROPPED, 0.0, 0.0); continue }
                val g = teaching.maxOf { Signals.engagement(it, songs[it.songId]?.likedAt, p) }
                out += Graded(imp.id, Outcome.ELSEWHERE, 0.5 * g, 0.5)
            } else {
                out += Graded(imp.id, Outcome.IGNORED, 0.0, 0.3)
            }
        }
        return out
    }

    /**
     * [first] followed through the rows that carried it on, as one play: started when it did,
     * heard for all their time together, ended when and how the last of them ended, and meant to
     * teach only if every part was. [first] itself when nothing carried it on.
     */
    internal fun asOnePlay(first: ListenRow, resumedBy: Map<Long, ListenRow>): ListenRow {
        var last = first
        var playedMs = first.playedMs
        var durationMs = first.durationMs
        var learn = first.learn
        val seen = HashSet<Long>()
        while (last.id > 0 && seen.add(last.id)) {
            val next = resumedBy[last.id] ?: break
            playedMs += next.playedMs
            if (next.durationMs > 0) durationMs = next.durationMs
            learn = learn && next.learn
            last = next
        }
        if (last === first) return first
        return first.copy(endedAt = last.endedAt, playedMs = playedMs, durationMs = durationMs, endReason = last.endReason, learn = learn)
    }

    /** How far before and after a tap its song's play may start and still be that tap's. */
    private const val TAP_BEFORE_MS = 5_000L
    private const val TAP_AFTER_MS = 60_000L

    /** The features column as stored: comma-separated, four decimals. */
    /**
     * The feature vector an impression was scored with, padded to today's width.
     *
     * It used to insist on exactly [Features.COUNT] numbers, which meant that adding a feature
     * would silently invalidate every impression ever stored: they would all parse to null and
     * drop out of the learner, taking the whole history with them. A vector written before a
     * feature existed simply did not measure it, and zero is the honest value for that, so short
     * vectors are padded rather than refused. Longer ones are still refused, because a vector
     * wider than the model is not something this version can read.
     */
    fun parseFeatures(text: String?): DoubleArray? {
        val parsed = text?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        if (parsed.isEmpty() || parsed.size > Features.COUNT) return null
        return DoubleArray(Features.COUNT) { parsed.getOrElse(it) { 0.0 } }
    }
}
