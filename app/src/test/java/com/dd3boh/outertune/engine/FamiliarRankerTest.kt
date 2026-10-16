/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port holds to the code that fitted it: the scores below are what the bake-off's own Python
 * computes for the same history (experiments/features/recs-ranker/fixture18.py): two weeks, two
 * artists, a Saturday among the days, a clock two hours ahead of UTC.
 */
class FamiliarRankerTest {
    private val t = 1_789_135_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val artistOf = mapOf(0 to "a0", 1 to "a0", 2 to "a1", 3 to "a1", 4 to "a0", 5 to "a1")

    private fun history(): EngineInput {
        val listens = ArrayList<ListenRow>()
        fun session(id: Long, start: Long, songs: List<Int>) = songs.forEachIndexed { i, s ->
            val begin = start + i * 210_000L
            listens += ListenRow("s$s", begin, begin + 200_000, 200_000, 200_000, EndReason.ENDED, 0, 0, id, 120)
        }
        session(1, t - 13 * day, listOf(0, 1, 2, 0))
        session(2, t - 9 * day - 5 * hour, listOf(2, 3, 2))
        session(3, t - 6 * day, listOf(1, 0, 4))
        session(4, t - 3 * day + 2 * hour, listOf(3, 5, 3, 2))
        session(5, t - 1 * day, listOf(0, 1, 4, 0))
        session(6, t - 4 * hour, listOf(2, 3, 1))
        val songs = (0..5).associate { "s$it" to SongRow("s$it", "s$it", artistOf.getValue(it), artistOf.getValue(it)) }
        return EngineInput(t, songs, listens, emptyList(), tzOffsetMin = 120)
    }

    @Test
    fun `the scores are the fitted ranker's, signal for signal`() {
        val s = FamiliarRanker.scores(history())
        assertEquals(setOf("s0", "s1", "s2", "s3", "s4", "s5"), s.keys)
        assertEquals(0.198472663, s.getValue("s0"), 1e-5)
        assertEquals(-0.045583123, s.getValue("s1"), 1e-5)
        assertEquals(1.116605672, s.getValue("s2"), 1e-5)
        assertEquals(-0.576635715, s.getValue("s3"), 1e-5)
        assertEquals(-0.948988853, s.getValue("s4"), 1e-5)
        assertEquals(-2.579116915, s.getValue("s5"), 1e-5)
    }

    @Test
    fun `a short listen counts for nothing`() {
        val input = history()
        val s = FamiliarRanker.scores(input)
        assertTrue(s.getValue("s2") > s.getValue("s0"))
        // Cut at ten seconds, a listen is not heard well: the scores do not move.
        val cut = input.copy(listens = input.listens + ListenRow("s5", t - hour, t - hour + 10_000, 10_000, 200_000, EndReason.SKIPPED, 0, 0, 7, 120))
        assertEquals(s, FamiliarRanker.scores(cut))
    }
}
