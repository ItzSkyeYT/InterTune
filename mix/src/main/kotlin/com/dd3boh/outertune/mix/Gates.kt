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
) {
    val tempo: Boolean get() = tempoZ >= MIN_Z && rivalRatio <= MAX_RIVAL && steadiness <= MAX_SPREAD && drift <= MAX_DRIFT

    val beats: Boolean get() = tempo && hitShare >= MIN_HITS && jitterMs <= MAX_JITTER_MS && activeBeats >= MIN_BEATS

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
        const val MIN_MARGIN = 2.0

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
