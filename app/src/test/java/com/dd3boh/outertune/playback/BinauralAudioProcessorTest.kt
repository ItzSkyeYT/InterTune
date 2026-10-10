/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
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
    fun `growing the layout on a configured processor does not crash`() {
        // Turning on 3D head tracking mid-playback reconfigures a processor that has already run,
        // with more channels than before. The gain used to be recomputed against filters still sized
        // for the old layout, and read past the end of them. Configure, grow, configure again, render.
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = true; fullSphere = false }
        p.configure(stereoFloat())
        p.flush()
        impulse(p, 1f, 0f, 64)

        p.fullSphere = true
        p.configure(stereoFloat())
        p.flush()
        val (left, right) = impulse(p, 1f, 0f, 256)
        assertTrue("the grown layout renders nothing", energy(left) + energy(right) > 0.0)

        // And back down, which never threw but did briefly compute a gain from the wrong filters.
        p.fullSphere = false
        p.configure(stereoFloat())
        p.flush()
        val (l2, r2) = impulse(p, 1f, 0f, 256)
        assertTrue("the shrunk layout renders nothing", energy(l2) + energy(r2) > 0.0)
    }

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
    fun `mono is refused, and so is a count that is neither 5 point 1 nor 7 point 1`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        for (channels in listOf(1, 3, 4, 5, 7, 9, 12)) {
            assertEquals(
                "$channels channels",
                AudioProcessor.AudioFormat.NOT_SET,
                p.configure(AudioProcessor.AudioFormat(48000, channels, C.ENCODING_PCM_FLOAT)),
            )
        }
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
    fun `the renderer does not retune the music`() {
        // What made switching this on sound worse rather than wider. Two speakers at thirty
        // degrees summed to about three decibels up in the bass and four and a half down through
        // the presence region: eight decibels of tilt, which is a tone control nobody asked for.
        //
        // Measured the way an ear meets it, by playing a tone and reading the level out, one
        // frequency at a time.
        fun levelAt(hz: Int, third: Boolean): Double {
            val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = third }
            p.configure(stereoFloat())
            p.flush()
            var n = 0
            var sum = 0.0
            var counted = 0
            repeat(6) {
                val input = ByteBuffer.allocateDirect(8 * 1024).order(ByteOrder.nativeOrder())
                repeat(1024) {
                    val v = sin(2.0 * PI * hz * n++ / 48000.0).toFloat()
                    input.putFloat(v).putFloat(v)   // centred, so this is the tone the ear hears
                }
                input.flip()
                p.queueInput(input)
                val out = p.output
                repeat(1024) {
                    val l = out.float
                    out.float
                    // Skip the first buffers while the convolutions fill.
                    if (n > 3072) { sum += l.toDouble() * l; counted++ }
                }
            }
            return sqrt(sum / counted)
        }

        // Averaged over each band, because the correction deliberately only flattens the broad
        // shape. A single tone can still sit in a narrow notch, and those notches are the pinna
        // cues that say where a sound is: removing them would take the effect with them.
        val bands = listOf(
            intArrayOf(100, 160, 250),
            intArrayOf(350, 500, 800),
            intArrayOf(1200, 1800, 2600),
            intArrayOf(3500, 4500, 5500),
            intArrayOf(6500, 8000, 9500),
            intArrayOf(11000, 13000, 15000),
        )
        for (third in booleanArrayOf(false, true)) {
            val levels = bands.map { tones ->
                20 * Math.log10(tones.map { levelAt(it, third) }.average())
            }
            val reference = levels.average()
            val worst = levels.maxOf { abs(it - reference) }
            assertTrue(
                "order ${if (third) 3 else 1} tilts by $worst dB across the bands: " +
                    levels.map { "%.1f".format(it - reference) },
                worst < 3.0,
            )
        }
    }

    @Test
    fun `changing stage width mid-stream rebuilds the correction, not just the gain`() {
        // Settings writes stageWidthDegrees straight onto the running processor with no restart
        // (MusicService.kt), and queueInput calls applyWidth every buffer. Configure at 30, run a
        // buffer, move to 60 and run another, then compare what it renders with a processor
        // configured at 60 from the start. After a flush both hold the same rings, history, speaker
        // encoding and yaw, so the only thing that can still differ is what was built for the
        // width: the tonal correction and the gain. They should match, not leave the new width
        // heard through the inverse built for 30.
        val moved = BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = false
            stageWidthDegrees = 30f
        }
        moved.configure(stereoFloat())
        moved.flush()
        impulse(moved, 1f, 1f, 8)

        moved.stageWidthDegrees = 60f
        impulse(moved, 1f, 1f, 8)

        val fresh = BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = false
            stageWidthDegrees = 60f
        }
        fresh.configure(stereoFloat())
        fresh.flush()
        moved.flush()

        // Off centre, so the harmonics driven by the difference between the channels are heard
        // as well as those driven by the sum.
        val heard = impulse(moved, 1f, 0.5f, 256)
        val expected = impulse(fresh, 1f, 0.5f, 256)
        assertArrayEquals("left ear, after moving to 60", expected.first, heard.first, 1e-6f)
        assertArrayEquals("right ear, after moving to 60", expected.second, heard.second, 1e-6f)
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

    // Surround in: a recording in 5.1 or 7.1 is its own loudspeakers.

    private fun surroundFloat(channels: Int, rate: Int = 48000) =
        AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT)

    private fun surround(channels: Int, third: Boolean = false, width: Float? = null) =
        BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            if (width != null) stageWidthDegrees = width
            configure(surroundFloat(channels))
            flush()
        }

    /** Feeds one frame of [frame] then silence, and returns [frames] frames of both ears. */
    private fun impulseOf(p: BinauralAudioProcessor, frame: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (n in 0 until frames) {
            val input = ByteBuffer.allocateDirect(4 * frame.size).order(ByteOrder.nativeOrder())
            for (v in frame) input.putFloat(if (n == 0) v else 0f)
            input.flip()
            p.queueInput(input)
            val out = p.output
            left[n] = out.float
            right[n] = out.float
        }
        return left to right
    }

    private fun only(channels: Int, vararg at: Pair<Int, Float>) = FloatArray(channels).also { for ((c, v) in at) it[c] = v }

    @Test
    fun `six channels and eight come out as two, at the same rate`() {
        for (channels in listOf(6, 8)) for (rate in listOf(44100, 48000)) {
            val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
            val out = p.configure(surroundFloat(channels, rate))
            assertEquals("$channels in", 2, out.channelCount)
            assertEquals(rate, out.sampleRate)
            assertEquals(C.ENCODING_PCM_FLOAT, out.encoding)
            assertTrue(p.isActive)
        }
    }

    @Test
    fun `what is only in the front pair comes out as it does from a stereo recording`() {
        for (third in listOf(false, true)) for (channels in listOf(6, 8)) {
            val stereo = BinauralAudioProcessor().apply { enabled = true; thirdOrder = third }
            stereo.configure(stereoFloat())
            stereo.flush()
            val expected = impulse(stereo, 0.7f, -0.3f, 256)
            val heard = impulseOf(surround(channels, third), only(channels, 0 to 0.7f, 1 to -0.3f), 256)
            assertArrayEquals("left ear, $channels channels, third order $third", expected.first, heard.first, 1e-6f)
            assertArrayEquals("right ear, $channels channels, third order $third", expected.second, heard.second, 1e-6f)
        }
    }

    @Test
    fun `the centre and the low notes reach both ears alike`() {
        for (channels in listOf(6, 8)) for (channel in listOf(2, SurroundLayout.LOW)) {
            val (left, right) = impulseOf(surround(channels, third = true), only(channels, channel to 1f), 256)
            assertTrue("channel $channel of $channels is heard", energy(left) > 0.01)
            assertArrayEquals("channel $channel of $channels", left, right, 1e-6f)
        }
    }

    @Test
    fun `one centre speaker is quieter than the same signal from both of the pair`() {
        // The pair carrying one signal comes out at the level it went in, which is what the gain
        // is derived for. One speaker straight ahead is one speaker, as it is in a room.
        val (left, _) = impulseOf(surround(6), only(6, 2 to 1f), SadieHrir.TAPS)
        val (both, _) = impulseOf(surround(6), only(6, 0 to 1f, 1 to 1f), SadieHrir.TAPS)
        assertEquals(1.0, energy(both), 1e-3)
        assertTrue("the centre alone: ${energy(left)}", energy(left) in 0.1..0.6)
    }

    @Test
    fun `a speaker on the left is louder in the left ear, and its twin on the right is its mirror`() {
        for ((channels, pairs) in listOf(6 to listOf(4 to 5), 8 to listOf(4 to 5, 6 to 7))) for ((l, r) in pairs) {
            val fromLeft = impulseOf(surround(channels, third = true), only(channels, l to 1f), 256)
            val fromRight = impulseOf(surround(channels, third = true), only(channels, r to 1f), 256)
            assertTrue("channel $l of $channels", energy(fromLeft.first) > 1.5 * energy(fromLeft.second))
            assertArrayEquals("channel $r of $channels, left ear", fromLeft.second, fromRight.first, 1e-6f)
            assertArrayEquals("channel $r of $channels, right ear", fromLeft.first, fromRight.second, 1e-6f)
        }
    }

    @Test
    fun `behind is not at the side, and neither is in front`() {
        val front = impulseOf(surround(8, third = true), only(8, 0 to 1f), 256)
        val back = impulseOf(surround(8, third = true), only(8, 4 to 1f), 256)
        val side = impulseOf(surround(8, third = true), only(8, 6 to 1f), 256)
        fun apart(a: Pair<FloatArray, FloatArray>, b: Pair<FloatArray, FloatArray>) =
            a.first.indices.sumOf { abs(a.first[it] - b.first[it]).toDouble() + abs(a.second[it] - b.second[it]) }
        assertTrue(apart(front, back) > 0.1)
        assertTrue(apart(front, side) > 0.1)
        assertTrue(apart(back, side) > 0.1)
    }

    @Test
    fun `the width chosen for a stereo recording is where a surround one's front pair stands`() {
        val narrow = impulseOf(surround(6, width = 30f), only(6, 0 to 1f), 256)
        val wide = impulseOf(surround(6, width = 60f), only(6, 0 to 1f), 256)
        assertTrue(narrow.first.indices.sumOf { abs(narrow.first[it] - wide.first[it]).toDouble() } > 0.05)
        // And the surrounds stay where the standard puts them. Read off the layout: what is heard
        // from them changes a little with the width all the same, since the tone correction is
        // made for the pair at that width.
        val at30 = SurroundLayout.azimuths(6, 30f)!!
        val at60 = SurroundLayout.azimuths(6, 60f)!!
        assertEquals(30f, at30[0], 0f)
        assertEquals(60f, at60[0], 0f)
        for (channel in 2 until 6) assertEquals("channel $channel", at30[channel], at60[channel], 0f)
    }

    @Test
    fun `a buffer of many frames is the same as the frames one by one`() {
        val frame = floatArrayOf(0.5f, -0.2f, 0.3f, 0.1f, -0.4f, 0.25f)
        val oneByOne = impulseOf(surround(6, third = true), frame, 200)
        val p = surround(6, third = true)
        val input = ByteBuffer.allocateDirect(4 * 6 * 200).order(ByteOrder.nativeOrder())
        for (n in 0 until 200) for (v in frame) input.putFloat(if (n == 0) v else 0f)
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertEquals(4 * 2 * 200, out.remaining())
        for (n in 0 until 200) {
            assertEquals("left, frame $n", oneByOne.first[n], out.float, 1e-6f)
            assertEquals("right, frame $n", oneByOne.second[n], out.float, 1e-6f)
        }
    }

    @Test
    fun `sixteen bit surround comes out as sixteen bit for two ears`() {
        val p = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        val out = p.configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT))
        assertEquals(2, out.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        p.flush()
        val input = ByteBuffer.allocateDirect(2 * 6 * 64).order(ByteOrder.nativeOrder())
        repeat(64) { n -> repeat(6) { input.putShort(if (n == 0) 8000 else 0) } }
        input.flip()
        p.queueInput(input)
        val heard = p.output
        assertEquals(2 * 2 * 64, heard.remaining())
        var loud = 0
        repeat(128) { if (heard.short.toInt() != 0) loud++ }
        assertTrue("something came out", loud > 8)
    }

    @Test
    fun `silence in is silence out, and a seek leaves no tail`() {
        val p = surround(8, third = true)
        val quiet = impulseOf(p, FloatArray(8), 64)
        assertEquals(0.0, energy(quiet.first) + energy(quiet.second), 0.0)
        impulseOf(p, FloatArray(8) { 0.5f }, 8)
        p.flush()
        val after = impulseOf(p, FloatArray(8), 256)
        assertEquals(0.0, energy(after.first) + energy(after.second), 0.0)
    }

    @Test
    fun `stereo after surround on the same processor is stereo as it always was`() {
        val p = surround(6)
        impulseOf(p, FloatArray(6) { 0.3f }, 32)
        p.configure(stereoFloat())
        p.flush()
        val fresh = BinauralAudioProcessor().apply { enabled = true; thirdOrder = false }
        fresh.configure(stereoFloat())
        fresh.flush()
        val heard = impulse(p, 1f, 0.5f, 256)
        val expected = impulse(fresh, 1f, 0.5f, 256)
        assertArrayEquals(expected.first, heard.first, 1e-6f)
        assertArrayEquals(expected.second, heard.second, 1e-6f)
    }

    @Test
    fun `the speakers of a surround recording stand in mirrored pairs`() {
        for (channels in listOf(6, 8)) {
            val at = SurroundLayout.azimuths(channels, 30f)!!
            assertEquals(channels, at.size)
            assertEquals(0f, at[2], 0f)
            assertEquals(0f, at[SurroundLayout.LOW], 0f)
            for (pair in listOf(0, 4) + if (channels == 8) listOf(6) else emptyList()) assertEquals(at[pair], -at[pair + 1], 0f)
            assertTrue(at.all { abs(it) <= 180f })
        }
        assertEquals(null, SurroundLayout.azimuths(2, 30f))
        assertEquals(null, SurroundLayout.azimuths(7, 30f))
    }

    // How much of it, and the bass left alone.

    private fun stereo(third: Boolean = false, share: Float = 1f, bass: Boolean = false, rate: Int = 48000) =
        BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            strength = share
            bassDirect = bass
            configure(stereoFloat(rate))
            flush()
        }

    /** Puts [left] and [right] through [p] in one buffer and returns the two ears. */
    private fun through(p: BinauralAudioProcessor, left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val input = ByteBuffer.allocateDirect(8 * left.size).order(ByteOrder.nativeOrder())
        for (n in left.indices) input.putFloat(left[n]).putFloat(right[n])
        input.flip()
        p.queueInput(input)
        val out = p.output
        val l = FloatArray(left.size)
        val r = FloatArray(left.size)
        for (n in left.indices) { l[n] = out.float; r[n] = out.float }
        return l to r
    }

    private fun tone(hz: Double, level: Float, frames: Int = 48000, rate: Int = 48000) =
        FloatArray(frames) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    /** How loud, once what it started with has died away. */
    private fun settled(a: FloatArray) = sqrt(a.drop(a.size / 3).fold(0.0) { acc, v -> acc + v.toDouble() * v } / (a.size - a.size / 3))

    private fun decibels(a: Double, b: Double) = 20 * kotlin.math.log10(a / b)

    @Test
    fun `nothing of the rendering is the recording as it came, late by the time the rendering takes`() {
        val (left, right) = impulse(stereo(share = 0f), 0.5f, -0.25f, 512)
        val at = left.indices.first { left[it] != 0f }
        assertTrue("held back by $at frames", at in 1..400)
        for (n in left.indices) {
            assertEquals("left, frame $n", if (n == at) 0.5f else 0f, left[n], 0f)
            assertEquals("right, frame $n", if (n == at) -0.25f else 0f, right[n], 0f)
        }
        // And that is where the rendering itself is strongest for a sound in the middle.
        val (rendered, _) = impulse(stereo(), 0.5f, 0.5f, 512)
        assertEquals(at, rendered.indices.maxByOrNull { abs(rendered[it]) })
    }

    @Test
    fun `half of it is half of each`() {
        for (third in listOf(false, true)) {
            val all = impulse(stereo(third), 0.5f, -0.25f, 512)
            val none = impulse(stereo(third, share = 0f), 0.5f, -0.25f, 512)
            val half = impulse(stereo(third, share = 0.5f), 0.5f, -0.25f, 512)
            for (n in 0 until 512) {
                assertEquals("left, frame $n", 0.5f * all.first[n] + 0.5f * none.first[n], half.first[n], 1e-6f)
                assertEquals("right, frame $n", 0.5f * all.second[n] + 0.5f * none.second[n], half.second[n], 1e-6f)
            }
        }
    }

    @Test
    fun `all of it, and more than all, is what it always was`() {
        val expected = impulse(stereo(), 0.7f, -0.3f, 256)
        for (share in listOf(1f, 1.5f)) {
            val heard = impulse(stereo(share = share), 0.7f, -0.3f, 256)
            assertArrayEquals(expected.first, heard.first, 0f)
            assertArrayEquals(expected.second, heard.second, 0f)
        }
    }

    @Test
    fun `with the bass left alone a low note stays in the ear it was in`() {
        val low = tone(50.0, 0.4f)
        val quiet = FloatArray(low.size)
        val (left, right) = through(stereo(third = true, bass = true), low, quiet)
        assertTrue("the other ear: ${settled(right)} of ${settled(left)}", settled(right) < 0.05 * settled(left))
        // Rendered, the same note is in both ears: a head does not shadow a note that low.
        val (wasLeft, wasRight) = through(stereo(third = true), low, quiet)
        assertTrue("rendered: ${settled(wasRight)} of ${settled(wasLeft)}", settled(wasRight) > 0.5 * settled(wasLeft))
    }

    @Test
    fun `and it is as loud as it went in`() {
        for (rate in listOf(44100, 48000)) {
            val low = tone(50.0, 0.4f, rate = rate)
            val (left, right) = through(stereo(third = true, bass = true, rate = rate), low, low)
            assertEquals("left ear at $rate", 0.0, decibels(settled(left), settled(low)), 0.5)
            assertEquals("right ear at $rate", 0.0, decibels(settled(right), settled(low)), 0.5)
        }
    }

    @Test
    fun `the rest still goes through the rendering`() {
        val high = tone(2000.0, 0.3f)
        val quiet = FloatArray(high.size)
        val (left, right) = through(stereo(third = true, bass = true), high, quiet)
        val (wasLeft, wasRight) = through(stereo(third = true), high, quiet)
        assertTrue("it reaches the other ear, as rendered sound does", settled(right) > 0.05 * settled(left))
        assertEquals("left ear, against all of it rendered", 0.0, decibels(settled(left), settled(wasLeft)), 0.5)
        assertEquals("right ear, against all of it rendered", 0.0, decibels(settled(right), settled(wasRight)), 0.5)
    }

    @Test
    fun `no hole where the lows hand over to the rest`() {
        val seen = StringBuilder()
        var worst = 0.0
        for (rate in listOf(44100, 48000)) for (third in listOf(false, true)) for (hz in listOf(40.0, 60.0, 80.0, 100.0, 120.0, 150.0, 200.0, 300.0, 500.0)) {
            val note = tone(hz, 0.3f, rate = rate)
            val (left, _) = through(stereo(third = third, bass = true, rate = rate), note, note)
            val off = decibels(settled(left), settled(note))
            seen.append("%d Hz at %d, third %s: %+.1f dB; ".format(hz.toInt(), rate, third, off))
            worst = maxOf(worst, abs(off))
        }
        assertTrue(seen.toString(), worst < 2.5)
    }

    @Test
    fun `a seek leaves nothing of the recording behind either`() {
        val p = stereo(third = true, share = 0.5f, bass = true)
        through(p, tone(60.0, 0.5f, 4800), tone(900.0, 0.5f, 4800))
        p.flush()
        val (left, right) = through(p, FloatArray(2048), FloatArray(2048))
        assertEquals(0.0, energy(left) + energy(right), 0.0)
    }

    // A room round the two speakers.

    private fun inRoom(share: Float, third: Boolean = true, rate: Int = 48000) =
        BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            room = share
            configure(stereoFloat(rate))
            flush()
        }

    @Test
    fun `no room is what it always was`() {
        val expected = impulse(stereo(third = true), 0.7f, -0.3f, 1200)
        val heard = impulse(inRoom(0f), 0.7f, -0.3f, 1200)
        assertArrayEquals(expected.first, heard.first, 0f)
        assertArrayEquals(expected.second, heard.second, 0f)
    }

    @Test
    fun `a room sends the sound back a few thousandths of a second late, and not before`() {
        val open = impulse(inRoom(0f), 1f, 1f, 1500)
        val room = impulse(inRoom(1f), 1f, 1f, 1500)
        // Up to the first reflection the two differ only by the room's being turned down to match.
        val first = (6.5 * 48).toInt()
        val early = (0 until first - 8).sumOf { abs(room.first[it]).toDouble() }
        val earlyOpen = (0 until first - 8).sumOf { abs(open.first[it]).toDouble() }
        assertTrue("the sound itself is still there: $early of $earlyOpen", early in 0.6 * earlyOpen..earlyOpen)
        // After the last of the open air's sound, only a room still speaks.
        val lateOpen = (700 until 1500).sumOf { abs(open.first[it]).toDouble() }
        val lateRoom = (300 until 1300).sumOf { abs(room.first[it]).toDouble() }
        assertEquals(0.0, lateOpen, 1e-4)
        assertTrue("something comes back: $lateRoom", lateRoom > 0.05)
    }

    @Test
    fun `a room is not louder than no room`() {
        for (rate in listOf(44100, 48000)) for (share in listOf(0.5f, 1f)) {
            val open = impulse(inRoom(0f, rate = rate), 1f, 1f, 2000)
            val room = impulse(inRoom(share, rate = rate), 1f, 1f, 2000)
            assertEquals("left ear at $rate, room $share", 0.0, 10 * kotlin.math.log10(energy(room.first) / energy(open.first)), 1.0)
            assertEquals("right ear at $rate, room $share", 0.0, 10 * kotlin.math.log10(energy(room.second) / energy(open.second)), 1.0)
        }
    }

    @Test
    fun `the room is the same on both sides`() {
        val fromLeft = impulse(inRoom(1f), 1f, 0f, 1500)
        val fromRight = impulse(inRoom(1f), 0f, 1f, 1500)
        assertArrayEquals(fromLeft.first, fromRight.second, 1e-6f)
        assertArrayEquals(fromLeft.second, fromRight.first, 1e-6f)
        val centred = impulse(inRoom(1f), 1f, 1f, 1500)
        assertArrayEquals(centred.first, centred.second, 1e-6f)
    }

    @Test
    fun `a seek leaves no echo of the room`() {
        val p = inRoom(1f)
        impulse(p, 1f, -1f, 64)
        p.flush()
        val (left, right) = impulse(p, 0f, 0f, 1500)
        assertEquals(0.0, energy(left) + energy(right), 0.0)
    }

    @Test
    fun `a room leaves the low notes as loud as they were`() {
        // Three reflections a few thousandths of a second apart add up at one low note and cancel
        // at the next: given the lows, this room took six decibels off everything from 40 to 80 Hz.
        val seen = StringBuilder()
        var worst = 0.0
        for (rate in listOf(44100, 48000)) for (hz in listOf(30.0, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0)) for (side in listOf(false, true)) {
            val note = tone(hz, 0.3f, rate = rate)
            val other = if (side) FloatArray(note.size) else note
            val open = through(inRoom(0f, rate = rate), note, other)
            val room = through(inRoom(1f, rate = rate), note, other)
            val left = decibels(settled(room.first), settled(open.first))
            val right = decibels(settled(room.second), settled(open.second))
            seen.append("%d Hz at %d%s: %+.2f and %+.2f dB; ".format(hz.toInt(), rate, if (side) ", one side" else "", left, right))
            worst = maxOf(worst, abs(left), abs(right))
        }
        assertTrue(seen.toString(), worst < 0.5)
    }

    /** The energy of [a] in the third of an octave round [centre]. */
    private fun third(a: FloatArray, centre: Double, rate: Int): Double {
        var sum = 0.0
        for (k in 0 until 15) {
            val w = 2 * PI * centre * Math.pow(2.0, (k - 7) / 14.0 / 3.0) / rate
            var re = 0.0
            var im = 0.0
            for (n in a.indices) { re += a[n] * cos(w * n); im -= a[n] * sin(w * n) }
            sum += re * re + im * im
        }
        return sum
    }

    @Test
    fun `a room does not retune the music`() {
        // What a sound in the middle is given by the room is space, not a tone of its own: third
        // of an octave by third of an octave it stays where it was. Before the walls were given
        // the recording as it came, and only its middle, 3 kHz stood six decibels proud.
        val thirds = listOf(160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
        for (rate in listOf(44100, 48000)) for (third in listOf(false, true)) {
            val open = through(inRoom(0f, third, rate), FloatArray(8192).also { it[0] = 1f }, FloatArray(8192).also { it[0] = 1f }).first
            for (share in listOf(0.5f, 1f)) {
                val room = through(inRoom(share, third, rate), FloatArray(8192).also { it[0] = 1f }, FloatArray(8192).also { it[0] = 1f }).first
                val seen = StringBuilder()
                var worst = 0.0
                var sum = 0.0
                for (hz in thirds) {
                    val off = 10 * kotlin.math.log10(third(room, hz, rate) / third(open, hz, rate))
                    seen.append("%d Hz %+.1f; ".format(hz.toInt(), off))
                    worst = maxOf(worst, abs(off))
                    sum += off
                }
                val about = "room $share at $rate, third order $third: $seen"
                assertTrue(about, worst < 2.0 * share + 0.3)
                // And on the whole it is neither up nor down: the walls' share is taken back from the speakers'.
                assertEquals(about, 0.0, sum / thirds.size, 0.5)
            }
        }
    }

    @Test
    fun `the room fills in the ear a sound is turned away from`() {
        // Which is the room doing its work: off one speaker alone, the far ear hears the walls.
        val quiet = FloatArray(8192)
        val click = FloatArray(8192).also { it[0] = 1f }
        val open = through(inRoom(0f), click, quiet)
        val room = through(inRoom(1f), click, quiet)
        fun middle(a: FloatArray) = listOf(500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0).sumOf { third(a, it, 48000) }
        val near = 10 * kotlin.math.log10(middle(room.first) / middle(open.first))
        val far = 10 * kotlin.math.log10(middle(room.second) / middle(open.second))
        assertEquals("the near ear", 0.0, near, 1.5)
        assertTrue("the far ear gains: $far dB, the near $near", far > near + 1.0)
    }

    // The air round a recording, moved out.

    private fun spread(share: Float, third: Boolean = true, rate: Int = 48000, strength: Float = 1f, bass: Boolean = false) =
        BinauralAudioProcessor().apply {
            enabled = true
            thirdOrder = third
            ambience = share
            this.strength = strength
            bassDirect = bass
            configure(stereoFloat(rate))
            flush()
        }

    private fun hiss(seed: Long, frames: Int, level: Float = 0.2f) = java.util.Random(seed).let { r -> FloatArray(frames) { (r.nextGaussian() * level).toFloat() } }

    private val late = DirectAmbientSplit.DELAY

    /** How alike the two ears are, at whatever small lag makes them most alike: 1 is the same sound in both. */
    private fun alike(left: FloatArray, right: FloatArray): Double {
        val from = 24000
        val to = left.size - 64
        var l = 0.0
        var r = 0.0
        for (n in from until to) { l += left[n].toDouble() * left[n]; r += right[n].toDouble() * right[n] }
        var best = 0.0
        for (lag in -48..48) {
            var sum = 0.0
            for (n in from until to) sum += left[n].toDouble() * right[n + lag]
            best = maxOf(best, abs(sum))
        }
        return best / sqrt(l * r)
    }

    @Test
    fun `no ambience is what it always was`() {
        val left = hiss(1, 6000)
        val right = hiss(2, 6000)
        val expected = through(stereo(third = true), left, right)
        val heard = through(spread(0f), left, right)
        assertArrayEquals(expected.first, heard.first, 0f)
        assertArrayEquals(expected.second, heard.second, 0f)
    }

    @Test
    fun `a sound in the middle is left where it was, one frame of the split late`() {
        for (share in listOf(0.3f, 1f)) {
            val sound = hiss(3, 40000)
            val was = through(stereo(third = true), sound, sound)
            val now = through(spread(share), sound, sound)
            for (n in late until 40000) {
                assertEquals("left at $n, ambience $share", was.first[n - late], now.first[n], 1e-5f)
                assertEquals("right at $n, ambience $share", was.second[n - late], now.second[n], 1e-5f)
            }
        }
    }

    @Test
    fun `what the channels do not share is heard further apart`() {
        val left = hiss(4, 72000)
        val right = hiss(5, 72000)
        val was = through(spread(0f), left, right)
        val half = through(spread(0.5f), left, right)
        val out = through(spread(1f), left, right)
        val before = alike(was.first, was.second)
        val between = alike(half.first, half.second)
        val after = alike(out.first, out.second)
        // Less alike is what wide is. Not less and less all the way: a sound is at its widest
        // near the side, and the ears cannot tell a little in front of that from a little behind.
        assertTrue("the two ears alike: $before with the pair, $between half way, $after all the way out", between < before - 0.05 && after < before - 0.05)
    }

    /** Noise with most of its energy low down, as music has: white noise through one pole at 800 Hz. */
    private fun warm(seed: Long, frames: Int, rate: Int): FloatArray {
        val white = hiss(seed, frames, 0.5f)
        val by = (1.0 - kotlin.math.exp(-2.0 * PI * 800 / rate)).toFloat()
        var y = 0f
        return FloatArray(frames) { y += by * (white[it] - y); y }
    }

    @Test
    fun `and it is as loud out there as it was with the pair`() {
        for (rate in listOf(44100, 48000)) for (third in listOf(false, true)) for (share in listOf(0.5f, 1f)) {
            val left = warm(6, 72000, rate)
            val right = warm(7, 72000, rate)
            val was = through(spread(0f, third, rate), left, right)
            val out = through(spread(share, third, rate), left, right)
            fun loud(p: Pair<FloatArray, FloatArray>) = (24000 until 72000).sumOf { p.first[it].toDouble() * p.first[it] + p.second[it].toDouble() * p.second[it] }
            assertEquals("ambience $share at $rate, third order $third", 0.0, 10 * kotlin.math.log10(loud(out) / loud(was)), 0.5)
        }
    }

    /** The energy in each third of an octave over the settled part of [a], at 48 kHz. */
    private fun thirds(a: FloatArray, centres: List<Double>): DoubleArray {
        val size = DirectAmbientSplit.SIZE
        val power = DoubleArray(size / 2 + 1)
        val re = FloatArray(size)
        val im = FloatArray(size)
        var at = 24000
        while (at + size <= a.size) {
            for (n in 0 until size) { re[n] = a[at + n] * (0.5f - 0.5f * cos(2 * PI * n / size).toFloat()); im[n] = 0f }
            DirectAmbientSplit.fft(re, im, false)
            for (k in 0..size / 2) power[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            at += size / 2
        }
        return DoubleArray(centres.size) { c ->
            val lo = centres[c] / Math.pow(2.0, 1.0 / 6)
            val hi = centres[c] * Math.pow(2.0, 1.0 / 6)
            var sum = 0.0
            for (k in 0..size / 2) { val hz = k * 48000.0 / size; if (hz >= lo && hz < hi) sum += power[k] }
            sum
        }
    }

    @Test
    fun `and of the same tone`() {
        // From the side a sound reaches the ears duller than from in front, and fuller lower down:
        // left alone, the air moved out was twelve decibels down at 4 kHz and three up under 600 Hz.
        // Its own tone filter gives that back, so that only its place changes.
        val centres = listOf(250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
        val left = hiss(18, 144000)
        val right = hiss(19, 144000)
        fun both(p: Pair<FloatArray, FloatArray>) = thirds(p.first, centres).zip(thirds(p.second, centres)) { a, b -> a + b }
        val was = both(through(spread(0f), left, right))
        for ((share, most) in listOf(0.5f to 2.5, 1f to 4.5)) {
            val out = both(through(spread(share), left, right))
            val seen = StringBuilder()
            var worst = 0.0
            var sum = 0.0
            for (i in centres.indices) {
                val off = 10 * kotlin.math.log10(out[i] / was[i])
                seen.append("%d Hz %+.1f; ".format(centres[i].toInt(), off))
                worst = maxOf(worst, abs(off))
                sum += abs(off)
            }
            assertTrue("ambience $share: $seen", worst < most && sum / centres.size < 1.2)
        }
    }

    @Test
    fun `the air on the left is the mirror of the air on the right`() {
        val left = hiss(8, 30000)
        val right = hiss(9, 30000)
        val one = through(spread(1f), left, right)
        val other = through(spread(1f), right, left)
        assertArrayEquals(one.first, other.second, 1e-5f)
        assertArrayEquals(one.second, other.first, 1e-5f)
    }

    @Test
    fun `with none of the rendering what is left is the whole recording, air and all`() {
        val left = hiss(10, 12000)
        val right = hiss(11, 12000)
        val p = spread(1f, strength = 0f)
        val (heardLeft, heardRight) = through(p, left, right)
        // Late by the split's frame and by what the rendering takes, as the recording beside it always is.
        val plain = impulse(stereo(third = true, share = 0f), 1f, 1f, 512).first
        val held = plain.indices.first { plain[it] != 0f } + late
        for (n in held until 12000) {
            assertEquals("left at $n", left[n - held], heardLeft[n], 1e-6f)
            assertEquals("right at $n", right[n - held], heardRight[n], 1e-6f)
        }
    }

    @Test
    fun `the bass left alone stays as loud with the air moved out`() {
        val low = tone(50.0, 0.4f, frames = 72000)
        val (left, right) = through(spread(1f, bass = true), low, low)
        assertEquals("left ear", 0.0, decibels(settled(left), settled(low)), 0.5)
        assertEquals("right ear", 0.0, decibels(settled(right), settled(low)), 0.5)
    }

    @Test
    fun `sixteen bit goes the same way`() {
        val left = hiss(12, 30000)
        val right = hiss(13, 30000)
        val float = through(spread(1f), FloatArray(30000) { (left[it] * 32768f).toInt().toShort() / 32768f }, FloatArray(30000) { (right[it] * 32768f).toInt().toShort() / 32768f })
        val p = BinauralAudioProcessor().apply {
            enabled = true; thirdOrder = true; ambience = 1f
            configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); flush()
        }
        val input = ByteBuffer.allocateDirect(4 * 30000).order(ByteOrder.nativeOrder())
        for (n in 0 until 30000) input.putShort((left[n] * 32768f).toInt().toShort()).putShort((right[n] * 32768f).toInt().toShort())
        input.flip()
        p.queueInput(input)
        val out = p.output
        for (n in 0 until 30000) {
            assertEquals("left at $n", float.first[n], out.short / 32768f, 2f / 32768f)
            assertEquals("right at $n", float.second[n], out.short / 32768f, 2f / 32768f)
        }
    }

    @Test
    fun `a seek leaves none of the air behind`() {
        val p = spread(1f, strength = 0.5f, bass = true)
        through(p, hiss(14, 6000), hiss(15, 6000))
        p.flush()
        val (left, right) = through(p, FloatArray(4000), FloatArray(4000))
        assertEquals(0.0, energy(left) + energy(right), 0.0)
    }

    @Test
    fun `turning the head turns the air with the rest`() {
        // A quarter turn to the left: the air that stood out at the left is now behind, and the two ears swap what they had of it.
        val left = hiss(16, 40000)
        val right = hiss(17, 40000)
        fun facing(yaw: Float) = spread(1f).also { it.headYawRadians = yaw }
        val ahead = through(facing(0f), left, right)
        val round = through(facing(PI.toFloat()), left, right)
        // Facing the other way everything is mirrored: left ear hears what the right did.
        fun loud(a: FloatArray) = (30000 until 40000).sumOf { a[it].toDouble() * a[it] }
        assertEquals(0.0, 10 * kotlin.math.log10(loud(round.first) / loud(ahead.second)), 1.5)
        assertEquals(0.0, 10 * kotlin.math.log10(loud(round.second) / loud(ahead.first)), 1.5)
    }

    @Test
    fun `air brought back in the middle of a song brings nothing of before with it`() {
        val p = spread(1f)
        through(p, hiss(20, 6000), hiss(21, 6000))
        p.ambience = 0f
        through(p, FloatArray(2000), FloatArray(2000))
        p.ambience = 1f
        // Nothing but silence has gone in since: what the split still held of the noise must not come out now.
        val (left, right) = through(p, FloatArray(4000), FloatArray(4000))
        assertEquals(0.0, energy(left) + energy(right), 0.0)
    }

    @Test
    fun `a room brought back in the middle of a song sends nothing of before back`() {
        val p = inRoom(1f)
        through(p, hiss(22, 6000), hiss(23, 6000))
        p.room = 0f
        through(p, FloatArray(2000), FloatArray(2000))
        p.room = 1f
        val (left, right) = through(p, FloatArray(4000), FloatArray(4000))
        assertEquals(0.0, energy(left) + energy(right), 0.0)
    }
}

