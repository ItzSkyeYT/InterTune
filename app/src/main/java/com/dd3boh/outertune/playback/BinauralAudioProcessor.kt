/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Puts the music in front of the listener instead of inside their head.
 *
 * Stereo on headphones is unnatural in a way people stop noticing: a hard-panned guitar arrives at
 * one ear and literally never reaches the other, which cannot happen in a room, so the brain gives
 * up on placing it and puts the whole mix on a line between the ears. This renders the same two
 * channels as two loudspeakers standing in front of you at thirty degrees, deriving what each ear
 * would actually receive, including the shadowing of the head and the shape of the outer ear. The
 * effect is called externalisation, and it is the honest version of what the upmix was reaching
 * for.
 *
 * The route taken, and why it is not the obvious one. The obvious way is a pair of head-related
 * impulse responses per virtual speaker, convolved directly. That works until you want to move the
 * soundstage, at which point you have to interpolate between measured directions, and these
 * responses are not minimum phase: the interaural delay is inside the impulse, so interpolating
 * coefficients comb filters. Encoding to first-order ambisonics instead makes a rotation a two by
 * two matrix on two signals, exact at any angle, with nothing to interpolate and nothing to click.
 * It costs a little more arithmetic and deletes a whole category of bug.
 *
 * What it is not. The recording has two channels and nothing recovers information that was never
 * recorded, so this is not Atmos and not a remaster. It is also not personalised: the responses
 * were measured on a Neumann KU100 dummy head, whose ears are not yours, so elevation will not
 * convince and front-back confusion is a known artefact of borrowed anatomy. First order is
 * deliberately blurry, and a dry rendering with no room in it externalises less than one with
 * early reflections. What it does do is take the mix off the line between your ears, which is the
 * part that makes headphones tiring.
 *
 * No Android in here beyond the media3 interface, so the arithmetic is tested.
 */
class BinauralAudioProcessor : BaseAudioProcessor() {

    /** Whether to render. Volatile: flipped on the main thread, read on the audio thread. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Where the soundstage sits relative to the head, in radians, anticlockwise.
     *
     * Exists and is always zero. Rotating the field is one line in [render] and the whole reason
     * for the ambisonic detour, but the orientation would have to come from the headphones, and on
     * this platform it cannot: Sensor.TYPE_HEAD_TRACKER is restricted to system_server and
     * audioserver by a hardcoded uid check with no permission to request, and the XM5 carries its
     * tracker over Bluetooth Classic HID, which Android exposes to no app at all. Left in place
     * because it costs two multiplies and is the difference between a fixed pair of speakers and a
     * room that stays still while you turn your head.
     */
    @Volatile
    var yawRadians: Float = 0f

    /** The decode filters at the stream's own rate. Rebuilt by [onConfigure]. */
    private var hW = FloatArray(0)
    private var hY = FloatArray(0)
    private var hX = FloatArray(0)
    private var taps = 0

    private val ringW = FloatArray(RING)
    private val ringY = FloatArray(RING)
    private val ringX = FloatArray(RING)
    private var writeIndex = 0

    /** Normalisation, worked out from the filters themselves. See [computeGain]. */
    private var gain = 1f

    /**
     * How much to scale the output so switching this on is not also turning it up.
     *
     * The same trap the upmix fell into: a rendering that is six decibels louder wins every
     * comparison it is given, whatever it does to the imaging. So the gain is derived rather than
     * guessed. A centred mono signal encodes to W and X only, giving a single effective impulse
     * response per ear; its energy is what that signal's level gets multiplied by, so dividing by
     * the square root of that energy sends mono in and mono out at the same level, at any rate.
     */
    private fun computeGain(): Float {
        var energy = 0.0
        for (t in 0 until taps) {
            val monoPath = MONO_W * hW[t] + MONO_X * hX[t]
            energy += monoPath.toDouble() * monoPath.toDouble()
        }
        if (energy <= 0.0) return 1f
        return (1.0 / sqrt(energy)).toFloat()
    }

    /**
     * The measured filters, moved to the rate the stream is actually running at.
     *
     * Necessary rather than nice: the set is measured at 48 kHz, which is what Opus decodes to,
     * but YouTube's AAC is 44.1 kHz and a local file is whatever it is. Refusing those would mean
     * a setting that works on some songs and silently does nothing on others, which is worse than
     * either working or being off. Windowed sinc interpolation, so this is band limited rather
     * than a nearest-sample smear; it runs once per format change, never per sample.
     *
     * Returns false if the rate is far enough out that the filters would not fit the ring, in
     * which case the stream passes through untouched.
     */
    private fun buildFilters(rate: Int): Boolean {
        if (rate <= 0) return false
        val ratio = rate.toDouble() / SadieHrir.SAMPLE_RATE
        val length = ceil(SadieHrir.TAPS * ratio).toInt()
        if (length < 8 || length > MAX_TAPS) return false

        taps = length
        hW = resample(SadieHrir.W, ratio, length)
        hY = resample(SadieHrir.Y, ratio, length)
        hX = resample(SadieHrir.X, ratio, length)
        gain = computeGain()
        clearTails()
        return true
    }

    private fun resample(source: ShortArray, ratio: Double, length: Int): FloatArray {
        if (ratio == 1.0) return FloatArray(source.size) { source[it] / 32768f }

        // Below unity the output rate is the lower one, so the filter has to be band limited to
        // the new Nyquist. Scaling the amplitude by the same factor keeps the sinc's area at one.
        val cutoff = min(1.0, ratio)
        val out = FloatArray(length)
        for (m in 0 until length) {
            val at = m / ratio
            val centre = floor(at).toInt()
            var acc = 0.0
            for (k in centre - HALF_WIDTH + 1..centre + HALF_WIDTH) {
                if (k < 0 || k >= source.size) continue
                val x = at - k
                acc += (source[k] / 32768.0) * cutoff * sinc(cutoff * x) * window(x)
            }
            out[m] = acc.toFloat()
        }
        return out
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    /** Hann, over the interpolation half width. Keeps the truncation from ringing. */
    private fun window(x: Double): Double {
        if (abs(x) >= HALF_WIDTH) return 0.0
        return 0.5 * (1.0 + cos(PI * x / HALF_WIDTH))
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (!buildFilters(inputAudioFormat.sampleRate)) return AudioProcessor.AudioFormat.NOT_SET
        // Stereo in, stereo out. Unlike the upmix this changes no format, only the samples.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * format.bytesPerFrame)

        val yaw = yawRadians
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)

        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames) {
                val l = inputBuffer.short / 32768f
                val r = inputBuffer.short / 32768f
                render(l, r, cosYaw, sinYaw) { v -> out.putShort(toPcm16(v)) }
            }

            C.ENCODING_PCM_FLOAT -> repeat(frames) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                render(l, r, cosYaw, sinYaw) { v -> out.putFloat(v) }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /**
     * One frame: encode to B-format, rotate, convolve, decode to two ears.
     *
     * The decode exploits the symmetry the filters were measured for. Only the left-right
     * component of a first-order field, ACN 1, changes sign when mirrored about the median plane,
     * so the two ears share everything else: the sum of the W and X convolutions is common to
     * both, the Y convolution is added for one ear and subtracted for the other. Three
     * convolutions rather than six, for exactly the same result.
     */
    private inline fun render(l: Float, r: Float, cosYaw: Float, sinYaw: Float, put: (Float) -> Unit) {
        // Encode: the two channels as loudspeakers at plus and minus thirty degrees.
        val sum = l + r
        val w = sum * ACN_W
        var y = (l - r) * SPEAKER_SIN
        var x = sum * SPEAKER_COS

        // Rotate the whole field. Identity while nothing drives yaw, and free when it is zero.
        if (sinYaw != 0f) {
            val rx = x * cosYaw + y * sinYaw
            val ry = y * cosYaw - x * sinYaw
            x = rx
            y = ry
        }

        val i = writeIndex
        ringW[i] = w
        ringY[i] = y
        ringX[i] = x

        var accW = 0f
        var accY = 0f
        var accX = 0f
        for (t in 0 until taps) {
            val k = (i - t) and MASK
            accW += ringW[k] * hW[t]
            accY += ringY[k] * hY[t]
            accX += ringX[k] * hX[t]
        }
        writeIndex = (i + 1) and MASK

        val common = (accW + accX) * gain
        val side = accY * gain
        put(common + side)
        put(common - side)
    }

    private fun toPcm16(v: Float): Short {
        val scaled = v * 32767f
        return when {
            scaled >= 32767f -> Short.MAX_VALUE
            scaled <= -32768f -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    override fun onFlush() = clearTails()

    override fun onReset() = clearTails()

    /** A seek must not drag the tail of the old audio into the new position. */
    private fun clearTails() {
        Arrays.fill(ringW, 0f)
        Arrays.fill(ringY, 0f)
        Arrays.fill(ringX, 0f)
        writeIndex = 0
    }

    companion object {
        /** Power of two so the ring index is a mask rather than a modulo. */
        private const val RING = 1024
        private const val MASK = RING - 1

        /**
         * Half the ring, so a whole filter plus a whole filter's worth of history always fits.
         * Reached at 96 kHz; above that the stream passes through, which is the right way to fail
         * for a rate nothing here streams and a tap count that would cost real battery.
         */
        private const val MAX_TAPS = RING / 2

        /** Taps either side of the interpolation point when moving the filters to a new rate. */
        private const val HALF_WIDTH = 16

        /**
         * SN3D puts no attenuation on the omnidirectional component: a unit source contributes
         * one to W. The older FuMa convention scales it by the square root of a half, and these
         * filters are not FuMa, so using that number would quietly render three decibels too
         * directional.
         */
        const val ACN_W = 1f

        /** Virtual loudspeakers at thirty degrees, the angle a stereo mix is made for. */
        const val SPEAKER_SIN = 0.5f          // sin 30
        const val SPEAKER_COS = 0.8660254f    // cos 30

        /** What a centred mono signal encodes to, used to derive the output gain. */
        const val MONO_W = 2f * ACN_W
        const val MONO_X = 2f * SPEAKER_COS
    }
}
