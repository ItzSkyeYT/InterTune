/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.fingerprint.Fft
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * How clean the stretcher is at beat matching's few percent, on real songs: his reels' songs as the
 * mix-better trial decoded them (48 kHz 16-bit stereo heads and tails), each excerpt through each
 * way of [LabStretch] at 0.97 and 1.03 (STRETCH_SPEEDS to change), and through a glide from 1.03
 * back to 1 over two bars, the song's beats the anchors as on the phone. For each render: the
 * spectral distance from the song itself at the frames the map takes (third-octave bands from 40 Hz
 * to 16 kHz, 43 ms windows every 10 ms, in dB: 0 is the song itself), its splices a second, how
 * alike the two sides of each are, how far the crossfades dip, and where the beats land. Clips for
 * listening beside the numbers.
 *
 *   STRETCH_TRIAL=<label> STRETCH_WAYS=wsola,keep ./gradlew :mix:test --tests '*StretchTrial*' --rerun
 *
 * writes experiments/features/automix/stretch/<label>/ (stretch.tsv, summary.txt, clips).
 */
class StretchTrial {

    private val decoded = File(System.getenv("STRETCH_DECODED") ?: "/home/melb/projects/intertune/experiments/features/mix-better/decoded")

    private val excerpts = listOf(
        "amidreaming.tail" to 12.0, "starboy.head" to 14.0, "lushlife.tail" to 10.0, "leanon.head" to 20.0,
        "saopaulo.tail" to 12.0, "whyb.head" to 20.0, "sandstorm.head" to 30.0, "middle.tail" to 14.0,
        "midnightcity.head" to 20.0, "afterhours.head" to 30.0,
    )

    private val ways = mapOf(
        "wsola" to LabStretch.Knobs("wsola"),
        "keep" to LabStretch.Knobs("keep", keepLevel = true),
        "wide" to LabStretch.Knobs("wide", keepLevel = true, searchS = 0.010),
        "hits" to LabStretch.Knobs("hits", keepLevel = true, avoidHits = true),
        "full" to LabStretch.Knobs("full", keepLevel = true, fullRate = true),
        "fullwide" to LabStretch.Knobs("fullwide", keepLevel = true, fullRate = true, searchS = 0.010),
        "planned" to LabStretch.Knobs("planned", keepLevel = true, planned = true),
        "planned5" to LabStretch.Knobs("planned5", keepLevel = true, planned = true, spliceS = 0.005, flexS = 0.005),
        "pfade8" to LabStretch.Knobs("pfade8", keepLevel = true, planned = true, fadeS = 0.008),
        "pfade5" to LabStretch.Knobs("pfade5", keepLevel = true, planned = true, fadeS = 0.005),
        "pfull" to LabStretch.Knobs("pfull", keepLevel = true, planned = true, finalFull = true),
        "pfullfade8" to LabStretch.Knobs("pfullfade8", keepLevel = true, planned = true, finalFull = true, fadeS = 0.008),
        "p10fade8" to LabStretch.Knobs("p10fade8", keepLevel = true, planned = true, finalFull = true, fadeS = 0.008, spliceS = 0.010, flexS = 0.004),
        "now" to LabStretch.Knobs("now"),
        "fade8" to LabStretch.Knobs("fade8", keepLevel = true, fadeS = 0.008),
        "pscore8" to LabStretch.Knobs("pscore8", keepLevel = true, planned = true, finalFull = true, fadeS = 0.008, fadeScore = true),
        "pscore12" to LabStretch.Knobs("pscore12", keepLevel = true, planned = true, finalFull = true, fadeS = 0.012, fadeScore = true),
    )

    private val seconds = 24.0
    private val clipFrom = 6.0
    private val clipSeconds = 12.0
    private val rate = 48_000

    class Row(
        val song: String, val map: String, val way: String, val lsdMean: Double, val lsdP95: Double, val low: Double, val mid: Double, val high: Double,
        val harmMean: Double, val harmP95: Double, val harmMid: Double, val harmHigh: Double,
        val splices: Double, val likeMean: Double, val below07: Double, val dipMean: Double, val dipWorst: Double, val beatErrMax: Double, val ms: Double,
    )

    @Test
    fun stretch() {
        val label = System.getenv("STRETCH_TRIAL")
        assumeTrue("STRETCH_TRIAL not set", !label.isNullOrBlank())
        val chosen = System.getenv("STRETCH_WAYS")?.split(',')?.map { it.trim() } ?: ways.keys.toList()
        val speeds = System.getenv("STRETCH_SPEEDS")?.split(',')?.map { it.trim().toDouble() } ?: listOf(0.97, 1.03)
        val clipWays = System.getenv("STRETCH_CLIPS")?.split(',')?.map { it.trim() }?.toSet() ?: chosen.toSet()
        val out = File("/home/melb/projects/intertune/experiments/features/automix/stretch/$label").apply { mkdirs() }
        val clips = File(out, "clips").apply { mkdirs() }
        val rows = ArrayList<Row>()
        for ((part, at) in excerpts) {
            val raw = File(decoded, "$part.s16")
            if (!raw.isFile) continue
            val n = (seconds * rate).toInt()
            val pcm = read(raw, (at * rate).toInt(), n)
            val front = FrontEnd.of(pcm, rate, 2)
            val analysis = RegionAnalysis.analyse(front, RegionKind.TAIL)
            val beats = analysis.beatFrames()
            val bpm = analysis.rhythm.bpm ?: 120.0
            val barFrames = 4 * 60.0 / bpm * rate
            val maps = speeds.map { "%.2f".format(it) to (TimeMap(0.5 * rate, 0.5 * rate, it) as FrameMap) } +
                ("glide" to GlideMap(0.0, 0.0, 1.03, 8.0 * rate, 8.0 * rate + 2 * barFrames))
            for ((mapName, map) in maps) {
                val length = min(map.outAt((n - rate).toDouble()), (n - rate).toDouble()).toLong()
                for (wayName in chosen) {
                    val started = System.nanoTime()
                    // "now" is the stretcher in the app; the others are the lab's ways, "wsola" the app's before.
                    val frames: Taken = if (wayName == "now") {
                        val w = Wsola(pcm, 2, rate, front.mono, front.factor, beats).frames(map, 0, length)
                        Taken(w.kFirst, w.positions, w::startOf, w::frame)
                    } else {
                        val knobs = ways[wayName] ?: error("no way $wayName")
                        val f = LabStretch(pcm, 2, rate, front.mono, front.factor, beats, knobs).frames(map, 0, length)
                        Taken(f.kFirst, f.positions, f::startOf, f::frame)
                    }
                    val rendered = DoubleArray((length * 2).toInt())
                    val x = DoubleArray(2)
                    for (o in 0 until length) {
                        frames.frame(o, x)
                        rendered[(2 * o).toInt()] = x[0]
                        rendered[(2 * o + 1).toInt()] = x[1]
                    }
                    val ms = (System.nanoTime() - started) / 1e6
                    val d = distance(pcm, rendered, map, length)
                    val taken = object : FrameMap {
                        override fun tailAt(out: Double): Double {
                            val o = out.toLong()
                            val k = Math.floorDiv(o, Wsola.HOP.toLong())
                            val j = (k - frames.kFirst).toInt().coerceIn(0, frames.positions.size - 1)
                            return (frames.positions[j] + (o - frames.startOf(frames.kFirst + j))).toDouble()
                        }
                        override fun outAt(tail: Double): Double = error("not used")
                        override val inPlaceUntil: Double get() = Double.NEGATIVE_INFINITY
                    }
                    val harm = distance(pcm, rendered, taken, length)
                    val spl = splices(pcm, frames, length)
                    val errors = beats.filter { it > rate && it < n - 2 * rate }.mapNotNull { landing(frames, map, it, length) }
                    rows += Row(
                        part, mapName, wayName, d[0], d[1], d[2], d[3], d[4], harm[0], harm[1], harm[3], harm[4],
                        spl.size / (length.toDouble() / rate),
                        spl.map { it.first }.average().takeIf { !it.isNaN() } ?: 1.0,
                        if (spl.isEmpty()) 0.0 else spl.count { it.first < 0.7 }.toDouble() / spl.size,
                        spl.map { it.second }.average().takeIf { !it.isNaN() } ?: 0.0,
                        spl.minOfOrNull { it.second } ?: 0.0,
                        errors.maxOfOrNull { abs(it) } ?: 0.0, ms,
                    )
                    if (wayName in clipWays && mapName in setOf("1.03", "0.97", "glide")) {
                        val c0 = (clipFrom * rate).toInt()
                        val c1 = min(length.toInt(), c0 + (clipSeconds * rate).toInt())
                        writeWav(File(clips, "$part-$mapName-$wayName.wav"), ShortArray((c1 - c0) * 2) { toPcm16(rendered[c0 * 2 + it]) }, rate)
                    }
                }
            }
            writeWav(File(clips, "$part-original.wav"), ShortArray((clipSeconds * rate).toInt() * 2) { pcm[(clipFrom * rate).toInt() * 2 + it] }, rate)
            val floor = distance(pcm, DoubleArray(pcm.size) { pcm[min(pcm.size - 1, it + 2 * 144)].toDouble() }, TimeMap.IDENTITY, n.toLong())
            println("$part: ${beats.size} beats at %.1f BPM; the measure's floor, the song against itself 3 ms on: %.3f dB (low %.3f, mid %.3f, high %.3f)".format(bpm, floor[0], floor[2], floor[3], floor[4]))
        }
        val tsv = StringBuilder("song\tmap\tway\tharm_mean_db\tharm_p95_db\tharm_mid_db\tharm_high_db\tlsd_mean_db\tlsd_p95_db\tlow_db\tmid_db\thigh_db\tsplices_per_s\tlikeness_mean\tbelow_0.7\tdip_mean_db\tdip_worst_db\tbeat_err_max_ms\trender_ms\n")
        for (r in rows) {
            tsv.append(
                "%s\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.2f\t%.3f\t%.3f\t%.2f\t%.2f\t%.2f\t%.0f\n".format(
                    r.song, r.map, r.way, r.harmMean, r.harmP95, r.harmMid, r.harmHigh, r.lsdMean, r.lsdP95, r.low, r.mid, r.high, r.splices, r.likeMean, r.below07, r.dipMean, r.dipWorst, r.beatErrMax, r.ms,
                ),
            )
        }
        File(out, "stretch.tsv").writeText(tsv.toString())
        val summary = StringBuilder("way\tmap\tharm_mean_db\tharm_p95_db\tharm_mid_db\tharm_high_db\tlsd_mean_db\tsplices_per_s\tlikeness_mean\tbelow_0.7\tdip_mean_db\tbeat_err_max_ms\n")
        for (way in chosen) for (m in rows.map { it.map }.distinct()) {
            val rs = rows.filter { it.way == way && it.map == m }
            if (rs.isEmpty()) continue
            summary.append(
                "%s\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.2f\t%.3f\t%.3f\t%.2f\t%.2f\n".format(
                    way, m, rs.map { it.harmMean }.average(), rs.map { it.harmP95 }.average(), rs.map { it.harmMid }.average(), rs.map { it.harmHigh }.average(),
                    rs.map { it.lsdMean }.average(), rs.map { it.splices }.average(), rs.map { it.likeMean }.average(), rs.map { it.below07 }.average(),
                    rs.map { it.dipMean }.average(), rs.maxOf { it.beatErrMax },
                ),
            )
        }
        File(out, "summary.txt").writeText(summary.toString())
        println(summary)
    }

    /** A render's grid frames, whichever stretcher made it. */
    class Taken(val kFirst: Long, val positions: LongArray, val startOf: (Long) -> Long, val frame: (Long, DoubleArray) -> Unit)

    /** Each splice's likeness (both channels, weighted as two half overlapping Hann windows blend) and the deepest its crossfade dips, in dB against the level just before and after it. */
    private fun splices(pcm: ShortArray, frames: Taken, length: Long): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val hop = Wsola.HOP
        val x = DoubleArray(2)
        val total = pcm.size / 2
        fun s(t: Long, c: Int) = if (t in 0 until total) pcm[(t * 2 + c).toInt()].toDouble() else 0.0
        for (j in 1 until frames.positions.size) {
            val k = frames.kFirst + j
            val at = frames.startOf(k)
            if (at < 480 || at + hop + 480 > length) continue
            val a = frames.positions[j - 1] + hop
            val b = frames.positions[j]
            if (a == b) continue
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in 0 until hop) {
                val w = (0.5 + 0.5 * cos(PI * i / hop)) * (0.5 - 0.5 * cos(PI * i / hop))
                for (c in 0 until 2) {
                    ab += w * s(a + i, c) * s(b + i, c)
                    aa += w * s(a + i, c) * s(a + i, c)
                    bb += w * s(b + i, c) * s(b + i, c)
                }
            }
            val like = if (aa * bb <= 0) 1.0 else ab / sqrt(aa * bb)
            out += like to dip(frames, at, x)
        }
        return out
    }

    private fun dip(frames: Taken, at: Long, x: DoubleArray): Double {
        fun level(from: Long, n: Int): Double {
            var s = 0.0
            for (o in from until from + n) {
                frames.frame(o, x)
                s += x[0] * x[0] + x[1] * x[1]
            }
            return s / n
        }
        val ref = (level(at - 480, 480) + level(at + Wsola.HOP, 480)) / 2
        if (ref < 1e4) return 0.0
        var worst = 0.0
        var o = at
        while (o + 120 <= at + Wsola.HOP) {
            worst = min(worst, 10 * log10(max(level(o, 120), 1e-9) / ref))
            o += 120
        }
        return worst
    }

    /**
     * The render's distance from the song at the frames [map] takes: per 10 ms, the third-octave
     * band levels of a 43 ms window of each channel, compared band by band in dB where the song's band
     * is within 60 dB of its loudest, as the root mean square. Mean, 95th percentile, and the mean
     * absolute difference under 250 Hz, from 250 Hz to 4 kHz and over 4 kHz.
     */
    private fun distance(pcm: ShortArray, out: DoubleArray, map: FrameMap, length: Long): DoubleArray {
        val size = 2048
        val fft = Fft(size)
        val win = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
        val edges = ArrayList<Int>()
        var f = 40.0
        while (f < 16_000) {
            edges += (f * size / rate).toInt().coerceAtLeast(1)
            f *= 2.0.pow(1.0 / 3)
        }
        val bin = FloatArray(size)
        val power = FloatArray(size / 2 + 1)
        val total = pcm.size / 2
        fun bands(get: (Long) -> Double, centre: Long): DoubleArray {
            for (i in 0 until size) bin[i] = (get(centre - size / 2 + i) * win[i]).toFloat()
            fft.realForwardSquaredMagnitude(bin, power)
            return DoubleArray(edges.size - 1) { b -> (edges[b] until edges[b + 1]).sumOf { power[it].toDouble() } }
        }
        val ds = ArrayList<Double>()
        var low = 0.0
        var mid = 0.0
        var high = 0.0
        var nl = 0
        var nm = 0
        var nh = 0
        var o = rate.toLong()
        while (o < length - rate) {
            val i = map.tailAt(o.toDouble()).roundToLong()
            var sum = 0.0
            var count = 0
            for (c in 0 until 2) {
                val eo = bands({ t -> if (t in 0 until length) out[(t * 2 + c).toInt()] else 0.0 }, o)
                val ei = bands({ t -> if (t in 0 until total) pcm[(t * 2 + c).toInt()].toDouble() else 0.0 }, i)
                val floor = ei.max() * 1e-6
                for (b in ei.indices) {
                    if (ei[b] < floor && eo[b] < floor) continue
                    val d = 10 * log10((eo[b] + floor) / (ei[b] + floor))
                    sum += d * d
                    count++
                    val hz = edges[b] * rate.toDouble() / size
                    when {
                        hz < 250 -> { low += abs(d); nl++ }
                        hz < 4000 -> { mid += abs(d); nm++ }
                        else -> { high += abs(d); nh++ }
                    }
                }
            }
            if (count > 0) ds += sqrt(sum / count)
            o += 480
        }
        ds.sort()
        return doubleArrayOf(ds.average(), ds[(ds.size * 95) / 100], low / max(1, nl), mid / max(1, nm), high / max(1, nh))
    }

    private fun landing(frames: Taken, map: FrameMap, b: Double, length: Long): Double? {
        val want = map.outAt(b)
        if (want < 0 || want >= length - Wsola.FRAME) return null
        var best: Double? = null
        for (j in frames.positions.indices) {
            val k = frames.kFirst + j
            val start = frames.startOf(k)
            val p = frames.positions[j]
            if (b >= p && b < p + Wsola.FRAME && abs(start + (b - p) - want) < 0.05 * rate) {
                val e = (start + (b - p) - want) / (rate / 1000.0)
                if (best == null || abs(e) < abs(best)) best = e
            }
        }
        return best
    }

    private fun read(file: File, from: Int, frames: Int): ShortArray {
        val bytes = ByteArray(frames * 4)
        java.io.RandomAccessFile(file, "r").use {
            it.seek(from * 4L)
            it.readFully(bytes)
        }
        val out = ShortArray(frames * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun writeWav(file: File, samples: ShortArray, rate: Int) {
        val n = samples.size / 2
        val data = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort(s)
        DataOutputStream(FileOutputStream(file)).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(n * 4)
            out.write(header.array())
            out.write(data.array())
        }
    }
}
