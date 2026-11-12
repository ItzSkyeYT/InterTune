/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The guess of where a song sings, on music made here: one value a step, between 0 and 1, none in
 * silence, and the syllable cue moving with a level that pulses at speech rate and not with a held
 * one. How right it is on real songs is measured on the PC against split voice parts
 * (experiments/features/mix-better/tools/voice_fit.py, voice_check.py).
 */
class VoiceGuessTest {

    private val rate = 44_100

    /** [silentS] seconds of silence, then a chord whose loudness moves at [pulseHz] (0: held), with a little noise. */
    private fun song(silentS: Double, seconds: Double, pulseHz: Double): ShortArray {
        val random = Random(7)
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            if (t < silentS) return@ShortArray 0
            val chord = sin(2 * PI * 220.0 * t) + 0.6 * sin(2 * PI * 330.0 * t) + 0.4 * sin(2 * PI * 1_320.0 * t) + 0.3 * sin(2 * PI * 2_200.0 * t)
            val env = if (pulseHz > 0) 0.55 + 0.45 * sin(2 * PI * pulseHz * t) else 1.0
            val v = 0.12 * chord * env + 0.005 * (random.nextDouble() - 0.5)
            (v * 32767).toInt().toShort()
        }
    }

    private fun end(silentS: Double, seconds: Double, pulseHz: Double): SongEnd =
        SongEnd.of(RegionAnalysis.analyse(song(silentS, seconds, pulseHz), rate, 2, RegionKind.HEAD), 0.0, 180_000.0)

    @Test
    fun `one guess a step, each between 0 and 1`() {
        val e = end(0.0, 20.0, 0.0)
        val v = checkNotNull(e.voice)
        assertEquals(e.levelDb.size, v.size)
        assertTrue(v.all { it in 0f..1f })
    }

    @Test
    fun `nobody sings in silence`() {
        val e = end(6.0, 26.0, 5.0)
        val quiet = checkNotNull(e.singing(1_000.0, 5_000.0))
        assertTrue("silence guessed %.2f".format(quiet), quiet < 0.1)
    }

    @Test
    fun `a level pulsing at syllable rate moves the syllable cue, a held one does not`() {
        fun syllable(pulse: Double): Double {
            val r = RegionAnalysis.analyse(song(0.0, 20.0, pulse), rate, 2, RegionKind.HEAD)
            val spectrum = Spectrum.of(r.guide, rate.toDouble() / r.decimation, r.decimation)
            val f = VoiceGuess.features(spectrum, rate, 300, SongEnd.STEP_MS)
            return (100 until 300).map { f[it][VoiceGuess.FEATURES - 1] }.average()
        }
        val pulsing = syllable(5.0)
        val held = syllable(0.0)
        assertTrue("syllable share %.2f pulsing at 5 Hz, %.2f held".format(pulsing, held), pulsing > held + 0.2)
    }
}
