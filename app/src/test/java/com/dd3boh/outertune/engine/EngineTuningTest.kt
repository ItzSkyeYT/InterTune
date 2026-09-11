/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineTuningTest {
    @Test
    fun `overrides round-trip and apply, unknown names are ignored`() {
        val text = EngineTuning.encode(mapOf("maxPerArtist" to 3.0, "rampTo" to 0.75, "nonsense" to 9.0))
        val parsed = EngineTuning.parse(text)
        assertEquals(3.0, parsed["maxPerArtist"]!!, 0.0)
        val p = EngineTuning.params(parsed)
        assertEquals(3, p.maxPerArtist); assertEquals(0.75, p.rampTo, 0.0); assertEquals(EngineParams.DEFAULT.rampFrom, p.rampFrom, 0.0)
        assertEquals(EngineParams.DEFAULT, EngineTuning.params(EngineTuning.parse("")))
    }

    @Test
    fun `every entry's default is the shipped value`() {
        val d = EngineParams.DEFAULT
        EngineTuning.entries.forEach { t ->
            // Applying the stated default must give back the defaults exactly.
            assertEquals(t.name, d, t.apply(d, t.default))
        }
    }
}
