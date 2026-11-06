/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bar features the reels' way reads ([ReelsSong]), from a whole song's PCM laid on its grid.
 *
 * A port of the PC prototype's analysis (experiments/features/automix/party-2010s/tools/analyze.py,
 * with the phrase tools' feat.py and sections.py it calls) and of its pulse check (pulse.py):
 * - each bar's level and its level under 150 Hz, from 512-point spectra of an 11,025 Hz mono copy
 *   (40 log-mel bands from 30 Hz to 5 kHz), averaged as power over each beat, then over each bar;
 * - section lines and peaks, from novelty in timbre (MFCC), in harmony (the chroma of each of a
 *   bar's beats, in order) and from steps in level and bass, counted every 8 bars from each strong
 *   change;
 * - the DJ bands of the 44.1 kHz mix: under 200 Hz, 200 Hz to 2.5 kHz, over 2.5 kHz;
 * - from a voice part, where there is one: its level against the song's loudest singing, the share
 *   of the bar sung, and how much the bar repeats the bars around it (a looped hook repeats, sung
 *   lines do not);
 * - the pulse check ([pulse]): per bar, the onset strength on the grid's beats over the 12 beats
 *   around it, at every shift from -60 to +60 ms, so a grid is trusted only where it holds.
 *
 * Input is 44.1 kHz float PCM, as the prototype read it. Where it differs from the prototype: the
 * spectra are in double precision (NumPy's were single), and the 11,025 Hz copy is taken from the
 * 44.1 kHz mix by a 129-tap low-pass, where ffmpeg's resampler made it from the source file (the
 * two agree to about -44 dB); it is rounded to 16 bits as ffmpeg's was.
 */
object ReelsAnalysis {

    const val RATE = 44_100
    const val LOW_RATE = 11_025

    /** One bar's features: the ones the decision reads, and the ones the prototype logs beside them. */
    data class Bar(
        val t: Double,
        val level: Double,
        val low150: Double,
        /** The phrase evidence at the line that opens this bar. */
        val ev: Double,
        val lowB: Double,
        val midB: Double,
        val highB: Double,
        /** The voice part's 90th percentile level in the bar, against the song's loudest singing, dB; NaN without a voice part. */
        val vdb: Double,
        /** The share of the bar sung: within 20 dB of the song's loudest singing. */
        val sung: Double,
        /** How much the bar's voice repeats one of the two bars either side (correlation of its mel frames). */
        val like: Double,
        val voice: BarVoice,
    ) {
        fun forDecision(): ReelsBar = ReelsBar(t, level, low150, voice)
    }

    class Result(
        val duration: Double,
        val bars: List<Bar>,
        val lines: List<Int>,
        val peaks: List<Int>,
        /** The last bar within 30 dB of the song's loud bars. */
        val lastSoundBar: Int,
        val hasVoice: Boolean,
    )

    /** The bars of [stereo] (interleaved, 44.1 kHz) on [grid], with the voice part [vocals] (mono, 44.1 kHz, as long) where given. */
    fun analyse(stereo: FloatArray, grid: ReelsGrid, vocals: FloatArray? = null): Result {
        val frames = stereo.size / 2
        val mono = FloatArray(frames) { (stereo[2 * it] + stereo[2 * it + 1]) / 2f }
        val dur = frames.toDouble() / RATE
        val per = 60.0 / grid.bpm
        val nBeats = ((dur - grid.t0) / per).toInt() + 1
        val beatT = DoubleArray(nBeats) { grid.t0 + it * per }
        val down = (0 until nBeats).filter { it % 4 == grid.phase }.toIntArray()
        val barT = DoubleArray(down.size) { beatT[down[it]] }
        val nb = barT.size

        val bars = barFeatures(lowRateCopy(mono), beatT, down, dur)
        val ev = evidence(bars)
        val lines = rephasedLines(ev, nb, 8, 0.5)
        val peaks = peaks(ev, 0.3, 2, 1, nb)
        val dj = djBands(mono, barT, min(dur, barT[nb - 1] + 4 * per))
        val voice = vocals?.let { voicePart(it, barT, per) }

        val loud = Py.percentile(bars.level, 80.0)
        val lastSound = (0 until nb).last { bars.level[it] > loud - 30 }
        val out = (0 until nb).map { j ->
            Bar(
                t = barT[j], level = bars.level[j], low150 = bars.low[j], ev = ev[j],
                lowB = dj[0][j], midB = dj[1][j], highB = dj[2][j],
                vdb = voice?.vdb?.get(j) ?: Double.NaN, sung = voice?.sung?.get(j) ?: Double.NaN,
                like = voice?.like?.get(j) ?: Double.NaN, voice = voice?.label?.get(j) ?: BarVoice.NONE,
            )
        }
        return Result(dur, out, lines, peaks, lastSound, voice != null)
    }

    /** The song as the decision reads it, from its analysis and its pulse check. */
    fun song(id: String, grid: ReelsGrid, result: Result, pulse: PulseCheck, lufs: Double?): ReelsSong = ReelsSong(
        id = id, grid = grid, duration = result.duration, bars = result.bars.map { it.forDecision() },
        lines = result.lines, peaks = result.peaks, pulse = pulse, lufs = lufs,
    )

    // ---- the 11,025 Hz copy

    private const val TAPS = 129
    private const val KAISER_BETA = 9.0

    /** A low-pass at 0.97 of 5,512.5 Hz (windowed sinc, Kaiser window), every fourth sample kept, rounded to 16 bits. */
    internal fun lowRateCopy(mono: FloatArray): FloatArray {
        val c = 0.97 * (LOW_RATE / 2.0) / (RATE / 2.0)
        val half = (TAPS - 1) / 2
        val h = DoubleArray(TAPS) { i ->
            val m = (i - half).toDouble()
            val x = c * m
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = 2.0 * i / (TAPS - 1) - 1
            c * sinc * besselI0(KAISER_BETA * sqrt(max(0.0, 1 - r * r))) / besselI0(KAISER_BETA)
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        val n = mono.size / 4
        return FloatArray(n) { k ->
            val centre = 4 * k
            var acc = 0.0
            val from = max(0, half - centre)
            val to = min(TAPS, mono.size - centre + half)
            for (i in from until to) acc += h[i] * mono[centre + i - half]
            (Math.rint(acc * 32768.0).coerceIn(-32768.0, 32767.0) / 32768.0).toFloat()
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-17 * sum) {
            term *= (x / (2 * k)) * (x / (2 * k))
            sum += term
            k++
        }
        return sum
    }

    // ---- level, bass, timbre and harmony per bar (feat.py and sections.py)

    private const val BANDS = 40

    private class BarFeatures(
        val level: DoubleArray,
        val low: DoubleArray,
        val mfcc: Array<DoubleArray>,
        val beatChroma: Array<DoubleArray>,
    )

    private fun barFeatures(x: FloatArray, beatT: DoubleArray, down: IntArray, dur: Double): BarFeatures {
        // Spectrum A: 512-point frames 128 apart, 40 log-mel bands 30 Hz to 5 kHz, floored 80 dB under each frame's loudest
        val winA = 512
        val hopA = 128
        val nA = 1 + max(0, (x.size - winA) / hopA)
        val (melW, centres) = melFilters(winA, LOW_RATE.toDouble(), BANDS, 30.0, 5000.0)
        val lowBands = (0 until BANDS).filter { centres[it] < 150.0 }
        val hannA = DoubleArray(winA) { 0.5 - 0.5 * cos(2 * PI * it / winA) }
        val fftA = RealFft(winA)
        val buf = DoubleArray(winA)
        val pw = DoubleArray(winA / 2 + 1)
        val logMel = DoubleArray(nA * BANDS)
        val level = DoubleArray(nA)
        val low = DoubleArray(nA)
        val mel = DoubleArray(BANDS)
        for (t in 0 until nA) {
            for (i in 0 until winA) buf[i] = x[t * hopA + i] * hannA[i]
            fftA.power(buf, pw)
            var top = -Double.MAX_VALUE
            for (b in 0 until BANDS) {
                var e = 0.0
                val w = melW[b]
                for (k in w.indices) e += w[k] * pw[k]
                mel[b] = e
                val db = 10 * log10(max(e, 1e-10))
                logMel[t * BANDS + b] = db
                if (db > top) top = db
            }
            for (b in 0 until BANDS) logMel[t * BANDS + b] = max(logMel[t * BANDS + b], top - 80.0)
            level[t] = 10 * log10(max(pw.sum() / winA, 1e-12))
            var lm = 0.0
            for (b in lowBands) lm += mel[b]
            low[t] = 10 * log10(max(lm / lowBands.size, 1e-12))
        }
        val tA = DoubleArray(nA) { (it * hopA + winA / 2.0) / LOW_RATE }

        // Spectrum B: 2048-point frames 512 apart, a 12-bin chroma 80 Hz to 4 kHz on log1p(100 |X|)
        val winB = 2048
        val hopB = 512
        val nB = 1 + max(0, (x.size - winB) / hopB)
        val cm = chromaMatrix(winB, LOW_RATE.toDouble())
        val hannB = DoubleArray(winB) { 0.5 - 0.5 * cos(2 * PI * it / winB) }
        val fftB = RealFft(winB)
        val bufB = DoubleArray(winB)
        val pwB = DoubleArray(winB / 2 + 1)
        val chroma = Array(nB) { DoubleArray(12) }
        for (t in 0 until nB) {
            for (i in 0 until winB) bufB[i] = x[t * hopB + i] * hannB[i]
            fftB.power(bufB, pwB)
            for (k in pwB.indices) pwB[k] = ln1p(100 * sqrt(pwB[k]))
            for (pc in 0 until 12) {
                var s = 0.0
                val row = cm[pc]
                for (k in row.indices) if (row[k] != 0.0) s += pwB[k] * row[k]
                chroma[t][pc] = s
            }
        }
        val tB = DoubleArray(nB) { (it * hopB + winB / 2.0) / LOW_RATE }

        // Each beat: the mean over its frames (levels as power), the chroma normalised
        val nBeats = beatT.size
        val edges = DoubleArray(nBeats + 1) { if (it < nBeats) beatT[it] else dur }
        val iA = IntArray(nBeats + 1) { searchLeft(tA, edges[it]) }
        val iB = IntArray(nBeats + 1) { searchLeft(tB, edges[it]) }
        val beatMel = Array(nBeats) { DoubleArray(BANDS) }
        val beatLevel = DoubleArray(nBeats)
        val beatLow = DoubleArray(nBeats)
        val beatChromaN = Array(nBeats) { DoubleArray(12) }
        for (j in 0 until nBeats) {
            val (a, b) = span(iA[j], iA[j + 1], nA)
            for (band in 0 until BANDS) {
                var s = 0.0
                for (t in a until b) s += logMel[t * BANDS + band]
                beatMel[j][band] = s / (b - a)
            }
            beatLevel[j] = powerMean(level, a, b)
            beatLow[j] = powerMean(low, a, b)
            val (ca, cb) = span(iB[j], iB[j + 1], nB)
            val c = beatChromaN[j]
            for (t in ca until cb) for (pc in 0 until 12) c[pc] += chroma[t][pc]
            for (pc in 0 until 12) c[pc] /= (cb - ca)
            val norm = max(sqrt(c.sumOf { it * it }), 1e-9)
            for (pc in 0 until 12) c[pc] /= norm
        }

        // Each bar: from its downbeat to the next
        val nb = down.size
        val barEdges = IntArray(nb + 1) { if (it < nb) down[it] else nBeats }
        val level2 = DoubleArray(nb)
        val low2 = DoubleArray(nb)
        val mfcc = Array(nb) { DoubleArray(13) }
        val beatChroma = Array(nb) { DoubleArray(48) }
        for (j in 0 until nb) {
            val a = barEdges[j]
            val b = max(barEdges[j + 1], a + 1)
            val lm = DoubleArray(BANDS)
            for (beat in a until b) for (band in 0 until BANDS) lm[band] += beatMel[beat][band]
            for (band in 0 until BANDS) lm[band] /= (b - a)
            for (c in 1..13) {
                var s = 0.0
                for (i in 0 until BANDS) s += lm[i] * cos(PI / BANDS * (i + 0.5) * c)
                mfcc[j][c - 1] = s
            }
            level2[j] = powerMean(beatLevel, a, b)
            low2[j] = powerMean(beatLow, a, b)
            for (k in 0 until min(4, barEdges[j + 1] - a)) {
                for (pc in 0 until 12) beatChroma[j][k * 12 + pc] = beatChromaN[a + k][pc]
            }
        }
        return BarFeatures(level2, low2, mfcc, beatChroma)
    }

    /** NumPy's v[a:b].mean() with feat.per_beat's guards: at least one frame, inside the array. */
    private fun span(i0: Int, i1: Int, n: Int): Pair<Int, Int> {
        val a = min(i0, n - 1)
        val b = min(max(i1, i0 + 1), n)
        return if (b > a) Pair(a, b) else Pair(a, a + 1)
    }

    /** The mean of [db] from [a] to [b] taken as power, back in dB. */
    private fun powerMean(db: DoubleArray, a: Int, b: Int): Double {
        var s = 0.0
        for (t in a until b) s += 10.0.pow(db[t] / 10)
        return 10 * log10(max(s / (b - a), 1e-12))
    }

    /** feat.mel_filters: triangles between mel-spaced points over the bins of an [nFft]-point spectrum, unnormalised. */
    private fun melFilters(nFft: Int, rate: Double, bands: Int, lo: Double, hi: Double): Pair<Array<DoubleArray>, DoubleArray> {
        fun mel(f: Double) = 2595.0 * log10(1 + f / 700.0)
        fun hz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1)
        val pts = linspace(mel(lo), mel(hi), bands + 2).map { hz(it) }
        val freqs = DoubleArray(nFft / 2 + 1) { it * rate / nFft }
        val fb = Array(bands) { b ->
            val l = pts[b]
            val c = pts[b + 1]
            val r = pts[b + 2]
            DoubleArray(freqs.size) { k ->
                val up = (freqs[k] - l) / max(c - l, 1e-9)
                val dn = (r - freqs[k]) / max(r - c, 1e-9)
                max(0.0, min(up, dn))
            }
        }
        return Pair(fb, DoubleArray(bands) { pts[it + 1] })
    }

    /** feat.chroma_matrix: each bin from [lo] to [hi] Hz spread over the 12 pitch classes by a Gaussian of 0.35 semitone. */
    private fun chromaMatrix(nFft: Int, rate: Double, lo: Double = 80.0, hi: Double = 4000.0): Array<DoubleArray> {
        val m = Array(12) { DoubleArray(nFft / 2 + 1) }
        for (k in 0..nFft / 2) {
            val f = k * rate / nFft
            if (f < lo || f > hi) continue
            val p = 12 * log2(f / 440.0) + 9
            for (pc in 0 until 12) {
                val d = (p - pc + 6).mod(12.0) - 6
                m[pc][k] = exp(-0.5 * (d / 0.35) * (d / 0.35))
            }
        }
        return m
    }

    private fun linspace(a: Double, b: Double, n: Int): DoubleArray {
        val step = (b - a) / (n - 1)
        return DoubleArray(n) { if (it == n - 1) b else a + it * step }
    }

    /** np.searchsorted(sorted, v): the first index whose value is not under [v]. */
    private fun searchLeft(sorted: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ---- the phrase evidence and lines (sections.py)

    private fun evidence(f: BarFeatures): DoubleArray {
        val n = f.level.size
        val a = foote(ssm(f.mfcc), 4)
        val b = foote(ssm(f.beatChroma), 4)
        return DoubleArray(n + 1) { l ->
            val lv = min(1.0, abs(step(f.level, l)) / 6.0)
            val lo = min(1.0, abs(step(f.low, l)) / 6.0)
            a[l] + b[l] + 0.5 * (lv + lo)
        }
    }

    /** sections.ssm: the cosine similarity of every two rows, each centred on the column means. */
    private fun ssm(v: Array<DoubleArray>): Array<DoubleArray> {
        val n = v.size
        val d = if (n == 0) 0 else v[0].size
        val mean = DoubleArray(d) { c -> v.sumOf { it[c] } / n }
        val u = Array(n) { r ->
            val row = DoubleArray(d) { v[r][it] - mean[it] }
            val norm = max(sqrt(row.sumOf { it * it }), 1e-9)
            DoubleArray(d) { row[it] / norm }
        }
        return Array(n) { i -> DoubleArray(n) { j -> var s = 0.0; for (c in 0 until d) s += u[i][c] * u[j][c]; s } }
    }

    /** sections.foote: the checkerboard novelty at each bar line 0..n with a Gaussian taper, scaled to its top. */
    private fun foote(s: Array<DoubleArray>, k: Int): DoubleArray {
        val n = s.size
        val sigma2 = k * k / 4.0
        val out = DoubleArray(n + 1)
        val offs = (-k until k).toList()
        for (line in 0..n) {
            val ok = offs.filter { line + it in 0 until n }
            if (ok.size < 2) continue
            var num = 0.0
            var den = 0.0
            for (ar in ok) for (ac in ok) {
                val w = exp(-((ar + 0.5) * (ar + 0.5) + (ac + 0.5) * (ac + 0.5)) / (2 * sigma2))
                val sign = if ((ar < 0) == (ac < 0)) 1.0 else -1.0
                num += sign * w * s[line + ar][line + ac]
                den += w
            }
            out[line] = max(0.0, num / den)
        }
        val top = out.max()
        if (top > 0) for (i in out.indices) out[i] /= top
        return out
    }

    /** sections.step: how far [values] move across bar line [line], the [width] bars after it against the [width] before. */
    private fun step(values: DoubleArray, line: Int, width: Int = 2): Double {
        val n = values.size
        val b0 = max(0, line - width)
        val a1 = min(n, line + width)
        if (a1 <= line || line <= b0) return 0.0
        var sa = 0.0
        for (i in line until a1) sa += values[i]
        var sb = 0.0
        for (i in b0 until line) sb += values[i]
        return sa / (a1 - line) - sb / (line - b0)
    }

    /** sections.peaks: local maxima over +-[width] lines, at least [rel] of the curve's top, between lines [lo] and [hi]. */
    internal fun peaks(curve: DoubleArray, rel: Double, width: Int, lo: Int, hi: Int): List<Int> {
        val n = curve.size
        var top = 0.0
        if (hi > lo) {
            top = -Double.MAX_VALUE
            for (j in lo until hi) top = max(top, curve[j])
        }
        val out = ArrayList<Int>()
        for (j in lo until hi) {
            var m = -Double.MAX_VALUE
            for (i in max(0, j - width) until min(n, j + width + 1)) m = max(m, curve[i])
            if (curve[j] >= m && curve[j] >= rel * top && curve[j] > 0) out += j
        }
        return out
    }

    /** sections.rephased_lines: from each strong change, a line every [unit] bars until 2 bars before the next. */
    internal fun rephasedLines(evidence: DoubleArray, nBars: Int, unit: Int, rel: Double): List<Int> {
        val strong = listOf(0) + peaks(evidence, rel, 2, 1, nBars)
        val lines = HashSet<Int>(strong)
        for ((i, a) in strong.withIndex()) {
            val b = if (i + 1 < strong.size) strong[i + 1] else nBars
            var k = a + unit
            while (k <= b - 2) {
                lines += k
                k += unit
            }
        }
        lines -= 0
        return lines.sorted()
    }

    // ---- the DJ bands of the mix

    /** Per bar, the mean power under 200 Hz, 200 Hz to 2.5 kHz and over 2.5 kHz of 4096-point frames 1024 apart, dB. */
    private fun djBands(mono: FloatArray, barT: DoubleArray, lastEdge: Double): Array<DoubleArray> {
        val win = 4096
        val hop = 1024
        val nf = 1 + max(0, (mono.size - win) / hop)
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val fft = RealFft(win)
        val buf = DoubleArray(win)
        val pw = DoubleArray(win / 2 + 1)
        val df = win * (1.0 / RATE)
        val sums = Array(3) { DoubleArray(nf) }
        for (t in 0 until nf) {
            for (i in 0 until win) buf[i] = mono[t * hop + i] * hann[i]
            fft.power(buf, pw)
            for (k in pw.indices) {
                val f = k / df
                val band = if (f < 200) 0 else if (f < 2500) 1 else 2
                sums[band][t] += pw[k]
            }
        }
        val tt = DoubleArray(nf) { (it * hop + win / 2.0) / RATE }
        val nb = barT.size
        val fi = IntArray(nb + 1) { searchLeft(tt, if (it < nb) barT[it] else lastEdge) }
        return Array(3) { band ->
            DoubleArray(nb) { j ->
                val a = fi[j]
                val b = max(fi[j + 1], fi[j] + 1)
                if (b <= nf) {
                    var s = 0.0
                    for (t in a until b) s += sums[band][t]
                    10 * log10(s / (b - a) + 1e-12)
                } else {
                    -120.0
                }
            }
        }
    }

    // ---- the voice part

    private class Voice(val vdb: DoubleArray, val sung: DoubleArray, val like: DoubleArray, val label: Array<BarVoice>)

    private fun voicePart(v: FloatArray, barT: DoubleArray, per: Double): Voice {
        val win = 2048
        val hop = 512
        val bands = 48
        val nf = 1 + max(0, (v.size - win) / hop)
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val fft = RealFft(win)
        val buf = DoubleArray(win)
        val pw = DoubleArray(win / 2 + 1)
        val freqs = DoubleArray(win / 2 + 1) { it / (win * (1.0 / RATE)) }
        // analyze.mel_fb: 48 triangles 150 Hz to 6 kHz on np.fft.rfftfreq's bins
        fun mel(h: Double) = 2595 * log10(1 + h / 700.0)
        val m = linspace(mel(150.0), mel(6000.0), bands + 2)
        val hz = DoubleArray(m.size) { 700 * (10.0.pow(m[it] / 2595) - 1) }
        val fb = Array(bands) { b ->
            DoubleArray(freqs.size) { k ->
                max(0.0, min((freqs[k] - hz[b]) / (hz[b + 1] - hz[b]), (hz[b + 2] - freqs[k]) / (hz[b + 2] - hz[b + 1])))
            }
        }
        val inBand = BooleanArray(freqs.size) { freqs[it] > 150 && freqs[it] < 6000 }
        val mm = FloatArray(nf * bands)
        val lv = DoubleArray(nf)
        for (t in 0 until nf) {
            for (i in 0 until win) buf[i] = v[t * hop + i] * hann[i]
            fft.power(buf, pw)
            for (b in 0 until bands) {
                var e = 0.0
                val row = fb[b]
                for (k in row.indices) if (row[k] != 0.0) e += row[k] * pw[k]
                mm[t * bands + b] = (10 * log10(e + 1e-10)).toFloat()
            }
            var s = 0.0
            for (k in pw.indices) if (inBand[k]) s += pw[k]
            lv[t] = 10 * log10(s + 1e-12)
        }
        val top = Py.percentile(lv, 99.0)
        val fps = RATE.toDouble() / hop
        val n = Py.round(4 * per * fps)
        val nb = barT.size
        val vdb = DoubleArray(nb)
        val sung = DoubleArray(nb)
        val like = DoubleArray(nb)
        val label = Array(nb) { BarVoice.NONE }
        for (j in 0 until nb) {
            val i0 = max(0, Py.round((barT[j] - 1024.0 / RATE) * fps))
            val i1 = min(nf, i0 + n)
            val len = max(0, i1 - i0)
            if (len < n / 2) {
                vdb[j] = -60.0
                continue
            }
            val seg = lv.copyOfRange(i0, i1)
            vdb[j] = Py.percentile(seg, 90.0) - top
            sung[j] = seg.count { it > top - 20 }.toDouble() / seg.size
            var best: Double? = null
            if (len >= n) {
                for (lag in intArrayOf(-2, -1, 1, 2)) {
                    val k0 = i0 + lag * n
                    if (k0 < 0 || k0 + n > nf) continue
                    val c = correlation(mm, i0 * bands, k0 * bands, n * bands)
                    best = if (best == null) c else max(best, c)
                }
            }
            like[j] = best ?: 0.0
            label[j] = if (vdb[j] > -20 && sung[j] > 0.25 && like[j] < 0.75) BarVoice.SUNG
            else if (vdb[j] > -25 && like[j] >= 0.75) BarVoice.RIFF
            else BarVoice.NONE
        }
        return Voice(vdb, sung, like, label)
    }

    /** The correlation of two blocks of [len] values of [x], each less its own mean. */
    private fun correlation(x: FloatArray, a: Int, b: Int, len: Int): Double {
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until len) {
            ma += x[a + i]
            mb += x[b + i]
        }
        ma /= len
        mb /= len
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until len) {
            val p = x[a + i] - ma
            val q = x[b + i] - mb
            sab += p * q
            saa += p * p
            sbb += q * q
        }
        return sab / (sqrt(saa) * sqrt(sbb) + 1e-9)
    }

    // ---- the pulse check (pulse.py)

    /** One bar of the pulse check, with the lag it found before the song's median was taken off. */
    data class PulseRow(val pulse: Double, val lagMs: Double?, val lagRelMs: Double?)

    class PulseResult(val medianLagMs: Double, val rows: List<PulseRow>) {
        fun check(barAgreement: Double?): PulseCheck = PulseCheck(barAgreement, rows.map { PulseBar(it.pulse, it.lagRelMs) })
    }

    private const val PULSE_HOP = 441

    /**
     * How the grid holds bar by bar on [stereo] (interleaved, 44.1 kHz): for each bar starting at
     * [barT], the onset strength (log spectral flux, 10 ms hops) read at the grid's beats over the
     * bar and its two neighbours, shifted by every lag from -60 to +60 ms in 2 ms steps.
     */
    fun pulse(stereo: FloatArray, barT: List<Double>, bpm: Double): PulseResult {
        val frames = stereo.size / 2
        val mono = FloatArray(frames) { (stereo[2 * it] + stereo[2 * it + 1]) / 2f }
        val env = onsetEnvelope(mono)
        val fps = RATE.toDouble() / PULSE_HOP
        val p = 60.0 / bpm
        // np.arange(-0.060, 0.0601, 0.002): the step taken as (start + step) - start
        val delta = (-0.060 + 0.002) - (-0.060)
        val lags = DoubleArray(61) { if (it == 1) -0.060 + 0.002 else -0.060 + it * delta }
        val end = frames.toDouble() / RATE - 0.1
        val rows = ArrayList<PulseRow>()
        for (t in barT) {
            val beats = (-4 until 8).map { t + it * p }.filter { it > 0.05 && it < end }
            val w0 = max(0, ((t - 4 * p) * fps).toInt())
            val w1 = min(env.size, ((t + 8 * p) * fps).toInt())
            if (beats.size < 6 || w1 - w0 < 50) {
                rows += PulseRow(0.0, null, null)
                continue
            }
            var mean = 0.0
            for (i in w0 until w1) mean += env[i]
            mean /= (w1 - w0)
            var sq = 0.0
            for (i in w0 until w1) sq += (env[i] - mean) * (env[i] - mean)
            val std = sqrt(sq / (w1 - w0))
            var bestJ = 0
            var bestS = -Double.MAX_VALUE
            for ((j, lag) in lags.withIndex()) {
                var s = 0.0
                for (b in beats) s += env[Math.rint((b + lag) * fps).toInt().coerceIn(0, env.size - 1)]
                s /= beats.size
                if (s > bestS) {
                    bestS = s
                    bestJ = j
                }
            }
            val z = (bestS - mean) / (std + 1e-9)
            rows += PulseRow(Py.round(z, 2), Py.round(lags[bestJ] * 1000, 1), null)
        }
        val pulsed = rows.filter { it.lagMs != null && it.pulse >= 1.0 }.map { it.lagMs!! }
        val med = if (pulsed.isEmpty()) 0.0 else Py.median(pulsed.toDoubleArray())
        return PulseResult(med, rows.map { r -> r.copy(lagRelMs = r.lagMs?.let { Py.round(it - med, 1) }) })
    }

    /** pulse.onset_env: the rise of log1p(100 |X|) over the frame before, summed over bins, less its 21-frame mean. */
    private fun onsetEnvelope(x: FloatArray): DoubleArray {
        val n = 2048
        val k = 1 + (x.size - n) / PULSE_HOP
        val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val fft = RealFft(n)
        val buf = DoubleArray(n)
        val pw = DoubleArray(n / 2 + 1)
        var prev: DoubleArray? = null
        val out = DoubleArray(k)
        for (t in 0 until k) {
            for (i in 0 until n) buf[i] = x[t * PULSE_HOP + i] * hann[i]
            fft.power(buf, pw)
            val s = DoubleArray(pw.size) { ln1p(100 * sqrt(pw[it])) }
            val pv = prev
            if (pv != null) {
                var sum = 0.0
                for (i in s.indices) {
                    val d = s[i] - pv[i]
                    if (d > 0) sum += d
                }
                out[t] = sum
            }
            prev = s
        }
        val w = 1.0 / 21
        return DoubleArray(k) { i ->
            var s = 0.0
            for (j in max(0, i - 10)..min(k - 1, i + 10)) s += out[j] * w
            out[i] - s
        }
    }
}

/** A radix-2 FFT in double precision, for the power spectra of real frames. */
internal class RealFft(private val n: Int) {
    init {
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    /** |X[k]|^2 for k in 0..n/2 of the real frame [input]. */
    fun power(input: DoubleArray, out: DoubleArray) {
        for (i in 0 until n) re[Integer.reverse(i) ushr (32 - levels)] = input[i]
        java.util.Arrays.fill(im, 0.0)
        var half = 1
        while (half < n) {
            val step = n / (half * 2)
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val p = j + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tre = re[p] * c + im[p] * s
                    val tim = -re[p] * s + im[p] * c
                    re[p] = re[j] - tre
                    im[p] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
        for (k in 0..n / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}
