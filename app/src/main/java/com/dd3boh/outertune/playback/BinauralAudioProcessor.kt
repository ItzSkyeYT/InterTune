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

    /** [factor] folded together with the speaker angle. Recomputed when the width changes. */
    private var encode = FloatArray(0)

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
    private fun buildLayout(order: Int) {
        if (order == appliedOrder) return
        appliedOrder = order

        val table = if (order >= 3) ORDER_3_LAYOUT else ORDER_1_LAYOUT
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
        if (clamped == appliedWidth) return
        appliedWidth = clamped
        val theta = clamped * PI.toFloat() / 180f
        encode = FloatArray(active)
        for (i in 0 until active) {
            val m = abs(degree[i]) * theta
            encode[i] = factor[i] * if (fromDifference[i]) sin(m) else cos(m)
        }
        if (taps > 0) gain = computeGain()
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
                if (fromDifference[i]) continue
                monoPath += 2f * encode[i] * filters[i * taps + t]
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
        buildLayout(if (thirdOrder) 3 else 1)
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

        // One volatile read per buffer. What it returns is where the head is; where the rotation
        // has actually got to is [rampYaw], and the gap is closed across this buffer.
        val target = headYawRadians
        val start = rampYaw
        var yaw = start
        var step = 0f
        if (target != 0f || start != 0f) {
            // Shortest way round. Without the wrap a head crossing the back of its own field takes
            // the stage the long way, three hundred and fifty degrees in twenty milliseconds.
            var delta = wrapPi(target - start)
            // Nothing voluntary turns this fast. Anything that does is a dropped report or a
            // tracker that re-referenced itself, and spreading it over a buffer beats taking it
            // whole.
            val cap = MAX_YAW_RATE * frames / format.sampleRate
            delta = delta.coerceIn(-cap, cap)
            step = delta / frames
            rampYaw = wrapPi(start + delta)
        }

        val harmonics = FloatArray(active)
        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames) {
                val l = inputBuffer.short / 32768f
                val r = inputBuffer.short / 32768f
                render(l, r, yaw, harmonics) { v -> out.putShort(toPcm16(v)) }
                yaw += step
            }

            C.ENCODING_PCM_FLOAT -> repeat(frames) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                render(l, r, yaw, harmonics) { v -> out.putFloat(v) }
                yaw += step
            }
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
    private inline fun render(l: Float, r: Float, yaw: Float, harmonics: FloatArray, put: (Float) -> Unit) {
        val sum = l + r
        val diff = l - r
        for (i in 0 until active) {
            harmonics[i] = (if (fromDifference[i]) diff else sum) * encode[i]
        }
        rotate(harmonics, yaw)

        val idx = writeIndex
        for (i in 0 until active) rings[i * RING + idx] = harmonics[i]

        var common = 0f
        var side = 0f
        for (i in 0 until active) {
            val base = i * RING
            val fbase = i * taps
            var acc = 0f
            // Split at the wrap rather than masking every tap: two straight runs over contiguous
            // memory, which at ten harmonics and two hundred and fifty six taps is the difference
            // between comfortable and not.
            val straight = min(taps, idx + 1)
            for (t in 0 until straight) acc += rings[base + idx - t] * filters[fbase + t]
            for (t in straight until taps) acc += rings[base + RING + idx - t] * filters[fbase + t]
            if (fromDifference[i]) side += acc else common += acc
        }
        writeIndex = (idx + 1) and MASK

        val c = common * gain
        val s = side * gain
        put(softClip(c + s))
        put(softClip(c - s))
    }

    /**
     * Turn the whole field by [yaw], the opposite way to the head.
     *
     * A horizontal rotation leaves the harmonics of degree zero alone and turns each remaining
     * pair by its own degree times the angle, exactly, with no interpolation anywhere. That is the
     * entire reason for encoding to ambisonics rather than convolving the speakers directly, and
     * it stays this cheap at any order.
     */
    private inline fun rotate(harmonics: FloatArray, yaw: Float) {
        if (yaw == 0f) return
        var cm = cos(yaw)
        var sm = sin(yaw)
        val c1 = cm
        val s1 = sm
        var lastDegree = 1
        for (p in pairDegree.indices) {
            val m = pairDegree[p]
            // Degrees come out of the layout in ascending runs, so the multiple angles are reached
            // by recurrence rather than by calling cos and sin again per harmonic.
            while (lastDegree < m) {
                val nc = cm * c1 - sm * s1
                sm = sm * c1 + cm * s1
                cm = nc
                lastDegree++
            }
            val si = pairSin[p]
            val ci = pairCos[p]
            val s = harmonics[si]
            val c = harmonics[ci]
            harmonics[ci] = c * cm + s * sm
            harmonics[si] = s * cm - c * sm
        }
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
