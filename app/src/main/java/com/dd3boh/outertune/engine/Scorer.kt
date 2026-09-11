/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.exp
import kotlin.math.ln

/** The eighteen numbers, by name: the twelve feature weights, the bias, four lane biases, position. */
class Weights(values: Map<String, Double> = emptyMap()) {
    private val w: Map<String, Double> = Features.priors.mapValues { (name, prior) -> values[name] ?: prior.value }
    operator fun get(name: String): Double = w[name] ?: 0.0
    fun asMap(): Map<String, Double> = w

    companion object {
        val PRIORS = Weights()
    }
}

object Scorer {
    fun sigmoid(x: Double): Double = 1 / (1 + exp(-x))

    /** The ranking score: the bias and the features, with position treated as missing at serving. */
    fun z(x: DoubleArray, w: Weights): Double {
        var s = w[Features.BIAS]
        for (i in 0 until Features.COUNT) s += w[Features.names[i]] * x[i]
        return s
    }

    /** Position: the four-row grid fills column by column, so the column, not the slot, is what the eye sees. */
    fun position(slot: Int, columns: Int = 4): Double = ln(1.0 + slot / columns) / ln(5.0)

    /** The predicted chance of a play, in its lane and at its slot. */
    fun p(x: DoubleArray, w: Weights, lane: Lane, slot: Int, columns: Int = 4): Double =
        sigmoid(z(x, w) + w[Features.laneBias(lane)] + w[Features.POS] * position(slot, columns))

    /** The two largest positive terms of the score: what most made the case for this card. */
    fun reasons(x: DoubleArray, w: Weights): List<String> =
        (0 until Features.COUNT).map { Features.names[it] to w[Features.names[it]] * x[it] }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(2).map { it.first }
}
