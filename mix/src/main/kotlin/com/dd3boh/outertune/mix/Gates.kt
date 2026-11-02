/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

/**
 * Whether a region's rhythm can be trusted, as gates and not as a weighted sum.
 *
 * Each stage has to pass on its own: a tempo that stands well above chance and has no close 3:2
 * rival and does not wander, a line of beats that keeps landing on onsets, and one phase of four
 * that clearly wins the bar. A sum would let a strong beat buy a weak downbeat, and a downbeat
 * guessed wrong puts the next song's one on this song's three, which no tightness of beats saves.
 * A gate that fails only drops a style: beats without downbeats still allow a plain blend.
 *
 * The thresholds are set for precision first, on the synthetic songs of the tests and then on
 * real ones: a confident answer must be right, and the share of songs that are confident is
 * whatever it turns out to be.
 */
class Gates(
    val tempoZ: Double,
    val rivalRatio: Double,
    val steadiness: Double,
    val drift: Double,
    val hitShare: Double,
    val jitterMs: Double,
    val activeBeats: Int,
    val downbeatMargin: Double,
    /** The bars were taken from an opening's first sound on a beat ([RegionAnalysis.firstSoundIsOne]). */
    val barsFromFirstSound: Boolean = false,
    /** The listener set this tempo by hand ([RegionAnalysis.atTempo]): the tempo and the beats are theirs to trust. */
    val byHand: Boolean = false,
    /** The tempo is the whole song's ([RegionAnalysis.onSong]), and so are the tempo's gate values. */
    val fromSong: Boolean = false,
) {
    val tempo: Boolean get() = byHand || tempoZ >= MIN_Z && rivalRatio <= MAX_RIVAL && steadiness <= MAX_SPREAD && drift <= MAX_DRIFT

    /**
     * How sure the beats are, 0 to 1, for the editor to say when it is not: each of the beat
     * gates read as a score that is 0.5 exactly at its limit, 1 at twice as clear and 0 at twice
     * as far off, and the lowest of them taken. So [beats] is exactly a confidence of [SURE] or
     * more. Held against the Harmonix annotations (mix-better, 2 Nov 2026, 89 windows of 23
     * songs): 92% of the windows at [SURE] or more had their beats right (tempo within 1%, 90% of
     * the beats within 25 ms), and 88% of the windows whose beats were right got there.
     */
    val beatConfidence: Double
        get() {
            if (byHand) return 1.0
            val scores = doubleArrayOf(
                higher(tempoZ, MIN_Z),
                lower(rivalRatio, MAX_RIVAL),
                lower(steadiness, MAX_SPREAD),
                lower(drift, MAX_DRIFT),
                // A share cannot pass 1: 0.5 at its limit, 1 when every beat lands, 0 as far under the limit.
                if (!hitShare.isFinite()) 0.0 else (0.5 + 0.5 * (hitShare - MIN_HITS) / (1.0 - MIN_HITS)).coerceIn(0.0, 1.0),
                lower(jitterMs, MAX_JITTER_MS),
                higher(activeBeats.toDouble(), MIN_BEATS.toDouble()),
            )
            return scores.min()
        }

    /**
     * How sure bar 1 is, 0 to 1: a bar taken from an opening's first sound on a beat is
     * [FIRST_SOUND_BARS] (12 of 13 right on the annotations); otherwise the downbeat margin read
     * as the beat gates are, 0.5 at [MIN_MARGIN], and never surer than the beats. [SURE] or more is
     * [downbeats]. On the annotations the bars at [SURE] or more were right in about two windows
     * of three, however wide the margin: bar 1 is the guess the editor says it is.
     */
    val barConfidence: Double
        get() {
            val beatsSure = beatConfidence
            if (barsFromFirstSound) return minOf(beatsSure.coerceAtLeast(SURE), FIRST_SOUND_BARS)
            return minOf(beatsSure, higher(downbeatMargin, MIN_MARGIN))
        }

    val beats: Boolean get() = byHand || tempo && hitShare >= MIN_HITS && jitterMs <= MAX_JITTER_MS && activeBeats >= MIN_BEATS

    val downbeats: Boolean get() = beats && downbeatMargin >= MIN_MARGIN

    /** What failed, in words, for the logs and the trial's summary. */
    fun failures(): List<String> = buildList {
        if (tempoZ < MIN_Z) add("pulse %.1f deviations above chance".format(tempoZ))
        if (rivalRatio > MAX_RIVAL) add("3:2 rival at %.0f%%".format(rivalRatio * 100))
        if (steadiness > MAX_SPREAD) add(if (steadiness.isInfinite()) "too few beats to judge steadiness" else "tempo spread %.1f%%".format(steadiness * 100))
        if (drift > MAX_DRIFT && drift.isFinite()) add("tempo drifts %.1f%%".format(drift * 100))
        if (hitShare < MIN_HITS) add("%.0f%% of beats on onsets".format(hitShare * 100))
        if (jitterMs > MAX_JITTER_MS) add("beats %.0f ms off the line".format(jitterMs))
        if (activeBeats < MIN_BEATS) add("only $activeBeats beats on the grid")
        if (downbeatMargin < MIN_MARGIN) add("downbeat margin %.1f".format(downbeatMargin))
    }

    companion object {
        const val MIN_Z = 4.0
        const val MAX_RIVAL = 0.85
        const val MAX_SPREAD = 0.02
        const val MAX_DRIFT = 0.01
        const val MIN_HITS = 0.8
        const val MAX_JITTER_MS = 15.0
        const val MIN_BEATS = 16
        const val MIN_MARGIN = 1.5

        /** A confidence of this or more is what the gates call sure. */
        const val SURE = 0.5

        /** The bar confidence of an opening that starts on a beat. */
        const val FIRST_SOUND_BARS = 0.9

        /** 0.5 at [limit] for a value that is better high: 1 at twice it, 0 at none. */
        private fun higher(value: Double, limit: Double): Double =
            if (!value.isFinite()) 0.0 else (0.5 * value / limit).coerceIn(0.0, 1.0)

        /** 0.5 at [limit] for a value that is better low: 1 at none, 0 at twice it. */
        private fun lower(value: Double, limit: Double): Double =
            if (!value.isFinite()) 0.0 else (1.0 - 0.5 * value / limit).coerceIn(0.0, 1.0)

        /** Nothing was found, so nothing passes. */
        val NONE = Gates(0.0, 1.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, Double.POSITIVE_INFINITY, 0, 0.0)
    }
}

/** A region's tempo, beats and bars, with the gates that say how far each can be trusted. */
class Rhythm(
    val tempo: TempoEstimate?,
    val grid: BeatGrid?,
    val downbeats: Downbeats?,
    val gates: Gates,
) {
    /** The grid's own tempo when there is one, which is the precise figure, else the estimate's. */
    val bpm: Double? get() = grid?.bpm ?: tempo?.bpm

    companion object {
        /** Tempo, then beats at that tempo, then bars on those beats, between frames [from] and [to]. */
        fun analyse(spectrum: Spectrum, from: Int = 0, to: Int = spectrum.frames): Rhythm {
            val tempo = Tempo.estimate(spectrum.onset, spectrum.frameRate, from, to)
                ?: return Rhythm(null, null, null, Gates.NONE)
            val grid = BeatGrid.track(spectrum.onset, spectrum.frameRate, tempo.periodFrames, from, to)
            val downbeats = grid?.let { Downbeats.find(spectrum, it) }
            val gates = Gates(
                tempoZ = tempo.z,
                rivalRatio = tempo.rivalRatio,
                steadiness = grid?.steadiness ?: Double.POSITIVE_INFINITY,
                drift = grid?.drift ?: Double.POSITIVE_INFINITY,
                hitShare = grid?.hitShare ?: 0.0,
                jitterMs = grid?.jitterMs ?: Double.POSITIVE_INFINITY,
                activeBeats = grid?.activeBeats ?: 0,
                downbeatMargin = downbeats?.margin ?: 0.0,
            )
            return Rhythm(tempo, grid, downbeats, gates)
        }
    }
}
