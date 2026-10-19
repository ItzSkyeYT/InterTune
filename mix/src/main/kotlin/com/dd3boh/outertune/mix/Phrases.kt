/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The bars of a region and where its phrases turn, for choosing where the outgoing song leaves.
 *
 * Music is built in phrases of 4, 8 and 16 bars, and a mix that ends between two of them sounds
 * like a mistake. So bar lines are counted back from the last strong bar, the last loud bar on
 * the beat, in steps of 16, 8 or 4, and each candidate is scored by how much changes there:
 * Foote's novelty (2000) on the bars' mean spectra, with a checkerboard of 4 bars a side, and how
 * far the level drops across it. Indices here count bar lines: bar line j opens bar j.
 */
class Phrases(
    /** Each bar line, in spectral frames. */
    val barLines: DoubleArray,
    /** The grid beat of each bar line. */
    val barBeats: IntArray,
    /** Foote's novelty at each bar line, 0 to 1. */
    val novelty: DoubleArray,
    /** The level of each bar, from its bar line to the next, in dBFS. */
    val barDb: DoubleArray,
    /** The bar line that closes the last strong bar. */
    val lastStrong: Int,
    /** Bar lines on the 16, 8 and 4 bar grids counted back from [lastStrong], scored. */
    val candidates: List<PhraseCandidate>,
) {
    val bars: Int get() = barLines.size - 1

    companion object {
        /** Bars either side of the novelty kernel's centre. */
        private const val KERNEL = 4

        /** How far under the median a bar may be and still be strong. */
        private const val STRONG_DB = 6.0

        val STEPS = intArrayOf(16, 8, 4)

        /** Null without trusted bars, or with fewer than four of them. */
        fun find(spectrum: Spectrum, grid: BeatGrid, downbeats: Downbeats, envelope: Envelope): Phrases? {
            val firstLine = downbeats.firstAtOrAfter(grid.firstBeat)
            val lines = ArrayList<Int>()
            var k = firstLine
            // Bar lines through the trusted stretch and the one that closes its last bar.
            while (k <= grid.lastBeat + Downbeats.BEATS_PER_BAR) {
                if (grid.frameOf(k) > spectrum.frames - 1) break
                lines += k
                k += Downbeats.BEATS_PER_BAR
            }
            if (lines.size < 5) return null
            val barLines = DoubleArray(lines.size) { grid.frameOf(lines[it]) }
            val bars = lines.size - 1

            // Each bar's mean spectrum, centred on the mean of all bars, for a cosine similarity
            // that measures change rather than how alike any two loud bars are.
            val vectors = Array(bars) { j ->
                val from = barLines[j].toInt().coerceIn(0, spectrum.frames - 1)
                val to = barLines[j + 1].toInt().coerceIn(from + 1, spectrum.frames)
                DoubleArray(Spectrum.BANDS) { b ->
                    var sum = 0.0
                    for (t in from until to) sum += spectrum.mel(t, b)
                    sum / (to - from)
                }
            }
            val centre = DoubleArray(Spectrum.BANDS) { b -> vectors.sumOf { it[b] } / bars }
            for (v in vectors) for (b in v.indices) v[b] -= centre[b]
            val norms = DoubleArray(bars) { j -> sqrt(vectors[j].sumOf { it * it }).coerceAtLeast(1e-9) }
            fun similarity(i: Int, j: Int): Double {
                var dot = 0.0
                for (b in 0 until Spectrum.BANDS) dot += vectors[i][b] * vectors[j][b]
                return dot / (norms[i] * norms[j])
            }

            val raw = DoubleArray(lines.size)
            for (line in 0..bars) {
                var sum = 0.0
                var weight = 0.0
                for (a in -KERNEL until KERNEL) for (b in -KERNEL until KERNEL) {
                    val i = line + a
                    val j = line + b
                    if (i !in 0 until bars || j !in 0 until bars) continue
                    val sign = if ((a < 0) == (b < 0)) 1.0 else -1.0
                    val taper = exp(-((a + 0.5) * (a + 0.5) + (b + 0.5) * (b + 0.5)) / (2.0 * 2.0 * 2.0))
                    sum += sign * taper * similarity(i, j)
                    weight += taper
                }
                raw[line] = if (weight > 0) max(0.0, sum / weight) else 0.0
            }
            val top = raw.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val novelty = DoubleArray(raw.size) { raw[it] / top }

            val barDb = DoubleArray(bars) { j ->
                val from = spectrum.frameToSample(barLines[j]).toLong()
                val to = spectrum.frameToSample(barLines[j + 1]).toLong()
                envelope.meanDb(from, to)
            }

            // The last strong bar: loud, and wholly on the trusted grid.
            var lastStrong = -1
            for (j in bars - 1 downTo 0) {
                val onGrid = lines[j] + Downbeats.BEATS_PER_BAR - 1 <= grid.lastBeat
                if (onGrid && barDb[j] >= envelope.medianDb - STRONG_DB) {
                    lastStrong = j + 1
                    break
                }
            }
            if (lastStrong < 0) return null

            val candidates = ArrayList<PhraseCandidate>()
            for (step in STEPS) {
                var line = lastStrong
                while (line >= 0) {
                    candidates += PhraseCandidate(line, step, score(line, novelty, barDb))
                    line -= step
                }
            }
            return Phrases(barLines, lines.toIntArray(), novelty, barDb, lastStrong, candidates)
        }

        /**
         * A candidate's worth: its novelty, plus how far the level falls across it, a fall of
         * 6 dB or more counting as much as the strongest novelty. Past the last bar counts as
         * silence, the strongest fall there is.
         */
        private fun score(line: Int, novelty: DoubleArray, barDb: DoubleArray): Double {
            val before = (max(0, line - 2) until line).map { barDb[it] }
            val after = (line until min(barDb.size, line + 2)).map { barDb[it] }
            val drop = when {
                before.isEmpty() -> 0.0
                after.isEmpty() -> 1.0
                else -> ((before.average() - after.average()) / STRONG_DB).coerceIn(-1.0, 1.0)
            }
            return novelty[line] + drop
        }
    }
}

/** A bar line where the outgoing song could leave, on the grid of [step] bars. */
class PhraseCandidate(val line: Int, val step: Int, val score: Double)
