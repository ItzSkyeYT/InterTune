/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The song read whole (WholeSongGrid), as Mixxx reads one with "Assume constant tempo", and the windows
 * laid on it (RegionAnalysis.onSong).
 */
class WholeSongGridTest {

    private fun grid(song: SynthSong): WholeSongGrid = checkNotNull(WholeSongGrid.analyse(FrontEnd.of(song.samples, song.sampleRate, 2))) { "no grid" }

    /** How far [ms] is from the nearest of [beats] (seconds), in ms. */
    private fun off(ms: Double, beats: DoubleArray): Double = beats.minOf { abs(it * 1000 - ms) }

    @Test
    fun aWholeTempoIsFoundWholeAndOnTheBeats() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 48, introBars = 4, seed = 3))
        val g = grid(song)
        println(g.describe())
        assertEquals(124.0, g.bpm, 1e-9)
        assertTrue(g.rounded)
        assertTrue(g.beatsSure)
        // Every drum beat of the song on the line, to the few ms the onset curve allows.
        val worst = song.beats.maxOf { b -> abs(g.msOf(g.kNear(b * 1000)) - b * 1000) }
        assertTrue("worst beat $worst ms off", worst < 12.0)
        assertTrue("bar 1 on a downbeat", song.downbeats.all { g.isDownbeat(g.kNear(it * 1000)) })
    }

    @Test
    fun aTempoThatIsNotWholeIsKept() {
        val song = Synth.song(SynthSpec(bpm = 123.4, bars = 48, seed = 5))
        val g = grid(song)
        println(g.describe())
        assertFalse(g.rounded)
        assertEquals(123.4, g.bpm, 0.02)
    }

    @Test
    fun roundingFollowsMixxx() {
        // A whole BPM inside the range the line allows.
        assertEquals(120.0, WholeSongGrid.roundWithin(119.97, 120.01, 120.05), 0.0)
        // None inside: a half under 85 BPM, a step of 1.5 over 127, if the range is over half a BPM wide.
        assertEquals(80.5, WholeSongGrid.roundWithin(80.3, 80.5, 80.9), 0.0)
        assertEquals(130.5, WholeSongGrid.roundWithin(130.2, 130.5, 130.8), 0.0)
        // A narrow range keeps the line's own.
        assertEquals(100.37, WholeSongGrid.roundWithin(100.36, 100.37, 100.38), 0.0)
    }

    @Test
    fun aStraightRunHoldsOneBeatOffTheLineButNotATempoChange() {
        val p = 50.0
        val steady = DoubleArray(40) { it * p }.also { it[20] += 5.0 }
        val runs = WholeSongGrid.constantRuns(steady, 2.5, 10.0)
        assertEquals("one run, one outlier allowed", 1, runs.size)
        assertEquals(0..39, runs[0])
        // 20 beats at one period, then 20 at a period 4% longer.
        val step = DoubleArray(40) { if (it < 20) it * p else 19 * p + (it - 19) * p * 1.04 }
        val split = WholeSongGrid.constantRuns(step, 2.5, 10.0)
        assertTrue("split at the change: $split", split.size >= 2 && split[0].last in 18..21)
    }

    @Test
    fun aSoftIntroGetsTheSongsLineLaidInIt() {
        // Eight bars of chords alone before the drums: the opening the phone reads has little beat
        // of its own in its first 15 s, and the song's line goes there as Mixxx carries it back.
        val song = Synth.song(SynthSpec(bpm = 120.0, bars = 40, introBars = 8, seed = 7))
        val g = grid(song)
        val head = RegionAnalysis.analyse(song.slice(0.0, 30.0), song.sampleRate, 2, RegionKind.HEAD)
        val laid = head.onSong(g, 0.0, exact = true)
        val lg = checkNotNull(laid.grid)
        assertTrue(laid.gates.fromSong)
        assertTrue(laid.beatsConfident)
        assertEquals(120.0, lg.bpm, 1e-9)
        // The intro's beats too, where the window alone found nothing to lay them on.
        for (b in song.allBeats.filter { it < song.drumsStart }) {
            val k = laid.beatNear(b * song.sampleRate)
            assertTrue("intro beat at $b s", abs(laid.beatFrame(k) / song.sampleRate - b) < 0.012)
        }
    }

    @Test
    fun aHeldEndKeepsTheSongsTempoAndFindsItsOwnPhase() {
        val song = Synth.song(SynthSpec(bpm = 126.0, bars = 48, seed = 11))
        val g = grid(song)
        val fromS = song.seconds - 40.0
        val tail = RegionAnalysis.analyse(song.slice(fromS), song.sampleRate, 2, RegionKind.TAIL)
        // Its place in the song known only to some tens of ms, as the phone knows a held end's.
        val laid = tail.onSong(g, fromS * 1000 + 40.0, exact = false)
        val lg = laid.grid!!
        assertTrue(laid.gates.fromSong)
        assertEquals(126.0, lg.bpm, 1e-9)
        val worst = song.beats.filter { it > fromS + 1 && it < song.trueEnd - 1 }.maxOf { b ->
            val k = laid.beatNear((b - fromS) * song.sampleRate)
            abs(laid.beatFrame(k) / song.sampleRate + fromS - b) * 1000
        }
        assertTrue("worst beat $worst ms off", worst < 12.0)
        // And the song's bars, counted in from where the end sits.
        val first = laid.firstDownbeat!!
        val at = laid.beatFrame(first) / song.sampleRate + fromS
        assertTrue("bar 1 at $at s", off(at * 1000, song.downbeats) < 12.0)
    }

    @Test
    fun aWindowAtItsOwnSureTempoKeepsIt() {
        // An opening whose sure beats run at another tempo than the song's (an intro at its own
        // tempo): the window is left as it is.
        val intro = Synth.song(SynthSpec(bpm = 100.0, bars = 12, seed = 13))
        val body = Synth.song(SynthSpec(bpm = 128.0, bars = 48, seed = 14))
        val g = grid(body)
        val head = RegionAnalysis.analyse(intro.slice(0.0, 30.0), intro.sampleRate, 2, RegionKind.HEAD)
        assertTrue(head.beatsConfident)
        val laid = head.onSong(g, 0.0, exact = true)
        assertFalse(laid.gates.fromSong)
        assertEquals(head.bpm!!, laid.bpm!!, 1e-9)
    }

    @Test
    fun aGridIsKeptAndReadBack() {
        val g = grid(Synth.song(SynthSpec(bpm = 128.0, bars = 32, seed = 17)))
        val back = checkNotNull(WholeSongGrid.decode(WholeSongGrid.encode(g)))
        assertEquals(g.bpm, back.bpm, 0.0)
        assertEquals(g.t0Ms, back.t0Ms, 0.0)
        assertEquals(g.barPhase, back.barPhase)
        assertEquals(g.beatsSure, back.beatsSure)
        // One of another version is found again, unless the listener locked it.
        val other = WholeSongGrid.encode(g).replace("\"v\":${WholeSongGrid.VERSION}", "\"v\":0")
        assertNull(WholeSongGrid.decode(other))
        val locked = WholeSongGrid.encode(g.lockedAt(127.5, 12.0)).replace("\"v\":${WholeSongGrid.VERSION}", "\"v\":0")
        val kept = checkNotNull(WholeSongGrid.decode(locked))
        assertTrue(kept.locked)
        assertEquals(127.5, kept.bpm, 0.0)
    }
}
