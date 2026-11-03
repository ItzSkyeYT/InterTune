/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A song's beats read from the whole song once, the way Mixxx does by default ("Assume constant
 * tempo"): one tempo and one straight line for all of it, the tempo rounded to a whole BPM wherever
 * that still keeps the line within [FIT_MS] of the beats heard at both ends. Kept with the song, so
 * the windows read later (the opening the phone reads ahead, the end it holds, the editor's) lay
 * their beats at this tempo ([RegionAnalysis.onSong]) instead of finding one of their own in 30 s,
 * where every miss of the tempo was. A grid the listener set by hand is [locked]: no analysis
 * replaces it.
 *
 * Every time is in ms from the song's first frame as the player plays it (the encoder delay
 * trimmed), beat k at [t0Ms] + k [beatMs]; the tempo is the line's, folded into the analysis's own
 * octave (85 to 170) like every window's.
 */
class WholeSongGrid(
    val bpm: Double,
    val t0Ms: Double,
    /** The beats the line was fitted through: where it is heard to hold. */
    val firstBeat: Int,
    val lastBeat: Int,
    /** The tempo was rounded to a whole (or half) BPM. */
    val rounded: Boolean,
    /** The grid index of a downbeat, and the beats in a bar, where the bars were found; -1 where not. */
    val barPhase: Int,
    val beatsPerBar: Int,
    val tempoSure: Boolean,
    val beatsSure: Boolean,
    val barsSure: Boolean,
    val beatConfidence: Double,
    val barConfidence: Double,
    /** The whole song's gate values, which a window laid on this line is judged by for its tempo. */
    val tempoZ: Double,
    val rivalRatio: Double,
    val steadiness: Double,
    val drift: Double,
    val hitShare: Double,
    val jitterMs: Double,
    val songMs: Double,
    val analysisMs: Double = 0.0,
    /** How many beats the straight line spans, and the share of the song's beats that sit on it. */
    val runBeats: Int = 0,
    val onLine: Double = 0.0,
    val locked: Boolean = false,
    val version: Int = VERSION,
) {
    val beatMs: Double get() = 60_000.0 / bpm

    fun msOf(k: Int): Double = t0Ms + k * beatMs

    /** The grid index nearest [ms]. */
    fun kNear(ms: Double): Int = ((ms - t0Ms) / beatMs).roundToInt()

    /** Whether grid index [k] is a downbeat, where the bars are known. */
    fun isDownbeat(k: Int): Boolean = barPhase >= 0 && Math.floorMod(k - barPhase, beatsPerBar) == 0

    /** The same grid set by the listener: their tempo and bar 1, and locked against any analysis. */
    fun lockedAt(bpm: Double, t0Ms: Double, barPhase: Int = this.barPhase): WholeSongGrid = WholeSongGrid(
        bpm, t0Ms, firstBeat, lastBeat, false, barPhase, beatsPerBar, true, true, barPhase >= 0, 1.0, if (barPhase >= 0) 1.0 else 0.0,
        tempoZ, rivalRatio, steadiness, drift, hitShare, jitterMs, songMs, analysisMs, runBeats, onLine, locked = true,
    )

    /** The same grid, locked against any analysis or not. */
    fun withLock(locked: Boolean): WholeSongGrid = WholeSongGrid(
        bpm, t0Ms, firstBeat, lastBeat, rounded, barPhase, beatsPerBar, tempoSure, beatsSure, barsSure, beatConfidence, barConfidence,
        tempoZ, rivalRatio, steadiness, drift, hitShare, jitterMs, songMs, analysisMs, runBeats, onLine, locked, version,
    )

    fun describe(): String =
        "%.2f BPM%s, %s".format(bpm, if (rounded) " (rounded)" else "", when {
            locked -> "set by hand"
            barsSure -> "beats and bars sure"
            beatsSure -> "beats sure, bars not"
            tempoSure -> "tempo sure"
            else -> "nothing sure"
        }) + ", %d beats straight, %.0f%% of beats on the line, read in %.0f ms".format(runBeats, onLine * 100, analysisMs)

    companion object {
        /** The form's version: a grid of another one is found again when its song next plays, unless locked. */
        const val VERSION = 1

        /** How far off the beats heard at either end of the line a rounded tempo may put it: Mixxx's "inaudible". */
        const val FIT_MS = 25.0

        /** How far a window's own line may run from the song's tempo for its onsets to set the phase of the song's line. */
        const val TEMPO_REACH = 0.01

        /** Whether a window's tempo is the song's, or its half or double, within [TEMPO_REACH]. */
        fun sameTempo(window: Double, song: Double): Boolean =
            doubleArrayOf(0.5, 1.0, 2.0).any { abs(window / (song * it) - 1) <= TEMPO_REACH }

        /** The longest song read whole; past it (a DJ set, an hour of rain) the windows are read as they are. */
        const val MAX_SECONDS = 15 * 60.0

        /** How far the errors of a constant stretch may add up: Mixxx's kMaxSecsPhaseErrorSum. */
        private const val MAX_ERROR_SUM_MS = 100.0

        /** The fewest beats a stretch needs to set or refine the tempo: Mixxx's kMinRegionBeatCount. */
        private const val MIN_RUN_BEATS = 16

        /** How far the line's tempo may be from the estimate the beats were placed at. */
        private const val ESTIMATE_REACH = 0.02

        /**
         * The song read whole, from its first frame, as Mixxx reads one with "Assume constant
         * tempo": the beats placed across the whole song at the estimated tempo, the stretches where
         * they run straight, the longest of them carried to stretches at both ends that sit on the
         * same line, the tempo rounded within what those stretches allow, and the line laid through
         * the beats that sit on it. Null where no straight stretch is found.
         */
        fun analyse(front: FrontEndResult): WholeSongGrid? {
            val started = System.nanoTime()
            val spectrum = Spectrum.of(front)
            val rough = Envelope.of(front)
            val frameRate = spectrum.frameRate
            val from = spectrum.sampleToFrame(rough.firstSound.toDouble()).toInt().coerceIn(0, spectrum.frames)
            val to = spectrum.sampleToFrame(rough.trueEnd.toDouble()).toInt().coerceIn(from, spectrum.frames)
            val tempo = Tempo.estimate(spectrum.onset, frameRate, from, to) ?: return null
            val placed = BeatGrid.dynamicProgramme(spectrum.onset, from, to, tempo.periodFrames)
            if (placed.size < MIN_RUN_BEATS) return null
            val beats = DoubleArray(placed.size) { Peaks.nearest(spectrum.onset, placed[it].toDouble(), 2)?.frame ?: placed[it].toDouble() }
            val maxError = FIT_MS / 1000.0 * frameRate
            val line = constantLine(beats, maxError, MAX_ERROR_SUM_MS / 1000.0 * frameRate) ?: return null
            val bpm = 60.0 * frameRate / line.period
            val whole = roundWithin(60.0 * frameRate / line.longestPeriod, bpm, 60.0 * frameRate / line.shortestPeriod)
            val period = 60.0 * frameRate / whole
            val through = adjustPhase(line.through, period, beats, maxError)
            val t0 = through - kotlin.math.floor(through / period) * period
            val grid = BeatGrid.onLine(spectrum.onset, frameRate, t0, period, from, to) ?: return null
            val downbeats = Downbeats.find(spectrum, grid)
            val near = abs(period / tempo.periodFrames - 1) <= ESTIMATE_REACH
            val gates = Gates(
                if (near) tempo.z else 0.0, tempo.rivalRatio, grid.steadiness, grid.drift, grid.hitShare, grid.jitterMs, grid.activeBeats,
                downbeats?.margin ?: 0.0,
            )
            val rhythm = RegionAnalysis.firstSoundIsOne(Rhythm(tempo, grid, downbeats, gates), rough, front.factor, front.sampleRate, spectrum.onset)
            val g = rhythm.gates
            val bars = rhythm.downbeats
            val toMs = { frame: Double -> (frame * Spectrum.HOP + Spectrum.ONSET_LAG) * front.factor * 1000.0 / front.sampleRate }
            return WholeSongGrid(
                bpm = 60.0 * frameRate / period,
                t0Ms = toMs(t0),
                firstBeat = grid.firstBeat,
                lastBeat = grid.lastBeat,
                rounded = abs(whole - bpm) > 1e-9,
                barPhase = bars?.let { Math.floorMod(it.phase, it.beatsPerBar) } ?: -1,
                beatsPerBar = bars?.beatsPerBar ?: Downbeats.BEATS_PER_BAR,
                tempoSure = g.tempo,
                beatsSure = g.beats,
                barsSure = g.downbeats,
                beatConfidence = g.beatConfidence,
                barConfidence = g.barConfidence,
                tempoZ = g.tempoZ,
                rivalRatio = g.rivalRatio,
                steadiness = g.steadiness,
                drift = g.drift,
                hitShare = g.hitShare,
                jitterMs = g.jitterMs,
                songMs = front.seconds * 1000.0,
                analysisMs = (System.nanoTime() - started) / 1e6,
                runBeats = line.runBeats,
                onLine = line.onLine,
            )
        }

        /** A straight line through a song's beats: its period, a frame it passes through, the periods its stretches allow, and how many beats it holds. */
        internal class ConstantLine(
            val period: Double, val through: Double, val shortestPeriod: Double, val longestPeriod: Double,
            val runBeats: Int, val onLine: Double,
        )

        /**
         * The runs of [beats] that are straight: each beat within [maxError] of the line from the
         * run's first beat to its last, one beat allowed off it (never the first after the start
         * nor the last), the errors never adding up past [maxSum]. Runs share their end beats, as
         * Mixxx's do.
         */
        internal fun constantRuns(beats: DoubleArray, maxError: Double, maxSum: Double): List<IntRange> {
            fun straight(left: Int, right: Int): Boolean {
                val length = (beats[right] - beats[left]) / (right - left)
                var ironed = beats[left]
                var sum = 0.0
                var outliers = 0
                for (i in left + 1..right) {
                    ironed += length
                    val e = ironed - beats[i]
                    sum += e
                    if (abs(e) > maxError) {
                        outliers++
                        if (outliers > 1 || i == left + 1 || i == right) return false
                    }
                    if (abs(sum) > maxSum) return false
                }
                return true
            }
            val runs = ArrayList<IntRange>()
            var left = 0
            while (left < beats.size - 1) {
                var best = left + 1
                var right = left + 2
                // Grown a beat at a time; a beat off the line may still sit inside a longer run, so
                // a few beyond a failure are tried before the run is closed.
                while (right < beats.size && right - best <= 3) {
                    if (straight(left, right)) best = right
                    right++
                }
                runs += left..best
                left = best
            }
            return runs
        }

        /**
         * Mixxx's constant tempo (BeatUtils::makeConstBpm, in our words): the longest straight run
         * sets the tempo; a run of at least [MIN_RUN_BEATS] beats near the start, then one near
         * the end, whose own tempo range holds it and which sits a number of beats away that both
         * ranges agree on, stretches it to there, which narrows the tempo to a few hundredths of a
         * BPM. Null without a run of [MIN_RUN_BEATS].
         */
        internal fun constantLine(beats: DoubleArray, maxError: Double, maxSum: Double): ConstantLine? {
            val runs = constantRuns(beats, maxError, maxSum)
            val longest = runs.maxByOrNull { it.last - it.first } ?: return null
            if (longest.last - longest.first < MIN_RUN_BEATS) return null
            fun length(r: IntRange) = (beats[r.last] - beats[r.first]) / (r.last - r.first)
            fun range(p: Double, n: Int) = (p - maxError / n) to (p + maxError / n)
            var startBeat = beats[longest.first]
            var endBeat = beats[longest.last]
            var count = longest.last - longest.first
            var period = length(longest)
            var (lo, hi) = range(period, count)
            val at = runs.indexOf(longest)
            // A run near the start first, then one near the end: the first of each that agrees.
            for (i in 0 until at) {
                val r = runs[i]
                val n = r.last - r.first
                if (n < MIN_RUN_BEATS) continue
                val (rlo, rhi) = range(length(r), n)
                if (period <= rlo || period >= rhi) continue
                val span = endBeat - beats[r.first]
                val most = Math.round(span / maxOf(lo, rlo)).toInt()
                val fewest = Math.round(span / minOf(hi, rhi)).toInt()
                if (most != fewest || most <= 0) continue
                val p = span / most
                if (p <= lo || p >= hi) continue
                startBeat = beats[r.first]
                count = most
                period = p
                range(period, count).let { lo = it.first; hi = it.second }
                break
            }
            for (i in runs.size - 1 downTo at + 1) {
                val r = runs[i]
                val n = r.last - r.first
                if (n < MIN_RUN_BEATS) continue
                val (rlo, rhi) = range(length(r), n)
                if (period <= rlo || period >= rhi) continue
                val span = beats[r.last] - startBeat
                val most = Math.round(span / maxOf(lo, rlo)).toInt()
                val fewest = Math.round(span / minOf(hi, rhi)).toInt()
                if (most != fewest || most <= 0) continue
                val p = span / most
                if (p <= lo || p >= hi) continue
                endBeat = beats[r.last]
                count = most
                period = p
                range(period, count).let { lo = it.first; hi = it.second }
                break
            }
            var on = 0
            for (b in beats) {
                val off = (b - startBeat) - Math.round((b - startBeat) / period) * period
                if (abs(off) <= maxError) on++
            }
            return ConstantLine(period, startBeat, lo, hi, count, on.toDouble() / beats.size)
        }

        /**
         * Mixxx's rounding (BeatUtils::roundBpmWithinRange): the whole BPM if it lies inside the
         * range the line allows, else the half (under 85 BPM) or the step of 1.5 (over 127), else
         * the third; the line's own BPM where none does.
         */
        internal fun roundWithin(min: Double, center: Double, max: Double): Double {
            val whole = Math.round(center).toDouble()
            if (whole > min && whole < max) return whole
            val width = max - min
            if (width > 0.5) {
                val snap = if (center < 85) Math.round(center * 2) / 2.0 else if (center > 127) Math.round(center / 3 * 2) * 3 / 2.0 else Double.NaN
                if (snap > min && snap < max) return snap
            }
            if (width > 1.0 / 3) {
                val snap = Math.round(center * 3) / 3.0
                if (snap > min && snap < max) return snap
            }
            return center
        }

        /** Mixxx's phase (BeatUtils::adjustPhase): the line through [through] moved by the mean offset of the beats within [maxError] of it. */
        internal fun adjustPhase(through: Double, period: Double, beats: DoubleArray, maxError: Double): Double {
            var sum = 0.0
            var n = 0
            for (b in beats) {
                val off = (b - through) - Math.round((b - through) / period) * period
                if (abs(off) < maxError) {
                    sum += off
                    n++
                }
            }
            return if (n == 0) through else through + sum / n
        }

        private val JSON = Json { prettyPrint = false }

        fun encode(g: WholeSongGrid): String = JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("v", g.version)
            put("bpm", g.bpm)
            put("t0Ms", g.t0Ms)
            put("firstBeat", g.firstBeat)
            put("lastBeat", g.lastBeat)
            put("rounded", g.rounded)
            put("barPhase", g.barPhase)
            put("beatsPerBar", g.beatsPerBar)
            put("tempoSure", g.tempoSure)
            put("beatsSure", g.beatsSure)
            put("barsSure", g.barsSure)
            put("beatConfidence", g.beatConfidence)
            put("barConfidence", g.barConfidence)
            put("tempoZ", g.tempoZ)
            put("rivalRatio", g.rivalRatio)
            put("steadiness", finite(g.steadiness))
            put("drift", finite(g.drift))
            put("hitShare", g.hitShare)
            put("jitterMs", finite(g.jitterMs))
            put("songMs", g.songMs)
            put("analysisMs", g.analysisMs)
            put("runBeats", g.runBeats)
            put("onLine", g.onLine)
            put("locked", g.locked)
        })

        /** A grid kept before, or null for one of another version that was not set by hand, or for anything unreadable. */
        fun decode(text: String): WholeSongGrid? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            fun d(k: String) = o[k]!!.jsonPrimitive.double
            fun i(k: String) = o[k]!!.jsonPrimitive.int
            fun b(k: String) = o[k]!!.jsonPrimitive.boolean
            val locked = b("locked")
            if (i("v") != VERSION && !locked) return null
            WholeSongGrid(
                d("bpm"), d("t0Ms"), i("firstBeat"), i("lastBeat"), b("rounded"), i("barPhase"), i("beatsPerBar"),
                b("tempoSure"), b("beatsSure"), b("barsSure"), d("beatConfidence"), d("barConfidence"),
                d("tempoZ"), d("rivalRatio"), d("steadiness"), d("drift"), d("hitShare"), d("jitterMs"), d("songMs"),
                d("analysisMs"), i("runBeats"), d("onLine"), locked, VERSION,
            )
        }.getOrNull()

        /** JSON has no infinity: a gate value that could not be measured is kept as a very large one, which fails the same. */
        private fun finite(v: Double): Double = if (v.isFinite()) v else 1e9
    }
}
