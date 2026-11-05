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
 * Tempo on synthetic songs whose tempo is known: right after folding wherever the beat is clear,
 * and never confident where there is no beat to be sure of. A confident wrong answer is the one
 * outcome that fails outright, because that is the one that would put two songs out of step.
 */
class TempoTest {

    private fun heard(spec: SynthSpec, seconds: Double = 30.0): Heard {
        val song = Synth.song(spec)
        return Heard.of(song, 0.0, seconds)
    }

    @Test
    fun fourOnTheFloorAt128() {
        val h = heard(SynthSpec(bpm = 128.0, bars = 20))
        println(h.line("4/4 128"))
        assertEquals(0.0, h.tempoError(), 0.001)
        assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun fourOnTheFloorAt124() {
        val h = heard(SynthSpec(bpm = 124.0, bars = 20, seed = 2))
        println(h.line("4/4 124"))
        assertEquals(0.0, h.tempoError(), 0.001)
        assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun aBackbeatAt100AndAt48Kilohertz() {
        val h = heard(SynthSpec(bpm = 100.0, bars = 16, groove = Groove.BACKBEAT, sampleRate = 48_000, seed = 3))
        println(h.line("backbeat 100 at 48k"))
        assertEquals(0.0, h.tempoError(), 0.001)
        assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun halfTimeAt90() {
        val h = heard(SynthSpec(bpm = 90.0, bars = 14, groove = Groove.HALF_TIME, seed = 4))
        println(h.line("half time 90"))
        assertEquals(0.0, h.tempoError(), 0.001)
        assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun theBeatSurvivesNoiseTwentyDecibelsDown() {
        val h = heard(SynthSpec(bpm = 128.0, bars = 20, noiseDb = -20.0, seed = 5))
        println(h.line("4/4 128, noise -20 dB"))
        assertEquals(0.0, h.tempoError(), 0.001)
        assertTrue(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun aShuffleIsRightOrNotConfident() {
        for (bpm in doubleArrayOf(96.0, 112.0, 132.0)) {
            val h = heard(SynthSpec(bpm = bpm, bars = 16, groove = Groove.BACKBEAT, swing = 2.0 / 3.0, seed = bpm.toLong()))
            println(h.line("shuffle $bpm"))
            if (h.rhythm.gates.tempo) {
                assertEquals("a confident shuffle at $bpm must have the right tempo", 0.0, h.tempoError(), 0.01)
            }
        }
    }

    @Test
    fun aPadWithNoPulseIsNotConfident() {
        val r = Heard.rhythmOf(Synth.pad(30.0))
        println("pad: z %.1f, %s".format(r.gates.tempoZ, r.gates.failures()))
        assertFalse(r.gates.tempo)
    }

    @Test
    fun whiteNoiseIsNotConfident() {
        val r = Heard.rhythmOf(Synth.noise(30.0))
        println("noise: z %.1f, %s".format(r.gates.tempoZ, r.gates.failures()))
        assertFalse(r.gates.tempo)
    }

    @Test
    fun aTempoThatRampsIsNotConfident() {
        val h = heard(SynthSpec(bpm = 120.0, bars = 15, rampPercent = 2.0, seed = 8))
        println(h.line("ramp 2%"))
        assertFalse(h.rhythm.gates.failures().toString(), h.rhythm.gates.beats)
    }

    @Test
    fun foldingKeepsTempoBetween85And170() {
        assertEquals(140.0, Tempo.fold(70.0), 1e-9)
        assertEquals(87.0, Tempo.fold(174.0), 1e-9)
        assertEquals(128.0, Tempo.fold(128.0), 1e-9)
        assertEquals(85.0, Tempo.fold(170.0), 1e-9)
        assertEquals(0.0, Tempo.distance(140.0, 70.0), 1e-9)
        assertEquals(0.0235, Tempo.distance(168.0, 86.0), 0.0005)
    }

    /** Not a check: every variant side by side, to read while tuning. */
    @Test
    fun survey() {
        val rows = ArrayList<String>()
        for (seed in 1L..3L) {
            for ((label, spec) in listOf(
                "4/4 128" to SynthSpec(bpm = 128.0, bars = 20),
                "4/4 174" to SynthSpec(bpm = 174.0, bars = 24),
                "backbeat 85" to SynthSpec(bpm = 85.0, bars = 12, groove = Groove.BACKBEAT),
                "backbeat 140" to SynthSpec(bpm = 140.0, bars = 20, groove = Groove.BACKBEAT),
                "half time 70" to SynthSpec(bpm = 70.0, bars = 10, groove = Groove.HALF_TIME),
                "half time 160" to SynthSpec(bpm = 160.0, bars = 22, groove = Groove.HALF_TIME),
                "shuffle 100" to SynthSpec(bpm = 100.0, bars = 14, groove = Groove.BACKBEAT, swing = 2.0 / 3.0),
                "waltz 120" to SynthSpec(bpm = 120.0, bars = 22, groove = Groove.WALTZ),
                "no chords 4/4 125" to SynthSpec(bpm = 125.0, bars = 20, chords = false),
            )) {
                rows += Heard.of(Synth.song(spec.copy(seed = seed)), 0.0, 30.0).line("$label s$seed")
            }
        }
        println(rows.joinToString("\n"))
    }
}
