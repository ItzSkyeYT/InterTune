/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored lane code, and the off-by-one that hid a whole lane from the learner.
 *
 * Impressions keep the lane as ordinal plus one. Both readers decoded it by hand with `in 1..4`,
 * written when there were four lanes, so AGAIN was thrown away on the way in and its bias sat at
 * zero looking like a verdict rather than an absence.
 */
class LaneCodeTest {

    @Test
    fun `every lane survives a round trip through its code`() {
        Lane.entries.forEach { lane ->
            assertEquals(lane, Lane.ofCode(lane.ordinal + 1))
        }
    }

    @Test
    fun `AGAIN is the one the old range dropped`() {
        // The exact card the learner never saw: ordinal 4, code 5, outside "in 1..4".
        assertEquals(Lane.AGAIN, Lane.ofCode(5))
    }

    @Test
    fun `zero means the card was not the engine's`() {
        assertNull(Lane.ofCode(0))
    }

    @Test
    fun `nothing is invented out of range`() {
        assertNull(Lane.ofCode(-1))
        assertNull(Lane.ofCode(Lane.entries.size + 1))
    }
}
