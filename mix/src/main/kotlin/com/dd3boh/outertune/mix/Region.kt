/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    /** The decimated frame [guide] starts at: 0, but for an opening kept with only the first seconds of its music. */
    val guideFrom: Int = 0,
    /**
     * Each hop's peak on any channel through a 4th-order high-pass at 120 Hz and through a
     * 2nd-order one at 400 Hz, 0 to 1: for an opening, the incoming song as a blend filters it,
     * whose peaks a filter can raise above the song's own. Null where they were not measured.
     */
    val hopPeakAbove120: FloatArray? = null,
    val hopPeakAbove400: FloatArray? = null,
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
                val back = k - bars.beatsPerBar
                if (back >= g.firstBeat - bars.beatsPerBar && beatFrame(back) >= earliest) k = back
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

    /** The highest peak on any channel between [from] and [to] in [peaks], 0 to 1; 1 where nothing is known. */
    fun peakBetween(from: Long, to: Long, peaks: FloatArray = hopPeak): Float {
        val lo = (from / hopFrames).toInt()
        val hi = ((to + hopFrames - 1) / hopFrames).toInt()
        var peak = 0f
        for (h in lo until hi) peak = maxOf(peak, if (h in peaks.indices) peaks[h] else 1f)
        return peak
    }

    /** The same region with its beats on another line: the one a mix lines up on, fitted through only the beats it uses. */
    fun withLine(line: BeatGrid.Line): RegionAnalysis {
        val g = grid ?: return this
        return copy(rhythm = Rhythm(rhythm.tempo, g.withLine(line.t0, line.period), rhythm.downbeats, rhythm.gates))
    }

    /**
     * The same region keeping only the stretch of its guide from full-rate frame [from] to [to],
     * for an opening kept for the next time its song comes next: what the check that the incoming
     * song lines up with its opening reads, without the rest of the copy.
     */
    fun keepingGuide(from: Long, to: Long): RegionAnalysis {
        val lo = (from / decimation).toInt().coerceIn(guideFrom, guideFrom + guide.size)
        val hi = (to / decimation).toInt().coerceIn(lo, guideFrom + guide.size)
        return copy(guide = guide.copyOfRange(lo - guideFrom, hi - guideFrom), guideFrom = lo)
    }

    private fun copy(rhythm: Rhythm = this.rhythm, guide: FloatArray = this.guide, guideFrom: Int = this.guideFrom) = RegionAnalysis(
        kind = kind, sampleRate = sampleRate, channels = channels, frames = frames, envelope = envelope, rhythm = rhythm,
        phrases = phrases, onset = onset, hopPeak = hopPeak, hopFrames = hopFrames, guide = guide, decimation = decimation,
        analysisMillis = analysisMillis, guideFrom = guideFrom, hopPeakAbove120 = hopPeakAbove120, hopPeakAbove400 = hopPeakAbove400,
    )

    /**
     * The same region with its beats laid at [bpm], a tempo the listener set by hand (tapped, typed,
     * halved or doubled where nothing was found): the beats are found again near that tempo on this
     * region's own onsets, so a tap a little off lands on the music, and they are trusted because
     * the listener said so. The bars are the downbeat finder's on the new beats, for the fix that
     * comes with the tempo to move. Null where the region has no sound to lay a beat on.
     */
    fun atTempo(bpm: Double): RegionAnalysis? {
        if (!(bpm.isFinite() && bpm in 20.0..400.0) || guideFrom != 0) return null
        val spectrum = Spectrum.of(guide, sampleRate.toDouble() / decimation, decimation)
        val frameRate = spectrum.frameRate
        val from = spectrum.sampleToFrame(envelope.firstSound.toDouble()).toInt().coerceIn(0, spectrum.frames)
        val to = spectrum.sampleToFrame(envelope.trueEnd.toDouble()).toInt().coerceIn(from, spectrum.frames)
        val period = Tempo.periodFor(bpm, frameRate)
        if (to - from < 4 * period) return null
        // Found again only where the music has a beat there to find; a pad keeps the listener's tempo as it is.
        val tracked = BeatGrid.track(spectrum.onset, frameRate, period, from, to)?.takeIf {
            abs(it.period / period - 1) <= HAND_TEMPO_REACH && it.hitShare >= Gates.MIN_HITS && it.jitterMs <= Gates.MAX_JITTER_MS && it.activeBeats >= Gates.MIN_BEATS
        }
        val grid = tracked ?: run {
            // Nothing steady near that tempo: the listener's own, on the phase its pulses land best.
            val (phase, _) = Tempo.bestPhase(spectrum.onset, from, to, period)
            val start = from + phase
            val t0 = start - kotlin.math.floor(start / period) * period
            val k0 = kotlin.math.ceil((from - t0) / period).toInt()
            val k1 = kotlin.math.floor((to - 1 - t0) / period).toInt()
            BeatGrid(t0, period, k0, k1, 1.0, 0.0, 0.0, 0.0, DoubleArray(0), frameRate)
        }
        val downbeats = Downbeats.find(spectrum, grid)
        val g = rhythm.gates
        val gates = Gates(
            g.tempoZ, g.rivalRatio, grid.steadiness, grid.drift, grid.hitShare, grid.jitterMs, grid.activeBeats,
            downbeats?.margin ?: 0.0, byHand = true,
        )
        return copy(rhythm = Rhythm(rhythm.tempo, grid, downbeats, gates))
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
        /** How near a beat an opening's first sound must land for that beat to be taken for its one. */
        const val FIRST_SOUND_ON_BEAT_MS = 60.0

        /** How far from a tempo set by hand the beats found again may run: a tap is good to about this. */
        const val HAND_TEMPO_REACH = 0.03

        /** The onset strength, in deviations over the local floor, a first sound must be struck with. */
        private const val FIRST_SOUND_ONSET = 2f

        /** The margin a bar line taken from the first sound is given: as sure as the gate asks, no surer. */
        private const val FIRST_SOUND_MARGIN = Gates.MIN_MARGIN

        /**
         * An opening's bars from its first sound: where the song starts on a beat of its trusted
         * grid, that beat is bar 1, whatever the downbeat finder says. Songs start on their one:
         * held against the Harmonix annotations (mix-better, 2 Nov 2026), the beat an opening's first
         * sound lands on was the one in 12 of 13 songs that start on a beat, where the finder's
         * phase was right in 6 of 22 openings. A first sound off the beats (a pickup, a beatless
         * intro) leaves the finder's answer as it is.
         */
        internal fun firstSoundIsOne(rhythm: Rhythm, envelope: Envelope, front: FrontEndResult, onset: FloatArray): Rhythm {
            val grid = rhythm.grid ?: return rhythm
            if (!rhythm.gates.beats) return rhythm
            val factor = front.factor
            fun frameOf(k: Int) = (grid.frameOf(k) * Spectrum.HOP + Spectrum.ONSET_LAG) * factor
            val first = envelope.firstSound.toDouble()
            val k = grid.indexNear((first / factor - Spectrum.ONSET_LAG) / Spectrum.HOP)
            if (abs(frameOf(k) - first) * 1000.0 / front.sampleRate > FIRST_SOUND_ON_BEAT_MS) return rhythm
            // And it must be struck there: a sound already going, or one fading in, is no bar line.
            if ((Peaks.nearest(onset, grid.frameOf(k), 2)?.value ?: 0f) < FIRST_SOUND_ONSET) return rhythm
            // The grid must hold from there: a first sound before the trusted beats begin is an intro.
            if (k < grid.firstBeat - 1 || k > grid.firstBeat + 1) return rhythm
            val bars = rhythm.downbeats
            val beatsPerBar = bars?.beatsPerBar ?: Downbeats.BEATS_PER_BAR
            val phase = Math.floorMod(k, beatsPerBar)
            val agrees = bars != null && bars.isDownbeat(k)
            val margin = if (agrees) max(bars!!.margin, FIRST_SOUND_MARGIN) else FIRST_SOUND_MARGIN
            val downbeats = Downbeats(phase, margin, bars?.scores ?: DoubleArray(Downbeats.BEATS_PER_BAR), beatsPerBar, bars?.halfMargin ?: 0.0, fromFirstSound = true)
            val g = rhythm.gates
            val gates = Gates(g.tempoZ, g.rivalRatio, g.steadiness, g.drift, g.hitShare, g.jitterMs, g.activeBeats, margin, barsFromFirstSound = true)
            return Rhythm(rhythm.tempo, grid, downbeats, gates)
        }

        /** The opening of the song coming in. */
        fun analyseHead(front: FrontEndResult): RegionAnalysis = analyse(front, RegionKind.HEAD)

        /** The held end of the song going out. */
        fun analyseTail(front: FrontEndResult): RegionAnalysis = analyse(front, RegionKind.TAIL)

        fun analyse(samples: ShortArray, sampleRate: Int, channels: Int, kind: RegionKind): RegionAnalysis =
            analyse(FrontEnd.of(samples, sampleRate, channels, filteredPeaks = kind == RegionKind.HEAD), kind)

        fun analyse(front: FrontEndResult, kind: RegionKind): RegionAnalysis {
            val started = System.nanoTime()
            val spectrum = Spectrum.of(front)
            val rough = Envelope.of(front)
            // Rhythm only where there is sound, so leading or trailing silence does not thin it.
            val from = spectrum.sampleToFrame(rough.firstSound.toDouble()).toInt().coerceIn(0, spectrum.frames)
            val to = spectrum.sampleToFrame(rough.trueEnd.toDouble()).toInt().coerceIn(from, spectrum.frames)
            val rhythm = Rhythm.analyse(spectrum, from, to).let { if (kind == RegionKind.HEAD) firstSoundIsOne(it, rough, front, spectrum.onset) else it }
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
                hopPeakAbove120 = front.hopPeakAbove120,
                hopPeakAbove400 = front.hopPeakAbove400,
            )
        }
    }
}
