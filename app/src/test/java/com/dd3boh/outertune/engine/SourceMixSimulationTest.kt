/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The share over simulated months of listening, seeded, at about the evidence one real phone gathers:
 * 35 contested edge cards a day on average, 70% related at a 13% play rate and the rest explore
 * at 4%, with half of YouTube's cards coming from seeds Last.fm has no list for and so not
 * counted. Each day's rows follow the share the day started with, as the app's would.
 */
class SourceMixSimulationTest {
    private val day = 86_400_000L
    private val seeds = 0 until 20

    /** The share at the start of each day 0 to [days]; [mult] gives (Last.fm, YouTube) play multipliers for a day. */
    private fun run(seed: Int, days: Int, burstOn: Int? = null, mult: (Int) -> Pair<Double, Double>): DoubleArray {
        val rng = Random(seed)
        val evidence = ArrayList<SourceEvidence>()
        val shares = DoubleArray(days + 1)
        for (d in 0..days) {
            val s = SourceMix.share(evidence, 0).share
            shares[d] = s
            if (d == days) break
            val (lm, ym) = mult(d)
            val n = 17 + rng.nextInt(36)
            for (i in 0 until n) {
                val related = rng.nextDouble() < 0.7
                val base = if (related) 0.13 else 0.04
                val lastFm = rng.nextDouble() < s
                if (!lastFm && rng.nextDouble() >= 0.5) continue
                val played = rng.nextDouble() < base * (if (lastFm) lm else ym)
                val y = if (played) 0.2 + 0.8 * rng.nextDouble() else 0.0
                val bits = (if (lastFm) Provenance.LASTFM else Provenance.YOUTUBE) or Provenance.CONTESTED
                evidence += SourceEvidence("d${d}c$i", 1, if (related) 1 else 4, bits, d * day + i * 1000L, if (played) Outcome.PLAYED else Outcome.IGNORED, y.toFloat())
            }
            // Thirty Last.fm cards started and skipped at once, the kind of burst a bad row gives.
            if (d == burstOn) for (j in 0 until 30) evidence += SourceEvidence("burst$j", 1, 1, Provenance.LASTFM or Provenance.CONTESTED, d * day + 500_000L + j, Outcome.PLAYED, 0f)
        }
        return shares
    }

    @Test
    fun `equal sources stay near half and inside the floor`() {
        val runs = seeds.map { run(it, 60) { 1.0 to 1.0 } }
        val mean = runs.map { it[60] }.average()
        assertTrue("mean at day 60 $mean", mean in 0.42..0.58)
        for (r in runs) for (s in r) assertTrue("share $s", s in 0.2..0.8)
        val firstDay = runs.map { kotlin.math.abs(it[1] - 0.5) }.average()
        assertTrue("mean movement on day one $firstDay", firstDay < 0.1)
    }

    @Test
    fun `a better Last-fm wins more of the places within a month`() {
        val twice = seeds.map { run(it, 30) { 2.0 to 1.0 }[30] }.average()
        val half = seeds.map { run(it, 30) { 1.5 to 1.0 }[30] }.average()
        assertTrue("2x at day 30: $twice", twice >= 0.62)
        assertTrue("1.5x at day 30: $half", half >= 0.56)
    }

    @Test
    fun `a reversal is followed back within a month`() {
        val back = seeds.count { seed ->
            val r = run(seed, 90) { d -> if (d < 60) 2.0 to 1.0 else 1.0 to 2.0 }
            (61..90).any { r[it] < 0.5 }
        }
        assertTrue("$back of 20 went back under half", back >= 17)
    }

    @Test
    fun `a burst of skipped cards barely moves the share`() {
        val change = seeds.map { seed -> run(seed, 46, burstOn = 45) { 1.0 to 1.0 }[46] - run(seed, 46) { 1.0 to 1.0 }[46] }.average()
        assertTrue("mean change $change", change > -0.1 && change <= 0.0)
    }
}
