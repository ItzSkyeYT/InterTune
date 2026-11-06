/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Biquad
import com.dd3boh.outertune.mix.FilterKind
import com.dd3boh.outertune.mix.edit.Bands
import kotlin.math.abs
import kotlin.math.min

/**
 * Where one side of a change takes its music from: frame [t] of its own clock, one sample per
 * channel in full-scale units (a 16-bit sample keeps its value). Frames outside the music are
 * silence.
 */
fun interface FrameSource {
    fun frame(t: Long, into: DoubleArray)
}

/** Interleaved 16-bit frames as a [FrameSource], frame 0 of [pcm] at frame [startFrame] of the clock. */
class PcmSource(
    private val pcm: ShortArray,
    private val channels: Int,
    private val frames: Long = (pcm.size / channels).toLong(),
    private val startFrame: Long = 0,
) : FrameSource {
    override fun frame(t: Long, into: DoubleArray) {
        val i = t - startFrame
        if (i < 0 || i >= frames) {
            for (c in 0 until channels) into[c] = 0.0
            return
        }
        val base = (i * channels).toInt()
        for (c in 0 until channels) into[c] = pcm[base + c].toDouble()
    }
}

/**
 * A stream cut into lows, mids and highs that always add back to the stream itself.
 *
 * Each band filter is a second-order Butterworth run forward and then backward over the stream.
 * That squares its magnitude into a Linkwitz-Riley's (6 dB down at the edge, 24 dB an octave past
 * it) and leaves no phase shift at all, so the bands are phase-true to each other and to the
 * stream: any mix of their gains has no notch and no bump at an edge, which a crossover run one
 * way only cannot give. The mids are the stream less the other two, so the three add back to the
 * stream whatever the filters do at the start of the music, and with no phase shift that rest is
 * a clean band-pass.
 *
 * Going backward needs what comes after, so the stream is taken in chunks of [CHUNK] frames on a
 * grid from [origin], each one run backward from rest [LOOK] frames past its end. The filters'
 * memory has fallen far under the last bit of a 16-bit sample by the time it reaches the chunk,
 * and every frame comes out the same whatever blocks it is asked for in.
 * The forward pass runs on from [origin] once, so frames are asked for in order; going back
 * starts it again.
 */
class BandSplit(
    private val source: FrameSource,
    private val channels: Int,
    sampleRate: Int,
    private val origin: Long,
    lowHz: Double = Bands.LOWS_BELOW_HZ,
    highHz: Double = Bands.HIGHS_ABOVE_HZ,
) {
    private val low = Biquad.coefficients(FilterKind.LOW_PASS, lowHz, BUTTERWORTH_Q, sampleRate)
    private val high = Biquad.coefficients(FilterKind.HIGH_PASS, min(highHz, 0.45 * sampleRate), BUTTERWORTH_Q, sampleRate)

    private val span = CHUNK + LOOK

    // The window of the chunk being read: the stream, and its forward-filtered lows and highs, over
    // the chunk and the LOOK frames after it.
    private val x = DoubleArray(span * channels)
    private val fl = DoubleArray(span * channels)
    private val fh = DoubleArray(span * channels)

    // The chunk's bands, once both ways.
    private val lows = DoubleArray(CHUNK * channels)
    private val highs = DoubleArray(CHUNK * channels)

    // The forward filters' state, per channel: two inputs back, two outputs back.
    private val lowState = DoubleArray(4 * channels)
    private val highState = DoubleArray(4 * channels)
    private val frame = DoubleArray(channels)

    /** The chunk held, -1 for none; frames from [origin] + chunk * CHUNK. */
    private var chunk = -1L

    /** How far the forward pass has got, in frames from [origin]. */
    private var forwardTo = 0L

    /**
     * Frame [t] of the stream and its three bands, one sample per channel each. [t] must be at or
     * after [origin]; frames before it are silence.
     */
    fun bands(t: Long, stream: DoubleArray, lowsOut: DoubleArray, midsOut: DoubleArray, highsOut: DoubleArray) {
        val rel = t - origin
        if (rel < 0) {
            for (c in 0 until channels) {
                stream[c] = 0.0; lowsOut[c] = 0.0; midsOut[c] = 0.0; highsOut[c] = 0.0
            }
            return
        }
        val k = Math.floorDiv(rel, CHUNK.toLong())
        if (k != chunk) load(k)
        val i = (rel - k * CHUNK).toInt()
        for (c in 0 until channels) {
            val s = x[i * channels + c]
            val l = lows[i * channels + c]
            val h = highs[i * channels + c]
            stream[c] = s
            lowsOut[c] = l
            highsOut[c] = h
            midsOut[c] = s - l - h
        }
    }

    private fun load(k: Long) {
        if (k < chunk || chunk < 0) restart()
        while (chunk < k) advance()
        backward()
    }

    private fun restart() {
        lowState.fill(0.0)
        highState.fill(0.0)
        forwardTo = 0
        chunk = -1
        // The first window: the forward pass over the first chunk and its look ahead.
        forward(0, span)
        chunk = 0
    }

    /** Moves the window one chunk on: the look ahead becomes the start, and a chunk more is run forward. */
    private fun advance() {
        val keep = LOOK * channels
        val shift = CHUNK * channels
        System.arraycopy(x, shift, x, 0, keep)
        System.arraycopy(fl, shift, fl, 0, keep)
        System.arraycopy(fh, shift, fh, 0, keep)
        forward(LOOK, span)
        chunk++
    }

    /** Runs the forward pass from the frame after [forwardTo] into window places [from] until [to]. */
    private fun forward(from: Int, to: Int) {
        for (i in from until to) {
            source.frame(origin + forwardTo, frame)
            forwardTo++
            for (c in 0 until channels) {
                val v = frame[c]
                x[i * channels + c] = v
                fl[i * channels + c] = step(low, lowState, c, v)
                fh[i * channels + c] = step(high, highState, c, v)
            }
        }
    }

    /** The window run backward from rest at its far end, the chunk's part kept. */
    private fun backward() {
        for (c in 0 until channels) {
            var lx1 = 0.0; var lx2 = 0.0; var ly1 = 0.0; var ly2 = 0.0
            var hx1 = 0.0; var hx2 = 0.0; var hy1 = 0.0; var hy2 = 0.0
            for (i in span - 1 downTo 0) {
                val lv = fl[i * channels + c]
                var ly = low[0] * lv + low[1] * lx1 + low[2] * lx2 - low[3] * ly1 - low[4] * ly2
                if (abs(ly) < TINY) ly = 0.0
                lx2 = lx1; lx1 = lv; ly2 = ly1; ly1 = ly
                val hv = fh[i * channels + c]
                var hy = high[0] * hv + high[1] * hx1 + high[2] * hx2 - high[3] * hy1 - high[4] * hy2
                if (abs(hy) < TINY) hy = 0.0
                hx2 = hx1; hx1 = hv; hy2 = hy1; hy1 = hy
                if (i < CHUNK) {
                    lows[i * channels + c] = ly
                    highs[i * channels + c] = hy
                }
            }
        }
    }

    private fun step(k: DoubleArray, s: DoubleArray, c: Int, v: Double): Double {
        val o = 4 * c
        var y = k[0] * v + k[1] * s[o] + k[2] * s[o + 1] - k[3] * s[o + 2] - k[4] * s[o + 3]
        if (abs(y) < TINY) y = 0.0
        s[o + 1] = s[o]
        s[o] = v
        s[o + 3] = s[o + 2]
        s[o + 2] = y
        return y
    }

    companion object {
        /** Frames split at a time. */
        const val CHUNK = 4096

        /**
         * How far past a chunk its backward pass starts. The slowest filter, the 120 Hz low-pass,
         * falls by about 1.1% a frame at 48 kHz, so its memory is down to 1e-10 after this many:
         * a few millionths of the last bit of a full-scale sample.
         */
        const val LOOK = 2048

        private const val BUTTERWORTH_Q = 0.707_106_781_186_547_6

        /** A filter's output this small is taken as silence, so a band left on silence never slows down with denormals. */
        private const val TINY = 1e-20
    }
}
