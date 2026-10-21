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
    /**
     * Each song as it would sound at that moment without the change, interleaved stereo and on
     * the same frames as [mix]: the outgoing song as stretched, the incoming song as it plays.
     * What the level of the mix is held against.
     */
    val plainA: FloatArray? = null,
    val plainB: FloatArray? = null,
) {
    fun interface Renderer {
        fun render(pick: AutoPick): Proof?
    }
}

/**
 * Whether a rendered change is clean enough to play: no sample past full scale, the two songs'
 * kicks within a few ms of each other wherever both are heard, no dead air inside the change, and
 * the mix never sagging well under both songs as each would sound there on its own.
 *
 * The sag is measured as people hear level: K-weighted (ITU-R BS.1770) over 3 s, every half
 * second through the change. A straight line from one song's level to the other's, as first
 * planned, called every quiet intro after a loud ending a dip, which is the song and not the mix;
 * held against each song at that very moment, only what the change itself takes away counts: a
 * headroom pulled too far, a song taken out before the other is in, two filters at once. The line
 * is still worked out, and reported, as how far the level moves.
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
        /** How far the mix's 3 s loudness may sink under the quieter of the two songs as each would sound there. */
        val maxSagDb: Double = 6.0,
    )

    class Verdict(
        val clipped: Int,
        val peakDb: Double,
        val driftMeanMs: Double?,
        val driftWorstMs: Double?,
        /** How many beats the drift was measured on. */
        val lockBeats: Int,
        val longestSilenceMs: Double,
        /** The deepest the mix sank under the quieter of the two songs as each would sound there, in dB; 0 where it never did. */
        val sagDb: Double,
        /** The 3 s loudness against the straight line from the outgoing song's level before the change to the incoming song's after it: the deepest dip and the highest rise, in dB. */
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
        val k = Loudness(rate)
        val mixL = k.shortTerm(p.mix)
        val sag = if (p.plainA != null && p.plainB != null) sag(mixL, k.shortTerm(p.plainA), k.shortTerm(p.plainB), rate, p.changeFrom, p.changeTo) else 0.0
        val (dip, bump) = line(mixL, rate, p.changeFrom, p.changeTo)
        val problems = ArrayList<String>()
        if (clipped > limits.maxClipped) problems += "$clipped samples past full scale"
        if (driftMean != null && driftWorst != null && (driftMean > limits.maxDriftMeanMs || driftWorst > limits.maxDriftWorstMs))
            problems += "kicks %.1f ms apart on average, %.1f at worst".format(driftMean, driftWorst)
        if (silence > limits.maxSilenceMs) problems += "%.0f ms of silence".format(silence)
        if (sag > limits.maxSagDb) problems += "the mix sags %.1f dB under both songs".format(sag)
        return Verdict(
            clipped = clipped,
            peakDb = 20 * log10(max(peak, 1e-9)),
            driftMeanMs = driftMean,
            driftWorstMs = driftWorst,
            lockBeats = beats,
            longestSilenceMs = silence,
            sagDb = sag,
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

    /** Loudness under this is not judged: too quiet for a sag to be heard. */
    private const val JUDGED_FROM = -50.0

    /**
     * The most the mix sinks under the quieter of the two songs as each would sound at that moment,
     * where both are sounding, through the change. 0 where it never does.
     */
    private fun sag(mix: Loudness.Curve, a: Loudness.Curve, b: Loudness.Curve, rate: Int, from: Int, to: Int): Double {
        var worst = 0.0
        var f = from
        while (f <= to) {
            val la = a.at(f)
            val lb = b.at(f)
            val lm = mix.at(f)
            if (la > JUDGED_FROM && lb > JUDGED_FROM && lm.isFinite()) worst = max(worst, min(la, lb) - lm)
            f += rate / 2
        }
        return worst
    }

    /** The mix against the straight line from its level over the 3 s before [from] to its level over the 3 s after [to]: deepest dip and highest rise. */
    private fun line(mix: Loudness.Curve, rate: Int, from: Int, to: Int): Pair<Double, Double> {
        val before = mix.at(from - 3 * rate / 2)
        val after = mix.at(to + 3 * rate / 2)
        if (!before.isFinite() || !after.isFinite() || to <= from) return 0.0 to 0.0
        var dip = 0.0
        var bump = 0.0
        var f = from
        while (f <= to) {
            val l = mix.at(f)
            if (l.isFinite()) {
                val x = (f - from).toDouble() / (to - from)
                val expected = before + (after - before) * x
                dip = min(dip, l - expected)
                bump = max(bump, l - expected)
            }
            f += rate / 2
        }
        return dip to bump
    }
}

/**
 * Loudness as ITU-R BS.1770 measures it: each channel through the K filter (a high shelf of 4 dB
 * above 1.5 kHz and a high-pass at 38 Hz), the mean square over 3 s windows, in LUFS. The filter
 * coefficients come from the standard's analogue prototypes at any sample rate, as pyloudnorm does.
 */
class Loudness(private val rate: Int) {

    /** The 3 s loudness centred on any frame, from prefix sums of the K-weighted power. */
    inner class Curve(private val prefix: DoubleArray) {
        private val frames = prefix.size - 1

        /** NaN where the 3 s window would leave the signal by more than half. */
        fun at(centre: Int): Double {
            val half = 3 * rate / 2
            val lo = (centre - half).coerceIn(0, frames)
            val hi = (centre + half).coerceIn(lo, frames)
            if (hi - lo < half) return Double.NaN
            val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
            return -0.691 + 10 * log10(max(mean, 1e-12))
        }
    }

    fun shortTerm(stereo: FloatArray): Curve {
        val n = stereo.size / 2
        val shelf = Array(2) { com.dd3boh.outertune.mix.Biquad(1).apply { set(shelfCoefficients()) } }
        val highPass = Array(2) { com.dd3boh.outertune.mix.Biquad(1).apply { set(highPassCoefficients()) } }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            var power = 0.0
            for (c in 0 until 2) {
                val y = highPass[c].process(shelf[c].process(stereo[2 * i + c].toDouble(), 0), 0)
                power += y * y
            }
            prefix[i + 1] = prefix[i] + power
        }
        return Curve(prefix)
    }

    private fun shelfCoefficients(): DoubleArray {
        val g = 3.99984385397
        val q = 0.7071752369554193
        val fc = 1681.974450955533
        val a = Math.pow(10.0, g / 40)
        val w0 = 2 * Math.PI * fc / rate
        val alpha = kotlin.math.sin(w0) / (2 * q)
        val cos = kotlin.math.cos(w0)
        val sa = kotlin.math.sqrt(a)
        val b0 = a * ((a + 1) + (a - 1) * cos + 2 * sa * alpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cos)
        val b2 = a * ((a + 1) + (a - 1) * cos - 2 * sa * alpha)
        val a0 = (a + 1) - (a - 1) * cos + 2 * sa * alpha
        val a1 = 2 * ((a - 1) - (a + 1) * cos)
        val a2 = (a + 1) - (a - 1) * cos - 2 * sa * alpha
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highPassCoefficients(): DoubleArray {
        val q = 0.5003270373253953
        val fc = 38.13547087613982
        val w0 = 2 * Math.PI * fc / rate
        val alpha = kotlin.math.sin(w0) / (2 * q)
        val cos = kotlin.math.cos(w0)
        val b0 = (1 + cos) / 2
        val b1 = -(1 + cos)
        val b2 = (1 + cos) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cos
        val a2 = 1 - alpha
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
