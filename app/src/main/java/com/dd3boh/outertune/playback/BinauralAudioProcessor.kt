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
import kotlin.math.atan2
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
 * channels as two loudspeakers standing in front of you, deriving what each ear would actually
 * receive, including the shadowing of the head and the shape of the outer ear.
 *
 * The route taken, and why it is not the obvious one. The obvious way is a pair of head-related
 * impulse responses per virtual speaker, convolved directly. That works until you want to move the
 * soundstage, at which point you have to interpolate between measured directions, and these
 * responses are not minimum phase: the interaural delay is inside the impulse, so interpolating
 * coefficients comb filters. Encoding to ambisonics instead makes a horizontal rotation a pair of
 * multiplies per harmonic, exact at any angle, with nothing to interpolate and nothing to click.
 *
 * Order matters more than anything else here. A first-order field has a directional blur roughly a
 * hundred and twenty degrees across, so two virtual speakers sixty degrees apart sit inside one
 * blur and the whole mix arrives as a single broad object. Third order narrows that enough to hear
 * instruments as separate places, and costs ten convolutions a sample rather than three.
 *
 * What it is not. The recording has two channels and nothing recovers information that was never
 * recorded, so this is not Atmos and not a remaster. It is also not personalised: the responses
 * were measured on a Neumann KU100 dummy head, whose ears are not yours, so elevation will not
 * convince and front-back confusion is a known artefact of borrowed anatomy.
 *
 * No Android in here beyond the media3 interface, so the arithmetic is tested.
 */
class BinauralAudioProcessor : BaseAudioProcessor() {

    /** Whether to render. Volatile: flipped on the main thread, read on the audio thread. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Third order rather than first. Sharper, and three times the arithmetic.
     *
     * Worth having as a choice rather than a constant because the cost is real: ten two hundred
     * and fifty six tap convolutions per sample is a hundred and twenty million multiplies a
     * second, which a current phone does not notice and a six year old tablet might.
     */
    @Volatile
    var thirdOrder: Boolean = true

    /**
     * How far the head has turned, in radians, anticlockwise, so plus is a turn to the left.
     *
     * The field counter-rotates by this, which is what keeps the stage where it was: turn ninety
     * degrees left and a source that was in front of you ends up on your right. That inversion is
     * already in [rotate], so whatever drives this passes head yaw straight in without negating it.
     *
     * Stepping it once per buffer is not enough. That is a step in the gain applied to most of the
     * harmonics, fifty times a second, which is a buzz rather than a soundstage, so [queueInput]
     * ramps it across the buffer instead.
     */
    @Volatile
    var headYawRadians: Float = 0f

    /**
     * The whole head orientation, as a quaternion in the tracker's own frame: X out the right
     * ear, Y out the nose, Z out the top of the head. Null falls back to [headYawRadians].
     *
     * Separate from the yaw because most of the time there is nothing to supply it, and a
     * renderer that only turns is both cheaper and, with a dummy head's ears rather than the
     * listener's, more convincing than one that also tilts.
     */
    @Volatile
    var headPose: FloatArray? = null

    /**
     * Carry the harmonics that only exist off the horizontal plane.
     *
     * Six of the sixteen are identically zero for anything at ear level, so a renderer that only
     * turns can drop them and does. The moment the head tilts, energy moves into exactly those
     * six, and without them a nod would quietly attenuate the soundstage instead of moving it.
     * Read when the format is configured, because it changes how much there is to convolve.
     */
    @Volatile
    var fullSphere: Boolean = false

    /**
     * How far apart the two virtual loudspeakers stand, in degrees either side of centre.
     *
     * Thirty is the angle a stereo mix is made for and the honest default. Wider is not more
     * correct, but it separates more, and at first order it was the only lever there was. At third
     * order the blur is narrow enough that thirty should already hold apart.
     */
    @Volatile
    var stageWidthDegrees: Float = DEFAULT_STAGE_WIDTH

    // The active harmonics, in the order they are stored. Rebuilt when the ambisonic order
    // changes. Only harmonics that are non-zero in the horizontal plane are carried at all: for a
    // source at zero elevation every harmonic with (n - |m|) odd evaluates to zero, which is six
    // of the sixteen at third order, and convolving those would be six multiplications of nothing.
    private var degree = IntArray(0)
    private var fromDifference = BooleanArray(0)
    private var factor = FloatArray(0)
    private var sourceChannel = IntArray(0)

    /**
     * What each harmonic is worth for each speaker with the head level.
     *
     * The level match and the peak check both have to be answered with the head straight, not
     * wherever it happens to be pointing: turning your head is not allowed to change the volume.
     * Derived from the same encoding the renderer uses rather than from a horizontal shortcut,
     * because the shortcut has no answer for the six harmonics that only exist off that plane.
     */
    private var identityLeft = FloatArray(0)
    private var identityRight = FloatArray(0)

    /** What each harmonic is worth for each speaker right now, and how it moves this buffer. */
    private var encodeLeft = FloatArray(0)
    private var encodeRight = FloatArray(0)
    private var stepLeft = FloatArray(0)
    private var stepRight = FloatArray(0)
    private var speakerAzimuth = 0f
    private val rotated = FloatArray(3)
    private val targetLeft = FloatArray(16)
    private val targetRight = FloatArray(16)
    private val poseBuffer = FloatArray(4)

    /** Indices into the active list for each rotating pair, and the degree it turns by. */
    private var pairSin = IntArray(0)
    private var pairCos = IntArray(0)
    private var pairDegree = IntArray(0)

    /** Filters and history, flattened: slot i tap t at i * taps + t, sample at i * RING + n. */
    private var filters = FloatArray(0)
    private var rings = FloatArray(0)
    private var active = 0
    private var taps = 0
    private var writeIndex = 0

    /**
     * How many frames have gone through, so the delay to the ear can be measured rather than
     * guessed.
     *
     * Everything this processor does happens before the sink's own buffer, the Bluetooth encoder
     * and the headphones, and the total of those is what the head tracking has to predict past.
     * Comparing this against the position the player reports, which is derived from what the
     * audio device says it has actually played, gives that total directly. Volatile because it is
     * written on the audio thread and read on the main one.
     */
    @Volatile
    var framesProcessed: Long = 0L
        private set

    /** Normalisation, worked out from the filters themselves. See [computeGain]. */
    private var gain = 1f

    private var appliedOrder = -1
    private var appliedSphere = false
    private var appliedWidth = Float.NaN
    private var appliedRate = -1

    /**
     * Where the rotation has actually reached, as opposed to where the head is.
     *
     * Audio thread only. Deliberately untouched by [clearTails]: a seek has to drop the
     * convolution tails, because the old audio must not follow the listener to the new position,
     * but the head has not moved, so the stage must not either.
     */
    private var rampYaw = 0f

    /**
     * Which harmonics exist in the horizontal plane, and what each one is worth.
     *
     * SN3D, the AmbiX convention these filters were made for, so the omnidirectional component
     * carries no attenuation. The constants are the spherical harmonics evaluated at zero
     * elevation; they were derived rather than typed, and the first-order row reproduces the plain
     * W, Y, X encoding exactly, which is what says the derivation is right.
     */
    private fun buildLayout(order: Int, sphere: Boolean) {
        if (order == appliedOrder && sphere == appliedSphere) return
        appliedOrder = order
        appliedSphere = sphere

        val table = when {
            order >= 3 && sphere -> ORDER_3_FULL_LAYOUT
            order >= 3 -> ORDER_3_LAYOUT
            else -> ORDER_1_LAYOUT
        }
        active = table.size / 3
        degree = IntArray(active)
        fromDifference = BooleanArray(active)
        factor = FloatArray(active)
        sourceChannel = IntArray(active)
        for (i in 0 until active) {
            val acn = table[i * 3].toInt()
            val m = table[i * 3 + 1].toInt()
            sourceChannel[i] = acn
            degree[i] = m
            // Mirroring the field about the median plane flips the sign of every harmonic with a
            // negative degree and leaves the rest alone, which is why one ear's filters are enough
            // for both, and it is also exactly the harmonics that are driven by the difference
            // between the channels rather than their sum.
            fromDifference[i] = m < 0
            factor[i] = table[i * 3 + 2]
        }

        // Harmonics pair up by degree: the sine and cosine of the same n and |m| rotate into each
        // other. Everything with degree zero is unchanged by a horizontal rotation.
        val sinIdx = ArrayList<Int>()
        val cosIdx = ArrayList<Int>()
        val degs = ArrayList<Int>()
        for (i in 0 until active) {
            if (degree[i] <= 0) continue
            val partner = (0 until active).firstOrNull {
                degree[it] == -degree[i] && sourceChannel[it] + degree[i] * 2 == sourceChannel[i]
            } ?: continue
            cosIdx.add(i)
            sinIdx.add(partner)
            degs.add(degree[i])
        }
        pairCos = cosIdx.toIntArray()
        pairSin = sinIdx.toIntArray()
        pairDegree = degs.toIntArray()

        appliedWidth = Float.NaN
        appliedRate = -1
    }

    /**
     * Move the virtual speakers, and renormalise so doing so is not also a volume change.
     *
     * Both speakers at once, so each harmonic sees the sum or the difference of the two channels
     * rather than each separately: the right speaker sits at minus the left one's angle, and
     * cosine does not care about that sign while sine flips with it.
     */
    private fun applyWidth(degrees: Float) {
        val clamped = degrees.coerceIn(MIN_STAGE_WIDTH, MAX_STAGE_WIDTH)
        if (clamped == appliedWidth && identityLeft.size == active) return
        appliedWidth = clamped
        speakerAzimuth = clamped * PI.toFloat() / 180f
        identityLeft = FloatArray(active)
        identityRight = FloatArray(active)
        encodeLeft = FloatArray(active)
        encodeRight = FloatArray(active)
        stepLeft = FloatArray(active)
        stepRight = FloatArray(active)
        encodeSpeakers(IDENTITY, identityLeft, identityRight)
        System.arraycopy(identityLeft, 0, encodeLeft, 0, active)
        System.arraycopy(identityRight, 0, encodeRight, 0, active)
        if (taps > 0) gain = computeGain()
    }

    /**
     * Where the two speakers end up, and what each harmonic is worth there.
     *
     * The whole of the head tracking, in one place. Rather than rotating the sound field, which
     * needs a different matrix for every order and is fiddly to get right, the two virtual
     * speakers are moved by the inverse of the head's rotation and re-encoded where they land.
     * The result is identical and exact at any angle, because there are only ever two sources and
     * moving two vectors is cheap, and it handles tilting for free: nothing about it assumes the
     * speakers stayed at ear level.
     */
    private fun encodeSpeakers(pose: FloatArray, intoLeft: FloatArray, intoRight: FloatArray) {
        // The field turns the opposite way to the head, so the conjugate. The tracker's axes are
        // not the ambisonic ones either: its Y is out of the nose where ambisonic X is the front,
        // and its X is out of the right ear where ambisonic Y is the left. Only up is shared.
        val w = pose[0]
        val rx = -pose[2]
        val ry = pose[1]
        val rz = -pose[3]

        val c = cos(speakerAzimuth)
        val s = sin(speakerAzimuth)
        rotate(w, rx, ry, rz, c, s, 0f, rotated)
        shInto(rotated, intoLeft)
        rotate(w, rx, ry, rz, c, -s, 0f, rotated)
        shInto(rotated, intoRight)
    }

    /** A unit vector through a quaternion, the long way round so no temporaries are allocated. */
    private fun rotate(w: Float, qx: Float, qy: Float, qz: Float, x: Float, y: Float, z: Float, into: FloatArray) {
        val tx = 2f * (qy * z - qz * y)
        val ty = 2f * (qz * x - qx * z)
        val tz = 2f * (qx * y - qy * x)
        into[0] = x + w * tx + (qy * tz - qz * ty)
        into[1] = y + w * ty + (qz * tx - qx * tz)
        into[2] = z + w * tz + (qx * ty - qy * tx)
    }

    /**
     * Real spherical harmonics to third order at one direction, SN3D, ambisonic axes.
     *
     * Written out rather than recursed. Sixteen expressions are easier to check against a
     * reference than a recurrence is, and at zero elevation they reduce to exactly the horizontal
     * table this used before, which is the check that matters.
     */
    private fun shInto(d: FloatArray, into: FloatArray) {
        val x = d[0]
        val y = d[1]
        val z = d[2]
        for (i in 0 until active) {
            into[i] = when (sourceChannel[i]) {
                0 -> 1f
                1 -> y
                2 -> z
                3 -> x
                4 -> SQRT3 * x * y
                5 -> SQRT3 * y * z
                6 -> (3f * z * z - 1f) * 0.5f
                7 -> SQRT3 * x * z
                8 -> SQRT3 * 0.5f * (x * x - y * y)
                9 -> SQRT58 * y * (3f * x * x - y * y)
                10 -> SQRT15 * x * y * z
                11 -> SQRT38 * y * (5f * z * z - 1f)
                12 -> z * (5f * z * z - 3f) * 0.5f
                13 -> SQRT38 * x * (5f * z * z - 1f)
                14 -> SQRT15 * 0.5f * z * (x * x - y * y)
                else -> SQRT58 * x * (x * x - 3f * y * y)
            }
        }
    }

    /**
     * How much to scale the output so switching this on is not also turning it up.
     *
     * The trap the upmix fell into: a rendering that is six decibels louder wins every comparison
     * it is given, whatever it does to the imaging. So the gain is derived rather than guessed. A
     * centred mono signal has no difference component, so only the harmonics driven by the sum
     * survive, and together they give one effective impulse response per ear; its energy is what
     * that signal's level gets multiplied by, so dividing by the square root of that energy sends
     * mono in and mono out at the same level, at any rate, order or width.
     */
    private fun computeGain(): Float {
        var energy = 0.0
        for (t in 0 until taps) {
            var monoPath = 0f
            for (i in 0 until active) {
                // Both speakers carrying the same signal. The harmonics of negative degree cancel
                // between them, which is why a centred source reaches both ears identically.
                monoPath += (identityLeft[i] + identityRight[i]) * filters[i * taps + t]
            }
            energy += monoPath.toDouble() * monoPath.toDouble()
        }
        if (energy <= 0.0) return 1f
        return (1.0 / sqrt(energy)).toFloat()
    }

    /**
     * Keep the rare peak under full scale without making everything quieter.
     *
     * Any head-related transfer function has a pronounced peak where the ear canal resonates,
     * around three to five kilohertz, and this set is no exception: a full scale centred sine
     * there comes out at nearly twice full scale. It is a real property of a real ear, not a bug,
     * and it is the same at first order and third, so it is not what the extra harmonics changed.
     *
     * The obvious fix, backing the gain off until nothing can ever exceed full scale, costs almost
     * six decibels to solve a problem that only occurs when the music is both loud and
     * concentrated at one frequency. That trade is wrong: it makes every track quieter to spare
     * the occasional one, and a renderer that is quieter than the signal it replaced loses every
     * comparison for the wrong reason.
     *
     * So the level match stands and the top is rounded off instead. Below the knee this is
     * arithmetically identity, so ordinary listening passes through untouched; above it the curve
     * approaches full scale and never reaches it. Vastly preferable to the hard clamp underneath,
     * which turns one loud moment into splatter across the whole spectrum.
     */
    private fun softClip(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = (a - KNEE) / (1f - KNEE)
        val shaped = KNEE + (1f - KNEE) * (over / (1f + over))
        return if (v < 0f) -shaped else shaped
    }

    /**
     * The measured filters, moved to the rate the stream is actually running at.
     *
     * Necessary rather than nice: the set is measured at 48 kHz, which is what Opus decodes to,
     * but YouTube's AAC is 44.1 kHz and a local file is whatever it is. Refusing those would mean
     * a setting that works on some songs and silently does nothing on others, which is worse than
     * either working or being off. Windowed sinc interpolation, so this is band limited rather
     * than a nearest-sample smear; it runs once per format change, never per sample.
     */
    private fun buildFilters(rate: Int): Boolean {
        if (rate <= 0) return false
        if (rate == appliedRate) return true
        val source = if (appliedOrder >= 3) SadieHrir3.H else SadieHrir.H
        val sourceTaps = if (appliedOrder >= 3) SadieHrir3.TAPS else SadieHrir.TAPS
        val ratio = rate.toDouble() / SadieHrir.SAMPLE_RATE
        val length = ceil(sourceTaps * ratio).toInt()
        if (length < 8 || length > MAX_TAPS) return false

        appliedRate = rate
        taps = min(length, KEEP_TAPS)
        filters = FloatArray(active * taps)
        for (i in 0 until active) {
            resampleInto(source, sourceChannel[i] * sourceTaps, sourceTaps, ratio, i * taps)
        }
        rings = FloatArray(active * RING)
        gain = computeGain()
        clearTails()
        return true
    }

    /** Half a Hann over the last few taps, so a truncated filter does not end in a step. */
    private fun fade(t: Int): Float {
        val from = taps - FADE_TAPS
        if (t < from) return 1f
        return (0.5 * (1.0 + cos(PI * (t - from) / FADE_TAPS))).toFloat()
    }

    private fun resampleInto(source: ShortArray, offset: Int, sourceTaps: Int, ratio: Double, into: Int) {
        if (ratio == 1.0) {
            for (t in 0 until taps) filters[into + t] = source[offset + t] / 32768f * fade(t)
            return
        }
        // Below unity the output rate is the lower one, so the filter has to be band limited to
        // the new Nyquist. Scaling the amplitude by the same factor keeps the sinc's area at one.
        val cutoff = min(1.0, ratio)
        for (m in 0 until taps) {
            val at = m / ratio
            val centre = floor(at).toInt()
            var acc = 0.0
            for (k in centre - HALF_WIDTH + 1..centre + HALF_WIDTH) {
                if (k < 0 || k >= sourceTaps) continue
                val x = at - k
                acc += (source[offset + k] / 32768.0) * cutoff * sinc(cutoff * x) * window(x)
            }
            filters[into + m] = acc.toFloat() * fade(m)
        }
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
        buildLayout(if (thirdOrder) 3 else 1, fullSphere)
        applyWidth(stageWidthDegrees)
        if (!buildFilters(inputAudioFormat.sampleRate)) return AudioProcessor.AudioFormat.NOT_SET
        applyWidth(stageWidthDegrees)
        // Stereo in, stereo out. Unlike the upmix this changes no format, only the samples.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * format.bytesPerFrame)
        applyWidth(stageWidthDegrees)

        // One volatile read per buffer, and the whole head orientation resolved here rather than
        // per sample. Where the speakers have to be is a question about the head; where each
        // harmonic is worth what follows from that, and neither changes within a buffer.
        val pose = headPose
        if (pose != null && pose.size == 4) {
            poseBuffer[0] = pose[0]; poseBuffer[1] = pose[1]
            poseBuffer[2] = pose[2]; poseBuffer[3] = pose[3]
            rampYaw = 0f
        } else {
            // Yaw only: the same rotation expressed as a quaternion about the head's up axis, so
            // there is one encoding path rather than two.
            val target = headYawRadians
            val start = rampYaw
            var delta = wrapPi(target - start)
            // Nothing voluntary turns this fast. Anything that does is a dropped report or a
            // tracker that re-referenced itself, and spreading it over a buffer beats taking it
            // whole.
            val cap = MAX_YAW_RATE * frames / format.sampleRate
            delta = delta.coerceIn(-cap, cap)
            rampYaw = wrapPi(start + delta)
            val half = rampYaw * 0.5f
            poseBuffer[0] = cos(half); poseBuffer[1] = 0f
            poseBuffer[2] = 0f; poseBuffer[3] = sin(half)
        }

        // Walked across the buffer rather than stepped at the seam. A step here is a step in the
        // gain applied to every harmonic at once, fifty times a second, which is a buzz.
        encodeSpeakers(poseBuffer, targetLeft, targetRight)
        val inv = 1f / frames
        for (i in 0 until active) {
            stepLeft[i] = (targetLeft[i] - encodeLeft[i]) * inv
            stepRight[i] = (targetRight[i] - encodeRight[i]) * inv
        }

        val harmonics = FloatArray(active)
        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames) {
                val l = inputBuffer.short / 32768f
                val r = inputBuffer.short / 32768f
                render(l, r, harmonics) { v -> out.putShort(toPcm16(v)) }
            }

            C.ENCODING_PCM_FLOAT -> repeat(frames) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                render(l, r, harmonics) { v -> out.putFloat(v) }
            }
        }
        for (i in 0 until active) {
            encodeLeft[i] = targetLeft[i]
            encodeRight[i] = targetRight[i]
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
        framesProcessed += frames.toLong()
    }

    /**
     * One frame: encode to B-format, rotate, convolve, decode to two ears.
     *
     * The decode exploits the symmetry the filters were measured for. Mirroring a field about the
     * median plane flips the sign of every harmonic of negative degree and leaves the rest alone,
     * so the two ears share everything else: the harmonics of degree zero or more sum to something
     * common to both, and the negative ones are added for the left ear and subtracted for the
     * right. One convolution per harmonic rather than two, for exactly the same result.
     */
    private inline fun render(l: Float, r: Float, harmonics: FloatArray, put: (Float) -> Unit) {
        for (i in 0 until active) {
            harmonics[i] = l * encodeLeft[i] + r * encodeRight[i]
            encodeLeft[i] += stepLeft[i]
            encodeRight[i] += stepRight[i]
        }

        val idx = writeIndex
        for (i in 0 until active) rings[i * RING + idx] = harmonics[i]

        var common = 0f
        var side = 0f
        for (i in 0 until active) {
            val base = i * RING
            val fbase = i * taps
            var acc = 0f
            // Split at the wrap rather than masking every tap: two straight runs over contiguous
            // memory, which at sixteen harmonics and a hundred and sixty taps is the difference
            // between comfortable and not.
            val straight = min(taps, idx + 1)
            for (t in 0 until straight) acc += rings[base + idx - t] * filters[fbase + t]
            for (t in straight until taps) acc += rings[base + RING + idx - t] * filters[fbase + t]
            if (fromDifference[i]) side += acc else common += acc
        }
        writeIndex = (idx + 1) and MASK

        val c = common * gain
        val sd = side * gain
        put(softClip(c + sd))
        put(softClip(c - sd))
    }

    /** Radians into (-pi, pi], so a turn past the back of the head takes the short way. */
    private fun wrapPi(a: Float): Float = atan2(sin(a), cos(a))

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
        Arrays.fill(rings, 0f)
        writeIndex = 0
        framesProcessed = 0L
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
         * How much of the measured response to keep.
         *
         * The tail past this is thirty six decibels down and carries three hundredths of a percent
         * of the energy, and dropping it is a third off the arithmetic, which at ten harmonics and
         * forty eight thousand samples a second is worth having. Faded out rather than cut, so the
         * truncation does not ripple across the spectrum.
         */
        private const val KEEP_TAPS = 160
        private const val FADE_TAPS = 16

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f)

        private const val SQRT3 = 1.7320508f
        private const val SQRT15 = 3.8729835f
        private const val SQRT38 = 0.6123724f
        private const val SQRT58 = 0.7905694f

        /** Where the output stops being left alone. Below this the soft clip is identity. */
        private const val KNEE = 0.85f

        /** Faster than any voluntary head turn, so only a glitch is ever clamped. 720 deg/s. */
        const val MAX_YAW_RATE = 12.566371f

        /** The angle a stereo mix is actually made for, and so the only defensible default. */
        const val DEFAULT_STAGE_WIDTH = 30f

        /** Narrower than this is pointless and wider stops being a soundstage. */
        const val MIN_STAGE_WIDTH = 15f
        const val MAX_STAGE_WIDTH = 90f

        /**
         * ACN index, degree, SN3D value at zero elevation, in threes.
         *
         * Every harmonic with (n - |m|) odd is identically zero in the horizontal plane and is
         * left out entirely. Ordered by degree so [rotate] can reach the multiple angles by
         * recurrence.
         */
        private val ORDER_1_LAYOUT = floatArrayOf(
            0f, 0f, 1f,             // W
            1f, -1f, 1f,            // Y
            3f, 1f, 1f,             // X
        )

        /**
         * All sixteen, for when the head may tilt.
         *
         * Same order: degree ascending, so the multiple angles the horizontal path reaches by
         * recurrence stay in runs. The six that the horizontal layout leaves out are the ones with
         * (n - |m|) odd, which vanish at ear level and carry everything above and below it.
         */
        private val ORDER_3_FULL_LAYOUT = floatArrayOf(
            0f, 0f, 1f,
            2f, 0f, 1f,
            6f, 0f, -0.5f,
            12f, 0f, 1f,
            1f, -1f, 1f,
            3f, 1f, 1f,
            5f, -1f, 1f,
            7f, 1f, 1f,
            11f, -1f, -0.6123724f,
            13f, 1f, -0.6123724f,
            4f, -2f, 0.8660254f,
            8f, 2f, 0.8660254f,
            10f, -2f, 1f,
            14f, 2f, 1f,
            9f, -3f, 0.7905694f,
            15f, 3f, 0.7905694f,
        )

        private val ORDER_3_LAYOUT = floatArrayOf(
            0f, 0f, 1f,
            6f, 0f, -0.5f,
            1f, -1f, 1f,
            3f, 1f, 1f,
            11f, -1f, -0.6123724f,
            13f, 1f, -0.6123724f,
            4f, -2f, 0.8660254f,
            8f, 2f, 0.8660254f,
            9f, -3f, 0.7905694f,
            15f, 3f, 0.7905694f,
        )
    }
}
