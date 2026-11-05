/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.fingerprint.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A key as a DJ reads it: a tonic pitch class (C is 0) and major or minor, with its Camelot code,
 * the wheel DJs mix by. Keys next to each other on the wheel, or with the same number, share
 * all but one note, so their melodies can be laid over each other.
 */
data class MusicalKey(val tonic: Int, val minor: Boolean) {
    init {
        require(tonic in 0..11)
    }

    /** The number on the Camelot wheel, 1 to 12. A minor key shares its relative major's. */
    val camelotNumber: Int
        get() {
            val major = if (minor) (tonic + 3) % 12 else tonic
            return (major * 7 + 7) % 12 + 1
        }

    /** "8A" for A minor, "8B" for C major. */
    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"

    /** "A minor", "C major", for the logs. */
    val name: String get() = NAMES[tonic] + if (minor) " minor" else " major"

    /**
     * How far apart two keys are on the wheel for mixing: 0 the same key, 1 a neighbour (one step
     * round, or the relative major or minor), 2 two steps round on the same ring, which most ears
     * accept as a lift, and 3 for anything else, a clash.
     */
    fun distanceTo(other: MusicalKey): Int {
        val d = abs(camelotNumber - other.camelotNumber).let { min(it, 12 - it) }
        return when {
            minor == other.minor -> min(d, 3)
            d == 0 -> 1
            else -> 3
        }
    }

    companion object {
        private val NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

        /** The key a Camelot code names, or null for anything else. */
        fun ofCamelot(code: String): MusicalKey? {
            val number = code.dropLast(1).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val minor = when (code.lastOrNull()) {
                'A' -> true
                'B' -> false
                else -> return null
            }
            for (tonic in 0..11) {
                val k = MusicalKey(tonic, minor)
                if (k.camelotNumber == number) return k
            }
            return null
        }
    }
}

/**
 * What the key finder heard: the best key, how well the music's pitch classes match it
 * ([correlation], -1 to 1), and how far ahead of the best key that is not its neighbour on the
 * wheel it stood ([margin]). A key is only used where it is [sure]: two songs are held apart for a clash only
 * when both keys are, so an unsure reading never shortens a mix.
 */
class KeyEstimate(val key: MusicalKey, val correlation: Double, val margin: Double, val chroma: DoubleArray) {
    val sure: Boolean get() = correlation >= Keys.MIN_CORRELATION && margin >= Keys.MIN_MARGIN

    fun describe(): String = "%s (%s) %s, r %.2f, margin %.2f".format(key.camelot, key.name, if (sure) "sure" else "unsure", correlation, margin)
}

/**
 * The key of a stretch of music, from its chroma: how much of each of the twelve pitch classes it
 * holds, measured on the decimated mono copy the analysis already made, and held against
 * Krumhansl and Kessler's profiles of the major and minor keys (1982), turned to each of the
 * twelve tonics. The best of the 24 is the key.
 *
 * Frames of 4096 samples (0.37 s at 11 kHz) under a Hann window, half overlapping. Each peak of
 * the spectrum from 80 Hz to 2.1 kHz gives its amplitude to the pitch class it lies in, shared
 * with the next one in proportion to how far between them it falls, so a note between two bins
 * still counts whole. Every frame is put on one scale before it is added, so a loud chorus does
 * not outvote a quiet verse, and silent frames are left out.
 */
object Keys {
    const val FRAME = 4096
    const val LOW_HZ = 80.0
    const val HIGH_HZ = 2100.0

    /** How well the chroma must match the best key, and how far ahead of the best clashing key, for the key to be sure. */
    const val MIN_CORRELATION = 0.55
    const val MIN_MARGIN = 0.08

    /** Of each key, how much each pitch class above the tonic belongs in it. */
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / FRAME)).toFloat() }

    /** The key of [mono] at [rate] between samples [from] and [to], or null when there is too little pitched sound in it. */
    fun estimate(mono: FloatArray, rate: Double, from: Int = 0, to: Int = mono.size): KeyEstimate? {
        val chroma = chroma(mono, rate, from, to) ?: return null
        return fromChroma(chroma)
    }

    /** The summed chroma of [mono] between [from] and [to], C first, or null with no pitched frame in it. */
    fun chroma(mono: FloatArray, rate: Double, from: Int = 0, to: Int = mono.size): DoubleArray? {
        val lo = from.coerceIn(0, mono.size)
        val hi = to.coerceIn(lo, mono.size)
        if (hi - lo < FRAME) return null
        val fft = Fft(FRAME)
        val buffer = FloatArray(FRAME)
        val power = FloatArray(FRAME / 2 + 1)
        val binHz = rate / FRAME
        val firstBin = max(1, (LOW_HZ / binHz).toInt())
        val lastBin = min(FRAME / 2, (HIGH_HZ / binHz).toInt())
        // Where each bin lies between two pitch classes, worked out once.
        val lower = IntArray(lastBin + 1)
        val share = DoubleArray(lastBin + 1)
        for (k in firstBin..lastBin) {
            val midi = 69.0 + 12.0 * ln(k * binHz / 440.0) / ln(2.0)
            val below = kotlin.math.floor(midi)
            lower[k] = Math.floorMod(below.toInt(), 12)
            share[k] = midi - below
        }
        val total = DoubleArray(12)
        val frame = DoubleArray(12)
        var used = 0
        var start = lo
        // The quietest frames that still count: a frame 50 dB under the loudest seen is silence.
        val energies = ArrayList<Double>()
        val frames = ArrayList<DoubleArray>()
        while (start + FRAME <= hi) {
            var energy = 0.0
            for (i in 0 until FRAME) {
                val x = mono[start + i]
                buffer[i] = x * window[i]
                energy += x.toDouble() * x
            }
            fft.realForwardSquaredMagnitude(buffer, power)
            java.util.Arrays.fill(frame, 0.0)
            // Only the spectrum's peaks count, at their amplitude: a note's overtones fall off
            // with their number, so the fundamental leads, where a log scale would let every
            // overtone count as much and read C major's fifths and thirds as E minor.
            for (k in max(firstBin, 1)..min(lastBin, FRAME / 2 - 1)) {
                val p = power[k]
                if (p <= power[k - 1] || p < power[k + 1]) continue
                val m = sqrt(p.toDouble())
                frame[lower[k]] += m * (1.0 - share[k])
                frame[(lower[k] + 1) % 12] += m * share[k]
            }
            energies += energy
            frames += frame.copyOf()
            start += FRAME / 2
        }
        val loudest = energies.maxOrNull() ?: return null
        if (loudest <= 0.0) return null
        for (i in frames.indices) {
            if (energies[i] < loudest * 1e-5) continue
            val f = frames[i]
            val top = f.maxOrNull() ?: continue
            if (top <= 0.0) continue
            for (c in 0 until 12) total[c] += f[c] / top
            used++
        }
        if (used == 0) return null
        for (c in 0 until 12) total[c] /= used
        return total
    }

    /** The key [chroma] fits best, with how well and how clearly. */
    fun fromChroma(chroma: DoubleArray): KeyEstimate? {
        require(chroma.size == 12)
        if (chroma.all { abs(it - chroma[0]) < 1e-12 }) return null
        val scores = ArrayList<Pair<MusicalKey, Double>>(24)
        for (minor in listOf(false, true)) for (tonic in 0..11) {
            val profile = if (minor) MINOR else MAJOR
            scores += MusicalKey(tonic, minor) to pearson(chroma) { c -> profile[Math.floorMod(c - tonic, 12)] }
        }
        val best = scores.maxBy { it.second }
        // The margin is held against the best key that is not a neighbour of it on the wheel: a
        // song read as its relative minor or a fifth away mixes the same, so that doubt does not
        // count.
        val rival = scores.filter { it.first.distanceTo(best.first) >= 2 }.maxOf { it.second }
        return KeyEstimate(best.first, best.second, best.second - rival, chroma.copyOf())
    }

    private inline fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        val mx = x.average()
        var my = 0.0
        for (c in 0 until 12) my += y(c)
        my /= 12
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (c in 0 until 12) {
            val dx = x[c] - mx
            val dy = y(c) - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0 || syy <= 0) return 0.0
        return sxy / sqrt(sxx * syy)
    }

    /** The key of a region from its analysis' own mono copy. */
    fun of(region: RegionAnalysis, fromFrame: Long = region.envelope.firstSound, toFrame: Long = region.envelope.trueEnd): KeyEstimate? {
        val rate = region.sampleRate.toDouble() / region.decimation
        val from = (fromFrame / region.decimation).toInt() - region.guideFrom
        val to = (toFrame / region.decimation).toInt() - region.guideFrom
        return estimate(region.guide, rate, from.coerceAtLeast(0), to.coerceAtMost(region.guide.size).coerceAtLeast(0))
    }
}
