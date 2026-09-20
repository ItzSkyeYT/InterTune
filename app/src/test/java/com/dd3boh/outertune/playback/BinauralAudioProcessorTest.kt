/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the binaural renderer does to the samples, what it refuses, and above all that it does not
 * cheat by being louder.
 */
class BinauralAudioProcessorTest {

    private fun stereoFloat(rate: Int = 48000) =
        AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)

    /** Feeds one frame then silence, and returns [frames] frames of both ears. */
    private fun impulse(p: BinauralAudioProcessor, l: Float, r: Float, frames: Int): Pair<FloatArray, FloatArray> {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (n in 0 until frames) {
            val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            input.putFloat(if (n == 0) l else 0f).putFloat(if (n == 0) r else 0f)
            input.flip()
            p.queueInput(input)
            // getOutput swaps in an empty buffer, so it has to be drained every frame.
            val out = p.output
            left[n] = out.float
            right[n] = out.float
        }
        return left to right
    }

    private fun energy(a: FloatArray) = a.fold(0.0) { acc, v -> acc + v.toDouble() * v }

    @Test
    fun `disabled, it is not in the chain at all`() {
        val p = BinauralAudioProcessor().apply { enabled = false }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereoFloat()))
        assertFalse(p.isActive)
    }

    @Test
    fun `enabled, stereo stays stereo at the same rate`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        val out = p.configure(stereoFloat())
        assertEquals(2, out.channelCount)
        assertEquals(48000, out.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
        assertTrue(p.isActive)
    }

    @Test
    fun `forty four one is rendered too, not skipped`() {
        // The filters are measured at 48 kHz, which is what Opus decodes to, but YouTube's AAC is
        // 44.1 kHz. Refusing it would mean a setting that works on some songs and silently does
        // nothing on others, which is the worst of the three options.
        val p = BinauralAudioProcessor().apply { enabled = true }
        val out = p.configure(stereoFloat(44100))
        assertEquals(44100, out.sampleRate)
        assertEquals(2, out.channelCount)
        assertTrue(p.isActive)
    }

    @Test
    fun `and it comes out at the right level there as well`() {
        // The filters are resampled, so their energy changes; the gain is derived after that
        // rather than before, or every AAC stream would play at the wrong level.
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat(44100))
        p.flush()

        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 1e-3)
        assertEquals("right ear", 1.0, energy(right), 1e-3)
    }

    @Test
    fun `and it still puts something in the far ear there`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat(44100))
        p.flush()

        val (left, right) = impulse(p, 1f, 0f, SadieHrir.TAPS)
        assertTrue("far ear", sqrt(energy(right)) > 0.05)
        assertTrue("quieter than the near one", energy(right) < energy(left))
    }

    @Test
    fun `a rate nothing streams passes through rather than costing battery`() {
        // At 192 kHz the filter would be a thousand taps, three of them, per sample. Passing the
        // audio through untouched is the right way to fail for a rate this app never serves.
        val p = BinauralAudioProcessor().apply { enabled = true }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereoFloat(192000)))
    }

    @Test
    fun `mono and multichannel are refused`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT)),
        )
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            p.configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)),
        )
    }

    @Test
    fun `a centred signal comes out at the level it went in`() {
        // The whole point of deriving the gain instead of guessing it. A rendering six decibels
        // louder wins every A-B it is given, whatever it does to the imaging, which is exactly how
        // the upmix fooled us. Convolving with a unit-energy response leaves the level alone, so
        // the impulse response of the centre path must carry an energy of one.
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 1e-3)
        assertEquals("right ear", 1.0, energy(right), 1e-3)
    }

    @Test
    fun `a centred signal stays centred`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val (left, right) = impulse(p, 0.7f, 0.7f, SadieHrir.TAPS)
        for (n in left.indices) {
            assertEquals("frame $n", left[n], right[n], 1e-7f)
        }
    }

    @Test
    fun `a pure side signal stays antisymmetric`() {
        // Only ACN 1 changes sign when the field is mirrored, so a signal that is nothing but
        // difference has to come out as one ear's exact opposite.
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val (left, right) = impulse(p, 0.5f, -0.5f, SadieHrir.TAPS)
        for (n in left.indices) {
            assertEquals("frame $n", left[n], -right[n], 1e-7f)
        }
        assertNotEquals("and it must not be silence", 0.0, energy(left), 1e-6)
    }

    @Test
    fun `a hard panned channel still reaches the far ear`() {
        // This is the entire difference from plain stereo. On headphones a hard-panned guitar never
        // reaches the other ear, which cannot happen in a room; here it arrives, quieter and later.
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val (left, right) = impulse(p, 1f, 0f, SadieHrir.TAPS)
        val near = sqrt(energy(left))
        val far = sqrt(energy(right))
        assertTrue("the far ear hears something: $far", far > 0.05)
        assertTrue("but less than the near ear: $near vs $far", far < near)
    }

    @Test
    fun `a seek does not drag the old tail into the new position`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        impulse(p, 1f, 1f, 8)
        p.flush()
        val (left, right) = impulse(p, 0f, 0f, SadieHrir.TAPS)
        assertEquals(0.0, energy(left), 1e-12)
        assertEquals(0.0, energy(right), 1e-12)
    }

    @Test
    fun `sixteen bit survives the round trip`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        val format = p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(2, format.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, format.encoding)
        p.flush()

        var peak = 0
        for (n in 0 until SadieHrir.TAPS) {
            val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            val v: Short = if (n == 0) 16000 else 0
            input.putShort(v).putShort(v)
            input.flip()
            p.queueInput(input)
            val out = p.output
            peak = maxOf(peak, abs(out.short.toInt()), abs(out.short.toInt()))
        }
        assertTrue("it produced audio: $peak", peak > 1000)
        assertTrue("and did not clip: $peak", peak < 32767)
    }

    @Test
    fun `the buffer that comes out is the size of the one that went in`() {
        val p = BinauralAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val input = ByteBuffer.allocateDirect(8 * 64).order(ByteOrder.nativeOrder())
        repeat(64) { input.putFloat(0.1f).putFloat(-0.1f) }
        input.flip()
        p.queueInput(input)
        assertEquals(8 * 64, p.output.remaining())
    }
}
