/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Where the drums fall in a bar. */
enum class Groove {
    /** Kick on every beat, clap on 2 and 4, hats on the off-beats: house and techno. */
    FOUR_ON_THE_FLOOR,

    /** Kick on 1 and 3, snare on 2 and 4, hats on every eighth: most pop and hip-hop. */
    BACKBEAT,

    /** Kick on 1, snare on 3, hats on every eighth: the half-time feel of trap and slowed edits. */
    HALF_TIME,

    /** Three beats to the bar, kick on 1, softer snares on 2 and 3. */
    WALTZ,

    /** No drums at all, only the chords and the bass. */
    NONE,
}

/**
 * A song made to order, with every answer known: where each beat, bar and phrase starts, where the
 * drums come in, where the outro starts and where the sound ends.
 *
 * Built like a club track or a beat tape: kick, snare or clap, hats, a chord and a bass note that
 * change on every bar line, and every [phraseBars] bars a crash and a change of energy (the hats
 * drop out and the chords move up an octave on every other phrase). That is the structure the
 * analysis is written to find, so a generator without it would only test the parts that do not
 * matter.
 */
data class SynthSpec(
    val bpm: Double = 128.0,
    val sampleRate: Int = 44_100,
    /** Bars of music with drums, after the intro and before the outro. */
    val bars: Int = 32,
    /** Bars of chords and bass alone before the drums come in. */
    val introBars: Int = 0,
    /** Bars of quiet chords alone after the drums stop, 12 dB down, then a cut to silence. */
    val outroBars: Int = 0,
    /** Bars over which the whole song fades out to silence at its end, instead of a cut. */
    val fadeBars: Int = 0,
    val leadSilence: Double = 0.0,
    val tailSilence: Double = 0.0,
    val groove: Groove = Groove.FOUR_ON_THE_FLOOR,
    /** Where the off-beat eighth falls, as a fraction of the beat: 0.5 straight, 2/3 a full shuffle. */
    val swing: Double = 0.5,
    /** How much faster the last beat is than the first, in percent. */
    val rampPercent: Double = 0.0,
    /** White noise at this level relative to the music's RMS, or none. */
    val noiseDb: Double? = null,
    val phraseBars: Int = 8,
    /** Chords on every bar line. Off leaves the drums and the bass as the only cue for bars. */
    val chords: Boolean = true,
    val seed: Long = 1,
    val gain: Double = 0.45,
) {
    val beatsPerBar: Int get() = if (groove == Groove.WALTZ) 3 else 4
}

class SynthSong(
    val spec: SynthSpec,
    /** Interleaved stereo, 16 bit. */
    val samples: ShortArray,
    /** Every beat with drums on it, in seconds. */
    val beats: DoubleArray,
    /** Every beat of the music, intro and outro included. */
    val allBeats: DoubleArray,
    /** The first beat of every bar with drums. */
    val downbeats: DoubleArray,
    /** The first beat of every bar of the music. */
    val allDownbeats: DoubleArray,
    /** Where each phrase of drums starts, which is also where a crash and an energy step fall. */
    val phrases: DoubleArray,
    val firstSound: Double,
    val drumsStart: Double,
    val outroStart: Double,
    val trueEnd: Double,
) {
    val sampleRate: Int get() = spec.sampleRate
    val frames: Int get() = samples.size / 2
    val seconds: Double get() = frames.toDouble() / sampleRate

    /** The frames from [fromSeconds] to [toSeconds], as a new interleaved array. */
    fun slice(fromSeconds: Double, toSeconds: Double = seconds): ShortArray {
        val from = (fromSeconds * sampleRate).roundToInt().coerceIn(0, frames)
        val to = (toSeconds * sampleRate).roundToInt().coerceIn(from, frames)
        return samples.copyOfRange(from * 2, to * 2)
    }
}

object Synth {

    fun song(spec: SynthSpec): SynthSong {
        val rate = spec.sampleRate
        val random = Random(spec.seed)
        val perBar = spec.beatsPerBar
        val musicBars = spec.introBars + spec.bars + spec.outroBars
        val totalBeats = musicBars * perBar
        // Beat k lasts 60 / bpm(k), with the tempo moving evenly from the first beat to the last.
        val beatTimes = DoubleArray(totalBeats + 1)
        beatTimes[0] = spec.leadSilence
        for (k in 0 until totalBeats) {
            val bpm = spec.bpm * (1.0 + spec.rampPercent / 100.0 * k / max(1, totalBeats - 1))
            beatTimes[k + 1] = beatTimes[k] + 60.0 / bpm
        }
        val musicEnd = beatTimes[totalBeats]
        val totalSeconds = musicEnd + spec.tailSilence + 0.05
        val n = (totalSeconds * rate).roundToInt()
        val left = FloatArray(n)
        val right = FloatArray(n)

        val kick = kick(rate, random)
        val snare = snare(rate, random)
        val clap = clap(rate, random)
        val hat = hat(rate, random)
        val crash = crash(rate, random)

        fun place(sound: FloatArray, at: Double, gain: Double, pan: Double = 0.0) {
            val start = (at * rate).roundToInt()
            val l = (gain * (1.0 - pan)).toFloat()
            val r = (gain * (1.0 + pan)).toFloat()
            for (i in sound.indices) {
                val j = start + i
                if (j < 0 || j >= n) continue
                left[j] += sound[i] * l
                right[j] += sound[i] * r
            }
        }

        // The chords and the bass, one per bar, over intro, drums and outro.
        val roots = doubleArrayOf(110.0, 87.307, 130.813, 97.999)
        val triad = doubleArrayOf(1.0, 1.189_207, 1.498_307)
        for (bar in 0 until musicBars) {
            val start = beatTimes[bar * perBar]
            val end = beatTimes[(bar + 1) * perBar]
            val drumBar = bar - spec.introBars
            val phrase = if (drumBar >= 0) drumBar / spec.phraseBars else -1
            val outro = bar >= spec.introBars + spec.bars
            val level = if (outro) 0.25 else 1.0
            val octave = if (phrase >= 0 && phrase % 2 == 1) 2.0 else 1.0
            val root = roots[bar % roots.size]
            if (spec.chords) {
                for ((i, ratio) in triad.withIndex()) {
                    val note = saw(root * 2 * ratio * octave, end - start, rate, attack = 0.03)
                    place(note, start, 0.07 * level, pan = (i - 1) * 0.3)
                }
            }
            if (!outro) place(bass(root / 2, end - start, rate), start, 0.35 * level)
        }

        // The drums, over the bars between intro and outro.
        val drumsFrom = spec.introBars
        for (bar in drumsFrom until drumsFrom + spec.bars) {
            val drumBar = bar - drumsFrom
            val phrase = drumBar / spec.phraseBars
            val energy = if (phrase % 2 == 0) 1.0 else 0.8
            val hats = phrase % 2 == 0 || spec.groove != Groove.FOUR_ON_THE_FLOOR
            if (drumBar % spec.phraseBars == 0) place(crash, beatTimes[bar * perBar], 0.5 * energy, pan = 0.2)
            for (beat in 0 until perBar) {
                val k = bar * perBar + beat
                val at = beatTimes[k]
                val next = beatTimes[k + 1]
                val off = at + (next - at) * spec.swing
                when (spec.groove) {
                    Groove.FOUR_ON_THE_FLOOR -> {
                        place(kick, at, 0.9 * energy)
                        if (beat == 1 || beat == 3) place(clap, at, 0.5 * energy, pan = -0.1)
                        if (hats) place(hat, off, 0.45 * energy, pan = 0.3)
                    }
                    Groove.BACKBEAT -> {
                        if (beat == 0 || beat == 2) place(kick, at, 0.9 * energy)
                        if (beat == 1 || beat == 3) place(snare, at, 0.6 * energy)
                        place(hat, at, 0.3 * energy, pan = 0.3)
                        place(hat, off, 0.25 * energy, pan = 0.3)
                    }
                    Groove.HALF_TIME -> {
                        if (beat == 0) place(kick, at, 0.9 * energy)
                        if (beat == 2) place(snare, at, 0.6 * energy)
                        place(hat, at, 0.3 * energy, pan = 0.3)
                        place(hat, off, 0.25 * energy, pan = 0.3)
                    }
                    Groove.WALTZ -> {
                        if (beat == 0) place(kick, at, 0.9 * energy) else place(snare, at, 0.35 * energy)
                        place(hat, at, 0.25 * energy, pan = 0.3)
                    }
                    Groove.NONE -> Unit
                }
            }
        }

        // A fade over the last bars, in decibels so it sounds like a fader pulled down.
        if (spec.fadeBars > 0) {
            val fadeStart = beatTimes[(musicBars - spec.fadeBars) * perBar]
            val from = (fadeStart * rate).roundToInt()
            val to = (musicEnd * rate).roundToInt()
            for (i in from until n) {
                val x = ((i - from).toDouble() / (to - from)).coerceIn(0.0, 1.0)
                val g = if (i >= to) 0f else 10.0.pow(-60.0 * x / 20.0).toFloat()
                left[i] *= g
                right[i] *= g
            }
        }

        // The song stops dead at its last bar line, whatever was still ringing.
        val cut = (musicEnd * rate).roundToInt().coerceAtMost(n)
        for (i in cut until n) {
            left[i] = 0f
            right[i] = 0f
        }

        if (spec.noiseDb != null) addNoise(left, right, spec.noiseDb, random, from = (spec.leadSilence * rate).roundToInt(), to = cut)

        val samples = ShortArray(n * 2)
        for (i in 0 until n) {
            samples[2 * i] = toShort((left[i] * spec.gain).toFloat())
            samples[2 * i + 1] = toShort((right[i] * spec.gain).toFloat())
        }

        val beatList = ArrayList<Double>()
        val downList = ArrayList<Double>()
        val allDownList = ArrayList<Double>()
        val phraseList = ArrayList<Double>()
        for (bar in 0 until musicBars) {
            allDownList += beatTimes[bar * perBar]
            if (bar in drumsFrom until drumsFrom + spec.bars) {
                downList += beatTimes[bar * perBar]
                if ((bar - drumsFrom) % spec.phraseBars == 0) phraseList += beatTimes[bar * perBar]
                for (beat in 0 until perBar) beatList += beatTimes[bar * perBar + beat]
            }
        }
        val outroStart = if (spec.outroBars > 0) beatTimes[(drumsFrom + spec.bars) * perBar] else musicEnd
        return SynthSong(
            spec = spec,
            samples = samples,
            beats = beatList.toDoubleArray(),
            allBeats = beatTimes.copyOf(totalBeats),
            downbeats = downList.toDoubleArray(),
            allDownbeats = allDownList.toDoubleArray(),
            phrases = phraseList.toDoubleArray(),
            firstSound = spec.leadSilence,
            drumsStart = beatTimes[drumsFrom * perBar],
            outroStart = outroStart,
            trueEnd = musicEnd,
        )
    }

    /**
     * A pad with no pulse: long chords that swell in and out at uneven times, over a drone. What
     * an ambient track does, and what must never be read as a beat.
     */
    fun pad(seconds: Double, sampleRate: Int = 44_100, seed: Long = 7, gain: Double = 0.4): ShortArray {
        val random = Random(seed)
        val n = (seconds * sampleRate).roundToInt()
        val left = FloatArray(n)
        val right = FloatArray(n)
        val drone = saw(55.0, seconds, sampleRate, attack = 2.0)
        for (i in 0 until n) {
            left[i] += drone[i] * 0.2f
            right[i] += drone[i] * 0.2f
        }
        var t = 0.0
        val notes = doubleArrayOf(220.0, 261.63, 329.63, 392.0, 440.0, 493.88)
        while (t < seconds) {
            val length = 3.0 + random.nextDouble() * 4.0
            val f = notes[random.nextInt(notes.size)]
            val swell = saw(f, length, sampleRate, attack = 1.2, release = 1.5)
            val start = (t * sampleRate).roundToInt()
            val pan = random.nextDouble(-0.5, 0.5).toFloat()
            for (i in swell.indices) {
                val j = start + i
                if (j >= n) break
                left[j] += swell[i] * 0.12f * (1 - pan)
                right[j] += swell[i] * 0.12f * (1 + pan)
            }
            t += 1.3 + random.nextDouble() * 2.9
        }
        return ShortArray(n * 2) { i -> toShort(((if (i % 2 == 0) left[i / 2] else right[i / 2]) * gain).toFloat()) }
    }

    /** Stereo white noise at [rmsDb] dBFS. */
    fun noise(seconds: Double, sampleRate: Int = 44_100, seed: Long = 11, rmsDb: Double = -18.0): ShortArray {
        val random = Random(seed)
        val n = (seconds * sampleRate).roundToInt()
        val amplitude = 10.0.pow(rmsDb / 20.0) * sqrt(3.0)
        return ShortArray(n * 2) { toShort(((random.nextDouble() * 2 - 1) * amplitude).toFloat()) }
    }

    /** Stereo sine at [hz] and [amplitude] of full scale. */
    fun sine(hz: Double, seconds: Double, sampleRate: Int = 44_100, amplitude: Double = 0.5): ShortArray {
        val n = (seconds * sampleRate).roundToInt()
        return ShortArray(n * 2) { i -> toShort((amplitude * sin(2 * PI * hz * (i / 2) / sampleRate)).toFloat()) }
    }

    /** Single clicks, a few samples of full-scale pulse each, at [times] in seconds. */
    fun clicks(times: DoubleArray, seconds: Double, sampleRate: Int = 44_100, amplitude: Double = 0.8): ShortArray {
        val n = (seconds * sampleRate).roundToInt()
        val out = ShortArray(n * 2)
        for (t in times) {
            val at = (t * sampleRate).roundToInt()
            for (i in 0 until 24) {
                val j = at + i
                if (j >= n) break
                val v = toShort((amplitude * exp(-i / 6.0) * if (i % 2 == 0) 1 else -1).toFloat())
                out[2 * j] = v
                out[2 * j + 1] = v
            }
        }
        return out
    }

    internal fun toShort(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun addNoise(left: FloatArray, right: FloatArray, db: Double, random: Random, from: Int, to: Int) {
        var squares = 0.0
        for (i in from until to) squares += (left[i] * left[i] + right[i] * right[i]).toDouble()
        val rms = sqrt(squares / max(1, 2 * (to - from)))
        val amplitude = rms * 10.0.pow(db / 20.0) * sqrt(3.0)
        for (i in from until to) {
            left[i] += ((random.nextDouble() * 2 - 1) * amplitude).toFloat()
            right[i] += ((random.nextDouble() * 2 - 1) * amplitude).toFloat()
        }
    }

    private fun kick(rate: Int, random: Random): FloatArray {
        val n = (0.35 * rate).roundToInt()
        var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val f = 45.0 + 110.0 * exp(-t / 0.025)
            phase += 2 * PI * f / rate
            val body = sin(phase) * exp(-t / 0.16) * min(1.0, t / 0.001)
            val click = (random.nextDouble() * 2 - 1) * exp(-t / 0.002) * 0.3
            (body + click).toFloat()
        }
    }

    private fun snare(rate: Int, random: Random): FloatArray {
        val n = (0.25 * rate).roundToInt()
        var last = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val white = random.nextDouble() * 2 - 1
            val bright = white - 0.85 * last
            last = white
            val noise = bright * exp(-t / 0.09) * 0.6
            val tone = sin(2 * PI * 185.0 * t) * exp(-t / 0.06) * 0.5
            (noise + tone).toFloat()
        }
    }

    private fun clap(rate: Int, random: Random): FloatArray {
        val n = (0.2 * rate).roundToInt()
        var last = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val white = random.nextDouble() * 2 - 1
            val bright = white - 0.7 * last
            last = white
            // Three quick bursts and a tail, the way a clap is layered.
            val bursts = exp(-t / 0.004) + exp(-max(0.0, t - 0.01) / 0.004) * (if (t >= 0.01) 1.0 else 0.0) +
                exp(-max(0.0, t - 0.02) / 0.05) * (if (t >= 0.02) 1.0 else 0.0)
            (bright * bursts * 0.5).toFloat()
        }
    }

    private fun hat(rate: Int, random: Random): FloatArray {
        val n = (0.06 * rate).roundToInt()
        var a = 0.0
        var b = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val white = random.nextDouble() * 2 - 1
            // Two first differences push the noise up to the top of the spectrum.
            val d1 = white - a
            a = white
            val d2 = d1 - b
            b = d1
            (d2 * 0.25 * exp(-t / 0.018)).toFloat()
        }
    }

    private fun crash(rate: Int, random: Random): FloatArray {
        val n = (1.6 * rate).roundToInt()
        var a = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val white = random.nextDouble() * 2 - 1
            val d = white - a
            a = white
            (d * 0.3 * exp(-t / 0.5)).toFloat()
        }
    }

    private fun bass(hz: Double, seconds: Double, rate: Int): FloatArray {
        val n = (seconds * rate).roundToInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val env = min(1.0, t / 0.005) * exp(-t / 1.2) * min(1.0, (seconds - t) / 0.01)
            (sin(2 * PI * hz * t) * env).toFloat()
        }
    }

    /** A soft sawtooth: four harmonics, with an attack and a release so notes join without clicks. */
    internal fun saw(hz: Double, seconds: Double, rate: Int, attack: Double, release: Double = 0.01): FloatArray {
        val n = (seconds * rate).roundToInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (h in 1..4) v += sin(2 * PI * hz * h * t) / h
            val env = min(1.0, t / attack) * min(1.0, max(0.0, seconds - t) / release)
            (v * env * 0.5).toFloat()
        }
    }
}

/** Scores against the generator's answers. */
object Truth {
    /**
     * The F-measure of [detected] against [truth], both in seconds: each true event can be matched
     * by one detection within [tolerance].
     */
    fun fMeasure(detected: DoubleArray, truth: DoubleArray, tolerance: Double = 0.07): Double {
        if (detected.isEmpty() || truth.isEmpty()) return 0.0
        val used = BooleanArray(truth.size)
        var hits = 0
        for (d in detected) {
            var best = -1
            var bestDistance = tolerance
            for (j in truth.indices) {
                if (used[j]) continue
                val distance = abs(truth[j] - d)
                if (distance <= bestDistance) {
                    best = j
                    bestDistance = distance
                }
            }
            if (best >= 0) {
                used[best] = true
                hits++
            }
        }
        val precision = hits.toDouble() / detected.size
        val recall = hits.toDouble() / truth.size
        return if (hits == 0) 0.0 else 2 * precision * recall / (precision + recall)
    }

    /** The truth that falls between [from] and [to] seconds. */
    fun within(truth: DoubleArray, from: Double, to: Double): DoubleArray = truth.filter { it >= from && it < to }.toDoubleArray()
}
