/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A FloatArray that grows, so a stream of unknown length can be collected without boxing every
 * value the way a MutableList<Float> would.
 */
class FloatList(initialCapacity: Int = 1024) {
    private var data = FloatArray(max(initialCapacity, 16))

    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Float {
        if (index >= size) throw IndexOutOfBoundsException("$index of $size")
        return data[index]
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Constants and conversions shared by every stage that reads PCM. */
object Pcm {
    /** 16-bit full scale, so a sample divided by it lies in [-1, 1). */
    const val FULL_SCALE = 32768f

    /**
     * The decimation that brings [sampleRate] down to about 11 to 12 kHz.
     *
     * Four for 44.1 and 48 kHz, which is what YouTube serves. The analysis only looks at the
     * spectrum up to 5 kHz, so anything finer is spent for nothing, and a quarter of the samples is
     * a quarter of the FFTs. Rarer rates are brought to the same range so the mel bands keep fitting
     * under the decimated Nyquist.
     */
    fun decimationFor(sampleRate: Int): Int = max(1, sampleRate / 11_025)

    /** Power as a mean square of full-scale samples, in dB, with silence held at -200 dB. */
    fun powerDb(meanSquare: Double): Double =
        if (meanSquare <= 1e-20) -200.0 else 10.0 * kotlin.math.log10(meanSquare)
}

/**
 * A 4th-order Butterworth low-pass at 0.45 of the new rate, followed by keeping every
 * [factor]-th sample.
 *
 * The cutoff sits at 90% of the new Nyquist, so this lets some of the octave above it fold back.
 * That is acceptable for onsets and levels: a hi-hat folded down is still a hi-hat arriving at the
 * same moment, and nothing here reads pitch. In double, because two sections at 5 kHz on a 44.1
 * kHz stream keep their poles close to the unit circle.
 */
class Decimator(inputRate: Int, val factor: Int) {

    private val b0: DoubleArray
    private val b1: DoubleArray
    private val b2: DoubleArray
    private val a1: DoubleArray
    private val a2: DoubleArray
    private val x1 = DoubleArray(2)
    private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2)
    private val y2 = DoubleArray(2)
    private var phase = 0

    init {
        require(factor >= 1)
        val cutoff = 0.45 * inputRate / factor
        // The two pole pairs of a 4th-order Butterworth.
        val qs = doubleArrayOf(0.541_196_100_146_197, 1.306_562_964_876_376_5)
        b0 = DoubleArray(2); b1 = DoubleArray(2); b2 = DoubleArray(2); a1 = DoubleArray(2); a2 = DoubleArray(2)
        val w0 = 2.0 * PI * cutoff / inputRate
        val c = cos(w0)
        for (i in 0..1) {
            val alpha = sin(w0) / (2.0 * qs[i])
            val a0 = 1.0 + alpha
            b0[i] = (1.0 - c) / 2.0 / a0
            b1[i] = (1.0 - c) / a0
            b2[i] = (1.0 - c) / 2.0 / a0
            a1[i] = -2.0 * c / a0
            a2[i] = (1.0 - alpha) / a0
        }
    }

    /** Takes one input sample and adds an output sample to [out] on every [factor]-th call. */
    fun push(x: Double, out: FloatList) {
        if (factor == 1) {
            out.add(x.toFloat())
            return
        }
        var v = x
        for (i in 0..1) {
            val y = b0[i] * v + b1[i] * x1[i] + b2[i] * x2[i] - a1[i] * y1[i] - a2[i] * y2[i]
            x2[i] = x1[i]; x1[i] = v
            y2[i] = y1[i]; y1[i] = if (abs(y) < 1e-20) 0.0 else y
            v = y1[i]
        }
        if (phase == 0) out.add(v.toFloat())
        phase = if (phase + 1 == factor) 0 else phase + 1
    }
}

/**
 * What the front end hands to the analysis: the decimated mono signal, and for every hop of the
 * full-rate signal its mean square and its peak.
 *
 * A hop is [Spectrum.HOP] decimated samples, so the level streams and the spectral frames share
 * one clock: spectral frame t starts at hop t. The levels are measured at the full rate on every
 * channel, not on the mono copy, because headroom has to see the true peaks of either channel and
 * the content above 5 kHz that the copy has lost.
 */
class FrontEndResult(
    val sampleRate: Int,
    val channels: Int,
    val factor: Int,
    val frames: Long,
    val mono: FloatArray,
    val hopPower: FloatArray,
    val hopPeak: FloatArray,
    /** Each hop's peak through a 4th-order high-pass at 120 Hz and through a 2nd-order one at 400 Hz, when they were asked for. */
    val hopPeakAbove120: FloatArray? = null,
    val hopPeakAbove400: FloatArray? = null,
) {
    val decimatedRate: Double get() = sampleRate.toDouble() / factor
    val hopFrames: Int get() = Spectrum.HOP * factor
    val seconds: Double get() = frames.toDouble() / sampleRate
}

/**
 * Turns interleaved 16-bit PCM into [FrontEndResult] as it arrives, in blocks of any size, so a
 * decoder can feed it straight from its output and no full-rate copy of the region is ever kept.
 *
 * With [filteredPeaks], each hop's peak is also measured through the two high-passes a blend puts
 * on the incoming song, a 4th-order one at 120 Hz (the bass held back in a beat mix) and a
 * 2nd-order one at 400 Hz (where the smooth blend's sweep starts): taking the bass out of a master
 * limited at full scale can raise its peaks well above the master's own, and the headroom of a
 * blend has to know.
 */
class FrontEnd @JvmOverloads constructor(val sampleRate: Int, val channels: Int, filteredPeaks: Boolean = false) {

    init {
        require(sampleRate > 0 && channels > 0)
    }

    private val above120 = if (filteredPeaks) Array(2) { Biquad(channels) } else null
    private val above400 = if (filteredPeaks) Biquad(channels) else null
    private val hopPeak120 = if (filteredPeaks) FloatList(4096) else null
    private val hopPeak400 = if (filteredPeaks) FloatList(4096) else null
    private var hopMax120 = 0.0
    private var hopMax400 = 0.0

    init {
        above120?.let { sections ->
            val q = Biquad.qualities(4)
            for (i in sections.indices) sections[i].set(FilterKind.HIGH_PASS, 120.0, q[i], sampleRate)
        }
        above400?.set(FilterKind.HIGH_PASS, 400.0, Biquad.qualities(2)[0], sampleRate)
    }

    val factor = Pcm.decimationFor(sampleRate)
    private val hopFrames = Spectrum.HOP * factor
    private val decimator = Decimator(sampleRate, factor)
    private val mono = FloatList(1 shl 16)
    private val hopPower = FloatList(4096)
    private val hopPeak = FloatList(4096)
    private var frames = 0L

    private var hopSquares = 0.0
    private var hopMax = 0
    private var hopFill = 0

    /** Takes [frameCount] frames of interleaved [samples] starting at frame [offsetFrames]. */
    fun push(samples: ShortArray, offsetFrames: Int = 0, frameCount: Int = samples.size / channels - offsetFrames) {
        var i = offsetFrames * channels
        val inverse = 1.0 / (Pcm.FULL_SCALE * channels)
        val above120 = above120
        val above400 = above400
        repeat(frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                val s = samples[i++].toInt()
                sum += s
                hopSquares += s.toDouble() * s
                val a = if (s < 0) -s else s
                if (a > hopMax) hopMax = a
                if (above120 != null && above400 != null) {
                    var y = s.toDouble()
                    for (section in above120) y = section.process(y, c)
                    if (kotlin.math.abs(y) > hopMax120) hopMax120 = kotlin.math.abs(y)
                    val z = above400.process(s.toDouble(), c)
                    if (kotlin.math.abs(z) > hopMax400) hopMax400 = kotlin.math.abs(z)
                }
            }
            decimator.push(sum * inverse, mono)
            hopFill++
            if (hopFill == hopFrames) closeHop()
        }
        frames += frameCount
    }

    private fun closeHop() {
        val scale = 1.0 / (Pcm.FULL_SCALE.toDouble() * Pcm.FULL_SCALE * channels * hopFill)
        hopPower.add((hopSquares * scale).toFloat())
        hopPeak.add(hopMax / Pcm.FULL_SCALE)
        hopPeak120?.add((hopMax120 / Pcm.FULL_SCALE).toFloat())
        hopPeak400?.add((hopMax400 / Pcm.FULL_SCALE).toFloat())
        hopSquares = 0.0
        hopMax = 0
        hopMax120 = 0.0
        hopMax400 = 0.0
        hopFill = 0
    }

    /** Everything pushed so far. A last partial hop counts as a hop of its own. */
    fun result(): FrontEndResult {
        if (hopFill > 0) closeHop()
        return FrontEndResult(
            sampleRate, channels, factor, frames, mono.toArray(), hopPower.toArray(), hopPeak.toArray(),
            hopPeak120?.toArray(), hopPeak400?.toArray(),
        )
    }

    companion object {
        /** The whole of [samples] at once. */
        @JvmStatic
        @JvmOverloads
        fun of(
            samples: ShortArray, sampleRate: Int, channels: Int, fromFrame: Int = 0,
            frameCount: Int = samples.size / channels - fromFrame, filteredPeaks: Boolean = false,
        ): FrontEndResult {
            val front = FrontEnd(sampleRate, channels, filteredPeaks)
            front.push(samples, fromFrame, frameCount)
            return front.result()
        }
    }
}
