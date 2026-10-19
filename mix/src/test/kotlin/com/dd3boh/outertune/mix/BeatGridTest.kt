/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The beat line: on the beats, to the precision a mix needs, and honest about where it holds.
 */
class BeatGridTest {

    @Test
    fun fourOnTheFloorLandsOnEveryBeat() {
        for ((bpm, seed) in listOf(128.0 to 1L, 124.0 to 2L)) {
            val h = Heard.of(Synth.song(SynthSpec(bpm = bpm, bars = 20, seed = seed)), 0.0, 30.0)
            println(h.line("4/4 $bpm"))
            assertTrue("F ${h.beatF()} at $bpm", h.beatF() > 0.95)
            assertTrue("jitter ${h.rhythm.gates.jitterMs} ms", h.rhythm.gates.jitterMs < 5.0)
        }
    }

    @Test
    fun theLineIsGoodToAFewHundredthsOfAPercent() {
        // Mixing needs the ratio of two tempos to 0.05%: over a 15 s overlap that is 8 ms.
        for ((bpm, groove) in listOf(128.0 to Groove.FOUR_ON_THE_FLOOR, 100.0 to Groove.BACKBEAT, 140.0 to Groove.HALF_TIME, 87.5 to Groove.BACKBEAT)) {
            val h = Heard.of(Synth.song(SynthSpec(bpm = bpm, bars = 24, groove = groove, seed = bpm.toLong())), 0.0, 30.0)
            val grid = checkNotNull(h.rhythm.grid) { "no grid" }
            val error = abs(Tempo.fold(grid.bpm) / Tempo.fold(bpm) - 1)
            println("%-20s grid %.4f BPM, off by %.4f%%".format("$groove $bpm", grid.bpm, error * 100))
            assertTrue("$groove $bpm: off by ${error * 100}%", error < 0.0002)
        }
    }

    @Test
    fun theLineIsWhereTheBeatsAreHeardNotJustParallelToThem() {
        val song = Synth.song(SynthSpec(bpm = 126.0, bars = 20, seed = 9))
        val h = Heard.of(song, 0.0, 30.0)
        val offsets = h.beats().map { t -> song.beats.minOf { abs(it - t) } }
        println("mean distance to the true beat %.2f ms".format(offsets.average() * 1000))
        assertTrue(offsets.average() < 0.004)
    }

    @Test
    fun aDrumBreakDoesNotBendTheLine() {
        // Two songs glued at a bar line with two bars of chords alone between them, as a breakdown
        // would be: the line must carry straight through the break.
        val spec = SynthSpec(bpm = 122.0, bars = 8, seed = 4)
        val a = Synth.song(spec)
        val gap = Synth.song(spec.copy(bars = 0, introBars = 2))
        val b = Synth.song(spec.copy(seed = 5))
        // Each part is cut at its own last bar line, so the beats run on without a seam.
        val samples = a.slice(0.0, a.trueEnd) + gap.slice(0.0, gap.trueEnd) + b.samples
        val rhythm = Heard.rhythmOf(samples)
        val grid = rhythm.grid!!
        val beat = 60.0 / 122.0
        println("break: %.4f BPM, %d beats, jitter %.1f ms, hits %.2f".format(grid.bpm, grid.activeBeats, grid.jitterMs, grid.hitShare))
        assertEquals(122.0, grid.bpm, 0.05)
        assertTrue("the line runs through the break", grid.activeBeats * beat > 30.0)
    }

    @Test
    fun aRampShowsAsDriftAndASteadyTempoDoesNot() {
        val ramp = Heard.of(Synth.song(SynthSpec(bpm = 120.0, bars = 15, rampPercent = 2.0, seed = 8)), 0.0, 30.0)
        val steady = Heard.of(Synth.song(SynthSpec(bpm = 120.0, bars = 15, seed = 8)), 0.0, 30.0)
        println("drift: ramp %.3f%%, steady %.3f%%".format(ramp.rhythm.grid!!.drift * 100, steady.rhythm.grid!!.drift * 100))
        assertTrue(ramp.rhythm.grid!!.drift > Gates.MAX_DRIFT)
        assertTrue(steady.rhythm.grid!!.drift < 0.001)
    }

    @Test
    fun aDrumlessIntroIsLeftOffTheTrustedStretch() {
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 12, introBars = 4, seed = 6))
        val h = Heard.of(song, 0.0, 30.0)
        val first = h.beats().first()
        println("drums at %.2f s, trusted stretch from %.2f s".format(song.drumsStart, first))
        assertTrue("the stretch starts within a beat of the drums", first > song.drumsStart - 60.0 / 128 - 0.05)
    }

    @Test
    fun leastSquaresRecoversALine() {
        val ks = (0 until 20).toList()
        val ts = ks.map { 3.5 + 41.2 * it }
        val (a, b) = BeatGrid.leastSquares(ks, ts)!!
        assertEquals(3.5, a, 1e-9)
        assertEquals(41.2, b, 1e-9)
        assertEquals(0.0, BeatGrid.drift(ks, ts), 1e-9)
    }

    @Test
    fun theBestStretchIsTheLongestRunMostlyHit() {
        val hits = BooleanArray(40) { it in 5..34 && it != 12 && it != 20 }
        assertEquals(5 to 34, BeatGrid.bestStretch(hits))
        assertEquals(null, BeatGrid.bestStretch(BooleanArray(10)))
    }
}
