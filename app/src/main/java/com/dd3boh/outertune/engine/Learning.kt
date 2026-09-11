/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.abs

/**
 * One graded impression: what the prediction saw, and what happened. Slot -1 is a pool pick,
 * applied pairwise on the difference of features with the biases left out.
 */
class Example(
    val features: DoubleArray,
    val lane: Lane?,
    val slot: Int,
    /** The grade: engagement for a play, half of it for a play elsewhere, 0 for a card passed over. */
    val y: Double,
    /** How much this example weighs: 1 played, 0.5 elsewhere, 0.3 ignored, 0.5 pool. */
    val u: Double,
    val pairwise: Boolean = false,
)

/**
 * The eighteen numbers and the one rule that moves them. For each example, oldest first:
 * `p = σ(w · x)`, `err = y - p`, `η = 0.05 u`, and every weight steps by `η (err x - 0.01 (w - w0))`
 * inside its bounds, so a penalty learns how strong it is but never that a card passed over should
 * be promoted, and everything is pulled, slowly, back toward its prior. A pool pick moves only the
 * twelve feature weights, on the difference between the pick and the row's unplayed cards: applied
 * whole it would move the bias thirty times as far as an ignored card moves it back.
 */
class Learner(initial: Map<String, Double> = emptyMap(), private val p: EngineParams = EngineParams.DEFAULT) {
    private val w: MutableMap<String, Double> = Features.priors.mapValues { (name, prior) -> (initial[name] ?: prior.value).coerceIn(prior.lo, prior.hi) }.toMutableMap()
    /** How many examples have been applied. */
    var updates = 0
        private set

    val weights: Weights get() = Weights(w)
    fun asMap(): Map<String, Double> = w.toMap()

    fun predict(e: Example): Double {
        var s = 0.0
        for (i in 0 until Features.COUNT) s += w[Features.names[i]]!! * e.features[i]
        if (!e.pairwise) {
            s += w[Features.BIAS]!!
            e.lane?.let { s += w[Features.laneBias(it)]!! }
            if (e.slot >= 0) s += w[Features.POS]!! * Scorer.position(e.slot, p.columns)
        }
        return Scorer.sigmoid(s)
    }

    /** Applies one example and returns the budget it spent, `η |err|`. */
    fun apply(e: Example): Double {
        val prediction = predict(e)
        val err = e.y - prediction
        val eta = LEARNING_RATE * e.u
        fun step(name: String, x: Double) {
            val prior = Features.priors[name] ?: return
            val cur = w[name]!!
            w[name] = (cur + eta * (err * x - PULL * (cur - prior.value))).coerceIn(prior.lo, prior.hi)
        }
        for (i in 0 until Features.COUNT) step(Features.names[i], e.features[i])
        if (!e.pairwise) {
            step(Features.BIAS, 1.0)
            e.lane?.let { step(Features.laneBias(it), 1.0) }
            if (e.slot >= 0) step(Features.POS, Scorer.position(e.slot, p.columns))
        }
        updates++
        return eta * abs(err)
    }

    companion object {
        const val LEARNING_RATE = 0.05
        /** The pull back to the prior: a time constant of 2000 full-weight updates. */
        const val PULL = 0.01
        /** At most this much `η |err|` is applied per local day; the rest waits for later days. */
        const val DAILY_BUDGET = 1.0
    }
}

/** A day's spending against the budget: applies what fits, oldest first, and says which examples were taken. */
class Budget(var spent: Double = 0.0, private val limit: Double = Learner.DAILY_BUDGET) {
    fun canAfford(): Boolean = spent < limit
    fun charge(cost: Double) { spent += cost }
}

/** How well the predictions have matched what happened: the Brier score and a reliability table. */
object Calibration {
    data class Bin(val lo: Double, val hi: Double, val count: Int, val meanPrediction: Double, val playRate: Double)

    /** Mean squared error between the prediction and whether the card won (`y >= 0.5`), over slotted examples. */
    fun brier(pairs: List<Pair<Double, Double>>): Double =
        if (pairs.isEmpty()) Double.NaN else pairs.sumOf { (p, y) -> val won = if (y >= 0.5) 1.0 else 0.0; (p - won) * (p - won) } / pairs.size

    fun reliability(pairs: List<Pair<Double, Double>>, bins: Int = 5): List<Bin> = (0 until bins).map { i ->
        val lo = i.toDouble() / bins; val hi = (i + 1).toDouble() / bins
        val inBin = pairs.filter { (p, _) -> p >= lo && (p < hi || (i == bins - 1 && p <= hi)) }
        Bin(lo, hi, inBin.size, if (inBin.isEmpty()) Double.NaN else inBin.sumOf { it.first } / inBin.size,
            if (inBin.isEmpty()) Double.NaN else inBin.count { it.second >= 0.5 }.toDouble() / inBin.size)
    }
}
