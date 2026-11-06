/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.Spectrum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * The reels' way's features as the port computes them from the audio, against the prototype's.
 *
 * Reads the PC trial's caches (decoded songs and their htdemucs voice parts, never copied): set
 * REELS_CACHE to experiments/features/automix/party-2010s/cache, and the rebuilt cache of the seven
 * regression pairs after a colon. Per feature, the median and the largest difference over every
 * bar of every song; then every fixture decision again from the port's
 * features, from the port's voice labels on the prototype's features, and from VoiceGuess (the
 * app's stand-in for a voice part, a bar sung where its mean is at least VoiceRule.SINGS). Writes
 * part-b.txt and the port's features per song to REELS_OUT (default round4/trial).
 */
class ReelsAnalysisTrial {

    private val env = System.getenv()

    @Test
    fun trial() {
        val cache = env["REELS_CACHE"]?.takeIf { it.isNotBlank() }
        assumeTrue("set REELS_CACHE to run", cache != null)
        val out = File(env["REELS_OUT"]?.takeIf { it.isNotBlank() } ?: "/home/melb/projects/intertune/experiments/features/automix/round4/trial")
        File(out, "features").mkdirs()
        val only = env["REELS_ONLY"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val dirs = cache!!.split(':').filter { it.isNotBlank() }.map { File(it) }
        fun file(name: String) = dirs.map { File(it, name) }.firstOrNull { it.exists() } ?: File(dirs[0], name)
        val ids = ReelsFixtures.ids().filter { (only == null || it in only) && file("$it.f32").exists() }
        val r = StringBuilder()
        fun say(s: String) {
            r.append(s).append('\n')
            println(s)
        }

        val diffs = LinkedHashMap<String, MutableList<Triple<Double, String, Int>>>()
        fun add(name: String, d: Double, id: String, bar: Int) {
            if (!d.isNaN()) diffs.getOrPut(name) { ArrayList() } += Triple(abs(d), id, bar)
        }
        val portSongs = HashMap<String, ReelsSong>()
        val portVoice = HashMap<String, List<BarVoice>>()
        val guessOnPy = HashMap<String, List<BarVoice>>()
        val guessOnPort = HashMap<String, List<BarVoice>>()
        var labelsSame = 0
        var labelsAll = 0
        var sungSame = 0
        var pulsedSame = 0
        var lagSame = 0
        var pulseAll = 0
        val guessTable = IntArray(4) // stems sung & guess sung, stems sung only, guess sung only, neither
        val guessByLabel = BarVoice.entries.associateWith { IntArray(2) } // per voice part label: guess not sung, sung
        val linesSame = ArrayList<String>()
        val linesDiff = ArrayList<String>()

        for (id in ids) {
            val started = System.nanoTime()
            val fixture = ReelsFixtures.song(id)
            val grid = fixture.grid
            val py = Json.parseToJsonElement(file("$id.analysis.json").readText()).jsonObject
            val pyPulse = Json.parseToJsonElement(file("$id.pulse.json").readText()).jsonObject
            val stereo = readF32(file("$id.f32"))
            val vocalsFile = file("$id.vocals.f32")
            val vocals = if (vocalsFile.exists()) readF32(vocalsFile) else null
            val res = ReelsAnalysis.analyse(stereo, grid, vocals)
            val pulse = ReelsAnalysis.pulse(stereo, res.bars.map { it.t }, grid.bpm)
            val barS = 4 * 60.0 / grid.bpm
            guessOnPy[id] = voiceGuess(stereo, fixture.bars.map { it.t }, barS)
            guessOnPort[id] = voiceGuess(stereo, res.bars.map { it.t }, barS)
            val seconds = (System.nanoTime() - started) / 1e9

            val pyBars = py["bars"]!!.jsonArray.map { it.jsonObject }
            val pyRows = pyPulse["bars"]!!.jsonArray.map { it.jsonObject }
            val n = min(pyBars.size, res.bars.size)
            fun pv(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.doubleOrNull ?: Double.NaN
            for (j in 0 until n) {
                val k = res.bars[j]
                val p = pyBars[j]
                add("t", k.t - pv(p, "t"), id, j)
                add("level", k.level - pv(p, "level"), id, j)
                add("low150", k.low150 - pv(p, "low150"), id, j)
                add("ev", k.ev - pv(p, "ev"), id, j)
                add("lowB", k.lowB - pv(p, "lowB"), id, j)
                add("midB", k.midB - pv(p, "midB"), id, j)
                add("highB", k.highB - pv(p, "highB"), id, j)
                add("vdb", k.vdb - pv(p, "vdb"), id, j)
                add("sung share", k.sung - pv(p, "sung"), id, j)
                add("like", k.like - pv(p, "like"), id, j)
                val pyVoice = BarVoice.of(p["voice"]?.jsonPrimitive?.contentOrNull)
                labelsAll++
                if (pyVoice == k.voice) labelsSame++
                if ((pyVoice == BarVoice.SUNG) == (k.voice == BarVoice.SUNG)) sungSame++
                val q = pulse.rows[j]
                val pq = pyRows[j]
                add("pulse", q.pulse - pv(pq, "pulse"), id, j)
                add("lagMs", (q.lagMs ?: Double.NaN) - pv(pq, "lagMs"), id, j)
                add("lagRelMs", (q.lagRelMs ?: Double.NaN) - pv(pq, "lagRelMs"), id, j)
                pulseAll++
                if ((q.pulse >= 1.0) == (pv(pq, "pulse") >= 1.0)) pulsedSame++
                if (q.lagRelMs == pq["lagRelMs"]?.jsonPrimitive?.doubleOrNull) lagSame++
                val stemSung = pyVoice == BarVoice.SUNG
                val guessSung = guessOnPy[id]!![j] == BarVoice.SUNG
                guessTable[if (stemSung && guessSung) 0 else if (stemSung) 1 else if (guessSung) 2 else 3]++
                guessByLabel[pyVoice]!![if (guessSung) 1 else 0]++
            }
            val pyLines = py["lines"]!!.jsonArray.map { it.jsonPrimitive.int }
            val pyPeaks = py["peaks"]!!.jsonArray.map { it.jsonPrimitive.int }
            val same = pyLines == res.lines && pyPeaks == res.peaks
            (if (same) linesSame else linesDiff) += id
            say(
                "%s: %d bars (prototype %d), %.1f s; lines %s%s; peaks %s%s; median lag %.1f ms (prototype %.1f); last sound bar %d (%d)".format(
                    Locale.ROOT, id, res.bars.size, pyBars.size, seconds,
                    if (pyLines == res.lines) "same" else "${res.lines} (prototype $pyLines)", "",
                    if (pyPeaks == res.peaks) "same" else "${res.peaks} (prototype $pyPeaks)", "",
                    pulse.medianLagMs, pyPulse["medianLagMs"]!!.jsonPrimitive.double, res.lastSoundBar,
                    py["last_sound_bar"]!!.jsonPrimitive.int,
                ),
            )
            val song = ReelsAnalysis.song(id, grid, res, pulse.check(fixture.pulse.barAgreement), fixture.lufs)
            portSongs[id] = song
            portVoice[id] = res.bars.map { it.voice }
            File(out, "features/$id.json").writeText(featuresJson(id, res, pulse, guessOnPort[id]!!).toString())
        }

        say("")
        say("Features, |port - prototype| over every bar: median, largest (where)")
        for ((name, list) in diffs) {
            val v = list.map { it.first }.sorted()
            val worst = list.maxBy { it.first }
            say("  %-11s median %.4g, largest %.4g (%s bar %d), %d bars".format(Locale.ROOT, name, v[v.size / 2], worst.first, worst.second, worst.third, v.size))
        }
        say("  voice labels equal in %d of %d bars, sung or not equal in %d".format(Locale.ROOT, labelsSame, labelsAll, sungSame))
        say("  pulse >= 1 equal in %d of %d bars, lagRelMs equal in %d".format(Locale.ROOT, pulsedSame, pulseAll, lagSame))
        say("  lines and peaks equal in ${linesSame.size} songs; differ in ${linesDiff.joinToString().ifEmpty { "none" }}")
        say(
            "  VoiceGuess against the voice part, per bar: both sung %d, voice part only %d, VoiceGuess only %d, neither %d".format(
                Locale.ROOT, guessTable[0], guessTable[1], guessTable[2], guessTable[3],
            ),
        )
        for ((label, c) in guessByLabel) say("    voice part '${label.label}': ${c[0] + c[1]} bars, VoiceGuess says sung in ${c[1]}")

        if (only == null && ids.size == ReelsFixtures.ids().size) {
            say("")
            say("Decisions on the render fields, against the frozen ones:")
            decisions(say = ::say, name = "prototype features and voice (part A)") { ReelsFixtures.song(it) }
            decisions(say = ::say, name = "port features and voice") { portSongs[it]!! }
            decisions(say = ::say, name = "prototype features, port voice") { withVoice(ReelsFixtures.song(it), portVoice[it]!!) }
            decisions(say = ::say, name = "prototype features, VoiceGuess") { withVoice(ReelsFixtures.song(it), guessOnPy[it]!!) }
            decisions(say = ::say, name = "port features, VoiceGuess") { withVoice(portSongs[it]!!, guessOnPort[it]!!) }
            decisions(say = ::say, name = "prototype features, no voice at all") { withVoice(ReelsFixtures.song(it), List(ReelsFixtures.song(it).bars.size) { BarVoice.NONE }) }
        }
        File(out, if (only == null) "part-b.txt" else "part-b-some.txt").writeText(r.toString())
    }

    /**
     * The app's whole-song grid (WholeSongGrid, Mixxx's constant tempo) against the Beat This! grid
     * the prototype used, on the same 24 songs: the tempo, where the app's beats sit against Beat
     * This!'s at a quarter, half and three quarters of the song (ms, and in beats), and its bar 1.
     * Numbers only. Writes grid.txt.
     */
    @Test
    fun grid() {
        val cache = env["REELS_CACHE"]?.takeIf { it.isNotBlank() }
        assumeTrue("set REELS_CACHE and REELS_GRID=1 to run", cache != null && env["REELS_GRID"] == "1")
        val dirs = cache!!.split(':').filter { it.isNotBlank() }.map { File(it) }
        fun file(name: String) = dirs.map { File(it, name) }.firstOrNull { it.exists() } ?: File(dirs[0], name)
        val out = File(env["REELS_OUT"]?.takeIf { it.isNotBlank() } ?: "/home/melb/projects/intertune/experiments/features/automix/round4/trial")
        out.mkdirs()
        val r = StringBuilder()
        fun say(s: String) {
            r.append(s).append('\n')
            println(s)
        }
        say("id: Beat This! bpm / app bpm (octave, %), beat offset app - Beat This! at 25/50/75% (ms; in beats), app bar 1 against Beat This!'s at 50% (beats), app gates")
        val tally = LinkedHashMap<String, Int>()
        for (id in ReelsFixtures.ids().filter { file("$it.f32").exists() }) {
            val bt = ReelsFixtures.song(id).grid
            val stereo = readF32(file("$id.f32"))
            val shorts = ShortArray(stereo.size) { Math.rint(stereo[it] * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort() }
            val g = com.dd3boh.outertune.mix.WholeSongGrid.analyse(com.dd3boh.outertune.mix.FrontEnd.of(shorts, ReelsAnalysis.RATE, 2))
            if (g == null) {
                say("$id: Beat This! %.2f; the app found no grid".format(Locale.ROOT, bt.bpm))
                tally.merge("no grid", 1, Int::plus)
                continue
            }
            val dur = stereo.size / 2.0 / ReelsAnalysis.RATE
            val pB = 60.0 / bt.bpm
            val pA = 60.0 / g.bpm
            val tA0 = g.t0Ms / 1000
            val ratio = g.bpm / bt.bpm
            val octave = listOf(0.5, 1.0, 2.0).minBy { abs(ratio / it - 1) }
            val offsets = listOf(0.25, 0.5, 0.75).map { f ->
                val kB = Math.round((f * dur - bt.t0) / pB)
                val tb = bt.t0 + kB * pB
                val kA = Math.round((tb - tA0) / pA)
                tA0 + kA * pA - tb
            }
            val mid = offsets[1]
            val beatClass = if (abs(mid) <= 0.025) "beats on" else if (abs(abs(mid) / pB - 0.5) <= 0.1) "half a beat off" else "beats off"
            // bar 1 at the middle: Beat This!'s downbeat nearest the middle, and the app's nearest it
            var barText = "no bars"
            var barClass = "app has no bars"
            if (g.barPhase >= 0 && abs(octave - 1.0) < 1e-9) {
                var kB = Math.round((0.5 * dur - bt.t0) / pB)
                while (Math.floorMod(kB - bt.phase, 4L) != 0L) kB++
                val tbd = bt.t0 + kB * pB
                var kA = Math.round((tbd - tA0) / pA)
                val cands = (kA - 4..kA + 4).filter { Math.floorMod(it - g.barPhase.toLong(), g.beatsPerBar.toLong()) == 0L }
                kA = cands.minBy { abs(tA0 + it * pA - tbd) }
                val raw = (tA0 + kA * pA - tbd) / pB
                // bar lines compared in 4-beat bars: an app bar of 8 beats whose line is 4 beats off still lands on a bar line
                val beats = raw - 4 * Math.round(raw / 4)
                barText = "%+.2f (raw %+.2f, %d-beat bars)".format(Locale.ROOT, beats, raw, g.beatsPerBar)
                barClass = if (abs(beats) <= 0.1) "bar lines same" else if (abs(abs(beats) - Math.round(abs(beats))) <= 0.1) "bar lines off by ${Math.round(abs(beats))} beats" else "bar lines off by part of a beat"
            } else if (g.barPhase >= 0) {
                barClass = "bars not compared (other octave)"
            }
            tally.merge(if (abs(octave - 1.0) < 1e-9) "same octave" else "octave x$octave", 1, Int::plus)
            tally.merge(beatClass, 1, Int::plus)
            tally.merge(barClass, 1, Int::plus)
            say(
                "%s: %.3f / %.3f (x%s, %+.3f%%), %s; bars %s (%s); tempoSure %s beatsSure %s barsSure %s, rounded %s".format(
                    Locale.ROOT, id, bt.bpm, g.bpm, octave, (ratio / octave - 1) * 100,
                    offsets.joinToString(" / ") { "%+.0f ms (%+.2f)".format(Locale.ROOT, it * 1000, it / pB) },
                    barText, barClass, g.tempoSure, g.beatsSure, g.barsSure, g.rounded,
                ),
            )
        }
        say("")
        for ((k, v) in tally) say("  $k: $v")
        File(out, "grid.txt").writeText(r.toString())
    }

    private fun decisions(say: (String) -> Unit, name: String, song: (String) -> ReelsSong) {
        var same = 0
        val notes = ArrayList<String>()
        val pairs = ReelsFixtures.pairs()
        for ((n, a, b) in pairs) {
            val frozen = ReelsFixtures.decision(n)
            val d = ReelsWay.decide(song(a), song(b))
            val json = d.toJson()
            val keys = RENDER_FIELDS.filter { frozen.containsKey(it) }
            val diffs = keys.flatMap { ReelsWayTest.jsonDiffs(frozen[it]!!, json[it] ?: kotlinx.serialization.json.JsonNull, it) }
            if (diffs.isEmpty()) {
                same++
            } else {
                notes += "    $n: frozen ${line(frozen)}; now ${line(json)}" +
                    (d.fit.why?.let { " (does not fit: ${it.joinToString()})" } ?: "") + "; " + diffs.take(4).joinToString("; ")
            }
        }
        say("  $name: $same of ${pairs.size} as frozen")
        notes.forEach(say)
    }

    private fun line(o: JsonObject): String {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: "-"
        return if (s("move") == "reels") "reels A ${s("exitBar")} B ${s("entryBarB")}/${s("dropBar")} ${s("shape")} swap ${s("swap")} echo ${s("echo")}"
        else "${s("move")} A ${s("exitBar")} B ${s("entryBarB")}"
    }

    private fun withVoice(s: ReelsSong, voice: List<BarVoice>): ReelsSong =
        s.copy(bars = s.bars.mapIndexed { i, b -> b.copy(voice = voice.getOrElse(i) { BarVoice.NONE }) })

    /** VoiceGuess over the whole song (the app's front end and spectrum), a bar sung where its mean reaches VoiceRule.SINGS. */
    private fun voiceGuess(stereo: FloatArray, barT: List<Double>, barS: Double): List<BarVoice> {
        val shorts = ShortArray(stereo.size) { Math.rint(stereo[it] * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort() }
        val front = FrontEnd.of(shorts, ReelsAnalysis.RATE, 2)
        val spectrum = Spectrum.of(front)
        val stepMs = 50.0
        val steps = (front.seconds * 1000 / stepMs).toInt()
        val p = VoiceGuess.of(spectrum, ReelsAnalysis.RATE, steps, stepMs, 0)
        return barT.map { t ->
            val i0 = (t * 1000 / stepMs).toInt().coerceIn(0, steps)
            val i1 = ((t + barS) * 1000 / stepMs).toInt().coerceIn(0, steps)
            val mean = if (i1 > i0) (i0 until i1).sumOf { p[it].toDouble() } / (i1 - i0) else 0.0
            if (mean >= VoiceRule.SINGS) BarVoice.SUNG else BarVoice.NONE
        }
    }

    private fun featuresJson(id: String, res: ReelsAnalysis.Result, pulse: ReelsAnalysis.PulseResult, guess: List<BarVoice>): JsonObject = buildJsonObject {
        put("id", id)
        put("duration", res.duration)
        put("lines", JsonArray(res.lines.map { JsonPrimitive(it) }))
        put("peaks", JsonArray(res.peaks.map { JsonPrimitive(it) }))
        put("last_sound_bar", res.lastSoundBar)
        put("medianLagMs", pulse.medianLagMs)
        put("bars", JsonArray(res.bars.mapIndexed { j, b ->
            buildJsonObject {
                put("bar", j)
                put("t", b.t)
                put("level", b.level)
                put("low150", b.low150)
                put("ev", b.ev)
                put("lowB", b.lowB)
                put("midB", b.midB)
                put("highB", b.highB)
                put("vdb", b.vdb)
                put("sung", b.sung)
                put("like", b.like)
                put("voice", b.voice.label)
                put("guess", guess[j].label)
                put("pulse", pulse.rows[j].pulse)
                put("lagMs", pulse.rows[j].lagMs)
                put("lagRelMs", pulse.rows[j].lagRelMs)
            }
        }))
    }

    companion object {
        /** What the prototype's renderer reads of a decision. */
        val RENDER_FIELDS = listOf(
            "move", "exitBar", "exitT", "boxStartBarA", "dropBar", "dropSure", "entryBarB", "entryT", "leadOffsetBars",
            "shape", "swap", "swapBoxBar", "ratio", "stretch", "echo", "octave", "fadeS",
        )

        /** Little-endian float32 samples, as the cache keeps them. */
        fun readF32(f: File): FloatArray {
            RandomAccessFile(f, "r").use { raf ->
                val ch = raf.channel
                val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }
}
