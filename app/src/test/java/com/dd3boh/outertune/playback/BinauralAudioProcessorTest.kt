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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
        val p = BinauralAudioProcessor().apply { enabled = false; thirdOrder = false }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereoFloat()))
        assertFalse(p.isActive)
    }

    @Test
    fun `enabled, stereo stays stereo at the same rate`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        val out = p.configure(stereoFloat(44100))
        assertEquals(44100, out.sampleRate)
        assertEquals(2, out.channelCount)
        assertTrue(p.isActive)
    }

    @Test
    fun `and it comes out at the right level there as well`() {
        // The filters are resampled, so their energy changes; the gain is derived after that
        // rather than before, or every AAC stream would play at the wrong level.
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        p.configure(stereoFloat(44100))
        p.flush()

        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 1e-3)
        assertEquals("right ear", 1.0, energy(right), 1e-3)
    }

    @Test
    fun `and it still puts something in the far ear there`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, p.configure(stereoFloat(192000)))
    }

    @Test
    fun `mono and multichannel are refused`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        p.configure(stereoFloat())
        p.flush()

        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 1e-3)
        assertEquals("right ear", 1.0, energy(right), 1e-3)
    }

    @Test
    fun `a centred signal stays centred`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
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

    /**
     * A processor whose rotation has finished ramping to [yaw], with clean convolution tails.
     *
     * The ramp is rate limited, so the angle cannot simply be set and measured: half a second of
     * silence is enough for any angle, and the flush afterwards drops the tails without touching
     * where the rotation got to, which is the behaviour a seek needs anyway.
     */
    private fun turned(yaw: Float, third: Boolean = false): BinauralAudioProcessor {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = third }
        p.configure(stereoFloat())
        p.flush()
        p.headYawRadians = yaw
        val silence = ByteBuffer.allocateDirect(8 * 24000).order(ByteOrder.nativeOrder())
        repeat(24000) { silence.putFloat(0f).putFloat(0f) }
        silence.flip()
        p.queueInput(silence)
        p.output
        p.flush()
        return p
    }

    @Test
    fun `turning sixty degrees left swaps the two speakers`() {
        // The speakers stand sixty degrees apart, so turning exactly that far to the left puts the
        // left one where the right one was. Nothing approximate: the field becomes the other
        // channel's field component for component, so the rendering has to be identical. It is
        // also the test that pins the direction, because it only holds for a leftward turn.
        val (turnedL, turnedR) = impulse(turned((PI / 3).toFloat()), 1f, 0f, SadieHrir.TAPS)
        val (restL, restR) = impulse(turned(0f), 0f, 1f, SadieHrir.TAPS)
        for (n in turnedL.indices) {
            assertEquals("left, frame $n", restL[n], turnedL[n], 1e-5f)
            assertEquals("right, frame $n", restR[n], turnedR[n], 1e-5f)
        }
    }

    @Test
    fun `turning thirty degrees left puts the left speaker dead ahead`() {
        // A source on the nose reaches both ears identically, because ACN 1 comes out at exactly
        // zero there, in float as well as on paper.
        val (left, right) = impulse(turned((PI / 6).toFloat()), 1f, 0f, SadieHrir.TAPS)
        for (n in left.indices) assertEquals("frame $n", left[n], right[n], 1e-6f)
    }

    @Test
    fun `turning left moves a centred mix into the right ear`() {
        // The plain language one. Turn your head left and the band is now on your right.
        val (l1, r1) = impulse(turned((PI / 2).toFloat()), 1f, 1f, SadieHrir.TAPS)
        assertTrue("right ear louder", energy(r1) > energy(l1))
        val (l2, r2) = impulse(turned((-PI / 2).toFloat()), 1f, 1f, SadieHrir.TAPS)
        assertTrue("and the other way round", energy(l2) > energy(r2))
    }

    @Test
    fun `left and right stay mirror images at any angle`() {
        // Only ACN 1 changes sign when the field is mirrored. Swap the channels, turn the other
        // way, and the ears swap, or the two halves of the rotation disagree with each other.
        for (d in intArrayOf(7, 35, 95, 170)) {
            val a = (d * PI / 180.0).toFloat()
            val (pL, pR) = impulse(turned(a), 0.8f, 0.2f, SadieHrir.TAPS)
            val (mL, mR) = impulse(turned(-a), 0.2f, 0.8f, SadieHrir.TAPS)
            for (n in pL.indices) {
                assertEquals("$d deg, frame $n", pL[n], mR[n], 1e-5f)
                assertEquals("$d deg, frame $n", pR[n], mL[n], 1e-5f)
            }
        }
    }

    @Test
    fun `no angle makes it silent, loud or nonsense`() {
        // The field's magnitude is preserved exactly by the rotation, but the ears do not hear a
        // constant level as it turns and should not: a real head is not equally sensitive in every
        // direction. So this is a guard band. What it catches is a rotation that has stopped being
        // one, which grows without bound.
        val reference = impulse(turned(0f), 1f, 1f, SadieHrir.TAPS).let { energy(it.first) + energy(it.second) }
        for (d in 0 until 360 step 15) {
            val (l, r) = impulse(turned((d * PI / 180.0).toFloat()), 1f, 1f, SadieHrir.TAPS)
            val e = energy(l) + energy(r)
            assertTrue("$d deg produced $e", e.isFinite() && e > reference / 4 && e < reference * 4)
        }
    }

    @Test
    fun `moving the head between buffers does not click`() {
        // Stepping the rotation once per buffer leaves a discontinuity about a hundred times the
        // size of anything inside the buffer, fifty times a second. Ramping across the buffer
        // brings it down to the slope changing rather than the signal jumping.
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        p.configure(stereoFloat())
        p.flush()
        val out = ArrayList<Float>()
        var n = 0
        for (block in 0 until 8) {
            p.headYawRadians = block * 0.075f     // about 200 deg/s at 1024 frames
            val input = ByteBuffer.allocateDirect(8 * 1024).order(ByteOrder.nativeOrder())
            repeat(1024) {
                val v = sin(2.0 * PI * 440.0 * n++ / 48000.0).toFloat()
                input.putFloat(v).putFloat(v * 0.3f)
            }
            input.flip()
            p.queueInput(input)
            val o = p.output
            repeat(1024) { out.add(o.float); o.float }
        }
        val d2 = FloatArray(out.size - 2) { abs(out[it + 2] - 2 * out[it + 1] + out[it]) }
        val interior = d2.sorted()[d2.size / 2]
        val seam = (1 until 8).maxOf { b -> (-4..3).maxOf { d2[b * 1024 + it] } }
        assertTrue("seam $seam against interior $interior", seam < interior * 10f)
    }

    @Test
    fun `a seek does not put the stage back in front`() {
        // The tails have to go, because the old audio must not follow the listener to the new
        // position. The head has not moved, so the rotation must not either.
        val p = turned((PI / 3).toFloat())
        impulse(p, 1f, 0f, 64)
        p.flush()
        val (afterL, afterR) = impulse(p, 1f, 0f, SadieHrir.TAPS)
        val (refL, refR) = impulse(turned((PI / 3).toFloat()), 1f, 0f, SadieHrir.TAPS)
        for (n in refL.indices) {
            assertEquals("left, frame $n", refL[n], afterL[n], 1e-6f)
            assertEquals("right, frame $n", refR[n], afterR[n], 1e-6f)
        }
    }

    @Test
    fun `third order is level matched too`() {
        // Ten harmonics instead of three, so the gain is a different number and derived the same
        // way. Switching order must not be a volume change either.
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true }
        p.configure(stereoFloat())
        p.flush()
        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 1e-3)
        assertEquals("right ear", 1.0, energy(right), 1e-3)
    }

    @Test
    fun `third order keeps a centred mix centred and a side signal antisymmetric`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true }
        p.configure(stereoFloat())
        p.flush()
        val (cl, cr) = impulse(p, 0.7f, 0.7f, SadieHrir.TAPS)
        for (n in cl.indices) assertEquals("centre, frame $n", cl[n], cr[n], 1e-7f)

        val q = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true }
        q.configure(stereoFloat())
        q.flush()
        val (sl, sr) = impulse(q, 0.5f, -0.5f, SadieHrir.TAPS)
        for (n in sl.indices) assertEquals("side, frame $n", sl[n], -sr[n], 1e-7f)
        assertNotEquals("and not silence", 0.0, energy(sl), 1e-6)
    }

    @Test
    fun `third order still swaps the speakers at sixty degrees`() {
        // The rotation is exact at every order, so the landmark that pins the first-order
        // direction has to hold with ten harmonics turning by one, two and three times the angle.
        val (turnedL, turnedR) = impulse(turned((PI / 3).toFloat(), third = true), 1f, 0f, SadieHrir.TAPS)
        val (restL, restR) = impulse(turned(0f, third = true), 0f, 1f, SadieHrir.TAPS)
        for (n in turnedL.indices) {
            assertEquals("left, frame $n", restL[n], turnedL[n], 1e-5f)
            assertEquals("right, frame $n", restR[n], turnedR[n], 1e-5f)
        }
    }

    @Test
    fun `third order tells two directions apart far better than first order`() {
        // The entire reason for the extra arithmetic, measured as the thing it actually claims.
        //
        // Not the interaural ratio, which is a trap: first order reports a bigger left-to-right
        // energy ratio for a hard-panned source than third order does, and it is wrong to. A real
        // head at thirty degrees is around one and a half to three times, third order gives two,
        // and first order's three and a half is the blur exaggerating rather than separation.
        //
        // What actually matters is whether two different directions render differently at all. A
        // hard-left source sits at whatever the stage width is, so moving the width from thirty to
        // sixty degrees moves the source, and the angle between the two impulse responses says how
        // much the renderer can tell them apart.
        fun response(third: Boolean, width: Float): FloatArray {
            val p = BinauralAudioProcessor().apply {
                enabled = true
                thirdOrder = third
                stageWidthDegrees = width
            }
            p.configure(stereoFloat())
            p.flush()
            val (l, r) = impulse(p, 1f, 0f, SadieHrir.TAPS)
            return l + r
        }

        fun separation(third: Boolean): Double {
            val a = response(third, 30f)
            val b = response(third, 60f)
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            // Angle between them, in degrees. Normalised, so the gain difference between widths
            // cancels and only the shape is compared.
            return Math.toDegrees(Math.acos((dot / sqrt(na * nb)).coerceIn(-1.0, 1.0)))
        }

        val first = separation(false)
        val third = separation(true)
        assertTrue("first order told them apart by $first deg, expected under 30", first < 30.0)
        assertTrue("third order told them apart by $third deg, expected over 40", third > 40.0)
        assertTrue("third order $third should beat first order $first by miles", third > first * 2)
    }

    @Test
    fun `a full scale sine never leaves full scale, at any order`() {
        // Every head-related transfer function peaks where the ear canal resonates, and this one
        // puts a full scale centred sine at nearly twice full scale around three kilohertz. Left
        // alone that hits the hard clamp underneath and splatters across the spectrum. Checked at
        // both orders because the peak is a property of the ear, not of the harmonic count.
        for (third in booleanArrayOf(false, true)) {
            for (hz in intArrayOf(200, 440, 1000, 2000, 4000, 8000)) {
                val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = third }
                p.configure(stereoFloat())
                p.flush()

                var peak = 0f
                var n = 0
                repeat(8) {
                    val input = ByteBuffer.allocateDirect(8 * 1024).order(ByteOrder.nativeOrder())
                    repeat(1024) {
                        val v = sin(2.0 * PI * hz * n++ / 48000.0).toFloat()
                        input.putFloat(v).putFloat(v)      // centred, full scale: the worst case
                    }
                    input.flip()
                    p.queueInput(input)
                    val out = p.output
                    // Skip the first buffer: the convolution is still filling.
                    repeat(1024) {
                        val l = abs(out.float)
                        val r = abs(out.float)
                        if (n > 2048) peak = maxOf(peak, l, r)
                    }
                }
                assertTrue(
                    "order ${if (third) 3 else 1} at $hz Hz peaked at $peak",
                    peak <= 1.0f,
                )
            }
        }
    }

    @Test
    fun `mono keeps its level, headroom is not taken from everything`() {
        // The wrong fix for the peak was backing the gain off until nothing could ever clip,
        // which cost almost six decibels and made every track quieter to spare the loud ones.
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true }
        p.configure(stereoFloat())
        p.flush()
        val (left, right) = impulse(p, 1f, 1f, SadieHrir.TAPS)
        assertEquals("left ear", 1.0, energy(left), 0.05)
        assertEquals("right ear", 1.0, energy(right), 0.05)
    }

    /** A head rotation as the tracker reports it: X out the right ear, Y the nose, Z the top. */
    private fun pose(ax: Float, ay: Float, az: Float, degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        val n = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 0f } ?: 1f
        val si = sin(half)
        return floatArrayOf(cos(half), ax / n * si, ay / n * si, az / n * si)
    }

    private fun posed(p: FloatArray?, l: Float, r: Float): Pair<FloatArray, FloatArray> {
        val proc = BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = true
            fullSphere = true
            headPose = p
        }
        proc.configure(stereoFloat())
        proc.flush()
        // Settle the interpolation, then clear the tails without disturbing where it settled.
        val silence = ByteBuffer.allocateDirect(8 * 4096).order(ByteOrder.nativeOrder())
        repeat(4096) { silence.putFloat(0f).putFloat(0f) }
        silence.flip()
        proc.queueInput(silence)
        proc.output
        proc.flush()
        return impulse(proc, l, r, SadieHrir.TAPS)
    }

    @Test
    fun `with the head level, the sphere renders what the horizontal path does`() {
        // Sixteen harmonics against ten. The six extra are identically zero at ear level, so
        // carrying them must change nothing at all, and if it does the layout is wrong.
        val (fullL, fullR) = posed(pose(0f, 0f, 1f, 0f), 1f, 0f)
        val (flatL, flatR) = impulse(turned(0f, third = true), 1f, 0f, SadieHrir.TAPS)
        for (n in fullL.indices) {
            assertEquals("left, frame $n", flatL[n], fullL[n], 1e-5f)
            assertEquals("right, frame $n", flatR[n], fullR[n], 1e-5f)
        }
    }

    @Test
    fun `turning still works through the full sphere path`() {
        // The landmark that pins the direction, checked again now the rotation happens by moving
        // the speakers rather than by turning the field.
        val (turnedL, turnedR) = posed(pose(0f, 0f, 1f, 60f), 1f, 0f)
        val (restL, restR) = posed(pose(0f, 0f, 1f, 0f), 0f, 1f)
        for (n in turnedL.indices) {
            assertEquals("left, frame $n", restL[n], turnedL[n], 1e-5f)
            assertEquals("right, frame $n", restR[n], turnedR[n], 1e-5f)
        }
    }

    @Test
    fun `looking up and down does something, and something different each way`() {
        // The whole point of carrying the other six harmonics. Without them a nod would leave the
        // rendering untouched, or quietly turn it down.
        val level = posed(pose(1f, 0f, 0f, 0f), 1f, 1f).first
        val up = posed(pose(1f, 0f, 0f, 30f), 1f, 1f).first
        val down = posed(pose(1f, 0f, 0f, -30f), 1f, 1f).first

        fun difference(a: FloatArray, b: FloatArray): Double {
            var d = 0.0
            for (i in a.indices) d += (a[i] - b[i]).toDouble() * (a[i] - b[i])
            return sqrt(d)
        }
        assertTrue("looking up changed nothing", difference(level, up) > 0.05)
        assertTrue("looking down changed nothing", difference(level, down) > 0.05)
        assertTrue("up and down are the same", difference(up, down) > 0.05)
    }

    @Test
    fun `a centred source stays centred however far the head tips`() {
        // Pitch keeps a frontal source in the median plane, so both ears must still hear the same
        // thing. It is the sharpest check that the six elevation harmonics are wired the right way
        // up: get one sign wrong and a nod pushes the sound sideways.
        for (d in floatArrayOf(-60f, -25f, 25f, 60f)) {
            val (l, r) = posed(pose(1f, 0f, 0f, d), 0.7f, 0.7f)
            for (n in l.indices) assertEquals("pitch $d, frame $n", l[n], r[n], 1e-6f)
        }
    }

    @Test
    fun `tilting the head mirrors, the same way turning it does`() {
        // Roll one way with the channels swapped has to equal rolling the other way, or the
        // elevation harmonics disagree with the horizontal ones about which side is which.
        for (d in floatArrayOf(20f, 55f)) {
            val (pL, pR) = posed(pose(0f, 1f, 0f, d), 0.8f, 0.2f)
            val (mL, mR) = posed(pose(0f, 1f, 0f, -d), 0.2f, 0.8f)
            for (n in pL.indices) {
                assertEquals("roll $d, frame $n", pL[n], mR[n], 1e-5f)
                assertEquals("roll $d, frame $n", pR[n], mL[n], 1e-5f)
            }
        }
    }

    @Test
    fun `no head position makes it silent, loud or nonsense`() {
        val reference = posed(pose(0f, 0f, 1f, 0f), 1f, 1f).let { energy(it.first) + energy(it.second) }
        for (axis in arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))) {
            for (d in 0 until 360 step 30) {
                val (l, r) = posed(pose(axis[0], axis[1], axis[2], d.toFloat()), 1f, 1f)
                val e = energy(l) + energy(r)
                assertTrue(
                    "axis ${axis.toList()} at $d deg produced $e",
                    e.isFinite() && e > reference / 6 && e < reference * 6,
                )
            }
        }
    }

    @Test
    fun `the buffer that comes out is the size of the one that went in`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        p.configure(stereoFloat())
        p.flush()

        val input = ByteBuffer.allocateDirect(8 * 64).order(ByteOrder.nativeOrder())
        repeat(64) { input.putFloat(0.1f).putFloat(-0.1f) }
        input.flip()
        p.queueInput(input)
        assertEquals(8 * 64, p.output.remaining())
    }
}
