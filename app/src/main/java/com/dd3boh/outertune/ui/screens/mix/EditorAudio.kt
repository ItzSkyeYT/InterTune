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
import com.dd3boh.outertune.mix.KeyEstimate
import com.dd3boh.outertune.mix.Keys
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionFile
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Spectrum
import com.dd3boh.outertune.mix.Tempo
import com.dd3boh.outertune.mix.WholeSongGrid
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.auto.SongEndJson
import com.dd3boh.outertune.mix.edit.TransitionJson
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.AutoOnBands
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.playback.automix.AutomixPace
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
    /** The key of the read part of the song, for its key pill; shown only where it is sure. */
    val key: KeyEstimate? = null,
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
    /** How long each part took and what came from the cache. */
    val took: LoadTimes? = null,
)

/** How long a pair took to load and which parts came from the editor's cache, for the log and the tests. */
class LoadTimes(val readMs: Double, val analysisMs: Double, val autoMs: Double, val analysisKept: Boolean, val autoKept: Boolean) {
    override fun toString(): String =
        "read in %.0f ms, analysed in %.0f ms%s, Auto in %.0f ms%s".format(
            readMs, analysisMs, if (analysisKept) " (kept)" else "", autoMs, if (autoKept) " (kept)" else "",
        )
}

/** Auto's pick for a pair as the editor last loaded it: the spec as Auto gave it, its words, and both songs' ends as it heard them. */
class KeptAuto(val spec: TransitionSpec, val why: String, val aEnd: SongEnd, val bEnd: SongEnd)

/**
 * Where the editor's windows are kept between openings, in the app's cache: Android may clear it,
 * and that only costs a read. Beside each window, what the analysis found in it, and for each pair
 * Auto's pick, so going back to a pair takes a moment instead of analysing it again (his ask of
 * 6 Nov 2026). Both are kept under [stamp], the build that made them: a new build analyses afresh.
 */
class EditorCache(cacheDir: File, private val stamp: String = "") {
    val dir = File(cacheDir, "mix/editor")
    private val autoDir = File(dir, "auto")

    fun files(id: String, end: String): Triple<File, File, File> {
        val key = digest(id)
        return Triple(File(dir, "$key-$end.pcm"), File(dir, "$key-$end.info"), File(dir, "$key-$end.bands"))
    }

    /** The window's analysis, dropped with the window. */
    fun analysisFile(id: String, end: String): File = File(dir, "${digest(id)}-$end.analysis")

    /** What the analysis found in the window [info] describes, when it was kept by this build for that window. */
    fun analysis(id: String, end: String, info: WindowInfo): RegionAnalysis? {
        val file = analysisFile(id, end)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER)).use { input ->
                if (input.readUTF() != stamp || input.readInt() != PairLoader.ANALYSIS_VERSION || input.readUTF() != info.toText()) return null
                RegionFile.read(input)
            }
        }.onFailure { Log.w(TAG, "A kept analysis could not be read", it) }.getOrNull()
            ?.takeIf { it.frames == info.frames && it.sampleRate == info.sampleRate && it.channels == info.channels }
    }

    fun keepAnalysis(id: String, end: String, info: WindowInfo, r: RegionAnalysis) {
        val file = analysisFile(id, end)
        write(file) { out ->
            out.writeUTF(stamp)
            out.writeInt(PairLoader.ANALYSIS_VERSION)
            out.writeUTF(info.toText())
            RegionFile.write(r, out)
        }
    }

    /** Auto's pick kept under [key], everything it was chosen from; null where there is none from this build. */
    fun auto(key: String): KeptAuto? {
        val file = File(autoDir, digest(key))
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER)).use { input ->
                if (text(input) != "$stamp\n$key") return null
                val why = text(input)
                val spec = TransitionJson.decode(text(input)) ?: return null
                val aEnd = SongEndJson.decode(text(input)) ?: return null
                val bEnd = SongEndJson.decode(text(input)) ?: return null
                file.setLastModified(System.currentTimeMillis())
                KeptAuto(spec, why, aEnd, bEnd)
            }
        }.onFailure { Log.w(TAG, "A kept pick could not be read", it) }.getOrNull()
    }

    fun keepAuto(key: String, kept: KeptAuto) {
        autoDir.mkdirs()
        write(File(autoDir, digest(key))) { out ->
            text("$stamp\n$key", out)
            text(kept.why, out)
            text(TransitionJson.encode(kept.spec), out)
            text(SongEndJson.encode(kept.aEnd), out)
            text(SongEndJson.encode(kept.bEnd), out)
        }
    }

    /** Keeps the newest windows, about four pairs' worth, with their analyses, and the newest picks. */
    fun prune() {
        val sets = dir.listFiles()?.filter { it.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        for (old in sets.drop(KEPT)) {
            val stem = old.name.removeSuffix(".pcm")
            listOf(".pcm", ".info", ".bands", ".analysis").forEach { File(dir, stem + it).delete() }
        }
        autoDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEPT_PICKS)?.forEach { it.delete() }
    }

    /** Written whole beside the file, then put in its place, so a file is never read half written. */
    private fun write(file: File, body: (DataOutputStream) -> Unit) {
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            DataOutputStream(BufferedOutputStream(part.outputStream(), BUFFER)).use(body)
            if (!part.renameTo(file)) {
                part.delete()
                throw IOException("could not keep ${file.name}")
            }
        }.onFailure { Log.w(TAG, "Could not keep ${file.name}", it) }
    }

    private companion object {
        const val TAG = "MixEditor"
        const val KEPT = 8
        const val KEPT_PICKS = 64
        const val BUFFER = 1 shl 16

        fun digest(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun text(value: String, out: DataOutputStream) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        fun text(input: DataInputStream): String {
            val n = input.readInt()
            if (n < 0 || n > MAX_TEXT) throw IOException("a text of $n bytes")
            return ByteArray(n).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }

        const val MAX_TEXT = 4 shl 20
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
    /** Each song's beats read from the whole song once, which its end and opening are laid on (WholeSongStore). */
    private val grids: ((String) -> WholeSongGrid?)? = null,
    /**
     * Reads a song with no grid yet whole and keeps its grid, as playback does; null where songs
     * are not read whole here (the player's cache off: the song would be fetched twice). Blocking.
     */
    private val readWhole: ((id: String, durationMs: Double, cancelled: () -> Boolean) -> WholeSongGrid?)? = null,
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

    /**
     * [levelA] and [levelB]: each song's level as the phone plays it now, which Auto's proofs hear
     * it at. [pace]: this phone's, as playback keeps it, which says how far before A's end a change
     * may start ([PairFacts.pace]).
     */
    fun load(a: SongInput, b: SongInput, levelA: Double = 1.0, levelB: Double = 1.0, pace: AutomixPace? = null, stage: (Stage) -> Unit, cancelled: () -> Boolean): LoadedPair {
        val started = System.nanoTime()
        stage(Stage.READING_A)
        val (aWindow, aBands) = try {
            window(a, TAIL, cancelled)
        } catch (e: IOException) {
            throw ReadFailed(Side.A, e)
        }
        // Each song read whole once where it never was, so its end and opening keep its own tempo.
        val aWhole = wholeOf(a.id) ?: readWhole?.let { r -> runCatching { r(a.id, a.durationMs, cancelled) }.getOrNull() }
        stage(Stage.READING_B)
        val (bWindow, bBands) = try {
            window(b, HEAD, cancelled)
        } catch (e: IOException) {
            throw ReadFailed(Side.B, e)
        }
        val bWhole = wholeOf(b.id) ?: readWhole?.let { r -> runCatching { r(b.id, b.durationMs, cancelled) }.getOrNull() }
        cache.prune()
        stage(Stage.ANALYSING)
        val readAt = System.nanoTime()
        // What was found in each window, from the cache when this build kept it for that window.
        val (aFound, aKept) = analysed(a.id, TAIL, aWindow, RegionKind.TAIL)
        val (bFound, bKept) = analysed(b.id, HEAD, bWindow, RegionKind.HEAD)
        // Each at its song's own tempo where the song was read whole: the opening from the song's
        // first frame, exactly; the end from where the decoder says it starts.
        val aAnalysis = aFound?.let { r -> aWhole?.let { r.onSong(it, aWindow.info.startMs, exact = false) } ?: r }
        val bAnalysis = bFound?.let { r -> bWhole?.let { r.onSong(it, bWindow.info.startMs, exact = bWindow.info.startMs == 0.0) } ?: r }
        val analysedAt = System.nanoTime()
        val aGrid = aAnalysis?.let { gridOf(it, aWindow.info.startMs) }
        val bGrid = bAnalysis?.let { gridOf(it, bWindow.info.startMs) }
        val aGates = aAnalysis?.gates
        val bGates = bAnalysis?.gates
        val aFacts = SongFacts(
            id = a.id, title = a.title, durationMs = aWindow.info.durationMs,
            windowStartMs = aWindow.info.startMs, windowEndMs = aWindow.info.endMs,
            grid = aGrid, fix = a.fix,
            focusMs = max(aWindow.info.startMs, aWindow.info.durationMs - FOCUS_BEFORE_END_MS),
            source = sourceOf(aWindow.info), analysis = ANALYSIS_VERSION,
            refit = { base, fix, focus -> aAnalysis?.let { refit(it, aWindow.info.startMs, base, fix, focus) } ?: base?.let { applyFix(it, fix, focus) } },
            beatConfidence = if (aGrid != null) aGates?.beatConfidence ?: 0.0 else 0.0,
            barConfidence = if (aGrid != null) aGates?.barConfidence ?: 0.0 else 0.0,
        )
        val bFirstSound = bAnalysis?.let { it.envelope.firstSound * 1000.0 / it.sampleRate } ?: 0.0
        val bFacts = SongFacts(
            id = b.id, title = b.title, durationMs = bWindow.info.durationMs,
            windowStartMs = bWindow.info.startMs, windowEndMs = bWindow.info.endMs,
            grid = bGrid, firstSoundMs = bFirstSound, fix = b.fix,
            focusMs = bFirstSound,
            source = sourceOf(bWindow.info), analysis = ANALYSIS_VERSION,
            refit = { base, fix, focus -> bAnalysis?.let { refit(it, bWindow.info.startMs, base, fix, focus) } ?: base?.let { applyFix(it, fix, focus) } },
            beatConfidence = if (bGrid != null) bGates?.beatConfidence ?: 0.0 else 0.0,
            barConfidence = if (bGrid != null) bGates?.barConfidence ?: 0.0 else 0.0,
        )
        val facts = PairFacts(
            a = aFacts, b = bFacts,
            pace = pace,
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
        var aKey: KeyEstimate? = null
        var bKey: KeyEstimate? = null
        // Auto's pick depends on nothing but what is keyed here: both windows and what was found in
        // them, the songs' whole grids, their fixes and their levels. A fix, a new read or a new
        // build is a new key, and Auto picks again.
        val autoKey = listOf(
            "ids ${a.id} ${b.id} albums ${a.albumId} ${b.albumId}",
            "a ${aWindow.info.toText()}", "b ${bWindow.info.toText()}",
            "whole a ${aWhole?.let { WholeSongGrid.encode(it) }}", "whole b ${bWhole?.let { WholeSongGrid.encode(it) }}",
            "fix a ${a.fix}", "fix b ${b.fix}",
            "levels %.6f %.6f".format(java.util.Locale.ROOT, levelA, levelB),
        ).joinToString("\n")
        val keptAuto = if (aAnalysis != null && bAnalysis != null) cache.auto(autoKey) else null
        val auto = if (keptAuto != null) {
            autoWhy = "${keptAuto.why}, kept"
            kept?.invoke(a.id, keptAuto.aEnd)
            kept?.invoke(b.id, keptAuto.bEnd)
            aKey = keptAuto.aEnd.key
            bKey = keptAuto.bEnd.key
            autoSpecOf(keptAuto.spec, aFacts, bFacts, facts)
        } else if (aAnalysis != null && bAnalysis != null) {
            runCatching {
                val aSong = AutoOnBands.Song(a.id, aCopy, aAnalysis, aWindow.info.startMs, aWindow.info.durationMs, a.albumId, a.fix)
                val bSong = AutoOnBands.Song(b.id, bCopy, bAnalysis, bWindow.info.startMs, bWindow.info.durationMs, b.albumId, b.fix)
                val chosen = AutoOnBands.choose(aSong, bSong, attenuationA = levelA, attenuationB = levelB)
                autoWhy = "${chosen.pick.describe()}, ${chosen.proofs} proofs in " + "%.0f ms".format(chosen.millis)
                kept?.invoke(a.id, aSong.end)
                kept?.invoke(b.id, bSong.end)
                aKey = aSong.end.key
                bKey = bSong.end.key
                cache.keepAuto(autoKey, KeptAuto(chosen.spec, autoWhy, aSong.end, bSong.end))
                autoSpecOf(chosen.spec, aFacts, bFacts, facts)
            }.onFailure { Log.w(TAG, "No Auto for the pair", it) }.getOrNull()
        } else null
        Log.i(TAG, "Auto: $autoWhy")
        // Auto found the keys on its way; without it, they are read here, as Auto reads them.
        if (aKey == null) aKey = aAnalysis?.let { runCatching { Keys.of(it) }.getOrNull() }
        if (bKey == null) bKey = bAnalysis?.let { runCatching { Keys.of(it) }.getOrNull() }
        val doneAt = System.nanoTime()
        val took = LoadTimes(
            readMs = (readAt - started) / 1e6, analysisMs = (analysedAt - readAt) / 1e6, autoMs = (doneAt - analysedAt) / 1e6,
            analysisKept = aKept && bKept, autoKept = keptAuto != null,
        )
        Log.i(TAG, "Pair loaded: $took")
        return LoadedPair(
            LoadedSong(a.id, a.title, aWindow, aBands, aAnalysis, aGrid, aKey),
            LoadedSong(b.id, b.title, bWindow, bBands, bAnalysis, bGrid, bKey),
            facts, auto ?: AutoSpec.fallback(facts), aCopy, bCopy, autoWhy, took,
        )
    }

    private fun wholeOf(id: String): WholeSongGrid? = grids?.let { runCatching { it(id) }.getOrNull() }

    /** What was found in [window], and whether it came from the cache; analysed and kept where it did not. */
    private fun analysed(id: String, end: String, window: PcmWindow, kind: RegionKind): Pair<RegionAnalysis?, Boolean> {
        cache.analysis(id, end, window.info)?.let { return it to true }
        val found = analyse(window, kind) ?: return null to false
        cache.keepAnalysis(id, end, window.info, found)
        return found to false
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
        cache.analysisFile(song.id, end).delete()
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

        /**
         * The analysis's beat line, in ms of the song, wherever its beats were trusted: with its bars
         * where they were found, sure or a guess ([SongFacts.barsSure] says which), so the listener
         * can match the beats and put bar 1 right by hand where the analysis could not.
         */
        fun gridOf(r: RegionAnalysis, windowStartMs: Double): SongGrid? {
            if (!r.beatsConfident) return null
            val period = r.periodFrames ?: return null
            val rate = r.sampleRate.toDouble()
            val downbeats = r.downbeats
            return SongGrid(
                t0Ms = windowStartMs + r.beatFrame(0) / rate * 1000.0,
                periodMs = period / rate * 1000.0,
                phase = downbeats?.phase ?: 0,
                beatsPerBar = downbeats?.beatsPerBar ?: com.dd3boh.outertune.mix.Downbeats.BEATS_PER_BAR,
            )
        }

        /**
         * A fix laid on the analysis: a new tempo is fitted again on the song's own onsets within
         * 8 s of [focusMs], so its beats land on what is heard there, and its 1 is the beat nearest
         * the downbeat the analysis had there. Then the 1 and the nudge, as [applyFix].
         */
        fun refit(r: RegionAnalysis, windowStartMs: Double, base: SongGrid?, fix: BeatFix, focusMs: Double): SongGrid? {
            // No beat found, or a tempo far from the one found: the beats laid again at the listener's
            // tempo over the whole window, as the phone lays them on its own copy (SongEnd.with).
            val hand = fix.bpm
            if (hand != null && (base == null || com.dd3boh.outertune.mix.auto.SongEnd.octaveOf(hand, base.bpm) == null)) {
                val again = r.atTempo(hand)?.let { gridOf(it, windowStartMs) } ?: return base?.let { applyFix(it, fix, focusMs) }
                return withOne(again.copy(t0Ms = again.t0Ms + fix.nudgeMs), fix)
            }
            if (base == null) return null
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
            return withOne(fitted.copy(t0Ms = fitted.t0Ms + fix.nudgeMs), fix)
        }
    }
}
