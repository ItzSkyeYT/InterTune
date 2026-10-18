/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A song in a reel is often sped up or slowed down, pitch and all, and Shazam then names nothing.
 * Put back to its own speed, the same window is named at the right second (his ask of 18 Oct 2026,
 * an Instagram reel; measured on three songs in experiments/recognition/speed).
 */
class SpeedRetryTest {

    private val rate = 16_000

    private fun tone(hz: Double, seconds: Double) =
        ShortArray((rate * seconds).toInt()) { (sin(2 * PI * hz * it / rate) * 12_000).toInt().toShort() }

    /** The frequency of a tone, from its upward zero crossings. */
    private fun frequencyOf(samples: ShortArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        return crossings * rate.toDouble() / samples.size
    }

    @Test
    fun `a tone heard a quarter fast is put back to its own pitch`() {
        val heard = tone(440.0 * 1.25, 12.0)
        val back = SpeedRetry.putBack(heard, 1.25)
        assertEquals(heard.size, back.size)
        assertEquals(440.0, frequencyOf(back), 3.0)
    }

    @Test
    fun `a tone heard slowed is put back too, and comes out shorter`() {
        val heard = tone(440.0 * 0.85, 12.0)
        val back = SpeedRetry.putBack(heard, 0.85)
        assertEquals((heard.size * 0.85).toInt(), back.size)
        assertEquals(440.0, frequencyOf(back), 3.0)
    }

    @Test
    fun `nothing is louder than it was`() {
        val heard = tone(523.0, 2.0)
        for (r in SpeedRetry.RATES) {
            val back = SpeedRetry.putBack(heard, r)
            assertTrue(back.maxOf { abs(it.toInt()) } <= heard.maxOf { abs(it.toInt()) })
        }
        assertEquals(0, SpeedRetry.putBack(ShortArray(0), 1.25).size)
    }

    @Test
    fun `a match that needed stretching to fit is a wrong guess`() {
        fun heard(time: Double, frequency: Double) =
            Recognised("Blame", "Calvin Harris", null, null, "1", 49.15, time, frequency)
        // Measured: put back to its own speed, Blame matched at these skews.
        assertTrue(SpeedRetry.fits(heard(-1.9276142E-4, -8.0525875E-5)))
        // A guess 6% off named "Sound Ke La La Voll. 5", a different song.
        assertTrue(!SpeedRetry.fits(heard(0.023740292, 0.03296554)))
        // And 8% off, a cover of Hello.
        assertTrue(!SpeedRetry.fits(heard(-0.046997666, -0.044192374)))
    }

    @Test
    fun `each window of a listen tries two other speeds, the reel's 0_8 among the first`() {
        assertEquals(listOf(1.25, 0.8), SpeedRetry.ratesFor(0))
        assertEquals(listOf(1.35, 0.85), SpeedRetry.ratesFor(1))
        assertEquals(listOf(1.15, 0.75), SpeedRetry.ratesFor(2))
        assertEquals(SpeedRetry.RATES.toSet().size, SpeedRetry.RATES.size)
    }

    @Test
    fun `a near miss is asked once more one step closer, a far one or a fit is not`() {
        fun heard(skew: Double) = Recognised("I'm Not Alone (Radio Edit)", "Calvin Harris", null, null, "1", 112.7, skew, skew)
        // Measured on the reel: from 0.85 the radio edit at -0.037, closer means lower.
        val closer = SpeedRetry.closer(0.85, heard(-0.036725104))!!
        assertEquals(0.85 * (1 - 0.036725104), closer, 1e-9)
        assertTrue(closer < 0.85 && closer > 0.8)
        assertEquals(null, SpeedRetry.closer(0.8, heard(-6.920099E-5)))
        assertEquals(null, SpeedRetry.closer(1.25, heard(0.2)))
    }

    @Test
    fun `only Listen once asks again, only after nothing was known, and the rate rides with the match`() {
        val engine = File("src/main/java/com/dd3boh/outertune/recognition/RecognitionEngine.kt").readText()
        val identify = engine.substringAfter("private suspend fun identify(").substringBefore("\n    }\n")
        assertTrue(identify.contains("if (keepGoing || heard != RecognitionOutcome.NoMatch) heard"))
        assertTrue(identify.contains("hearAgainAtSpeed(window.samples)"))
        val again = engine.substringAfter("private suspend fun hearAgainAtSpeed(").substringBefore("\n    }\n")
        assertTrue(again.contains("SpeedRetry.ratesFor(onceMisses)"))
        assertTrue(again.contains("SpeedRetry.closer(rate, match)"))
        assertTrue(again.contains("heardAtRate = rate"))
        assertTrue("a guess that only fits stretched is not taken", again.contains("if (SpeedRetry.fits(match)) return"))
        // The position on show moves at the speed the room plays it.
        assertTrue(engine.contains("} else outcome.track.heardAtRate"))
    }
}
