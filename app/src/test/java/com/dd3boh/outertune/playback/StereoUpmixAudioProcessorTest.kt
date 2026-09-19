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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the upmix does to the samples, and what it refuses to touch. */
class StereoUpmixAudioProcessorTest {

    private fun stereoFloat(rate: Int = 48000) =
        AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)

    @Test
    fun `disabled, it is not in the chain at all`() {
        val p = StereoUpmixAudioProcessor().apply { enabled = false }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereoFloat()))
        assertFalse(p.isActive)
    }

    @Test
    fun `enabled, stereo becomes six channels at the same rate`() {
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        val out = p.configure(stereoFloat(44100))
        assertEquals(6, out.channelCount)
        assertEquals(44100, out.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
        assertTrue(p.isActive)
    }

    @Test
    fun `anything already multichannel is left alone`() {
        // If YouTube ever does serve 5.1, the real thing must pass through untouched.
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        val six = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(six))
    }

    @Test
    fun `the fronts are untouched and the matrix is what it claims`() {
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putFloat(0.8f).putFloat(-0.2f)
        input.flip()
        p.queueInput(input)

        val out = p.output
        val got = FloatArray(6) { out.float }

        val t = StereoUpmixAudioProcessor.TRIM
        assertEquals("front left is the original, trimmed", 0.8f * t, got[0], 1e-6f)
        assertEquals("front right is the original, trimmed", -0.2f * t, got[1], 1e-6f)
        assertEquals("centre is the sum at -3dB", (0.8f + -0.2f) * 0.7071f * t, got[2], 1e-5f)
        assertEquals("LFE is silent on purpose", 0f, got[3], 1e-9f)
        assertEquals("back left is the difference", (0.8f - -0.2f) * 0.5f * t, got[4], 1e-5f)
        assertEquals("back right is its opposite", -((0.8f - -0.2f) * 0.5f * t), got[5], 1e-5f)
    }

    @Test
    fun `a mono signal puts nothing in the surrounds`() {
        // Identical channels have no difference component, so a centred mono recording must not
        // acquire a phantom surround field out of nowhere.
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putFloat(0.5f).putFloat(0.5f)
        input.flip()
        p.queueInput(input)

        val out = p.output
        val got = FloatArray(6) { out.float }
        assertEquals(0f, got[4], 1e-9f)
        assertEquals(0f, got[5], 1e-9f)
        assertEquals("and the centre carries it", 0.5f * 2 * 0.7071f * StereoUpmixAudioProcessor.TRIM, got[2], 1e-5f)
    }

    @Test
    fun `a centred signal comes back at the level it went in`() {
        // The system folds the six channels back to stereo on the way out, adding centre and
        // surrounds into left and right at -3 dB each. Without the trim that returns about +6 dB
        // and the setting wins every comparison by being louder, whatever it does to the imaging.
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        p.configure(stereoFloat())
        p.flush()

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putFloat(0.5f).putFloat(0.5f)
        input.flip()
        p.queueInput(input)

        val out = p.output
        val ch = FloatArray(6) { out.float }
        // ITU-R BS.775: L = FL + 0.707*C + 0.707*BL
        val folded = ch[0] + 0.7071f * ch[2] + 0.7071f * ch[4]
        assertEquals("a mono signal must come back where it started", 0.5f, folded, 0.05f)
    }

    @Test
    fun `sixteen bit survives the round trip`() {
        val p = StereoUpmixAudioProcessor().apply { enabled = true }
        val out = p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(6, out.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        p.flush()

        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(16000).putShort(-4000)
        input.flip()
        p.queueInput(input)

        val out16 = p.output
        val got = ShortArray(6) { out16.short }
        assertEquals((16000 * StereoUpmixAudioProcessor.TRIM).toInt(), got[0].toInt())
        assertEquals((-4000 * StereoUpmixAudioProcessor.TRIM).toInt(), got[1].toInt())
        assertEquals(0, got[3].toInt())
    }
}
