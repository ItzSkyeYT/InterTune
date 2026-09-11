/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmStartTest {
    private val now = 1_789_135_200_000L
    private val day = 86_400_000L

    /** A listener with a habit: every evening, one of artist 0's songs, always finished. */
    private fun history(): EngineInput {
        val songs = LinkedHashMap<String, SongRow>()
        for (a in 0 until 6) for (i in 0 until 6) songs["a${a}s$i"] = SongRow("a${a}s$i", "Song $a $i", "art$a", "artist$a", inLibrary = true)
        val edges = ArrayList<Edge>()
        for (a in 0 until 6) for (i in 0 until 6) for (j in 0 until 6) edges += Edge("a${a}s$i", "a${(a + 1) % 6}s$j")
        val listens = ArrayList<ListenRow>()
        for (d in 1..40) {
            val start = now - d * day
            val song = "a0s${d % 6}"
            listens += ListenRow(song, start, start + 200_000, 200_000, 200_000, EndReason.ENDED, PlayOrigin.SEARCH.code, 0, d.toLong(), 0)
            listens += ListenRow("a${1 + d % 5}s${d % 6}", start + 300_000, start + 400_000, 100_000, 200_000, EndReason.SKIPPED, PlayOrigin.RADIO.code, 1, d.toLong(), 0)
        }
        return EngineInput(now, songs, listens, edges)
    }

    @Test
    fun `nothing to learn from leaves the priors`() {
        val r = WarmStart.run(EngineInput(now, emptyMap(), emptyList(), emptyList()))
        assertEquals(0, r.sessions)
        for ((name, v) in r.weights) assertEquals(Features.priors[name]!!.value, v, 0.0)
    }

    @Test
    fun `a history moves the weights, inside their bounds, and the same history twice gives the same weights`() {
        val a = WarmStart.run(history(), maxSessions = 30)
        assertTrue(a.sessions > 0); assertTrue(a.examples > 0)
        assertTrue(a.weights.any { (name, v) -> Math.abs(v - Features.priors[name]!!.value) > 1e-6 })
        for ((name, v) in a.weights) { val pr = Features.priors[name]!!; assertTrue(name, v >= pr.lo - 1e-9 && v <= pr.hi + 1e-9) }
        for (name in listOf("x_sat", "x_gap", "x_imp", "x_over", "w_pos")) assertTrue(a.weights[name]!! <= 0.0)
        val b = WarmStart.run(history(), maxSessions = 30)
        for ((name, v) in a.weights) assertEquals(v, b.weights[name]!!, 1e-12)
    }
}
