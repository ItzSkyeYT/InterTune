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
 * Reads the PC trial's cache (decoded songs and their htdemucs voice parts, never copied): set
 * REELS_CACHE to experiments/features/automix/party-2010s/cache. Per feature, the median and the
 * largest difference over every bar of the 24 songs; then the 23 decisions again from the port's
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
        val ids = ReelsFixtures.ids().filter { only == null || it in only }
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
            val py = Json.parseToJsonElement(File(cache, "$id.analysis.json").readText()).jsonObject
            val pyPulse = Json.parseToJsonElement(File(cache, "$id.pulse.json").readText()).jsonObject
            val stereo = readF32(File(cache!!, "$id.f32"))
            val vocalsFile = File(cache, "$id.vocals.f32")
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

        if (only == null) {
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

    private fun decisions(say: (String) -> Unit, name: String, song: (String) -> ReelsSong) {
        var same = 0
        val notes = ArrayList<String>()
        for ((n, a, b) in ReelsFixtures.pairs()) {
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
        say("  $name: $same of 23 as frozen")
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
