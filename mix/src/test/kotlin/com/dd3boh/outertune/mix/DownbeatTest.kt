/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar: the right beat of four called the one wherever the music says so, and never a confident
 * guess where it does not. A downbeat a beat off puts the next song's one on this song's two, which
 * no tightness of beats can save, so a wrong confident phase fails outright.
 */
class DownbeatTest {

    private fun heard(spec: SynthSpec) = Heard.of(Synth.song(spec), 0.0, 30.0)

    @Test
    fun fourOnTheFloorFindsTheOne() {
        for ((bpm, seed) in listOf(128.0 to 1L, 124.0 to 2L)) {
            val h = heard(SynthSpec(bpm = bpm, bars = 20, seed = seed))
            println(h.line("4/4 $bpm"))
            assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.downbeats)
            assertEquals(1.0, h.downbeatAccuracy(), 0.0)
        }
    }

    @Test
    fun aBackbeatAndAHalfTimeBeatFindTheOne() {
        for ((label, spec) in listOf(
            "backbeat 100" to SynthSpec(bpm = 100.0, bars = 16, groove = Groove.BACKBEAT, seed = 3),
            "backbeat 140" to SynthSpec(bpm = 140.0, bars = 20, groove = Groove.BACKBEAT, seed = 5),
            "half time 90" to SynthSpec(bpm = 90.0, bars = 14, groove = Groove.HALF_TIME, seed = 4),
            "shuffle 112" to SynthSpec(bpm = 112.0, bars = 16, groove = Groove.BACKBEAT, swing = 2.0 / 3.0, seed = 7),
        )) {
            val h = heard(spec)
            println(h.line(label))
            assertTrue("$label: ${h.rhythm.gates.failures()}", h.rhythm.gates.downbeats)
            assertEquals(label, 1.0, h.downbeatAccuracy(), 0.0)
        }
    }

    @Test
    fun aWaltzReadInFoursIsNotConfident() {
        for (seed in 1L..3L) {
            val h = heard(SynthSpec(bpm = 120.0, bars = 22, groove = Groove.WALTZ, seed = seed))
            println(h.line("waltz s$seed"))
            assertFalse(h.rhythm.gates.downbeats)
        }
    }

    @Test
    fun withoutChordsThePhaseIsRightOrNotClaimed() {
        for (seed in 1L..3L) {
            val h = heard(SynthSpec(bpm = 125.0, bars = 20, chords = false, seed = seed))
            println(h.line("no chords s$seed"))
            if (h.rhythm.gates.downbeats) assertEquals(1.0, h.downbeatAccuracy(), 0.0)
        }
    }

    @Test
    fun theHelpersCountBarsFromThePhase() {
        val bars = Downbeats(phase = 2, margin = 5.0, scores = DoubleArray(4))
        assertTrue(bars.isDownbeat(2))
        assertTrue(bars.isDownbeat(-2))
        assertFalse(bars.isDownbeat(3))
        assertEquals(6, bars.firstAtOrAfter(3))
        assertEquals(2, bars.firstAtOrAfter(2))
        assertEquals(2, bars.lastAtOrBefore(5))
        assertEquals(-2, bars.lastAtOrBefore(1))
    }
}
