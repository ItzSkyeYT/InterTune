/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowBuildCodecTest {
    @Test
    fun `a row survives the round trip`() {
        val cards = listOf(
            Card("abc", Lane.RELATED, 1.5, 0.1234, DoubleArray(Features.COUNT) { it / 10.0 }, listOf("x_seed", "x_ctx"), "seed1", false),
            Card("def", Lane.AGAIN, -2.0, 0.05, DoubleArray(Features.COUNT), listOf("wildcard"), null, true),
        )
        val back = RowBuildCodec.decode(RowBuildCodec.encode(cards))
        assertEquals(cards.map { it.songId }, back.map { it.songId })
        assertEquals(listOf(Lane.RELATED, Lane.AGAIN), back.map { it.lane })
        assertEquals(0.1234, back[0].p, 1e-9); assertEquals(listOf("x_seed", "x_ctx"), back[0].reasons); assertEquals("seed1", back[0].seedId)
        assertTrue(back[1].sampled); assertEquals(null, back[1].seedId); assertEquals(0.5, back[0].features[5], 1e-9)
    }

    @Test
    fun `nothing and rubbish decode to nothing`() {
        assertTrue(RowBuildCodec.decode(null).isEmpty())
        assertTrue(RowBuildCodec.decode("").isEmpty())
        assertTrue(RowBuildCodec.decode("one\ttwo").isEmpty())
    }
}
