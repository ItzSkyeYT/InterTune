/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.better

import com.dd3boh.outertune.mix.BeatGrid
import com.dd3boh.outertune.mix.Gates
import com.dd3boh.outertune.mix.Groove
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.Tempo
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionJson
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.SpecPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * The beat when the analysis is not sure of it, and the listener's beat when they set it: a tempo
 * snapped to the whole BPM a loose line cannot tell from its own, a confidence that is exactly the
 * gates at its middle, the octave a tempo is shown in, a tempo and a bar 1 set by hand laid on a
 * song with no beat found, and a change whose next song comes in later in its music starting where
 * that song's first frame plays.
 */
class BeatByHandTest {

    private val frameRate = 44_100.0 / 4 / 128

    private fun line(bpm: Double, beats: Int, noiseFrames: Double, seed: Long): Pair<List<Int>, List<Double>> {
        val period = 60.0 * frameRate / bpm
        val random = Random(seed)
        val ks = (0 until beats).toList()
        val ts = ks.map { 100.0 + it * period + noiseFrames * random.nextGaussian() }
        return ks to ts
    }

    @Test
    fun aLooseLineIsTakenToTheWholeBpmItCannotTellFromItsOwn() {
        // Ten beats a frame or two off a line at 97.7: the line cannot tell 97.7 from 98.
        val (ks, ts) = line(97.7, 10, 1.5, 1)
        val period = BeatGrid.leastSquares(ks, ts)!!.second
        val whole = BeatGrid.wholeBpm(ks, ts, period, frameRate)
        assertNotNull(whole)
        assertEquals(98.0, 60.0 * frameRate / whole!!, 1e-9)
        // A hundred beats exactly on a line at 97.5 can: it keeps its own.
        val (ks2, ts2) = line(97.5, 100, 0.0, 2)
        val period2 = BeatGrid.leastSquares(ks2, ts2)!!.second
        assertNull(BeatGrid.wholeBpm(ks2, ts2, period2, frameRate))
    }

    @Test
    fun theBeatConfidenceIsTheGatesAtItsMiddle() {
        val sure = Gates(6.0, 0.5, 0.01, 0.005, 0.95, 6.0, 40, 3.0)
        assertTrue(sure.beats)
        assertTrue(sure.beatConfidence >= Gates.SURE)
        assertTrue(sure.barConfidence >= Gates.SURE)
        // Each gate failed on its own takes the confidence under its middle.
        for (g in listOf(
            Gates(3.0, 0.5, 0.01, 0.005, 0.95, 6.0, 40, 3.0),
            Gates(6.0, 0.9, 0.01, 0.005, 0.95, 6.0, 40, 3.0),
            Gates(6.0, 0.5, 0.03, 0.005, 0.95, 6.0, 40, 3.0),
            Gates(6.0, 0.5, 0.01, 0.02, 0.95, 6.0, 40, 3.0),
            Gates(6.0, 0.5, 0.01, 0.005, 0.7, 6.0, 40, 3.0),
            Gates(6.0, 0.5, 0.01, 0.005, 0.95, 20.0, 40, 3.0),
            Gates(6.0, 0.5, 0.01, 0.005, 0.95, 6.0, 10, 3.0),
        )) {
            assertFalse(g.beats)
            assertTrue("${g.beatConfidence}", g.beatConfidence < Gates.SURE)
        }
        // Beats sure, the one not: the bars are a guess.
        val guessed = Gates(6.0, 0.5, 0.01, 0.005, 0.95, 6.0, 40, 0.8)
        assertTrue(guessed.beats && !guessed.downbeats)
        assertTrue(guessed.barConfidence < Gates.SURE)
        // From an opening's first sound on a beat, and by hand.
        val first = Gates(6.0, 0.5, 0.01, 0.005, 0.95, 6.0, 40, 1.5, barsFromFirstSound = true)
        assertEquals(minOf(first.beatConfidence, Gates.FIRST_SOUND_BARS), first.barConfidence, 1e-9)
        assertTrue(first.barConfidence >= Gates.SURE)
        assertEquals(1.0, Gates(0.0, 1.0, 1.0, 1.0, 0.0, 99.0, 0, 0.0, byHand = true).beatConfidence, 0.0)
    }

    @Test
    fun aTempoIsShownInTheOctaveAListenerSaysIt() {
        assertEquals(70.0, Tempo.felt(140.0), 1e-9)
        assertEquals(98.0, Tempo.felt(98.0), 1e-9)
        assertEquals(128.0, Tempo.felt(128.0), 1e-9)
        assertEquals(81.0, Tempo.felt(162.0), 1e-9)
        assertEquals(95.0, Tempo.felt(190.0), 1e-9)
    }

    @Test
    fun aTempoAndABarOneSetByHandAreLaidWhereNoBeatWasFound() {
        // A pad: nothing steady to find, so nothing is trusted.
        val pad = RegionAnalysis.analyse(Synth.pad(40.0), 44_100, 2, RegionKind.TAIL)
        assertFalse(pad.beatsConfident)
        val end = SongEnd.of(pad, 100_000.0, 140_000.0)
        val fixed = end.with(BeatFix(bpm = 120.0, oneMs = 120_000.0))
        val line = fixed.line!!
        assertTrue(line.beatsTrusted && line.barsTrusted)
        assertEquals(120.0, line.bpm, 0.5)
        // The one is where the listener put it.
        assertTrue(line.isDownbeat(line.kNear(120_000.0)))
        assertTrue(abs(line.msOf(line.kNear(120_000.0)) - 120_000.0) < line.beatMs / 2)
        // The editor's grid and the phone's both come from the same region laid again at that tempo.
        val again = pad.atTempo(120.0)!!
        assertTrue(again.beatsConfident)
        assertEquals(1.0, again.gates.beatConfidence, 0.0)
    }

    @Test
    fun aBarOneSetByHandWinsOverTheFindersOnASongWithABeat() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 24, groove = Groove.FOUR_ON_THE_FLOOR, seed = 5))
        val tail = RegionAnalysis.analyse(song.slice(song.seconds - 40.0), 44_100, 2, RegionKind.TAIL)
        val from = (song.seconds - 40.0) * 1000.0
        val end = SongEnd.of(tail, from, song.seconds * 1000.0)
        val line = end.line!!
        // One beat after one of the finder's downbeats.
        val k = line.downbeatAtOrAfter(line.kNear(from + 10_000.0)) + 1
        val moved = end.with(BeatFix(oneMs = line.msOf(k)))
        assertTrue(moved.line!!.isDownbeat(k))
        assertTrue(moved.line!!.barsTrusted)
        // The fix survives being written and read again.
        val fix = BeatFix(bpm = 124.0, downbeatShift = 1, nudgeMs = 5, source = "s", oneMs = 12_345.5)
        assertEquals(fix, TransitionJson.fix(TransitionJson.fixTree(fix)))
        assertNull(TransitionJson.fix(TransitionJson.fixTree(fix.copy(oneMs = null)))!!.oneMs)
    }

    @Test
    fun aNextSongComingInLaterInItsMusicStartsWhereItsFirstFramePlays() {
        val spec = TransitionSpec(
            a = Anchor("a", 200_000.0, 120.0), b = Anchor("b", 10_000.0, 120.0),
            lanes = LaneSet.BANDS, bars = 8, preset = Preset.FADE,
            moves = Presets.moves(Preset.FADE, LaneSet.BANDS, 8.0, 0.25)!!,
        )
        // B's first frame plays 10 s before bar 1: the end must be held from before that.
        val first = SpecPlan.firstChangeMs(spec)
        assertTrue("$first", first <= 200_000.0 - 10_000.0)
        // B at its first downbeat starts, as before, with its first move.
        val atStart = SpecPlan.firstChangeMs(spec.copy(b = spec.b.copy(atMs = 0.0)))
        // Its tempo ramp of four beats, 2 s at 120 BPM, came first there.
        assertEquals(8_000.0, atStart - first, 1.0)
    }
}
