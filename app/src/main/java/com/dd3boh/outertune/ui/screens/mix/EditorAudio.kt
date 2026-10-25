/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Spectrum
import com.dd3boh.outertune.mix.Tempo
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.AutoOnBands
import com.dd3boh.outertune.mix.engine.Copy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One song as the editor has it: its window of audio, its bands, and what the analysis found. */
class LoadedSong(
    val id: String,
    val title: String,
    val pcm: PcmWindow,
    val bands: BandEnvelopes,
    val analysis: RegionAnalysis?,
    val grid: SongGrid?,
)

/**
 * Both songs, the facts the editor works from, and Auto's spec; [aCopy] and [bCopy] are the two
 * windows as the engine renders from them, for the preview, which plays the change as the phone
 * will.
 */
class LoadedPair(
    val a: LoadedSong,
    val b: LoadedSong,
    val facts: PairFacts,
    val auto: TransitionSpec,
    val aCopy: Copy,
    val bCopy: Copy,
    /** Auto's own words for its pick, for the log. */
    val autoWhy: String = "",
)

/** Where the editor's windows are kept between openings, in the app's cache: Android may clear it, and that only costs a read. */
class EditorCache(cacheDir: File) {
    val dir = File(cacheDir, "mix/editor")

    fun files(id: String, end: String): Triple<File, File, File> {
        val key = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return Triple(File(dir, "$key-$end.pcm"), File(dir, "$key-$end.info"), File(dir, "$key-$end.bands"))
    }

    /** Keeps the newest windows, about four pairs' worth. */
    fun prune() {
        val sets = dir.listFiles()?.filter { it.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        for (old in sets.drop(KEPT)) {
            val stem = old.name.removeSuffix(".pcm")
            listOf(".pcm", ".info", ".bands").forEach { File(dir, stem + it).delete() }
        }
    }

    private companion object {
        const val KEPT = 8
    }
}

/**
 * Reads the end of A and the opening of B for the editor, or takes them from its cache, measures
 * their bands, analyses them with :mix and asks Auto for its pick, each candidate rendered and
 * judged as the phone does before it plays one. Blocking, on Dispatchers.IO;
 * [stage] is told which song is being read, for the now line.
 */
@OptIn(UnstableApi::class)
class PairLoader(
    private val source: DataSource.Factory,
    private val cache: EditorCache,
    /** Where what was found of each song's end and opening is kept, for the playlist's pills. */
    private val kept: ((String, SongEnd) -> Unit)? = null,
) {

    enum class Stage { READING_A, READING_B, ANALYSING }

    /** Thrown when a song could not be read at all; [side] says which. */
    class ReadFailed(val side: Side, cause: Throwable) : IOException(cause)

    class SongInput(
        val id: String,
        val title: String,
        val durationMs: Double,
        val albumId: String?,
        val fix: BeatFix,
    )

    /** [levelA] and [levelB]: each song's level as the phone plays it now, which Auto's proofs hear it at. */
    fun load(a: SongInput, b: SongInput, levelA: Double = 1.0, levelB: Double = 1.0, stage: (Stage) -> Unit, cancelled: () -> Boolean): LoadedPair {
        stage(Stage.READING_A)
        val (aWindow, aBands) = try {
            window(a, TAIL, cancelled)
        } catch (e: IOException) {
            throw ReadFailed(Side.A, e)
        }
        stage(Stage.READING_B)
        val (bWindow, bBands) = try {
            window(b, HEAD, cancelled)
        } catch (e: IOException) {
            throw ReadFailed(Side.B, e)
        }
        cache.prune()
        stage(Stage.ANALYSING)
        val aAnalysis = analyse(aWindow, RegionKind.TAIL)
        val bAnalysis = analyse(bWindow, RegionKind.HEAD)
        val aGrid = aAnalysis?.let { gridOf(it, aWindow.info.startMs) }
        val bGrid = bAnalysis?.let { gridOf(it, bWindow.info.startMs) }
        val aFacts = SongFacts(
            id = a.id, title = a.title, durationMs = aWindow.info.durationMs,
            windowStartMs = aWindow.info.startMs, windowEndMs = aWindow.info.endMs,
            grid = aGrid, fix = a.fix,
            focusMs = max(aWindow.info.startMs, aWindow.info.durationMs - FOCUS_BEFORE_END_MS),
            source = sourceOf(aWindow.info), analysis = ANALYSIS_VERSION,
            refit = { base, fix, focus -> aAnalysis?.let { refit(it, aWindow.info.startMs, base, fix, focus) } ?: applyFix(base, fix, focus) },
        )
        val bFirstSound = bAnalysis?.let { it.envelope.firstSound * 1000.0 / it.sampleRate } ?: 0.0
        val bFacts = SongFacts(
            id = b.id, title = b.title, durationMs = bWindow.info.durationMs,
            windowStartMs = bWindow.info.startMs, windowEndMs = bWindow.info.endMs,
            grid = bGrid, firstSoundMs = bFirstSound, fix = b.fix,
            focusMs = bFirstSound,
            source = sourceOf(bWindow.info), analysis = ANALYSIS_VERSION,
            refit = { base, fix, focus -> bAnalysis?.let { refit(it, bWindow.info.startMs, base, fix, focus) } ?: applyFix(base, fix, focus) },
        )
        val facts = PairFacts(
            a = aFacts, b = bFacts,
            active = { side, lane: Lane, ms ->
                val w = if (side == Side.A) aWindow.info else bWindow.info
                (if (side == Side.A) aBands else bBands).active(lane, ms - w.startMs)
            },
        )
        // The windows as the engine renders from them, on the heap: Auto's proofs and the preview
        // play the change from these, exactly as the phone will.
        val aCopy = copyOf(aWindow)
        val bCopy = copyOf(bWindow)
        var autoWhy = "no analysis"
        val auto = if (aAnalysis != null && bAnalysis != null) {
            runCatching {
                val aSong = AutoOnBands.Song(a.id, aCopy, aAnalysis, aWindow.info.startMs, aWindow.info.durationMs, a.albumId, a.fix)
                val bSong = AutoOnBands.Song(b.id, bCopy, bAnalysis, bWindow.info.startMs, bWindow.info.durationMs, b.albumId, b.fix)
                val chosen = AutoOnBands.choose(aSong, bSong, attenuationA = levelA, attenuationB = levelB)
                autoWhy = "${chosen.pick.describe()}, ${chosen.proofs} proofs in %.0f ms".format(chosen.millis)
                kept?.invoke(a.id, aSong.end)
                kept?.invoke(b.id, bSong.end)
                autoSpecOf(chosen.spec, aFacts, bFacts, facts)
            }.onFailure { Log.w(TAG, "No Auto for the pair", it) }.getOrNull()
        } else null
        Log.i(TAG, "Auto: $autoWhy")
        return LoadedPair(
            LoadedSong(a.id, a.title, aWindow, aBands, aAnalysis, aGrid),
            LoadedSong(b.id, b.title, bWindow, bBands, bAnalysis, bGrid),
            facts, auto ?: AutoSpec.fallback(facts), aCopy, bCopy, autoWhy,
        )
    }

    /** A window read whole into the heap, the form the engine renders from. */
    private fun copyOf(window: PcmWindow): Copy {
        val pcm = ShortArray(window.frames * window.info.channels)
        window.read(0, window.frames, pcm)
        return Copy(pcm, window.info.sampleRate, window.info.channels, window.info.startMs)
    }

    /**
     * Auto's spec with its anchors in the editor's own terms (the window and analysis they were
     * found on, and each song's fix), so the page takes it as Auto's; Auto's fallback when the
     * pick cannot be heard whole with what may be read of the two songs.
     */
    private fun autoSpecOf(spec: TransitionSpec, a: SongFacts, b: SongFacts, facts: PairFacts): TransitionSpec {
        val written = spec.copy(
            a = MixEditor.anchor(a, spec.a.atMs, spec.a.bpm).copy(beatsPerBar = spec.a.beatsPerBar),
            b = MixEditor.anchor(b, spec.b.atMs, spec.b.bpm).copy(beatsPerBar = spec.b.beatsPerBar),
        )
        return written.takeIf { it.isValid && (it.plain || AutoSpec.fits(it, facts)) } ?: AutoSpec.fallback(facts)
    }

    private fun window(song: SongInput, end: String, cancelled: () -> Boolean): Pair<PcmWindow, BandEnvelopes> {
        val (pcm, json, bandsFile) = cache.files(song.id, end)
        val known = json.takeIf { it.exists() }?.let { WindowInfo.of(it.readText()) }
        if (known != null && pcm.length() == known.frames * known.channels * 2) {
            val bands = BandEnvelopes.read(bandsFile)
            if (bands != null) {
                val now = System.currentTimeMillis()
                listOf(pcm, json, bandsFile).forEach { it.setLastModified(now) }
                return PcmWindow.map(pcm, known) to bands
            }
        }
        cache.dir.mkdirs()
        val part = File(pcm.path + ".part")
        var meter: BandMeter? = null
        val info = WindowDecoder(source).decode(
            mediaId = song.id,
            knownDurationMs = song.durationMs,
            startOf = { duration -> if (end == HEAD) 0.0 else duration - PairFacts.REACH_A_MS },
            lengthMs = if (end == HEAD) PairFacts.REACH_B_MS else PairFacts.REACH_A_MS,
            out = part,
            cancelled = cancelled,
            measure = { info, samples, frames -> (meter ?: BandMeter(info.sampleRate, info.channels).also { meter = it }).push(samples, frames) },
        )
        val bands = meter?.result() ?: throw IOException("nothing measured")
        if (!part.renameTo(pcm)) throw IOException("could not keep ${pcm.name}")
        bands.write(bandsFile)
        json.writeText(info.toText())
        return PcmWindow.map(pcm, info) to bands
    }

    private fun analyse(window: PcmWindow, kind: RegionKind): RegionAnalysis? = runCatching {
        val front = FrontEnd(window.info.sampleRate, window.info.channels, filteredPeaks = kind == RegionKind.HEAD)
        val block = ShortArray(BLOCK_FRAMES * window.info.channels)
        var at = 0
        while (at < window.frames) {
            val n = min(BLOCK_FRAMES, window.frames - at)
            window.read(at, n, block)
            front.push(block, 0, n)
            at += n
        }
        RegionAnalysis.analyse(front.result(), kind)
    }.onFailure { Log.w(TAG, "The ${kind.name.lowercase()} could not be analysed", it) }.getOrNull()

    companion object {
        private const val TAG = "MixEditor"
        private const val HEAD = "head"
        private const val TAIL = "tail"
        private const val BLOCK_FRAMES = 8192

        /** The editor's own analysis of a window, for the anchors it writes. */
        const val ANALYSIS_VERSION = 1

        /** A new tempo is pinned this far before A's end, about where its change is. */
        const val FOCUS_BEFORE_END_MS = 20_000.0

        private fun sourceOf(info: WindowInfo) = "editor:${info.sampleRate}:${info.channels}"

        /** The analysis's beat line, in ms of the song, where its bars were trusted. */
        fun gridOf(r: RegionAnalysis, windowStartMs: Double): SongGrid? {
            if (!r.barsConfident) return null
            val downbeats = r.downbeats ?: return null
            val period = r.periodFrames ?: return null
            val rate = r.sampleRate.toDouble()
            return SongGrid(
                t0Ms = windowStartMs + r.beatFrame(0) / rate * 1000.0,
                periodMs = period / rate * 1000.0,
                phase = downbeats.phase,
                beatsPerBar = downbeats.beatsPerBar,
            )
        }

        /**
         * A fix laid on the analysis: a new tempo is fitted again on the song's own onsets within
         * 8 s of [focusMs], so its beats land on what is heard there, and its 1 is the beat nearest
         * the downbeat the analysis had there. Then the 1 and the nudge, as [applyFix].
         */
        fun refit(r: RegionAnalysis, windowStartMs: Double, base: SongGrid, fix: BeatFix, focusMs: Double): SongGrid {
            val bpm = fix.bpm ?: return applyFix(base, fix, focusMs)
            val grid = r.grid ?: return applyFix(base, fix, focusMs)
            val rate = r.sampleRate.toDouble()
            val focusFrame = (focusMs - windowStartMs) / 1000.0 * rate
            val around = 8.0 * rate
            val from = r.spectralFrame(focusFrame - around).roundToInt().coerceIn(0, r.onset.size)
            val to = r.spectralFrame(focusFrame + around).roundToInt().coerceIn(from, r.onset.size)
            if (to - from < 16) return applyFix(base, fix, focusMs)
            val period = Tempo.periodFor(bpm, grid.frameRate)
            val (phase, _) = Tempo.bestPhase(r.onset, from, to, period)
            val factor = r.decimation.toDouble()
            fun msOfSpectral(t: Double) = windowStartMs + (t * Spectrum.HOP + Spectrum.ONSET_LAG) * factor / rate * 1000.0
            val t0 = msOfSpectral(from + phase)
            val periodMs = msOfSpectral(from + phase + period) - t0
            val pivot = base.beatMs(base.downbeatNear(focusMs))
            val k = ((pivot - t0) / periodMs).roundToInt()
            val fitted = SongGrid(t0 + k * periodMs, periodMs, phase = 0, beatsPerBar = base.beatsPerBar)
            return fitted.copy(t0Ms = fitted.t0Ms + fix.nudgeMs, phase = fix.downbeatShift)
        }
    }
}
