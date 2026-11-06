/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The reels' way's features on made-up music, so the port is held without the PC trial's songs
 * (ReelsAnalysisTrial compares it with the prototype on 24 real ones).
 */
class ReelsAnalysisTest {

    @Test
    fun fftIsTheDft() {
        val n = 64
        val rnd = Random(3)
        val x = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val out = DoubleArray(n / 2 + 1)
        RealFft(n).power(x, out)
        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += x[i] * cos(2 * PI * k * i / n)
                im -= x[i] * sin(2 * PI * k * i / n)
            }
            assertEquals(re * re + im * im, out[k], 1e-9)
        }
    }

    @Test
    fun lowRateCopyKeepsTheBandAndDropsWhatWouldFoldBack() {
        fun rmsDb(hz: Double): Double {
            val mono = FloatArray(44_100) { (0.5 * sin(2 * PI * hz * it / 44_100)).toFloat() }
            val y = ReelsAnalysis.lowRateCopy(mono)
            val mid = y.copyOfRange(1000, y.size - 1000)
            return 20 * log10(sqrt(mid.sumOf { it.toDouble() * it } / mid.size) / (0.5 / sqrt(2.0)))
        }
        assertEquals(0.0, rmsDb(1000.0), 0.05)
        assertEquals(0.0, rmsDb(4000.0), 0.05)
        assertTrue(rmsDb(8000.0) < -60)
    }

    /**
     * 48 bars at 120 BPM: a pad and a hi-hat on every beat, and from bar 16 a kick on every beat.
     * The bars fall on the grid, the bass comes in at bar 16 and the reels' way hears it as a drop,
     * and the grid holds everywhere with the same lag.
     */
    @Test
    fun aMadeUpSong() {
        val rate = ReelsAnalysis.RATE
        val bpm = 120.0
        val t0 = 0.25
        val seconds = 96.0
        val frames = (seconds * rate).toInt()
        val mono = DoubleArray(frames) { 0.05 * sin(2 * PI * 440.0 * it / rate) }
        val rnd = Random(7)
        var k = 0
        while (true) {
            val tb = t0 + k * 60.0 / bpm
            val at = (tb * rate).toInt()
            if (at >= frames) break
            for (i in 0 until (0.03 * rate).toInt()) if (at + i < frames) mono[at + i] += 0.2 * rnd.nextDouble(-1.0, 1.0) * exp(-i / (0.008 * rate))
            if (k >= 64) for (i in 0 until (0.2 * rate).toInt()) if (at + i < frames) mono[at + i] += 0.8 * sin(2 * PI * 55.0 * i / rate) * exp(-i / (0.08 * rate))
            k++
        }
        val stereo = FloatArray(2 * frames) { mono[it / 2].toFloat() }
        val grid = ReelsGrid(bpm, t0, 0)
        val res = ReelsAnalysis.analyse(stereo, grid)
        val nBeats = ((seconds - t0) / 0.5).toInt() + 1
        assertEquals((nBeats + 3) / 4, res.bars.size)
        for ((j, b) in res.bars.withIndex()) assertEquals(t0 + 4 * j * 0.5, b.t, 1e-12)
        assertTrue("the bass comes in at bar 16: ${res.bars[15].low150} then ${res.bars[16].low150}", res.bars[16].low150 - res.bars[15].low150 >= 20)
        assertTrue(res.bars.all { it.voice == BarVoice.NONE } && !res.hasVoice)
        assertTrue("a section line at the drop: ${res.lines}", 16 in res.lines)

        val pulse = ReelsAnalysis.pulse(stereo, res.bars.map { it.t }, bpm)
        for (j in 2 until res.bars.size - 2) {
            val row = pulse.rows[j]
            assertTrue("bar $j pulse ${row.pulse}", row.pulse >= 1.0)
            assertTrue("bar $j lag ${row.lagRelMs}", abs(row.lagRelMs!!) <= 4.0)
        }
        val song = ReelsAnalysis.song("made-up", grid, res, pulse.check(1.0), null)
        val drops = ReelsWay.drops(ReelsWay.Profile(song))
        assertEquals(listOf(16), drops.map { it.bar })
        assertTrue(ReelsWay.gridCheck(song.pulse, (16 until 20).toList()).ok)
    }
}
