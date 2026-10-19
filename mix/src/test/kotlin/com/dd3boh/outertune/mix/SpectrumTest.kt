/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/**
 * The front end: rates, levels and peaks measured where the rest of the analysis expects them, the
 * decimator doing its job, and onsets landing on the sounds that make them.
 */
class SpectrumTest {

    @Test
    fun theDecimatedRateIsAboutElevenKilohertzForBothStreamRates() {
        assertEquals(4, Pcm.decimationFor(44_100))
        assertEquals(4, Pcm.decimationFor(48_000))
        assertEquals(2, Pcm.decimationFor(22_050))
        val front = FrontEnd.of(Synth.sine(440.0, 2.0, 48_000), 48_000, 2)
        assertEquals(12_000.0, front.decimatedRate, 0.0)
        assertEquals(24_000, front.mono.size)
        assertEquals(512, front.hopFrames)
    }

    @Test
    fun levelsAreMeasuredAtTheFullRateOnEveryChannel() {
        // A sine at half scale has a mean square of 1/8, -9.03 dB, and peaks at 0.5.
        val front = FrontEnd.of(Synth.sine(1000.0, 1.0, 44_100, amplitude = 0.5), 44_100, 2)
        val middle = front.hopPower.size / 2
        assertEquals(-9.03, Pcm.powerDb(front.hopPower[middle].toDouble()), 0.05)
        assertEquals(0.5f, front.hopPeak[middle], 0.001f)
    }

    @Test
    fun theDecimatorPassesTheBandAndCutsWhatWouldFoldBack() {
        fun levelAfter(hz: Double): Double {
            val front = FrontEnd.of(Synth.sine(hz, 1.0, 44_100, amplitude = 0.5), 44_100, 2)
            val mono = front.mono
            var squares = 0.0
            for (i in mono.size / 4 until mono.size * 3 / 4) squares += mono[i].toDouble() * mono[i]
            return 10 * log10(squares / (mono.size / 2))
        }
        assertEquals("1 kHz passes", -9.03, levelAfter(1000.0), 0.1)
        assertTrue("9 kHz is cut by at least 20 dB", levelAfter(9000.0) < -29.0)
    }

    @Test
    fun blockSizesDoNotChangeAnything() {
        val song = Synth.song(SynthSpec(bars = 4))
        val whole = FrontEnd.of(song.samples, 44_100, 2)
        val random = Random(3)
        val front = FrontEnd(44_100, 2)
        var at = 0
        while (at < song.frames) {
            val n = minOf(song.frames - at, 1 + random.nextInt(3000))
            front.push(song.samples, at, n)
            at += n
        }
        val pieces = front.result()
        assertArrayEquals(whole.mono, pieces.mono, 0f)
        assertArrayEquals(whole.hopPower, pieces.hopPower, 0f)
        assertArrayEquals(whole.hopPeak, pieces.hopPeak, 0f)
    }

    @Test
    fun aToneLandsInTheBandAroundIt() {
        val spectrum = Spectrum.of(FrontEnd.of(Synth.sine(1000.0, 1.0), 44_100, 2))
        val frame = spectrum.frames / 2
        val loudest = (0 until Spectrum.BANDS).maxBy { spectrum.mel(frame, it) }
        val nearest = spectrum.bandCentres.indices.minBy { abs(spectrum.bandCentres[it] - 1000.0) }
        assertTrue("band $loudest for 1 kHz, nearest centre is band $nearest", abs(loudest - nearest) <= 1)
    }

    @Test
    fun silenceHasNoOnsetsAndNothingUndefined() {
        val spectrum = Spectrum.of(FrontEnd.of(ShortArray(44_100 * 2 * 3), 44_100, 2))
        assertTrue(spectrum.onset.all { it == 0f })
        assertTrue(spectrum.logMel.all { it.isFinite() })
    }

    @Test
    fun onsetsAreHeardWhereTheDrumsAre() {
        // Kicks, claps and hats at 128 BPM: every kick should be an onset peak, placed within a few
        // milliseconds of where it was laid down.
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 16, chords = false))
        val spectrum = Spectrum.of(FrontEnd.of(song.samples, 44_100, 2))
        val offsets = ArrayList<Double>()
        // The very first kick starts the first frame and so has no frame before it to rise from.
        val heard = song.beats.filter { it > 0.1 }
        for (beat in heard) {
            val frame = spectrum.sampleToFrame(beat * 44_100)
            val peak = Peaks.nearest(spectrum.onset, frame, 3) ?: continue
            offsets += (spectrum.frameToSample(peak.frame) / 44_100 - beat) * 1000
        }
        val mean = offsets.average()
        val spread = offsets.maxOf { abs(it - mean) }
        println("onset offset to the kicks: mean %.2f ms, spread %.2f ms over %d".format(mean, spread, offsets.size))
        assertEquals("found every kick", heard.size, offsets.size)
        assertEquals("kicks are placed where they were laid down", 0.0, mean, 3.0)
        assertTrue("and all within one frame of that", spread < 12.0)
    }

    @Test
    fun theGeneratorDoesNotClip() {
        val song = Synth.song(SynthSpec(bars = 16))
        val peak = song.samples.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak < 32_000)
    }
}
