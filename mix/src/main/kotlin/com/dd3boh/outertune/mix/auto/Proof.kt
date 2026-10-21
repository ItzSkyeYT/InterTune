/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

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
    /** Why the change cannot be played on the music at hand at all, so it is refused unheard; null for a change rendered. */
    val refused: String? = null,
) {
    fun interface Renderer {
        fun render(pick: AutoPick): Proof?
    }

    companion object {
        /** A change the renderer cannot play on the music it has (it starts too early, it runs past a copy), with why. */
        fun refused(sampleRate: Int, why: String) = Proof(sampleRate, FloatArray(0), 0, 0, refused = why)
    }
}

/**
 * Whether a rendered change is clean enough to play: no sample past full scale, the two songs'
 * beats within a few ms of each other wherever both are heard, no dead air inside the change, and
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
        /** The kicks where both songs have one, with how late the outgoing song's sit; null where too few meet. */
        val lock: Lock?,
        val longestSilenceMs: Double,
        /** The deepest the mix sank under the quieter of the two songs as each would sound there, in dB; 0 where it never did. */
        val sagDb: Double,
        /** The 3 s loudness against the straight line from the outgoing song's level before the change to the incoming song's after it: the deepest dip and the highest rise, in dB. */
        val dipDb: Double,
        val bumpDb: Double,
        val passes: Boolean,
        val why: String,
    )

    fun check(p: Proof, limits: Limits = Limits()): Verdict {
        p.refused?.let { why ->
            return Verdict(0, -200.0, null, null, 0, null, 0.0, 0.0, 0.0, 0.0, passes = false, why = "cannot play: $why")
        }
        val rate = p.sampleRate
        val frames = p.mix.size / 2
        // Only the change is judged: outside it each song plays as it is, its own peaks at full
        // scale included, and splitting it into lanes and adding them back can leave a few parts
        // in a million over a sample at full scale, which no output hears.
        var clipped = 0
        var peak = 0.0
        for (i in 2 * max(0, p.changeFrom) until 2 * min(frames, p.changeTo)) {
            val a = abs(p.mix[i].toDouble())
            if (a > 1.0 + 1e-4) clipped++
            if (a > peak) peak = a
        }
        val lock = drift(p)
        val driftMean = lock?.meanMs
        val driftWorst = lock?.worstMs
        val beats = lock?.beats ?: 0
        val silence = longestSilence(p.mix, rate, max(0, p.changeFrom), min(frames, p.changeTo), limits.silenceDb)
        val k = Loudness(rate)
        val mixL = k.shortTerm(p.mix)
        val sag = if (p.plainA != null && p.plainB != null) sag(mixL, k.shortTerm(p.plainA), k.shortTerm(p.plainB), rate, p.changeFrom, p.changeTo) else 0.0
        val (dip, bump) = line(mixL, rate, p.changeFrom, p.changeTo)
        val problems = ArrayList<String>()
        if (clipped > limits.maxClipped) problems += "$clipped samples past full scale"
        if (driftMean != null && driftWorst != null && (driftMean > limits.maxDriftMeanMs || driftWorst > limits.maxDriftWorstMs))
            problems += "beats %.1f ms apart on average, %.1f at worst".format(driftMean, driftWorst)
        if (silence > limits.maxSilenceMs) problems += "%.0f ms of silence".format(silence)
        if (sag > limits.maxSagDb) problems += "the mix sags %.1f dB under both songs".format(sag)
        return Verdict(
            clipped = clipped,
            peakDb = 20 * log10(max(peak, 1e-9)),
            driftMeanMs = driftMean,
            driftWorstMs = driftWorst,
            lockBeats = beats,
            lock = lock,
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
     * read off fitted lines: at each of the incoming song's beats where both songs have a low
     * onset (a kick or a bass note) within 50 ms of it, how far apart the two are; mean, worst and
     * how many beats. Nulls where fewer than four beats have both: a beat where only one song
     * kicks cannot flam, and a fitted line is not what is heard.
     */
    /**
     * How the two songs' beats sit against each other where they meet: over each two bars, the
     * lag at which the rises of their low bands (kicks, bass notes) best line up; the mean and
     * the worst of those lags, how many stretches were judged, the median lag (how late the
     * outgoing song runs, negative early) and how far the lags spread around it.
     */
    class Lock(val meanMs: Double, val worstMs: Double, val beats: Int, val lateMs: Double, val spreadMs: Double)

    /** Stretches of this many of the incoming song's beats are judged on their own. */
    private const val LOCK_BEATS = 8

    fun drift(p: Proof): Lock? {
        val a = p.lockA ?: return null
        val b = p.lockB ?: return null
        if (p.lockBeats.size < LOCK_BEATS + 1) return null
        val rate = p.sampleRate
        val ea = lowRises(a, rate)
        val eb = lowRises(b, rate)
        val totalA = ea.sum()
        val totalB = eb.sum()
        if (totalA <= 0 || totalB <= 0) return null
        val lags = ArrayList<Double>()
        var i = 0
        while (i + LOCK_BEATS < p.lockBeats.size) {
            val from = (p.lockBeats[i] * 1000.0 / rate).toInt()
            val to = (p.lockBeats[i + LOCK_BEATS] * 1000.0 / rate).toInt()
            i += LOCK_BEATS
            if (to - from < 200 || to > min(ea.size, eb.size)) continue
            // Both songs must have their share of low rises here, or there is nothing to line up.
            val share = (to - from).toDouble() / min(ea.size, eb.size)
            val sa = (from until to).sumOf { ea[it] }
            val sb = (from until to).sumOf { eb[it] }
            if (sa < 0.3 * share * totalA || sb < 0.3 * share * totalB) continue
            lag(ea, eb, from, to)?.let { lags += it }
        }
        if (lags.isEmpty()) return null
        val sorted = lags.sorted()
        val median = sorted[sorted.size / 2]
        val spread = lags.map { abs(it - median) }.sorted()[lags.size / 2]
        return Lock(lags.map { abs(it) }.average(), lags.maxOf { abs(it) }, lags.size * LOCK_BEATS, median, spread)
    }

    /**
     * How late [a]'s low band runs behind [b]'s over their whole length, in ms (negative: ahead),
     * the lag within 40 ms at which their rises best line up; null where either is silent.
     */
    fun lagMs(a: FloatArray, b: FloatArray, rate: Int): Double? {
        val ea = lowRises(a, rate)
        val eb = lowRises(b, rate)
        return lag(ea, eb, 0, min(ea.size, eb.size))
    }

    private fun lag(ea: DoubleArray, eb: DoubleArray, from: Int, to: Int): Double? {
        val maxLag = 40
        val lo = max(from, maxLag)
        val hi = min(to, min(ea.size, eb.size) - maxLag)
        if (hi - lo < 100) return null
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = DoubleArray(2 * maxLag + 1)
        for (l in -maxLag..maxLag) {
            var sum = 0.0
            for (t in lo until hi) sum += ea[t + l] * eb[t]
            scores[l + maxLag] = sum
            if (sum > bestScore) {
                bestScore = sum
                best = l
            }
        }
        if (bestScore <= 0.0) return null
        var lag = best.toDouble()
        val i = best + maxLag
        if (i in 1 until scores.size - 1) {
            val l = scores[i - 1]
            val c = scores[i]
            val r = scores[i + 1]
            val d = l - 2 * c + r
            if (d < 0) lag += (0.5 * (l - r) / d).coerceIn(-0.5, 0.5)
        }
        return lag
    }

    /** The rises of a signal's envelope under 150 Hz, one value a millisecond. */
    private fun lowRises(x: FloatArray, rate: Int): DoubleArray {
        val lp = Array(2) { com.dd3boh.outertune.mix.Biquad(1).apply { set(com.dd3boh.outertune.mix.FilterKind.LOW_PASS, 150.0, 0.7071067811865476, rate) } }
        val per = max(1, rate / 1000)
        val bins = x.size / per
        val env = DoubleArray(bins)
        for (i in 0 until bins * per) {
            val y = lp[1].process(lp[0].process(x[i].toDouble(), 0), 0)
            env[i / per] += y * y
        }
        val out = DoubleArray(bins)
        for (t in 1 until bins) out[t] = max(0.0, kotlin.math.sqrt(env[t]) - kotlin.math.sqrt(env[t - 1]))
        return out
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
