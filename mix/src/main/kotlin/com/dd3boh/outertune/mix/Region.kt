/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

/** Which end of a song a region is. */
enum class RegionKind {
    /** The opening of the song coming in, read ahead of time. */
    HEAD,

    /** The held end of the song going out, exactly as it will be heard. */
    TAIL,
}

/**
 * Everything the planner needs to know about one end of a song, every position in full-rate frames
 * from the start of the region: the start of the song for a head, the first held frame for a tail.
 */
class RegionAnalysis(
    val kind: RegionKind,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val envelope: Envelope,
    val rhythm: Rhythm,
    val phrases: Phrases?,
    /** The onset curve, kept for the check that the live stream lines up with what was analysed. */
    val onset: FloatArray,
    /** Each hop's peak on any channel, 0 to 1, for headroom. */
    val hopPeak: FloatArray,
    val hopFrames: Int,
    /** The decimated mono copy the analysis read, at a [decimation]-th of the rate: the stretcher's guide. */
    val guide: FloatArray,
    val decimation: Int,
    val analysisMillis: Double,
) {
    private val factor: Int get() = decimation

    val grid: BeatGrid? get() = rhythm.grid
    val downbeats: Downbeats? get() = rhythm.downbeats
    val gates: Gates get() = rhythm.gates
    val bpm: Double? get() = rhythm.bpm

    val beatsConfident: Boolean get() = gates.beats && grid != null
    val barsConfident: Boolean get() = gates.downbeats && grid != null && downbeats != null

    /** The beat period in full-rate frames, when there is a grid. */
    val periodFrames: Double? get() = grid?.let { it.period * Spectrum.HOP * factor }

    /** Where grid beat [k] falls, in full-rate frames. */
    fun beatFrame(k: Int): Double {
        val g = grid ?: error("no grid")
        return (g.frameOf(k) * Spectrum.HOP + Spectrum.ONSET_LAG) * factor
    }

    /** The grid beat nearest full-rate frame [frame]. */
    fun beatNear(frame: Double): Int {
        val g = grid ?: error("no grid")
        return g.indexNear((frame / factor - Spectrum.ONSET_LAG) / Spectrum.HOP)
    }

    /**
     * The first downbeat, as a grid index: the first of the trusted stretch, or for an opening, as
     * much as a bar earlier along the same line if that is still at or after the first sound. An
     * opening's first beat often cannot be heard as an onset (a kick on the very first frame has no
     * frame before it to rise from, and a first beat is often soft), and the line is good to a few
     * milliseconds over a single bar.
     */
    val firstDownbeat: Int?
        get() {
            val g = grid ?: return null
            val bars = downbeats ?: return null
            var k = bars.firstAtOrAfter(g.firstBeat)
            if (kind == RegionKind.HEAD) {
                val earliest = envelope.firstSound - 0.03 * sampleRate
                val back = k - Downbeats.BEATS_PER_BAR
                if (back >= g.firstBeat - Downbeats.BEATS_PER_BAR && beatFrame(back) >= earliest) k = back
            }
            return k
        }

    /** Every beat of the trusted stretch, in full-rate frames: the anchors for stretching this region. */
    fun beatFrames(): DoubleArray {
        val g = grid ?: return DoubleArray(0)
        return DoubleArray(g.activeBeats) { beatFrame(g.firstBeat + it) }
    }

    /** The spectral frame of full-rate frame [frame]. */
    fun spectralFrame(frame: Double): Double = (frame / factor - Spectrum.ONSET_LAG) / Spectrum.HOP

    /** Onsets a second between [from] and [to], counting peaks at least one deviation above the floor. */
    fun onsetDensity(from: Long, to: Long): Double {
        val lo = spectralFrame(from.toDouble()).toInt().coerceIn(0, onset.size)
        val hi = spectralFrame(to.toDouble()).toInt().coerceIn(lo, onset.size)
        if (hi <= lo) return 0.0
        val count = Peaks.of(onset, 1f, 3, lo, hi).size
        return count / ((to - from).toDouble() / sampleRate)
    }

    /** The highest peak on any channel between [from] and [to], 0 to 1; 1 where nothing is known. */
    fun peakBetween(from: Long, to: Long): Float {
        val lo = (from / hopFrames).toInt()
        val hi = ((to + hopFrames - 1) / hopFrames).toInt()
        var peak = 0f
        for (h in lo until hi) peak = maxOf(peak, if (h in hopPeak.indices) hopPeak[h] else 1f)
        return peak
    }

    /** A line for the logs and the trial's summary. */
    fun describe(): String {
        val g = gates
        val tempo = bpm?.let { "%.2f BPM".format(it) } ?: "no tempo"
        val trust = when {
            barsConfident -> "beats and bars"
            beatsConfident -> "beats, not bars"
            g.tempo -> "tempo only"
            else -> "nothing"
        }
        return "$tempo, trusted: $trust" + (if (barsConfident) "" else " (" + g.failures().joinToString("; ") + ")")
    }

    companion object {
        /** The opening of the song coming in. */
        fun analyseHead(front: FrontEndResult): RegionAnalysis = analyse(front, RegionKind.HEAD)

        /** The held end of the song going out. */
        fun analyseTail(front: FrontEndResult): RegionAnalysis = analyse(front, RegionKind.TAIL)

        fun analyse(samples: ShortArray, sampleRate: Int, channels: Int, kind: RegionKind): RegionAnalysis =
            analyse(FrontEnd.of(samples, sampleRate, channels), kind)

        fun analyse(front: FrontEndResult, kind: RegionKind): RegionAnalysis {
            val started = System.nanoTime()
            val spectrum = Spectrum.of(front)
            val rough = Envelope.of(front)
            // Rhythm only where there is sound, so leading or trailing silence does not thin it.
            val from = spectrum.sampleToFrame(rough.firstSound.toDouble()).toInt().coerceIn(0, spectrum.frames)
            val to = spectrum.sampleToFrame(rough.trueEnd.toDouble()).toInt().coerceIn(from, spectrum.frames)
            val rhythm = Rhythm.analyse(spectrum, from, to)
            val bar = rhythm.bpm?.let { 240.0 / it } ?: 2.0
            val envelope = if (rhythm.bpm != null) Envelope.of(front, bar) else rough
            val phrases = if (rhythm.grid != null && rhythm.downbeats != null) Phrases.find(spectrum, rhythm.grid, rhythm.downbeats, envelope) else null
            return RegionAnalysis(
                kind = kind,
                sampleRate = front.sampleRate,
                channels = front.channels,
                frames = front.frames,
                envelope = envelope,
                rhythm = rhythm,
                phrases = phrases,
                onset = spectrum.onset,
                hopPeak = front.hopPeak,
                hopFrames = front.hopFrames,
                guide = front.mono,
                decimation = front.factor,
                analysisMillis = (System.nanoTime() - started) / 1e6,
            )
        }
    }
}
