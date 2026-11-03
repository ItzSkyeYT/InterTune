/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.KeyEstimate
import com.dd3boh.outertune.mix.MusicalKey
import com.dd3boh.outertune.mix.RegionKind
import kotlin.math.floor
import kotlin.math.max

/**
 * Song ends made by hand for the planner's rules: a beat line, its trust, its phrase lines and its
 * levels, with every answer known. No audio: the planner reads only these.
 */
object Ends {

    private const val STEP = SongEnd.STEP_MS

    /** A sure key with the given Camelot code. */
    fun key(code: String): KeyEstimate = KeyEstimate(MusicalKey.ofCamelot(code)!!, 0.8, 0.2, DoubleArray(12))

    /**
     * The end of a song of [songMs] at [bpm]: the last [regionMs] of it, beats from the start of
     * the region to [beatsEndMs] before the end (the song's beat runs out there), bar lines on
     * every fourth beat from the first, a phrase line every [phraseBars] bars counted so that one
     * falls [phraseOffsetBars] bars into the region, and music to the end.
     */
    fun tail(
        bpm: Double,
        songMs: Double = 200_000.0,
        regionMs: Double = 60_000.0,
        beatsTrusted: Boolean = true,
        barsTrusted: Boolean = true,
        phraseOffsetBars: Int = 0,
        phraseBars: Int = 8,
        heard: Boolean = true,
        beatsEndMs: Double = 0.0,
        outroMs: Double? = null,
        fadeMs: Double? = null,
        density: Double = 4.0,
        key: KeyEstimate? = null,
        kickless: Boolean = false,
        firstBeatOffsetMs: Double = 120.0,
        beatsPerBar: Int = 4,
        silenceAtEndMs: Double = 0.0,
        voice: ((Double) -> Double)? = null,
    ): SongEnd {
        val from = songMs - regionMs
        val beatMs = 60_000.0 / bpm
        val t0 = from + firstBeatOffsetMs
        val lastK = floor((songMs - max(beatsEndMs, silenceAtEndMs) - t0) / beatMs).toInt()
        val line = BeatLine(t0, beatMs, 0, lastK, beatsPerBar, 0, beatsTrusted, barsTrusted)
        val steps = (regionMs / STEP).toInt()
        val level = FloatArray(steps) { i ->
            val ms = from + i * STEP
            when {
                ms >= songMs - silenceAtEndMs -> -90f
                outroMs != null && ms >= songMs - outroMs -> -24f
                fadeMs != null && ms >= songMs - fadeMs -> (-12.0 - 48.0 * (ms - (songMs - fadeMs)) / fadeMs).toFloat()
                else -> -12f
            }
        }
        val low = FloatArray(steps) { if (kickless) -50f else -20f }
        return SongEnd(
            kind = RegionKind.TAIL, songMs = songMs, fromMs = from, toMs = songMs, sampleRate = 44_100, channels = 2,
            firstSoundMs = from, trueEndMs = songMs - max(20.0, silenceAtEndMs), outroStartMs = outroMs?.let { songMs - it } ?: (songMs - 20),
            introEndMs = null, fadeMs = fadeMs, medianDb = -12.0, levelDb = level, lowDb = low, stepMs = STEP,
            bpm = bpm, steadyTempo = beatsTrusted, line = line,
            phrases = if (barsTrusted || beatsTrusted) phrases(line, phraseOffsetBars, phraseBars, heard) else null,
            key = key, density = density, trustNote = "made by hand",
            voice = voice?.let { f -> FloatArray(steps) { i -> f(from + i * STEP).toFloat() } },
        )
    }

    /**
     * The opening of a song at [bpm]: its first sound at [firstSoundMs], beats trusted from
     * [beatsFromBars] bars after its first downbeat (0: from the first), its first rise
     * [introEndBars] bars in where it has one, read for [regionMs].
     */
    fun head(
        bpm: Double,
        songMs: Double = 200_000.0,
        regionMs: Double = 60_000.0,
        firstSoundMs: Double = 50.0,
        firstDownbeatMs: Double = 60.0,
        beatsTrusted: Boolean = true,
        barsTrusted: Boolean = true,
        beatsFromBars: Int = 0,
        introEndBars: Double? = null,
        kicklessBars: Int = 0,
        density: Double = 4.0,
        key: KeyEstimate? = null,
        beatsPerBar: Int = 4,
        steadyTempo: Boolean = beatsTrusted,
        voice: ((Double) -> Double)? = null,
    ): SongEnd {
        val beatMs = 60_000.0 / bpm
        val line = BeatLine(firstDownbeatMs, beatMs, beatsFromBars * beatsPerBar, ((regionMs - firstDownbeatMs) / beatMs).toInt() - 1, beatsPerBar, 0, beatsTrusted, barsTrusted)
        val steps = (regionMs / STEP).toInt()
        val kickEnd = firstDownbeatMs + kicklessBars * beatsPerBar * beatMs
        val level = FloatArray(steps) { i -> if (i * STEP < firstSoundMs) -90f else -12f }
        val low = FloatArray(steps) { i -> if (i * STEP < firstSoundMs) -90f else if (i * STEP < kickEnd) -50f else -20f }
        return SongEnd(
            kind = RegionKind.HEAD, songMs = songMs, fromMs = 0.0, toMs = regionMs, sampleRate = 44_100, channels = 2,
            firstSoundMs = firstSoundMs, trueEndMs = regionMs, outroStartMs = regionMs,
            introEndMs = introEndBars?.let { firstDownbeatMs + it * beatsPerBar * beatMs }, fadeMs = null, medianDb = -12.0,
            levelDb = level, lowDb = low, stepMs = STEP, bpm = bpm, steadyTempo = steadyTempo, line = line,
            phrases = phrases(line, 0, 8, true), key = key, density = density, trustNote = "made by hand",
            voice = voice?.let { f -> FloatArray(steps) { i -> f(i * STEP).toFloat() } },
        )
    }

    /** A beatless end: no tempo, no grid. */
    fun noBeat(kind: RegionKind, songMs: Double = 200_000.0, regionMs: Double = 60_000.0, fadeMs: Double? = null, firstSoundMs: Double? = null): SongEnd {
        val from = if (kind == RegionKind.TAIL) songMs - regionMs else 0.0
        val steps = (regionMs / STEP).toInt()
        val level = FloatArray(steps) { i ->
            val ms = from + i * STEP
            if (fadeMs != null && kind == RegionKind.TAIL && ms >= songMs - fadeMs) (-12.0 - 48.0 * (ms - (songMs - fadeMs)) / fadeMs).toFloat() else -12f
        }
        return SongEnd(
            kind = kind, songMs = songMs, fromMs = from, toMs = from + regionMs, sampleRate = 44_100, channels = 2,
            firstSoundMs = firstSoundMs ?: (from + 30), trueEndMs = from + regionMs - 20, outroStartMs = from + regionMs - 20,
            introEndMs = null, fadeMs = fadeMs, medianDb = -12.0, levelDb = level, lowDb = FloatArray(steps) { -30f }, stepMs = STEP,
            bpm = null, steadyTempo = false, line = null, phrases = null, key = null, density = 1.0, trustNote = "no beat",
        )
    }

    /** Every bar line of [line]'s trusted stretch, with its phrase level counted from the bar [offsetBars] in. */
    fun phrases(line: BeatLine, offsetBars: Int, phraseBars: Int, heard: Boolean): PhraseMap {
        val first = line.downbeatAtOrAfter(line.firstK)
        val beats = ArrayList<Int>()
        var k = first
        while (k <= line.lastK + line.beatsPerBar) {
            beats += k
            k += line.beatsPerBar
        }
        val level = IntArray(beats.size) { j ->
            val bar = j - offsetBars
            when {
                Math.floorMod(bar, 2 * phraseBars) == 0 && phraseBars >= 8 -> 16
                Math.floorMod(bar, phraseBars) == 0 -> phraseBars
                Math.floorMod(bar, 4) == 0 -> 4
                Math.floorMod(bar, 2) == 0 -> 2
                else -> 1
            }
        }
        val strength = DoubleArray(beats.size) { j -> if (level[j] >= 8) 1.5 else 0.3 }
        return PhraseMap(beats.toIntArray(), level, strength, heard, if (heard) 3.0 else 0.0)
    }
}
