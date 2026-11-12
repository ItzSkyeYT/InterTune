/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.GlideMap
import com.dd3boh.outertune.mix.edit.TransitionSpec

/**
 * Where the next song is heard on the change's bars, for drawing it: B's own ms at bar position u of
 * the change, and the bar where a moment of B is heard. On the change's bars as
 * [TransitionSpec.bMsAt] has it, its loop included, while B keeps them; where B is the song
 * adjusted, from the bar line A is gone on, B glides back to its own tempo over
 * [SpecPlan.GLIDE_BARS] bars and plays at it after, so its bars no longer fall on the change's.
 * Worked out from the plan's own layout ([SpecPlan.bLayout]), so the picture is what plays.
 */
class BHeard(private val spec: TransitionSpec) {
    private val layout = SpecPlan.bLayout(spec, RATE)
    private val map = layout?.let { GlideMap(0.0, it.first.toDouble(), it.speed, it.glideFrom, it.glideTo) }
    private val barFrames = spec.heardBarMs * RATE / 1000.0

    /** The change's bar from which B leaves its bars for its own tempo; never where B keeps them. */
    val glideFromU: Double = layout?.let { it.firstU + it.glideFrom / barFrames } ?: Double.POSITIVE_INFINITY

    /** The change's bar from which B plays at its own tempo again. */
    val glideToU: Double = layout?.let { it.firstU + it.glideTo / barFrames } ?: Double.POSITIVE_INFINITY

    /** B's own ms heard at bar position [u] of the change. */
    fun msAt(u: Double): Double {
        val l = layout ?: return spec.bMsAt(u)
        if (u <= glideFromU) return spec.bMsAt(u)
        val v = map!!.tailAt((u - l.firstU) * barFrames)
        // A glide that begins while B's loop still plays: the loop's own bars, at B's own tempo.
        l.loop?.let { p -> if (v >= p.outFrom && v < p.outTo) return (p.srcFrom + (v - p.outFrom) % p.frames) * 1000.0 / RATE }
        return v * 1000.0 / RATE
    }

    /** How many of B's own ms one bar of the change holds at bar position [u]. */
    fun msPerBar(u: Double): Double {
        val l = layout ?: return spec.b.barMs
        if (u <= glideFromU) return spec.b.barMs
        return map!!.speedAt((u - l.firstU) * barFrames) * spec.heardBarMs
    }

    /** The bar of the change near [u] where B's own [ms] is heard, [ms] being near what is heard there: a downbeat of B found near [msAt] (u). */
    fun uNear(ms: Double, u: Double): Double = u + (ms - msAt(u)) / msPerBar(u)

    /** The change's bar where B's own [ms] is heard, past its loop. */
    fun uAt(ms: Double): Double {
        val l = layout ?: return (ms - spec.b.atMs) / spec.b.barMs + spec.loopBars
        return l.firstU + map!!.outAt(ms * RATE / 1000.0) / barFrames
    }

    private companion object {
        const val RATE = 48_000
    }
}
