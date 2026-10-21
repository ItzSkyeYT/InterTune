/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.Peaks
import com.dd3boh.outertune.mix.Spectrum
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A change rendered ahead of time as it would play, for [ProofCheck] to judge before it is used.
 * The renderer is the engine's (the lane mixer on the phone, the trial's on the PC); Auto only
 * reads what it made.
 */
class Proof(
    val sampleRate: Int,
    /** The change as heard, interleaved stereo at full scale 1.0, after the shared headroom and before any limit. */
    val mix: FloatArray,
    /** The frames of [mix] the change spans: from the first frame that differs from plain playback to the last of the outgoing song, its echo included. */
    val changeFrom: Int,
    val changeTo: Int,
    /**
     * Where both songs' beats are heard together: the outgoing song as stretched, before its
     * curves and filters, and the incoming song as it plays, both mono, from frame [lockFrom] of
     * [mix]. Null for a change whose beats never overlap.
     */
    val lockA: FloatArray? = null,
    val lockB: FloatArray? = null,
    val lockFrom: Int = 0,
    /** The incoming song's beats over the lock, in frames of [lockA]. */
    val lockBeats: DoubleArray = DoubleArray(0),
) {
    fun interface Renderer {
        fun render(pick: AutoPick): Proof?
    }
}

/**
 * Whether a rendered change is clean enough to play: no sample past full scale, the two songs'
 * kicks within a few ms of each other wherever both are heard, no silence opened inside the
 * change, and the level never sinking well under the straight line from the outgoing song's level
 * to the incoming song's.
 */
object ProofCheck {

    class Limits(
        val maxClipped: Int = 0,
        /** On bands, DESIGN.md 7's target: 10 ms on average, 20 ms at worst. */
        val maxDriftMeanMs: Double = 10.0,
        val maxDriftWorstMs: Double = 20.0,
        /** A stretch this quiet and this long inside a change is dead air. */
        val silenceDb: Double = -50.0,
        val maxSilenceMs: Double = 300.0,
        /** How far the 3 s level may sink under the line from one song's level to the other's. */
        val maxDipDb: Double = 3.0,
    )

    class Verdict(
        val clipped: Int,
        val peakDb: Double,
        val driftMeanMs: Double?,
        val driftWorstMs: Double?,
        /** How many beats the drift was measured on. */
        val lockBeats: Int,
        val longestSilenceMs: Double,
        /** The deepest the 3 s level sank under the line from A's level to B's, and the most it rose over it, in dB. */
        val dipDb: Double,
        val bumpDb: Double,
        val passes: Boolean,
        val why: String,
    )

    /** Kicks are matched to a beat within this much of it. */
    const val LOCK_WINDOW_MS = 50.0

    fun check(p: Proof, limits: Limits = Limits()): Verdict {
        val rate = p.sampleRate
        val frames = p.mix.size / 2
        var clipped = 0
        var peak = 0.0
        for (v in p.mix) {
            val a = abs(v.toDouble())
            if (a > 1.0) clipped++
            if (a > peak) peak = a
        }
        val (driftMean, driftWorst, beats) = drift(p)
        val silence = longestSilence(p.mix, rate, max(0, p.changeFrom), min(frames, p.changeTo), limits.silenceDb)
        val (dip, bump) = loudness(p.mix, rate, p.changeFrom, p.changeTo)
        val problems = ArrayList<String>()
        if (clipped > limits.maxClipped) problems += "$clipped samples past full scale"
        if (driftMean != null && driftWorst != null && (driftMean > limits.maxDriftMeanMs || driftWorst > limits.maxDriftWorstMs))
            problems += "kicks %.1f ms apart on average, %.1f at worst".format(driftMean, driftWorst)
        if (silence > limits.maxSilenceMs) problems += "%.0f ms of silence".format(silence)
        if (-dip > limits.maxDipDb) problems += "the level sinks %.1f dB".format(-dip)
        return Verdict(
            clipped = clipped,
            peakDb = 20 * log10(max(peak, 1e-9)),
            driftMeanMs = driftMean,
            driftWorstMs = driftWorst,
            lockBeats = beats,
            longestSilenceMs = silence,
            dipDb = dip,
            bumpDb = bump,
            passes = problems.isEmpty(),
            why = if (problems.isEmpty()) "clean" else problems.joinToString(", "),
        )
    }

    /**
     * How far the outgoing song's kicks sit from the incoming song's, heard in the audio and not
     * read off fitted lines: at each of the incoming song's beats, the outgoing song's low onset
     * nearest it within 50 ms against the incoming song's own there, or the beat itself where the
     * incoming song has none; mean, worst and how many beats. Nulls where fewer than four beats
     * have a kick from the outgoing song.
     */
    fun drift(p: Proof): Triple<Double?, Double?, Int> {
        val a = p.lockA ?: return Triple(null, null, 0)
        val b = p.lockB ?: return Triple(null, null, 0)
        if (p.lockBeats.size < 4) return Triple(null, null, 0)
        val rate = p.sampleRate
        val aKicks = lowOnsets(a, rate)
        val bKicks = lowOnsets(b, rate)
        val window = LOCK_WINDOW_MS / 1000.0 * rate
        val gaps = ArrayList<Double>()
        for (beat in p.lockBeats) {
            val ka = aKicks.filter { abs(it - beat) < window }.minByOrNull { abs(it - beat) } ?: continue
            val kb = bKicks.filter { abs(it - beat) < window }.minByOrNull { abs(it - beat) }
            gaps += abs(ka - (kb ?: beat)) * 1000.0 / rate
        }
        if (gaps.size < 4) return Triple(null, null, gaps.size)
        return Triple(gaps.average(), gaps.max(), gaps.size)
    }

    /** The low onsets of a mono signal (kicks and bass notes), in frames. */
    private fun lowOnsets(mono: FloatArray, rate: Int): List<Double> {
        val shorts = ShortArray(mono.size) { (mono[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
        val spectrum = Spectrum.of(FrontEnd.of(shorts, rate, 1))
        return Peaks.of(spectrum.lowOnset, 1.5f, 8).map { spectrum.frameToSample(it.frame) }
    }

    /** The longest run of 50 ms windows under [db] between frames [from] and [to], in ms. */
    private fun longestSilence(mix: FloatArray, rate: Int, from: Int, to: Int, db: Double): Double {
        val window = max(1, rate / 20)
        val threshold = Math.pow(10.0, db / 10)
        var run = 0
        var longest = 0
        var f = from
        while (f + window <= to) {
            var sum = 0.0
            for (i in f until f + window) {
                val l = mix[2 * i].toDouble()
                val r = mix[2 * i + 1].toDouble()
                sum += (l * l + r * r) / 2
            }
            if (sum / window < threshold) {
                run++
                longest = max(longest, run)
            } else run = 0
            f += window
        }
        return longest * 50.0
    }

    /**
     * The 3 s level through the change against the straight line from the outgoing song's level
     * over the 6 s before it to the incoming song's over the 6 s after it: the deepest dip and the
     * highest rise, in dB. Windows every half second; where the mix runs out, what is there.
     */
    fun loudness(mix: FloatArray, rate: Int, from: Int, to: Int): Pair<Double, Double> {
        val frames = mix.size / 2
        fun level(a: Int, b: Int): Double {
            val lo = a.coerceIn(0, frames)
            val hi = b.coerceIn(lo, frames)
            if (hi - lo < rate / 10) return Double.NaN
            var sum = 0.0
            for (i in lo until hi) {
                val l = mix[2 * i].toDouble()
                val r = mix[2 * i + 1].toDouble()
                sum += (l * l + r * r) / 2
            }
            return 10 * log10(max(sum / (hi - lo), 1e-12))
        }
        val before = level(from - 6 * rate, from)
        val after = level(to, to + 6 * rate)
        if (before.isNaN() || after.isNaN() || to <= from) return 0.0 to 0.0
        var dip = 0.0
        var bump = 0.0
        val half = rate / 2
        var centre = from
        while (centre <= to) {
            val l = level(centre - 3 * rate / 2, centre + 3 * rate / 2)
            if (!l.isNaN()) {
                val x = (centre - from).toDouble() / (to - from)
                val line = before + (after - before) * x
                dip = min(dip, l - line)
                bump = max(bump, l - line)
            }
            centre += half
        }
        return dip to bump
    }
}
