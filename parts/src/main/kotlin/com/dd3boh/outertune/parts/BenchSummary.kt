/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

/** One segment in a bench run: which worker took it, when it started after the run did, and each step's time. */
data class SegmentTime(
    val index: Int,
    val worker: Int,
    val startNanos: Long,
    val forwardNanos: Long,
    val modelNanos: Long,
    val inverseNanos: Long,
) {
    val totalNanos: Long get() = forwardNanos + modelNanos + inverseNanos
}

/**
 * A bench run's numbers. Each worker's first segment is its warm-up (the runtime sizes its buffers
 * then: 89 s against 17 s on the Pixel 5 at one thread on 19 Oct), so the steady figures leave it
 * out when a worker had more than one. Kotlin is the two transforms around the model, timed apart
 * so a debuggable build's slower Kotlin can be read past.
 */
class BenchSummary(
    val times: List<SegmentTime>,
    val wallNanos: Long,
    val frames: Int,
    val sessions: Int,
) {
    val steady: List<SegmentTime> = times.groupBy { it.worker }.values
        .flatMap { mine -> mine.sortedBy { it.startNanos }.drop(1) }
        .ifEmpty { times }

    val audioSeconds: Double get() = frames.toDouble() / Scnet.RATE

    /** Wall seconds per second of audio for the whole run, warm-ups included. */
    val wallPerAudioSecond: Double get() = wallNanos / 1e9 / audioSeconds

    fun meanSeconds(of: (SegmentTime) -> Long): Double = steady.map(of).average() / 1e9

    /** The share of a steady segment spent in our Kotlin, both transforms. */
    val kotlinShare: Double get() = steady.sumOf { it.forwardNanos + it.inverseNanos }.toDouble() / steady.sumOf { it.totalNanos }

    /**
     * Wall seconds per second of audio once warm, all sessions side by side, at [overlap]: a
     * segment brings SEGMENT / overlap new samples. [withoutKotlin] counts the model alone.
     */
    fun steadyPerAudioSecond(overlap: Double, withoutKotlin: Boolean = false): Double {
        val segment = if (withoutKotlin) meanSeconds { it.modelNanos } else meanSeconds { it.totalNanos }
        return segment / sessions / (Scnet.SEGMENT / overlap / Scnet.RATE)
    }
}
