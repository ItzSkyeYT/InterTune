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
 * computes for the same history (experiments/features/recs-ranker/fixture.py).
 */
class FamiliarRankerTest {
    private val t = 1_789_135_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun history(): EngineInput {
        val listens = ArrayList<ListenRow>()
        fun session(id: Long, start: Long, songs: List<Int>) = songs.forEachIndexed { i, s ->
            val begin = start + i * 210_000L
            listens += ListenRow("s$s", begin, begin + 200_000, 200_000, 200_000, EndReason.ENDED, 0, 0, id, 0)
        }
        session(1, t - 10 * day, listOf(0, 1, 2, 0, 1))
        session(2, t - 3 * day, listOf(1, 2, 3))
        session(3, t - 5 * hour, listOf(0, 1, 2))
        return EngineInput(t, (0..3).associate { "s$it" to SongRow("s$it", "s$it", "a", "A") }, listens, emptyList())
    }

    @Test
    fun `the scores are the fitted ranker's, signal for signal`() {
        val s = FamiliarRanker.scores(history())
        assertEquals(-0.536631175, s.getValue("s0"), 1e-5)
        assertEquals(-2.197943942, s.getValue("s1"), 1e-5)
        assertEquals(-2.184066868, s.getValue("s2"), 1e-5)
        assertEquals(-1.522422059, s.getValue("s3"), 1e-5)
    }

    @Test
    fun `what came after the song heard last leads, and a short listen counts for nothing`() {
        val input = history()
        val s = FamiliarRanker.scores(input)
        // s2 was heard last; s0 and s3 each followed it once.
        assertTrue(s.getValue("s0") > s.getValue("s1"))
        // A listen cut at ten seconds is not heard well: the scores do not move.
        val cut = input.copy(listens = input.listens + ListenRow("s3", t - hour, t - hour + 10_000, 10_000, 200_000, EndReason.SKIPPED, 0, 0, 4, 0))
        assertEquals(s, FamiliarRanker.scores(cut))
    }
}
