/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs

/** A region of a synthetic song run through the analysis, with its answers in the song's own time. */
class Heard(val song: SynthSong, val fromSeconds: Double, val spectrum: Spectrum, val rhythm: Rhythm) {

    private val rate get() = song.sampleRate

    /** Where a spectral frame of the region falls in the song, in seconds. */
    fun secondsOf(frame: Double): Double = fromSeconds + spectrum.frameToSample(frame) / rate

    /** The grid's beats over its trusted stretch, in song seconds. */
    fun beats(): DoubleArray {
        val grid = rhythm.grid ?: return DoubleArray(0)
        return DoubleArray(grid.activeBeats) { secondsOf(grid.frameOf(grid.firstBeat + it)) }
    }

    /** The grid's downbeats over its trusted stretch, in song seconds. */
    fun downbeats(): DoubleArray {
        val grid = rhythm.grid ?: return DoubleArray(0)
        val bars = rhythm.downbeats ?: return DoubleArray(0)
        return (grid.firstBeat..grid.lastBeat).filter { bars.isDownbeat(it) }.map { secondsOf(grid.frameOf(it)) }.toDoubleArray()
    }

    /** The beats the song really has where the grid claims to know them. */
    fun trueBeats(): DoubleArray {
        val b = beats()
        if (b.isEmpty()) return DoubleArray(0)
        return Truth.within(song.beats, b.first() - 0.1, b.last() + 0.1)
    }

    /** The F-measure of the grid against the true beats of its stretch. */
    fun beatF(): Double = Truth.fMeasure(beats(), trueBeats())

    /** The share of the grid's downbeats that fall within 70 ms of a true one. */
    fun downbeatAccuracy(): Double {
        val d = downbeats()
        if (d.isEmpty()) return 0.0
        return d.count { t -> song.allDownbeats.any { abs(it - t) <= 0.07 } }.toDouble() / d.size
    }

    /** The tempo error of the grid against the song's tempo, after folding, as a fraction. */
    fun tempoError(): Double {
        val bpm = rhythm.bpm ?: return 1.0
        return Tempo.distance(bpm, song.spec.bpm)
    }

    fun line(label: String): String {
        val g = rhythm.gates
        return "%-28s bpm %7.3f (raw %6.1f) z %5.1f rival %.2f spread %.4f hits %.2f jitter %5.1f ms beats %3d margin %.2f | F %.3f down %.2f | %s%s%s".format(
            label, rhythm.bpm ?: 0.0, rhythm.tempo?.rawBpm ?: 0.0, g.tempoZ, g.rivalRatio, g.steadiness, g.hitShare, g.jitterMs,
            g.activeBeats, g.downbeatMargin, beatF(), downbeatAccuracy(),
            if (g.tempo) "T" else "-", if (g.beats) "B" else "-", if (g.downbeats) "D" else "-",
        )
    }

    companion object {
        /** The analysis of [song] between [fromSeconds] and [toSeconds]. */
        fun of(song: SynthSong, fromSeconds: Double = 0.0, toSeconds: Double = song.seconds): Heard {
            val front = FrontEnd.of(song.slice(fromSeconds, toSeconds), song.sampleRate, 2)
            val spectrum = Spectrum.of(front)
            return Heard(song, fromSeconds, spectrum, Rhythm.analyse(spectrum))
        }

        /** The analysis of raw [samples], for signals that are not songs. */
        fun rhythmOf(samples: ShortArray, sampleRate: Int = 44_100): Rhythm =
            Rhythm.analyse(Spectrum.of(FrontEnd.of(samples, sampleRate, 2)))
    }
}
