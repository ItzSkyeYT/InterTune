/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.better

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.WholeSongGrid
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.auto.AutoMoves
import com.dd3boh.outertune.mix.auto.BeatLine
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.auto.atOrAfterBStart
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.AutoOnBands
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.ChangeRender
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.PcmSource
import com.dd3boh.outertune.mix.engine.Placing
import com.dd3boh.outertune.mix.engine.SpecPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Not a test: the beat-matching measure of the mix-better work. Skipped unless MIX_BETTER_DIR is set.
 *
 * Reads the songs that experiments/features/mix-better/tools/prep.py cut into their two ends, and
 * writes, for MIX_BETTER_LABEL:
 *
 * - analysis.jsonl: every song's opening and end as the phone analyses them (30 s and the last
 *   40 s) and as the editor does (60 s and the last 72 s): tempo, the beat line, the bars, the
 *   gates, and the onsets the line was fitted through, for tools/measure.py to hold against the
 *   Harmonix annotations;
 * - pairs/NN-a-b/<variant>.json and .wav: for each pair of pairs.tsv, Auto's change as the phone
 *   makes it, and an 8-bar Fade with beat matching on (4 bars where the tempos are 4 to 6% apart)
 *   as the editor previews it and as the phone then plays it once saved, each with the frames it
 *   happens on and A as stretched and B as it plays on the output clock (mono), plus the mix as
 *   heard (stereo), for tools/measure.py to measure where the two songs' beats fall.
 *
 * Run with --rerun. Nothing here leaves the PC or goes into git.
 */
class MixBetterTrial {

    private class Song(val id: String, val rate: Int, val frames: Long, val headFrames: Int, val tailFrom: Long, val dir: File) {
        val songMs: Double get() = frames * 1000.0 / rate

        /** Frames [from, to) of the song's opening. */
        fun head(from: Int, to: Int): ShortArray = read(File(dir, "$id.head.s16"), from.toLong(), min(to, headFrames).toLong())

        /** Frames [from, to) of the song, from its end file. */
        fun tail(from: Long, to: Long): ShortArray = read(File(dir, "$id.tail.s16"), from - tailFrom, min(to, frames) - tailFrom)

        private fun read(f: File, lo: Long, hi: Long): ShortArray {
            val n = (hi - lo).toInt().coerceAtLeast(0)
            val bytes = ByteArray(n * 4)
            RandomAccessFile(f, "r").use {
                it.seek(lo * 4)
                it.readFully(bytes)
            }
            val out = ShortArray(n * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }
    }

    /** One end of a song as analysed, with the music it was analysed on. */
    private class End(val song: Song, val window: String, val fromFrame: Long, val pcm: ShortArray, val region: RegionAnalysis, val end: SongEnd) {
        val fromMs: Double get() = fromFrame * 1000.0 / song.rate
        val copy: Copy get() = Copy(pcm, song.rate, 2, fromMs)
    }

    private val env = System.getenv()

    @Test
    fun trial() {
        val dir = env["MIX_BETTER_DIR"]?.takeIf { it.isNotBlank() }
        assumeTrue("set MIX_BETTER_DIR to run", dir != null)
        val root = File(dir!!)
        this.root = root
        val label = env["MIX_BETTER_LABEL"]?.takeIf { it.isNotBlank() } ?: "run"
        val out = File(root, "out/$label").apply { mkdirs() }
        val decoded = File(root, "decoded")
        val only = env["MIX_BETTER_ONLY"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val songs = decoded.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { f ->
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            Song(o["id"]!!.jsonPrimitive.content, o["rate"]!!.jsonPrimitive.int, o["frames"]!!.jsonPrimitive.long, o["headFrames"]!!.jsonPrimitive.int, o["tailFrom"]!!.jsonPrimitive.long, decoded)
        }.associateBy { it.id }
        // MIX_BETTER_PAIRS: another list of pairs, or "none" for the songs alone.
        val pairsFile = env["MIX_BETTER_PAIRS"]?.takeIf { it.isNotBlank() }
        val pairs = if (pairsFile == "none") emptyList() else File(pairsFile ?: File(root, "pairs.tsv").path).readLines().drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

        // MIX_BETTER_WHOLE: each song read whole first, and every window laid at its tempo
        // (RegionAnalysis.onSong): "map" lays an opening on the song's own line, "fit" finds its
        // phase on the window's onsets; a "bars" suffix counts the whole song's bars into it.
        whole = env["MIX_BETTER_WHOLE"]?.takeIf { it.isNotBlank() }
        if (whole != null) {
            val paths = File(root, "songs.tsv").readLines().drop(1).map { it.split('\t') }.associate { it[0] to it[1] }
            val cache = File(root, "out/whole-grids-v${WholeSongGrid.VERSION}").apply { mkdirs() }
            for (s in songs.values) {
                if (only != null && s.id !in only) continue
                val f = File(cache, "${s.id}.json")
                val g = if (f.isFile) WholeSongGrid.decode(f.readText()) else wholeGrid(s, File(paths[s.id] ?: error("no path for ${s.id}")))?.also { f.writeText(WholeSongGrid.encode(it)) }
                if (g != null) grids[s.id] = g
            }
        }

        // Every song's two ends, as the phone and as the editor read them.
        if (env["MIX_BETTER_SKIP_SONGS"] != "1") {
            File(out, "analysis.jsonl").bufferedWriter().use { w ->
                for (s in songs.values) {
                    if (only != null && s.id !in only) continue
                    grids[s.id]?.let { g ->
                        w.write(describeWhole(s, g).toString())
                        w.write("\n")
                    }
                    for (window in WINDOWS) {
                        val e = analyse(s, window)
                        w.write(describe(e).toString())
                        w.write("\n")
                    }
                }
            }
        }

        val pairsDir = File(out, "pairs").apply { mkdirs() }
        val summary = StringBuilder()
        for ((n, p) in pairs.withIndex()) {
            val a = songs[p[0]] ?: error("no song ${p[0]}")
            val b = songs[p[1]] ?: error("no song ${p[1]}")
            if (only != null && a.id !in only && b.id !in only) continue
            val dirOut = File(pairsDir, "%02d-%s-%s".format(n + 1, a.id, b.id)).apply { mkdirs() }
            summary.append(pair(a, b, dirOut)).append("\n")
        }
        File(out, "pairs.txt").writeText(summary.toString())
        print(summary)
    }

    private fun analyse(s: Song, window: String, tailSeconds: Double = PHONE_TAIL_S): End {
        val rate = s.rate
        return when (window) {
            "phoneHead", "editorHead" -> {
                val seconds = if (window == "phoneHead") PHONE_HEAD_S else EDITOR_HEAD_S
                val pcm = s.head(0, (seconds * rate).toInt())
                val region = RegionAnalysis.analyse(pcm, rate, 2, RegionKind.HEAD).let { r -> onSong(s, r, 0.0, exact = true) }
                End(s, window, 0, pcm, region, SongEnd.of(region, 0.0, s.songMs))
            }
            else -> {
                val seconds = if (window == "phoneTail") tailSeconds else EDITOR_TAIL_S
                val from = max(0L, s.frames - (seconds * rate).toLong())
                val pcm = s.tail(from, s.frames)
                val region = RegionAnalysis.analyse(pcm, rate, 2, RegionKind.TAIL).let { r -> onSong(s, r, from * 1000.0 / rate, exact = false) }
                End(s, window, from, pcm, region, SongEnd.of(region, from * 1000.0 / rate, s.songMs))
            }
        }
    }

    private var whole: String? = null
    private var root: File = File(".")
    private val grids = HashMap<String, WholeSongGrid>()

    private fun onSong(s: Song, r: RegionAnalysis, offsetMs: Double, exact: Boolean): RegionAnalysis {
        val mode = whole ?: return r
        val g = grids[s.id] ?: return r
        return r.onSong(g, offsetMs, exact, songLine = mode.startsWith("map"), songBars = mode.endsWith("bars"))
    }

    /** The song decoded whole by ffmpeg, as prep.py decodes its ends, and read as the app reads a song whole. */
    private fun wholeGrid(s: Song, path: File): WholeSongGrid? {
        val p = ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", path.path, "-f", "s16le", "-ac", "2", "-ar", s.rate.toString(), "-")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val front = FrontEnd(s.rate, 2)
        val buffer = ByteArray(1 shl 16)
        val shorts = ShortArray(buffer.size / 2)
        var carry = 0
        var frames = 0L
        val most = (WholeSongGrid.MAX_SECONDS * s.rate).toLong()
        p.inputStream.use { input ->
            while (frames < most) {
                val n = input.read(buffer, carry, buffer.size - carry)
                if (n < 0) break
                val bytes = carry + n
                val usable = bytes / 4 * 4
                ByteBuffer.wrap(buffer, 0, usable).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, usable / 2)
                front.push(shorts, 0, usable / 4)
                frames += usable / 4
                carry = bytes - usable
                if (carry > 0) System.arraycopy(buffer, usable, buffer, 0, carry)
            }
        }
        p.waitFor()
        val read = front.result()
        val g = WholeSongGrid.analyse(read)
        println("${s.id}: whole ${"%.1f".format(frames.toDouble() / s.rate)} s, " + (g?.describe() ?: "no grid: " + RegionAnalysis.analyse(read, RegionKind.HEAD).describe()))
        return g
    }

    private fun describeWhole(s: Song, g: WholeSongGrid): JsonObject = buildJsonObject {
        put("song", s.id)
        put("window", "whole")
        put("fromMs", 0.0)
        put("toMs", s.songMs)
        put("songMs", s.songMs)
        put("gridBpm", g.bpm)
        put("t0Ms", g.t0Ms)
        put("beatMs", g.beatMs)
        put("firstBeat", g.firstBeat)
        put("lastBeat", g.lastBeat)
        put("rounded", g.rounded)
        put("phase", g.barPhase)
        put("beatsPerBar", g.beatsPerBar)
        put("gateTempo", g.tempoSure)
        put("gateBeats", g.beatsSure)
        put("gateBars", g.barsSure)
        put("beatConfidence", g.beatConfidence)
        put("barConfidence", g.barConfidence)
        put("lineBeatsTrusted", g.beatsSure)
        put("lineBarsTrusted", g.barsSure)
        put("linePhase", g.barPhase)
        put("lineBpb", g.beatsPerBar)
        put("analysisMs", g.analysisMs)
    }

    private fun describe(e: End): JsonObject = buildJsonObject {
        val r = e.region
        val rate = e.song.rate
        put("song", e.song.id)
        put("window", e.window)
        put("fromMs", e.fromMs)
        put("toMs", e.fromMs + r.frames * 1000.0 / rate)
        put("songMs", e.song.songMs)
        put("firstSoundMs", e.end.firstSoundMs)
        put("trueEndMs", e.end.trueEndMs)
        r.rhythm.tempo?.let { t ->
            put("tempoBpm", t.bpm)
            put("rawBpm", t.rawBpm)
            put("z", t.z)
            put("rival", t.rivalRatio)
        }
        val g = r.grid
        if (g != null) {
            put("gridBpm", g.bpm)
            put("t0Ms", e.fromMs + r.beatFrame(0) * 1000.0 / rate)
            put("beatMs", r.periodFrames!! * 1000.0 / rate)
            put("firstBeat", g.firstBeat)
            put("lastBeat", g.lastBeat)
            put("hitShare", g.hitShare)
            put("jitterMs", g.jitterMs)
            put("steadiness", g.steadiness)
            put("drift", g.drift)
            putJsonArray("heardK") { g.heardK.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("heardMs") { g.heardT.forEach { t -> add(kotlinx.serialization.json.JsonPrimitive(e.fromMs + r.spectralFrameToFrame(t) * 1000.0 / rate)) } }
        }
        r.downbeats?.let { d ->
            put("phase", d.phase)
            put("beatsPerBar", d.beatsPerBar)
            put("margin", d.margin)
            put("halfMargin", d.halfMargin)
        }
        put("fromSong", r.gates.fromSong)
        put("beatConfidence", r.gates.beatConfidence)
        put("barConfidence", r.gates.barConfidence)
        put("gateTempo", r.gates.tempo)
        put("gateBeats", r.gates.beats)
        put("gateBars", r.gates.downbeats)
        put("failures", r.gates.failures().joinToString("; "))
        e.end.line?.let { l ->
            put("lineBeatsTrusted", l.beatsTrusted)
            put("lineBarsTrusted", l.barsTrusted)
            put("lineFromFirstBeat", l.barsFromFirstBeat)
            put("linePhase", l.phase)
            put("lineBpb", l.beatsPerBar)
            put("lineT0Ms", l.t0Ms)
            put("lineBeatMs", l.beatMs)
        }
    }

    private fun RegionAnalysis.spectralFrameToFrame(t: Double): Double =
        (t * com.dd3boh.outertune.mix.Spectrum.HOP + com.dd3boh.outertune.mix.Spectrum.ONSET_LAG) * decimation

    // ---- pairs

    private fun pair(a: Song, b: Song, dir: File): String {
        val line = StringBuilder("${dir.name}:")
        if (a.rate != b.rate) return "$line different rates"
        val rate = a.rate
        val aEnd = analyse(a, "phoneTail")
        val bHead = analyse(b, "phoneHead")
        val bCopy = Copy(b.head(0, (PHONE_COPY_S * rate).toInt()), rate, 2, 0.0)
        val earliest = (EARLIEST_S * rate).toLong()

        // 1. Auto, as the phone makes it.
        val aSong = AutoOnBands.Song(a.id, aEnd.copy, aEnd.region, aEnd.fromMs, a.songMs)
        val bSong = AutoOnBands.Song(b.id, bCopy, bHead.region, 0.0, b.songMs)
        val chosen = AutoOnBands.choose(aSong, bSong, earliest, maxProofs = PHONE_PROOFS)
        line.append(" auto ${chosen.pick.describe()}")
        chosen.render?.let { write(dir, "auto-phone", it, aEnd.pcm, bCopy.pcm, aEnd.fromMs, chosen.pick.kind.name) }

        // 2. An 8-bar Fade with beat matching on, made in the editor and previewed there.
        val aEditor = analyse(a, "editorTail")
        val bEditor = analyse(b, "editorHead")
        val lock = forcedLock(aEditor, bEditor, a.songMs - PLAY_REACH_S * 1000)
        if (lock == null) {
            line.append("; no lock: ").append(lockWhy)
            return line.toString()
        }
        line.append("; lock ${lock.bars} bars A %.2f to B %.2f BPM at %.4f".format(lock.a.bpm, lock.b.bpm, lock.tempoRatio()))
        val editorFacts = ChangeFacts(
            rate, 2, (lock.a.atMs - aEditor.fromMs) * rate / 1000.0, (aEditor.pcm.size / 2).toLong(),
            lock.b.atMs * rate / 1000.0, (bEditor.pcm.size / 2).toLong(),
            anchors = aEditor.region.takeIf { it.grid != null && lock.beatMatched }?.beatFrames(),
        )
        when (val r = SpecPlan.plan(lock, editorFacts)) {
            is SpecPlan.Result.Refused -> line.append("; editor refused: ${r.why}")
            is SpecPlan.Result.Planned -> write(dir, "lock-editor", LaneMixer(r.plan).render(aEditor.pcm, bEditor.pcm, 1.0, 1.0), aEditor.pcm, bEditor.pcm, aEditor.fromMs, "forced")
        }
        val plans = LinkedHashMap<String, com.dd3boh.outertune.mix.engine.LanePlan>()
        (SpecPlan.plan(lock, editorFacts) as? SpecPlan.Result.Planned)?.let { plans["lock-editor"] = it.plan }
        // The same change with B's bar 1 looped four times in front of it, and with A's mids gone
        // from bar 1 (the cheap stand-in for a vocal mute), for the loop and voice measures.
        if (env["MIX_BETTER_VARIANTS"] == "1") {
            val looped = lock.copy(introLoop = IntroLoop(1, lock.b.atMs, 4))
            when (val r = SpecPlan.plan(looped, editorFacts)) {
                is SpecPlan.Result.Refused -> line.append("; loop refused: ${r.why}")
                is SpecPlan.Result.Planned -> {
                    plans["loop-editor"] = r.plan
                    write(dir, "loop-editor", LaneMixer(r.plan).render(aEditor.pcm, bEditor.pcm, 1.0, 1.0), aEditor.pcm, bEditor.pcm, aEditor.fromMs, "B's bar 1 looped 4 times")
                    r.plan.loop?.let { l -> File(dir, "loop-editor-loop.json").writeText(buildJsonObject {
                        put("srcFrom", l.srcFrom); put("frames", l.frames); put("outFrom", l.outFrom); put("outTo", l.outTo); put("seam", l.seam)
                        put("bStart", r.plan.bStart); put("bBar1", r.plan.facts.bBar1); put("barFrames", r.plan.barFrames)
                        putJsonArray("joins") { l.joins().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        // The looped bar's key against the outgoing song's over the change: a bar with
                        // no sure key (drums alone) is always safe; one with a key should sit within a
                        // step of A's on the Camelot wheel.
                        val barKey = com.dd3boh.outertune.mix.Keys.of(bEditor.region, l.srcFrom, l.srcFrom + l.frames)
                        val aFrom = (lock.a.atMs - aEditor.fromMs) * rate / 1000.0
                        val aKey = com.dd3boh.outertune.mix.Keys.of(aEditor.region, aFrom.toLong().coerceAtLeast(0), aEditor.region.envelope.trueEnd)
                        barKey?.let { put("loopKey", it.key.camelot); put("loopKeySure", it.sure); put("loopKeyCorrelation", it.correlation) }
                        aKey?.let { put("aKey", it.key.camelot); put("aKeySure", it.sure) }
                        if (barKey != null && aKey != null) put("camelotDistance", aKey.key.distanceTo(barKey.key))
                    }.toString()) }
                }
            }
            // A's first 2 bars of the change played twice more before it goes on.
            val outro = lock.copy(outroLoop = com.dd3boh.outertune.mix.edit.OutroLoop(2, lock.a.atMs, 2))
            when (val r = SpecPlan.plan(outro, editorFacts)) {
                is SpecPlan.Result.Refused -> line.append("; outgoing loop refused: ${r.why}")
                is SpecPlan.Result.Planned -> {
                    plans["outroloop-editor"] = r.plan
                    write(dir, "outroloop-editor", LaneMixer(r.plan).render(aEditor.pcm, bEditor.pcm, 1.0, 1.0), aEditor.pcm, bEditor.pcm, aEditor.fromMs, "A's 2 bars from bar 1 twice more")
                    r.plan.outro?.let { o -> File(dir, "outroloop-editor-loop.json").writeText(buildJsonObject {
                        put("srcFrom", o.srcFrom); put("frames", o.frames); put("seam", o.seam); put("times", o.times)
                        putJsonArray("joins") { for (k in 0 until o.times) add(kotlinx.serialization.json.JsonPrimitive(r.plan.aMap.outAt((o.end + k * o.frames).toDouble()))) }
                        put("after", r.plan.aMap.outAt((o.end + o.times * o.frames).toDouble()))
                        put("aBarOut", o.frames / r.plan.speed)
                    }.toString()) }
                }
            }
            val midsOut = lock.copy(muted = setOf(LaneId(Side.A, Lane.MIDS)))
            when (val r = SpecPlan.plan(midsOut, editorFacts)) {
                is SpecPlan.Result.Refused -> line.append("; mids out refused: ${r.why}")
                is SpecPlan.Result.Planned -> {
                    plans["midsout-editor"] = r.plan
                    write(dir, "midsout-editor", LaneMixer(r.plan).render(aEditor.pcm, bEditor.pcm, 1.0, 1.0), aEditor.pcm, bEditor.pcm, aEditor.fromMs, "A's mids gone from bar 1")
                }
            }
            // A's mids out and B's in on the middle bar line, as the basses swap: the voices meet
            // there and nowhere else.
            val midSwap = lock.copy(moves = AutoMoves.fade(lock.bars.toDouble(), 0.25, oneVoice = true), preset = Preset.CUSTOM)
            when (val r = SpecPlan.plan(midSwap, editorFacts)) {
                is SpecPlan.Result.Refused -> line.append("; mid swap refused: ${r.why}")
                is SpecPlan.Result.Planned -> {
                    plans["midswap-editor"] = r.plan
                    write(dir, "midswap-editor", LaneMixer(r.plan).render(aEditor.pcm, bEditor.pcm, 1.0, 1.0), aEditor.pcm, bEditor.pcm, aEditor.fromMs, "the mids swapped on the middle bar line")
                }
            }
            // Each song's voice, and A without its voice, as the change shapes them: the parts the
            // stems folder split (tools/stems_prep.py) through the very same plan, one side at a time.
            val stemsDir = File(root, "stems")
            val aVox = stemOf(stemsDir, a, "tail", "vocals", aEditor.pcm.size)
            val bVox = stemOf(stemsDir, b, "head", "vocals", bEditor.pcm.size)
            if (aVox != null && bVox != null) {
                val aInst = ShortArray(aEditor.pcm.size) { (aEditor.pcm[it] - aVox[it]).coerceIn(-32768, 32767).toShort() }
                val silentA = ShortArray(aEditor.pcm.size)
                val silentB = ShortArray(bEditor.pcm.size)
                for ((name, plan) in plans) {
                    val from = max(0L, floor(plan.out(-1.0)).toLong()).coerceAtMost(plan.mixFrom)
                    val to = max(ceil(plan.out(plan.spec.lengthBars + 1.0)).toLong(), plan.mixTo)
                    fun heard(av: ShortArray, bv: ShortArray) = LaneMixer(plan).render(av, bv, 1.0, 1.0).mix(av, bv, from, to)
                    wavMono(File(dir, "$name-avox.wav"), heard(aVox, silentB), plan.sampleRate)
                    wavMono(File(dir, "$name-bvox.wav"), heard(silentA, bVox), plan.sampleRate)
                    wavMono(File(dir, "$name-ainst.wav"), heard(aInst, silentB), plan.sampleRate)
                    File(dir, "$name-vox.json").writeText(buildJsonObject {
                        put("from", from); put("to", to); put("mixFrom", plan.mixFrom); put("mixTo", plan.mixTo); put("bStart", plan.bStart)
                        put("bar1", plan.bar1); put("barFrames", plan.barFrames); put("rate", plan.sampleRate)
                        plan.loop?.let { l -> put("loopFrom", l.outFrom); put("loopTo", l.outTo) }
                    }.toString())
                }
            }
        }

        // 3. The same change saved and played on the phone: the end held early enough for it, B's
        // copy as long as it needs, A's anchor found again on the held end's own analysis.
        val firstMs = SpecPlan.firstChangeMs(lock)
        val heldS = ((a.songMs - firstMs) / 1000.0 + SAVED_LEAD_S).coerceIn(PHONE_TAIL_S, PHONE_TAIL_MAX_S)
        val held = analyse(a, "phoneTail", heldS)
        val copyS = ((lock.b.atMs + lock.lengthMs + 2 * lock.b.barMs) / 1000.0 + 0.4 + 2.0).coerceIn(PHONE_COPY_S, 60.0)
        val bSaved = Copy(b.head(0, (copyS * rate).toInt()), rate, 2, 0.0)
        Placing.facts(lock, rate, 2, held.fromMs, (held.pcm.size / 2).toLong(), held.region, bSaved.frames.toLong(), earliest).fold(
            onSuccess = { facts ->
                // A forced lock on beats the gates do not trust: Placing would refuse them, so the
                // anchors are taken from the grid as it stands, to measure what it would do.
                when (val r = SpecPlan.plan(lock, facts)) {
                    is SpecPlan.Result.Refused -> line.append("; phone refused: ${r.why}")
                    is SpecPlan.Result.Planned -> write(dir, "lock-phone", LaneMixer(r.plan).render(held.pcm, bSaved.pcm, 1.0, 1.0), held.pcm, bSaved.pcm, held.fromMs, "saved")
                }
            },
            onFailure = { e ->
                line.append("; phone: ${e.message}")
                val near = (lock.a.atMs - held.fromMs) * rate / 1000.0
                val g = held.region.grid
                if (g != null) {
                    val at = held.region.beatFrame(held.region.beatNear(near))
                    val facts = ChangeFacts(rate, 2, at, (held.pcm.size / 2).toLong(), lock.b.atMs * rate / 1000.0, bSaved.frames.toLong(), earliest, anchors = held.region.beatFrames())
                    when (val r = SpecPlan.plan(lock, facts)) {
                        is SpecPlan.Result.Refused -> line.append("; phone refused: ${r.why}")
                        is SpecPlan.Result.Planned -> write(dir, "lock-phone", LaneMixer(r.plan).render(held.pcm, bSaved.pcm, 1.0, 1.0), held.pcm, bSaved.pcm, held.fromMs, "saved, beats untrusted")
                    }
                }
            },
        )
        return line.toString()
    }

    private var lockWhy = ""

    /** The downbeat of [line] nearest [ms], or null. */
    private fun nearestDownbeat(line: BeatLine?, ms: Double): Double? {
        val l = line ?: return null
        val k = l.kNear(ms)
        val d = listOf(l.downbeatAtOrBefore(k), l.downbeatAtOrAfter(k)).minBy { abs(l.msOf(it) - ms) }
        return l.msOf(d)
    }

    /**
     * An 8-bar Fade with beat matching on, as the editor makes one from the grids as they stand,
     * trusted or not: B's bar 1 its first downbeat, A's the last downbeat from which 8 bars fit
     * before its true end and the change can still be played ([earliestMs]). 4 bars where the
     * tempos are 4 to 6% apart; null where they are further.
     */
    private fun forcedLock(a: End, b: End, earliestMs: Double): TransitionSpec? {
        val la = a.end.line
        val lb = b.end.line
        if (la == null || lb == null) {
            lockWhy = "no beat line on " + (if (la == null) "A" else "B")
            return null
        }
        var k1 = lb.downbeatAtOrAfter(lb.kNear(b.end.firstSoundMs) - 1)
        while (lb.msOf(k1) < b.end.firstSoundMs - 30.0) k1 += lb.beatsPerBar
        var best = lb.bpm / la.bpm
        var perB = 1.0
        for (m in doubleArrayOf(2.0, 0.5)) {
            val r = lb.bpm / (la.bpm * m)
            if (abs(1 - r) < abs(1 - best)) {
                best = r
                perB = 1.0 / m
            }
        }
        val bars = when {
            abs(best - 1) <= Planner.BEAT_EIGHT_BAR_MAX_SPEED -> 8
            abs(best - 1) <= Planner.BEAT_MAX_SPEED -> 4
            else -> {
                lockWhy = "tempos %.1f%% apart".format(abs(best - 1) * 100)
                return null
            }
        }
        val beatsA = (bars * 4 * perB).roundToInt()
        val ramp = if (abs(best - 1) > 0.03) 8 else 4
        var kS = la.downbeatAtOrBefore(la.kNear(a.end.trueEndMs) - beatsA)
        while (kS > la.firstK - 400 && (la.msOf(kS + beatsA) > a.end.trueEndMs || la.msOf(kS - ramp) < max(earliestMs, a.fromMs))) {
            if (la.msOf(kS - ramp) < max(earliestMs, a.fromMs)) {
                lockWhy = "8 bars do not fit in what can be played of A's end"
                return null
            }
            kS -= la.beatsPerBar
        }
        val aLocal = a.end.lineFor(kS - ramp, kS + beatsA) ?: la
        val kEndB = k1 + bars * 4
        val bLocal = b.end.lineFor(k1, kEndB) ?: lb
        return TransitionSpec(
            a = Anchor(a.song.id, aLocal.msOf(kS), aLocal.bpm, 4),
            b = Anchor(b.song.id, atOrAfterBStart(bLocal.msOf(k1)), bLocal.bpm, 4),
            lanes = LaneSet.BANDS,
            bars = bars,
            beatMatch = true,
            preset = Preset.FADE,
            moves = AutoMoves.fade(bars.toDouble(), 0.25),
        )
    }

    /**
     * The change's frames and its sound: A as stretched and B as it plays, each alone and mono on
     * the output clock, and the mix as heard, from a bar before the change to a bar after it.
     */
    private fun write(dir: File, name: String, render: ChangeRender, aPcm: ShortArray, bPcm: ShortArray, aStartMs: Double, note: String) {
        val p = render.plan
        val rate = p.sampleRate
        val spec = p.spec
        val from = max(0L, floor(p.out(-1.0)).toLong()).coerceAtMost(p.mixFrom)
        val to = max(ceil(p.out(spec.lengthBars + 1.0)).toLong(), p.mixTo)
        val n = (to - from).toInt()
        val aSource = LaneMixer(p).outgoingSource(aPcm)
        val bSource = p.loop?.let { com.dd3boh.outertune.mix.engine.LoopedSource(bPcm, 2, (bPcm.size / 2).toLong(), p.bStart, it) }
            ?: PcmSource(bPcm, 2, (bPcm.size / 2).toLong(), p.bStart)
        val aMono = ShortArray(n)
        val bMono = ShortArray(n)
        val fa = DoubleArray(2)
        val fb = DoubleArray(2)
        val aEnd = p.aMap.outAt((aPcm.size / 2 + (p.outro?.shift ?: 0L)).toDouble())
        for (i in 0 until n) {
            val o = from + i
            if (o < aEnd) aSource.frame(o, fa) else fa.fill(0.0)
            bSource.frame(o, fb)
            aMono[i] = clamp((fa[0] + fa[1]) / 2)
            bMono[i] = clamp((fb[0] + fb[1]) / 2)
        }
        val mix = render.mix(aPcm, bPcm, from, to)
        wav(File(dir, "$name-a.wav"), aMono, rate, 1)
        wav(File(dir, "$name-b.wav"), bMono, rate, 1)
        wav(File(dir, "$name-mix.wav"), mix, rate, 2)
        val o = buildJsonObject {
            put("variant", name)
            put("note", note)
            put("rate", rate)
            put("from", from)
            put("to", to)
            put("aStartMs", aStartMs)
            put("aId", spec.a.song)
            put("bId", spec.b.song)
            put("aAtMs", spec.a.atMs)
            put("aBpm", spec.a.bpm)
            put("bAtMs", spec.b.atMs)
            put("bBpm", spec.b.bpm)
            put("bpb", spec.b.beatsPerBar)
            put("bars", spec.bars)
            put("seconds", spec.seconds)
            put("lengthBars", spec.lengthBars)
            put("beatMatched", spec.beatMatched)
            put("preset", spec.from.key)
            put("bar1", p.bar1)
            put("barFrames", p.barFrames)
            put("bStart", p.bStart)
            put("mixFrom", p.mixFrom)
            put("mixTo", p.mixTo)
            put("aGone", p.aGone)
            put("bothFrom", p.bothFrom)
            put("bothTo", p.bothTo)
            put("speed", p.speed)
            put("rampStart", if (p.aMap.isIdentity) -1.0 else p.aMap.rampStart)
            put("rampLength", p.aMap.rampLength)
            put("aBar1", p.facts.aBar1)
            put("bBar1", p.facts.bBar1)
            put("describe", p.describe())
        }
        File(dir, "$name.json").writeText(o.toString())
    }

    private fun clamp(v: Double): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()

    /** A part of [s]'s end as tools/stems_prep.py wrote it, as long as the window it was split from; null where there is none. */
    private fun stemOf(dir: File, s: Song, end: String, part: String, samples: Int): ShortArray? {
        val f = File(dir, "${s.id}.$end.$part.s16")
        if (!f.isFile) return null
        val bytes = f.readBytes()
        val out = ShortArray(samples)
        val n = minOf(samples, bytes.size / 2)
        ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, n)
        return out
    }

    /** Interleaved stereo [samples] written as one mono file, the two channels' mean. */
    private fun wavMono(file: File, samples: ShortArray, rate: Int) {
        val mono = ShortArray(samples.size / 2) { ((samples[2 * it] + samples[2 * it + 1]) / 2).toShort() }
        wav(file, mono, rate, 1)
    }

    private fun wav(file: File, samples: ShortArray, rate: Int, channels: Int) {
        val n = samples.size / channels
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort(s)
        DataOutputStream(FileOutputStream(file)).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 2 * channels).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
            header.put("data".toByteArray()).putInt(n * 2 * channels)
            out.write(header.array())
            out.write(data.array())
        }
    }

    companion object {
        val WINDOWS = listOf("phoneHead", "phoneTail", "editorHead", "editorTail")
        const val PHONE_HEAD_S = 30.0
        const val PHONE_COPY_S = 32.0
        const val PHONE_TAIL_S = 40.0
        const val PHONE_TAIL_MAX_S = 46.0
        const val EDITOR_HEAD_S = 60.0
        const val EDITOR_TAIL_S = 72.0
        const val SAVED_LEAD_S = 15.0

        /** What a saved change may reach before A's end on the phone (PairFacts.PLAY_REACH_MS). */
        const val PLAY_REACH_S = 31.0

        /** Where in the held end a change may start at the earliest: the capture and the work. */
        const val EARLIEST_S = 12.0
        const val PHONE_PROOFS = 4
    }
}
