/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ln
import kotlin.math.pow

/**
 * How present a song is in the listener's memory, from when and how well it was heard.
 *
 * ACT-R base-level activation: ln of the sum over past listens of (hours since + 1)^-d, each term
 * scaled by how much of that listen was heard. Power-law decay rather than a half-life, because a
 * half-life erases a favourite from two years ago and a power law keeps it at a low weight, which
 * is what the rediscovery lane needs. d = 0.5 is the ACT-R default and what the Deezer relistening
 * work (PISA, REACTA, Ex2Vec) fixes it at; fitted exponents on Last.fm data did not clearly beat
 * it. The one-hour offset keeps a play from this minute finite.
 *
 * With d = 0.5 a full listen weighs 0.20 after a day, 0.077 after a week, 0.037 after a month and
 * 0.011 after a year. Two full listens a day apart score higher than one, and a listen an hour ago
 * outscores the same listen a week ago: the two properties every relistening model agrees on.
 *
 * Ages are clamped at zero. The legacy event table stores the wall clock as if it were UTC, so a
 * play from this morning can look like it is two hours in the future; the listen table stores true
 * UTC, but a clock set backwards produces the same thing, and a negative age must never reach the
 * power.
 */
object Activation {

    const val DECAY = 0.5
    const val OFFSET_HOURS = 1.0

    /** No listens at all. Well below any real score, and finite so it can be compared. */
    const val NONE = -20.0

    /**
     * @param listens pairs of (milliseconds since the listen ended, engagement weight 0..1)
     */
    fun of(listens: List<Pair<Long, Float>>, decay: Double = DECAY, offsetHours: Double = OFFSET_HOURS): Double {
        var sum = 0.0
        for ((ageMs, weight) in listens) {
            if (weight <= 0f) continue
            val hours = (ageMs.coerceAtLeast(0L) / 3_600_000.0) + offsetHours
            sum += weight * hours.pow(-decay)
        }
        return if (sum <= 0.0) NONE else ln(sum)
    }
}
