/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/** A build's lean as row_build keeps it, for grading the build's impressions. */
data class BuildLean(val id: Long, val leanApplied: Int, val leadWeight: Double)

/**
 * What a card's evidence weighs when its build leaned.
 *
 * A lean shows one lane twelve times a row instead of two to seven, and every card shown is an
 * example for the one set of weights. Left alone, Never heard would teach x_novel and b_explore
 * from twelve passed-over explore cards a row, and whoever went back to Auto would carry that with
 * them. So the lead lane's impressions are weighed down until that lane's share of a build's
 * evidence is the share an Auto build would have given it. The other lanes keep weight one: their
 * quotas are the blend's proportions of the cards left, so with the lead lane at its Auto share,
 * every lane is close to its own.
 */
object LeanWeighting {
    /**
     * The odds of the lane's Auto quota over the odds of its lean quota: the weight that gives the
     * lead lane exactly its Auto share of the row's evidence. Never above one, and one with no lean.
     *
     * With a the Auto quota, l the lean quota and R the row, the lead lane's share of the summed
     * weight is l w / (l w + R - l), which equals a / R exactly when w = (a / (R - a)) / (l / (R - l)).
     */
    fun leadWeight(autoQuota: Int, leanQuota: Int, rowSize: Int): Double {
        if (leanQuota <= 0 || leanQuota >= rowSize || autoQuota >= rowSize) return 1.0
        val auto = autoQuota.toDouble() / (rowSize - autoQuota)
        val lean = leanQuota.toDouble() / (rowSize - leanQuota)
        return (auto / lean).coerceIn(0.0, 1.0)
    }

    /** The weight of one impression: [leadWeight] for the build's lead lane, one for everything else. */
    fun of(lane: Lane?, lead: Lane?, leadWeight: Double): Double = if (lead != null && lane == lead) leadWeight else 1.0

    /**
     * The u a graded impression is stored with. Only the engine's own cards (team 1) of a build
     * that leaned are scaled, so Apply and Rebuild replay the stored number as it is, and every
     * other row, Auto's included, keeps its grade exactly.
     */
    fun gradedU(u: Double, team: Int, lane: Lane?, build: BuildLean?): Double =
        if (team != 1 || build == null) u else u * of(lane, Lean.ofCode(build.leanApplied).lane, build.leadWeight)

    /** The pool's first forty, the whole of it with no lean: the only songs a pool pick may be. */
    const val POOL_PICK_CANDIDATES = 40

    /**
     * The songs that may become pool picks: the first forty. The lead-lane spares a lean adds after
     * them are there for the tidy pass, and a lean must not widen what counts as a song the row
     * had and missed.
     */
    fun poolPickCandidates(pool: List<Card>): List<Card> = pool.take(POOL_PICK_CANDIDATES)

    /**
     * The pool-pick reference: the mean features of the shown, unplayed cards, each weighed as its
     * impression is, so a pool pick under Never heard is compared with a row shaped like Auto's
     * rather than with twelve explore cards. Null with nothing to compare against.
     */
    fun reference(cards: List<Card>, lead: Lane?, leadWeight: Double): DoubleArray? {
        if (cards.isEmpty()) return null
        val w = cards.map { of(it.lane, lead, leadWeight) }
        val total = w.sum().takeIf { it > 0 } ?: return null
        return DoubleArray(Features.COUNT) { i -> cards.indices.sumOf { k -> w[k] * cards[k].features.getOrElse(i) { 0.0 } } / total }
    }
}
