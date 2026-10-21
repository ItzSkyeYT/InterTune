/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.KeyEstimate
import com.dd3boh.outertune.mix.Keys
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Spectrum
import com.dd3boh.outertune.mix.edit.BeatFix
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A song's beats as a straight line in its own clock: beat k at [t0Ms] + k [beatMs]. Trusted
 * between [firstK] and [lastK]; bar lines where (k - [phase]) is a multiple of [beatsPerBar].
 */
class BeatLine(
    val t0Ms: Double,
    val beatMs: Double,
    val firstK: Int,
    val lastK: Int,
    val beatsPerBar: Int,
    val phase: Int,
    /** The beats land on onsets, steadily: a mix may lock to them. */
    val beatsTrusted: Boolean,
    /** Which beat is the one is clear as well: a mix may line up bars on them. */
    val barsTrusted: Boolean,
    /** The bars are trusted only because an opening's first beat was taken for its one ([SongEnd.of]). */
    val barsFromFirstBeat: Boolean = false,
) {
    val bpm: Double get() = 60_000.0 / beatMs
    val barMs: Double get() = beatsPerBar * beatMs

    fun msOf(k: Int): Double = t0Ms + k * beatMs
    fun msOf(k: Double): Double = t0Ms + k * beatMs

    /** The beat nearest [ms]. */
    fun kNear(ms: Double): Int = ((ms - t0Ms) / beatMs).roundToInt()

    fun isDownbeat(k: Int): Boolean = Math.floorMod(k - phase, beatsPerBar) == 0

    /** The first bar line at or after beat [k]. */
    fun downbeatAtOrAfter(k: Int): Int = k + Math.floorMod(phase - k, beatsPerBar)

    /** The last bar line at or before beat [k]. */
    fun downbeatAtOrBefore(k: Int): Int = k - Math.floorMod(k - phase, beatsPerBar)

    fun trusts(k: Int): Boolean = k in firstK..lastK

    /** The same line with the listener's fix: moved by its nudge, its one moved by whole beats, and its tempo halved or doubled. */
    fun with(fix: BeatFix): BeatLine {
        if (fix.isNone) return this
        var line = BeatLine(t0Ms + fix.nudgeMs, beatMs, firstK, lastK, beatsPerBar, Math.floorMod(phase + fix.downbeatShift, beatsPerBar), beatsTrusted, barsTrusted)
        val bpmFix = fix.bpm ?: return line
        val r = bpmFix / bpm
        line = when {
            // Half the tempo: the same beats, but a bar holds twice as many of them.
            abs(r - 0.5) < 0.02 -> BeatLine(line.t0Ms, beatMs, firstK, lastK, beatsPerBar * 2, line.phase, beatsTrusted, barsTrusted)
            // Double: every other beat line becomes a bar line of half the length.
            abs(r - 2.0) < 0.04 && beatsPerBar % 2 == 0 -> BeatLine(line.t0Ms, beatMs, firstK, lastK, beatsPerBar / 2, Math.floorMod(line.phase, beatsPerBar / 2), beatsTrusted, barsTrusted)
            abs(r - 1.0) < 0.01 -> line
            // A tempo tapped far from what was found means the grid is wrong, and Auto must not lock to it.
            else -> BeatLine(line.t0Ms, 60_000.0 / bpmFix, firstK, lastK, beatsPerBar, line.phase, beatsTrusted = false, barsTrusted = false)
        }
        return line
    }
}

/**
 * The bar lines of a song's end or opening that a change may start or end on, each with the
 * longest phrase it opens: 16, 8, 4, 2 or 1 (only a bar). [strength] is how much the music
 * changes there, novelty and level together, 0 to about 2.
 */
class PhraseMap(
    /** The grid beat of each bar line, in order. */
    val beats: IntArray,
    val level: IntArray,
    val strength: DoubleArray,
    /** Whether the phrase grid was found from where the music changes ([PhraseFinder]) and not only counted from the last loud bar. */
    val heard: Boolean,
    /** How clearly the 8-bar grid's place won over the next best, as a t value; 0 where it was only counted. */
    val margin: Double,
) {
    fun indexOf(k: Int): Int = beats.indexOf(k)

    /** The phrase level of bar line [k], 0 when it is not one of the map's lines. */
    fun levelOf(k: Int): Int = indexOf(k).let { if (it < 0) 0 else level[it] }

    fun strengthOf(k: Int): Double = indexOf(k).let { if (it < 0) 0.0 else strength[it] }

    /** The same phrases with every bar line moved by [beats], as when the listener moves the one. */
    fun shifted(beats: Int): PhraseMap =
        if (beats == 0) this else PhraseMap(IntArray(this.beats.size) { this.beats[it] + beats }, level, strength, heard, margin)
}

/**
 * Everything Auto knows about one end of a song: the outgoing song's end or the incoming song's
 * opening. Every time is in ms of the song's own clock, so it can be kept beside the song and
 * read again without the audio.
 */
class SongEnd(
    val kind: RegionKind,
    /** The whole song's length. */
    val songMs: Double,
    /** The stretch of the song this was measured on. */
    val fromMs: Double,
    val toMs: Double,
    val sampleRate: Int,
    val channels: Int,
    val firstSoundMs: Double,
    val trueEndMs: Double,
    /** Where the level settles 6 dB under the song's for good; the true end when it never does. */
    val outroStartMs: Double,
    /** An opening's first rise of 6 dB held for a bar, where its drop or first verse comes in. */
    val introEndMs: Double?,
    /** How long the fade that ends the song is, when it ends in one. */
    val fadeMs: Double?,
    val medianDb: Double,
    /** The level over a second, every [stepMs] from [fromMs], in dBFS. */
    val levelDb: FloatArray,
    /** The level under 150 Hz, kicks and bass, on the same steps, in dB on the analysis' own scale. */
    val lowDb: FloatArray,
    val stepMs: Double,
    /** The tempo the region holds, even where its beats are not trusted; null where it holds none. */
    val bpm: Double?,
    /** The tempo stands well above chance and does not wander. */
    val steadyTempo: Boolean,
    val line: BeatLine?,
    val phrases: PhraseMap?,
    val key: KeyEstimate?,
    /** Onsets a second over the whole region. */
    val density: Double,
    /** What the gates said, for the logs. */
    val trustNote: String,
    /**
     * A line through only the onsets heard between two beats, for the stretch a mix lines up on;
     * null where too few were heard there or they wander. Only a region analysed here has one.
     */
    private val localLine: ((Int, Int) -> BeatLine?)? = null,
) {
    val seconds: Double get() = (toMs - fromMs) / 1000.0

    /** The level at [ms], dBFS. */
    fun levelAt(ms: Double): Double = sample(levelDb, ms)

    /** The level under 150 Hz at [ms]. */
    fun lowAt(ms: Double): Double = sample(lowDb, ms)

    /** The mean level between [fromMs] and [toMs]. */
    fun meanDb(from: Double, to: Double): Double = mean(levelDb, from, to)

    fun meanLowDb(from: Double, to: Double): Double = mean(lowDb, from, to)

    /** The median of the low level over the whole region, what "kicks and bass playing" sounds like here. */
    val medianLowDb: Double by lazy {
        val sounding = ArrayList<Float>()
        for (i in lowDb.indices) {
            val ms = fromMs + i * stepMs
            if (ms >= firstSoundMs && ms <= trueEndMs) sounding += lowDb[i]
        }
        if (sounding.isEmpty()) -200.0 else sounding.sorted()[sounding.size / 2].toDouble()
    }

    /** The beats from [fromK] to [toK] on a line fitted through them alone, or the region's line where none can be fitted. */
    fun lineFor(fromK: Int, toK: Int): BeatLine? = localLine?.invoke(fromK, toK) ?: line

    /** The same end with the listener's fix to its beat applied. */
    fun with(fix: BeatFix?): SongEnd {
        if (fix == null || fix.isNone || line == null) return this
        val fixed = line.with(fix)
        return SongEnd(
            kind, songMs, fromMs, toMs, sampleRate, channels, firstSoundMs, trueEndMs, outroStartMs, introEndMs, fadeMs, medianDb,
            // A moved one moves every phrase line with it; a halved or doubled tempo leaves them unknown.
            levelDb, lowDb, stepMs, fixed.bpm, steadyTempo, fixed, if (fixed.beatsPerBar == line.beatsPerBar) phrases?.shifted(fix.downbeatShift) else null,
            key, density, trustNote + "; fixed by hand",
            localLine?.let { local -> { a: Int, b: Int -> local(a, b)?.with(fix) } },
        )
    }

    private fun index(ms: Double): Double = (ms - fromMs) / stepMs

    private fun sample(values: FloatArray, ms: Double): Double {
        if (values.isEmpty()) return -200.0
        val i = index(ms).roundToInt().coerceIn(0, values.size - 1)
        return values[i].toDouble()
    }

    private fun mean(values: FloatArray, from: Double, to: Double): Double {
        if (values.isEmpty()) return -200.0
        val lo = floor(index(from)).toInt().coerceIn(0, values.size - 1)
        val hi = floor(index(to)).toInt().coerceIn(lo + 1, values.size)
        var sum = 0.0
        for (i in lo until hi) sum += values[i]
        return sum / (hi - lo)
    }

    companion object {
        /** Levels are kept every this many ms: fine enough for a bar, small enough to keep beside a song. */
        const val STEP_MS = 50.0

        /**
         * What [region] says, for a region of [regionMs] starting at [fromMs] of a song of
         * [songMs]. The phrases are found again here from where the music changes ([PhraseFinder]),
         * and the key from the region's own mono copy.
         */
        fun of(region: RegionAnalysis, fromMs: Double, songMs: Double, firstBeatBars: Boolean = true): SongEnd {
            val rate = region.sampleRate
            fun ms(frame: Double) = fromMs + frame * 1000.0 / rate
            val env = region.envelope
            val spectrum = Spectrum.of(region.guide, rate.toDouble() / region.decimation, region.decimation)
            val steps = max(1, ((region.frames * 1000.0 / rate) / STEP_MS).toInt())
            val level = FloatArray(steps) { i ->
                val frame = (i * STEP_MS / 1000.0 * rate).toLong()
                env.levelDb[env.hopOf(frame)]
            }
            val low = lowLevels(spectrum, region, steps)
            val g = region.grid
            val bars = region.downbeats
            // An opening's first beat is its one, as a DJ assumes: a song starts on bar 1. Taken
            // only where the beats are trusted from the very start, the first sound lands on one
            // of them, and the downbeat finder, unsure as it is, leans the same way.
            val firstBeatIsOne = firstBeatBars && region.kind == RegionKind.HEAD && !region.barsConfident && region.beatsConfident &&
                g != null && bars != null && bars.margin >= 0.0 && run {
                    val k = region.beatNear(env.firstSound.toDouble())
                    val off = abs(region.beatFrame(k) - env.firstSound) * 1000.0 / rate
                    off <= FIRST_BEAT_MS && k <= g.firstBeat + 1 && bars.isDownbeat(k)
                }
            val line = if (g != null) {
                BeatLine(
                    t0Ms = ms(region.beatFrame(0)),
                    beatMs = region.periodFrames!! * 1000.0 / rate,
                    firstK = g.firstBeat,
                    lastK = g.lastBeat,
                    beatsPerBar = bars?.beatsPerBar ?: 4,
                    phase = bars?.phase ?: 0,
                    beatsTrusted = region.beatsConfident,
                    barsTrusted = region.barsConfident || firstBeatIsOne,
                    barsFromFirstBeat = firstBeatIsOne,
                )
            } else null
            val phrases = if (line != null && bars != null && g != null) PhraseFinder.find(spectrum, region, line) else null
            val local: ((Int, Int) -> BeatLine?)? = if (g != null && line != null) { a: Int, b: Int ->
                g.lineBetween(a, b)?.takeIf { it.jitterMs <= LOCAL_JITTER_MS && it.endsMs <= LOCAL_ENDS_MS }?.let { l ->
                    val r = region.withLine(l)
                    BeatLine(ms(r.beatFrame(0)), r.periodFrames!! * 1000.0 / rate, line.firstK, line.lastK, line.beatsPerBar, line.phase, line.beatsTrusted, line.barsTrusted)
                }
            } else null
            val regionMs = region.frames * 1000.0 / rate
            return SongEnd(
                kind = region.kind,
                songMs = songMs,
                fromMs = fromMs,
                toMs = fromMs + regionMs,
                sampleRate = rate,
                channels = region.channels,
                firstSoundMs = ms(env.firstSound.toDouble()),
                trueEndMs = ms(env.trueEnd.toDouble()),
                outroStartMs = ms(env.outroStart.toDouble()),
                introEndMs = env.introEnd?.let { ms(it.toDouble()) },
                fadeMs = env.fadeFrames?.let { it * 1000.0 / rate },
                medianDb = env.medianDb,
                levelDb = level,
                lowDb = low,
                stepMs = STEP_MS,
                bpm = region.bpm,
                steadyTempo = region.gates.tempo,
                line = line,
                phrases = phrases,
                key = Keys.of(region),
                density = region.onsetDensity(env.firstSound, env.trueEnd),
                trustNote = region.describe() + if (firstBeatIsOne) "; bars from its first beat" else "",
                localLine = local,
            )
        }

        /** How near a grid beat an opening's first sound must land for that beat to be taken for its one. */
        const val FIRST_BEAT_MS = 60.0

        /** How far the onsets on the beats a mix uses may sit off the line through them, as the prototype's planner allows. */
        const val LOCAL_JITTER_MS = 12.0
        const val LOCAL_ENDS_MS = 8.0

        /** The mean of the mel bands under 150 Hz over each step, in dB on the spectrum's own scale. */
        private fun lowLevels(spectrum: Spectrum, region: RegionAnalysis, steps: Int): FloatArray {
            val bands = spectrum.bandCentres.count { it < Spectrum.LOW_ONSET_HZ }.coerceAtLeast(1)
            val out = FloatArray(steps)
            val rate = region.sampleRate
            for (i in 0 until steps) {
                val from = spectrum.sampleToFrame(i * STEP_MS / 1000.0 * rate).toInt().coerceIn(0, max(0, spectrum.frames - 1))
                val to = spectrum.sampleToFrame((i + 1) * STEP_MS / 1000.0 * rate).toInt().coerceIn(from + 1, max(from + 1, spectrum.frames))
                var sum = 0.0
                var n = 0
                for (t in from until min(to, spectrum.frames)) {
                    // Power, not dB, is averaged, so a kick's peak counts as loud as it is.
                    var p = 0.0
                    for (b in 0 until bands) p += Math.pow(10.0, spectrum.mel(t, b) / 10.0)
                    sum += p / bands
                    n++
                }
                out[i] = if (n == 0) -200f else (10.0 * kotlin.math.log10(max(sum / n, 1e-12))).toFloat()
            }
            return out
        }
    }
}
