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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer: ten bands an octave apart, from 31 Hz to 16 kHz, each up to 12 dB up or down.
 *
 * In the app rather than Android's, so it works the same on every phone, before spatial audio,
 * and with nothing asked of the phone's own effects. Three things keep it honest, the ways an
 * audio stage goes wrong here:
 *  - Flat or off, the samples pass through untouched, bit for bit.
 *  - A boost never makes it louder: the whole signal is lowered by the curve's highest point
 *    (the headroom, worked out from the curve itself, overlaps of neighbouring bands included),
 *    so a bass boost is the rest made quieter rather than the bass clipping.
 *  - Each band is a peaking filter of the usual kind (the Audio EQ Cookbook's), measured band by
 *    band in EqualizerAudioProcessorTest.
 */
class EqualizerAudioProcessor : BaseAudioProcessor() {

    /** What to play with: written from the player's side, read once per buffer on the audio thread. */
    @Volatile
    var settings: Settings = Settings.OFF

    data class Settings(val enabled: Boolean, val gainsDb: List<Float>) {
        init {
            require(gainsDb.size == BANDS.size) { "${gainsDb.size} gains for ${BANDS.size} bands" }
        }

        /** Whether it changes nothing: off, or every band at nought. */
        val isFlat: Boolean get() = !enabled || gainsDb.all { abs(it) < 0.05f }

        companion object {
            val OFF = Settings(false, List(BANDS.size) { 0f })
        }
    }

    private var built: Settings? = null
    private var builtRate = 0
    private var filters: Array<Biquad> = emptyArray()
    private var preamp = 1.0
    /** The headroom in use: it glides to [preamp] over a buffer rather than jumping, which would click. */
    private var applied = 1.0
    /** Two values of state per band per channel, for the transposed direct form II. */
    private var state = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        // As GainAudioProcessor: 16 bit and float are what reach here; anything else is left
        // untouched by declaring it unhandled.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val wanted = settings
        if (wanted.isFlat) {
            output.put(inputBuffer)
            output.flip()
            built = null
            return
        }
        val channels = inputAudioFormat.channelCount
        if (wanted != built || builtRate != inputAudioFormat.sampleRate || state.size != filters.size * channels * 2) {
            build(wanted, inputAudioFormat.sampleRate, channels)
        }
        val bytes = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val frames = maxOf(1, size / (bytes * channels))
        val step = (preamp - applied) / frames
        var gain = applied
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = position
                var channel = 0
                while (i < limit - 1) {
                    val x = inputBuffer.getShort(i) / SHORT_SCALE
                    val y = run(x, channel, gain) * SHORT_SCALE
                    output.putShort(y.roundToInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort())
                    channel = if (channel + 1 == channels) 0.also { gain += step } else channel + 1
                    i += 2
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                var i = position
                var channel = 0
                while (i < limit - 3) {
                    output.putFloat(run(inputBuffer.getFloat(i).toDouble(), channel, gain).coerceIn(-1.0, 1.0).toFloat())
                    channel = if (channel + 1 == channels) 0.also { gain += step } else channel + 1
                    i += 4
                }
            }

            else -> output.put(inputBuffer)
        }
        applied = preamp
        inputBuffer.position(limit)
        output.flip()
    }

    /** One sample of one channel through [gain] and every band. */
    private fun run(input: Double, channel: Int, gain: Double): Double {
        var x = input * gain
        var s = channel * filters.size * 2
        for (f in filters) {
            val y = f.b0 * x + state[s]
            state[s] = f.b1 * x - f.a1 * y + state[s + 1]
            state[s + 1] = f.b2 * x - f.a2 * y
            x = y
            s += 2
        }
        return x
    }

    private fun build(wanted: Settings, rate: Int, channels: Int) {
        val sameShape = built != null && builtRate == rate && state.size == Eq.filters(wanted.gainsDb, rate.toDouble()).size * channels * 2
        filters = Eq.filters(wanted.gainsDb, rate.toDouble()).toTypedArray()
        preamp = Eq.dbToGain(-Eq.headroomDb(filters.toList(), rate.toDouble()))
        // A slider moving changes only the gains: the filters keep their state, or every step of
        // a drag would start them from silence and be heard as a click. Another rate, another
        // layout or the curve coming back from flat starts them afresh.
        if (!sameShape) {
            state = DoubleArray(filters.size * channels * 2)
            applied = preamp
        }
        built = wanted
        builtRate = rate
    }

    override fun onFlush() {
        state.fill(0.0)
    }

    override fun onReset() {
        built = null
        filters = emptyArray()
        state = DoubleArray(0)
    }

    /** One band's filter, its coefficients divided by a0. */
    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** The arithmetic, apart so that it can be measured. */
    object Eq {
        /** An octave's worth of bandwidth for each band, so neighbours meet near half their gain. */
        const val Q = 1.41

        fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)

        /**
         * The bands' filters at [rate], one for every band: a band at nought is a filter that passes
         * everything as it is, kept so each band's state stays in its place while a slider moves.
         * Only a band too near the top of the range for this rate is left out.
         */
        fun filters(gainsDb: List<Float>, rate: Double): List<Biquad> =
            BANDS.zip(gainsDb).filter { (hz, _) -> hz < rate * 0.45 }.map { (hz, db) -> peaking(hz, db.toDouble(), Q, rate) }

        /** A peaking filter, as in Robert Bristow-Johnson's Audio EQ Cookbook. */
        fun peaking(hz: Double, gainDb: Double, q: Double, rate: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * hz / rate
            val alpha = sin(w0) / (2 * q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = (1 + alpha * a) / a0, b1 = (-2 * cosW0) / a0, b2 = (1 - alpha * a) / a0,
                a1 = (-2 * cosW0) / a0, a2 = (1 - alpha / a) / a0,
            )
        }

        /** How loud a sine at [hz] comes out of [filters] in a row, in dB. */
        fun responseDb(filters: List<Biquad>, hz: Double, rate: Double): Double {
            val w = 2 * PI * hz / rate
            var total = 0.0
            for (f in filters) {
                // |b0 + b1 e^-jw + b2 e^-2jw| / |1 + a1 e^-jw + a2 e^-2jw|
                val nr = f.b0 + f.b1 * cos(w) + f.b2 * cos(2 * w)
                val ni = -(f.b1 * sin(w) + f.b2 * sin(2 * w))
                val dr = 1 + f.a1 * cos(w) + f.a2 * cos(2 * w)
                val di = -(f.a1 * sin(w) + f.a2 * sin(2 * w))
                total += 20 * log10(sqrt(nr * nr + ni * ni) / sqrt(dr * dr + di * di))
            }
            return total
        }

        /** The curve's highest point between 20 Hz and 20 kHz, or nought when nothing is raised. */
        fun headroomDb(filters: List<Biquad>, rate: Double): Double {
            if (filters.isEmpty()) return 0.0
            val top = minOf(20_000.0, rate * 0.49)
            var peak = 0.0
            var hz = 20.0
            while (hz <= top) {
                peak = max(peak, responseDb(filters, hz, rate))
                hz *= 1.02
            }
            return peak
        }
    }

    companion object {
        /** The bands' centres, an octave apart. */
        val BANDS = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

        /** How far a band goes, up or down. */
        const val MAX_DB = 12f

        private const val SHORT_SCALE = 32767.0
        private const val SHORT_MIN = -32768
        private const val SHORT_MAX = 32767
    }
}
