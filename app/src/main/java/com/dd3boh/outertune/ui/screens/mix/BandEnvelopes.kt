/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.Biquad
import com.dd3boh.outertune.mix.FilterKind
import com.dd3boh.outertune.mix.edit.Bands
import com.dd3boh.outertune.mix.edit.Lane
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A window of a song as three bands, lows, mids and highs, each as its peak level every
 * [hopMs] ms: what the canvas draws a song's waveform from, coloured by band. One pass over the
 * window through Linkwitz-Riley crossovers at the bands' edges, the stored result about a
 * thousandth of what was read.
 */
class BandEnvelopes(
    val hopMs: Double,
    val lows: FloatArray,
    val mids: FloatArray,
    val highs: FloatArray,
) {
    init {
        require(lows.size == mids.size && mids.size == highs.size)
    }

    val size: Int get() = lows.size

    /** The level the song's loud parts reach: its 98th percentile of the three bands summed, never under a floor. */
    val reference: Float by lazy {
        if (size == 0) return@lazy 1f
        val sums = FloatArray(size) { lows[it] + mids[it] + highs[it] }
        sums.sort()
        max(sums[min(size - 1, (size * 0.98).toInt())], 0.02f)
    }

    /** Each band's own typical level when it plays: its 75th percentile, for whether a band has music. */
    private val typical: Map<Lane, Float> by lazy {
        mapOf(Lane.LOWS to lows, Lane.MIDS to mids, Lane.HIGHS to highs).mapValues { (_, v) ->
            if (v.isEmpty()) 0f else v.copyOf().also { it.sort() }[min(v.size - 1, (v.size * 0.75).toInt())]
        }
    }

    fun band(lane: Lane): FloatArray = when (lane) {
        Lane.LOWS, Lane.BASS -> lows
        Lane.HIGHS, Lane.DRUMS -> highs
        else -> mids
    }

    /** The loudest of [lane] over [fromMs] to [toMs], counted from the window's start; 0 outside it. */
    fun peak(lane: Lane, fromMs: Double, toMs: Double): Float {
        val v = band(lane)
        if (v.isEmpty()) return 0f
        val lo = (fromMs / hopMs).toInt()
        val hi = (toMs / hopMs).toInt()
        if (hi < 0 || lo >= v.size) return 0f
        var p = 0f
        for (i in max(0, lo)..min(v.size - 1, max(lo, hi))) if (v[i] > p) p = v[i]
        return p
    }

    /** Whether [lane] has music at [ms]: within 12 dB of how it usually plays. */
    fun active(lane: Lane, ms: Double): Boolean {
        val level = peak(lane, ms - hopMs, ms + hopMs)
        return level > (typical[lane] ?: 0f) * 0.25f && level > 0.003f
    }

    fun write(file: File) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeDouble(hopMs)
            out.writeInt(size)
            for (v in listOf(lows, mids, highs)) for (x in v) out.writeFloat(x)
        }
    }

    companion object {
        private const val MAGIC = 0x4D584231 // "MXB1"

        /** One hop: 10 ms, a pixel's worth at the editor's deepest zoom. */
        const val HOP_MS = 10.0

        fun read(file: File): BandEnvelopes? = try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("not band envelopes")
                val hop = input.readDouble()
                val n = input.readInt()
                if (n !in 0..10_000_000) throw IOException("$n hops")
                val bands = List(3) { FloatArray(n) { input.readFloat() } }
                BandEnvelopes(hop, bands[0], bands[1], bands[2])
            }
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * Measures [BandEnvelopes] from interleaved 16-bit PCM pushed in blocks of any size, so a window
 * is measured as it is decoded or read back, never held whole as floats.
 */
class BandMeter(private val sampleRate: Int, private val channels: Int) {
    // Each a 4th-order Linkwitz-Riley: two 2nd-order Butterworth sections alike. The mids are a
    // band of their own, not what the other two leave: a filter moves the phase of what it
    // passes, and the song minus its low-passed lows still holds most of a bass note.
    private val low = Array(2) { Biquad(channels) }
    private val high = Array(2) { Biquad(channels) }
    private val midLow = Array(2) { Biquad(channels) }
    private val midHigh = Array(2) { Biquad(channels) }
    private val hopFrames = max(1, (sampleRate * BandEnvelopes.HOP_MS / 1000).toInt())
    private val lows = ArrayList<Float>()
    private val mids = ArrayList<Float>()
    private val highs = ArrayList<Float>()
    private var fill = 0
    private var pl = 0.0
    private var pm = 0.0
    private var ph = 0.0

    init {
        val q = Biquad.qualities(2)[0]
        val top = minOf(Bands.HIGHS_ABOVE_HZ, 0.45 * sampleRate)
        for (i in 0..1) {
            low[i].set(FilterKind.LOW_PASS, Bands.LOWS_BELOW_HZ, q, sampleRate)
            high[i].set(FilterKind.HIGH_PASS, top, q, sampleRate)
            midLow[i].set(FilterKind.HIGH_PASS, Bands.LOWS_BELOW_HZ, q, sampleRate)
            midHigh[i].set(FilterKind.LOW_PASS, top, q, sampleRate)
        }
    }

    fun push(samples: ShortArray, frames: Int) {
        var i = 0
        repeat(frames) {
            for (c in 0 until channels) {
                val x = samples[i++] / 32768.0
                var l = x
                for (s in low) l = s.process(l, c)
                var h = x
                for (s in high) h = s.process(h, c)
                var m = x
                for (s in midLow) m = s.process(m, c)
                for (s in midHigh) m = s.process(m, c)
                if (abs(l) > pl) pl = abs(l)
                if (abs(m) > pm) pm = abs(m)
                if (abs(h) > ph) ph = abs(h)
            }
            if (++fill == hopFrames) close()
        }
    }

    private fun close() {
        lows += pl.toFloat()
        mids += pm.toFloat()
        highs += ph.toFloat()
        pl = 0.0; pm = 0.0; ph = 0.0
        fill = 0
    }

    fun result(): BandEnvelopes {
        if (fill > 0) close()
        return BandEnvelopes(1000.0 * hopFrames / sampleRate, lows.toFloatArray(), mids.toFloatArray(), highs.toFloatArray())
    }
}
