/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The canvas's colours tell the truth: a bass note is blue, a voice amber, a hi-hat white. */
class BandEnvelopesTest {

    private val rate = 48_000

    private fun tone(hz: Double, seconds: Double, amplitude: Double = 0.5): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n * 2) { i -> (sin(2 * PI * hz * (i / 2) / rate) * amplitude * 32767).roundToInt().toShort() }
    }

    private fun measure(samples: ShortArray): BandEnvelopes = BandMeter(rate, 2).also { it.push(samples, samples.size / 2) }.result()

    @Test
    fun `a 50 Hz tone is lows, a 1 kHz tone mids, a 12 kHz tone highs`() {
        for ((hz, lane) in listOf(50.0 to Lane.LOWS, 1_000.0 to Lane.MIDS, 12_000.0 to Lane.HIGHS)) {
            val e = measure(tone(hz, 1.0))
            val levels = Lane.entries.filter { it in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS) }.associateWith { e.peak(it, 500.0, 900.0) }
            assertEquals("$hz Hz: $levels", lane, levels.maxBy { it.value }.key)
            assertTrue("$hz Hz: $levels", levels.getValue(lane) > 0.4f)
        }
    }

    @Test
    fun `a hop is 10 ms, and the window's loud level is its reference`() {
        val e = measure(tone(1_000.0, 2.0, 0.25))
        assertEquals(10.0, e.hopMs, 1e-9)
        assertEquals(200, e.size)
        assertEquals(0.25f, e.reference, 0.03f)
    }

    @Test
    fun `a band is active where it plays and not in silence`() {
        val music = tone(50.0, 1.0)
        val silence = ShortArray(rate * 2)
        val e = measure(music + silence)
        assertTrue(e.active(Lane.LOWS, 500.0))
        assertFalse(e.active(Lane.LOWS, 1_500.0))
    }

    @Test
    fun `envelopes read back as they were written`() {
        val e = measure(tone(440.0, 0.5))
        val file = File.createTempFile("bands", ".bin").apply { deleteOnExit() }
        e.write(file)
        val back = BandEnvelopes.read(file)
        assertNotNull(back)
        assertEquals(e.mids.toList(), back!!.mids.toList())
    }

    @Test
    fun `a window's description reads back as it was written`() {
        val info = WindowInfo(48_000, 2, 236_123.5, 3_456_000, 308_000.0)
        assertEquals(info, WindowInfo.of(info.toText()))
        assertEquals(null, WindowInfo.of("v=2\nrate=1"))
    }
}
