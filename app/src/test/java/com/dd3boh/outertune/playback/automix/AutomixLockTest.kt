/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The beat mix's check that the incoming song lines up with the opening read ahead: the same
 * stretch lines up to the millisecond wherever it starts, a stretch that came out 20 ms late is
 * caught, and other music does not pass for the song.
 */
class AutomixLockTest {

    private val rate = 44_100

    /** Thirty seconds of a beat at 120 BPM: a kick and a click on every beat, a quieter one between, over a soft tone. */
    private val song: ShortArray by lazy {
        val random = Random(4)
        val frames = 30 * rate
        val out = ShortArray(frames * 2)
        val beat = rate / 2
        for (f in 0 until frames) {
            val inBeat = f % beat
            val inHalf = f % (beat / 2)
            val t = f.toDouble() / rate
            var v = 0.05 * sin(2 * PI * 220 * t)
            v += 0.6 * exp(-inBeat / (0.03 * rate)) * sin(2 * PI * 55 * inBeat / rate)
            v += 0.3 * exp(-inHalf / (0.005 * rate)) * (random.nextDouble() - 0.5)
            val s = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            out[f * 2] = s
            out[f * 2 + 1] = s
        }
        out
    }

    private val head by lazy { RegionAnalysis.analyse(song, rate, 2, RegionKind.HEAD) }

    /** Two seconds of the song from [from], as they would reach the mixer [lateMs] late. */
    private fun live(from: Int, lateMs: Double = 0.0): ShortArray {
        val late = (lateMs * rate / 1000).toInt()
        val frames = 2 * rate
        return ShortArray(frames * 2) { i ->
            val f = from + i / 2 - late
            if (f < 0) 0 else song[f * 2 + i % 2]
        }
    }

    @Test
    fun `the same stretch lines up, wherever it starts`() {
        for (from in listOf(rate * 4, rate * 4 + 137, rate * 10 + 511)) {
            val r = AutomixLock.measure(head, live(from), from.toLong(), 2)!!
            assertEquals("from $from", 0.0, r.offsetMs, 2.0)
            assertTrue("from $from: match ${r.match}", r.match > 0.8)
            assertTrue(AutomixLock.holds(r))
        }
    }

    @Test
    fun `a stretch twenty milliseconds late is caught`() {
        val from = rate * 6
        val r = AutomixLock.measure(head, live(from, lateMs = 20.0), from.toLong(), 2)!!
        assertEquals(20.0, r.offsetMs, 3.0)
        assertFalse(AutomixLock.holds(r))
    }

    @Test
    fun `other music does not pass for the song`() {
        val random = Random(9)
        val noise = ShortArray(2 * rate * 2) { (random.nextInt(-8000, 8000)).toShort() }
        val r = AutomixLock.measure(head, noise, (rate * 6).toLong(), 2)!!
        assertFalse("match ${r.match}", AutomixLock.holds(r))
    }

    @Test
    fun `an opening kept with only its first seconds of music still checks there, and says nothing past them`() {
        val first = head.envelope.firstSound
        val kept = head.keepingGuide(first - rate / 4, first + (4.5 * rate).toLong())
        assertTrue("kept ${kept.guide.size} of ${head.guide.size}", kept.guide.size < head.guide.size / 4)
        for (from in listOf(rate + 300, rate * 2 + 41)) {
            val r = AutomixLock.measure(kept, live(from), from.toLong(), 2)!!
            assertEquals("from $from", 0.0, r.offsetMs, 2.0)
            assertTrue(AutomixLock.holds(r))
        }
        assertEquals(null, AutomixLock.measure(kept, live(rate * 10), (rate * 10).toLong(), 2))
    }

    @Test
    fun `a stretch under a second, or past what was read, is not judged`() {
        val short = ShortArray(rate / 2 * 2)
        assertEquals(null, AutomixLock.measure(head, short, (rate * 4).toLong(), 2))
        assertEquals(null, AutomixLock.measure(head, live(rate * 4), (rate * 29).toLong(), 2))
    }
}
