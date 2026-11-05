/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Specs
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved change placed in the held end: on A's own beat, though the end's place in A is known only roughly. */
class PlacingTest {

    private val rate = 44_100
    private val song by lazy { Synth.song(SynthSpec(bpm = 111.0, bars = 40, seed = 71)) }
    private val tailFrom = 40.0
    private val tail by lazy { RegionAnalysis.analyse(song.slice(song.seconds - tailFrom), rate, 2, RegionKind.TAIL) }

    private fun spec(atMs: Double, fix: BeatFix = BeatFix.NONE): TransitionSpec {
        val s = Specs.fadeOnBands()
        return s.copy(a = s.a.copy(atMs = atMs, bpm = 111.0, fix = fix))
    }

    @Test
    fun `A's anchor lands on the beat the held end's analysis finds, a clock 40 ms out notwithstanding`() {
        val tailStartMs = (song.seconds - tailFrom) * 1000
        // A downbeat 20 s into the held end, as the editor saved it; the held end thought to start 40 ms later than it did.
        val beat = song.downbeats.first { it > song.seconds - tailFrom + 20 }
        val placed = Placing.facts(spec(beat * 1000), rate, 2, tailStartMs + 40, tail.frames, tail, 30L * rate, 0).getOrThrow()
        val truth = (beat - (song.seconds - tailFrom)) * rate
        assertEquals(truth, placed.aBar1, 0.003 * rate)
        assertEquals(1_210.0 / 1000 * rate, placed.bBar1, 1e-9)
        // A nudge made in Fix the beat moves the anchor off the found beat by as much.
        val nudged = Placing.facts(spec(beat * 1000 + 15, BeatFix(nudgeMs = 15)), rate, 2, tailStartMs + 40, tail.frames, tail, 30L * rate, 0).getOrThrow()
        assertEquals(placed.aBar1 + 0.015 * rate, nudged.aBar1, 1.0)
    }

    @Test
    fun `a change in bars is not placed on an end with no beat to find`() {
        val pad = RegionAnalysis.analyse(Synth.pad(40.0, rate), rate, 2, RegionKind.TAIL)
        val result = Placing.facts(spec(10_000.0), rate, 2, 0.0, pad.frames, pad, 30L * rate, 0)
        assertTrue(result.isFailure)
        // One timed in seconds is placed by the clock: it has no beat to meet.
        val seconds = spec(10_000.0).copy(bars = 0, seconds = 6.0)
        assertEquals(10.0 * rate, Placing.facts(seconds, rate, 2, 0.0, pad.frames, pad, 30L * rate, 0).getOrThrow().aBar1, 1e-6)
    }
}
