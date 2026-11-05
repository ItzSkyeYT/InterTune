/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.Biquad
import com.dd3boh.outertune.mix.FilterKind
import com.dd3boh.outertune.mix.TimeMap
import com.dd3boh.outertune.mix.Wsola
import com.dd3boh.outertune.mix.edit.Bands
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneState
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.laneAt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The trial's own player for a change on bands, until the engine's lane mixer exists: both songs
 * cut into lows, mids and highs (Linkwitz-Riley at 120 Hz and 6 kHz, the mids being the song less
 * the other two, so the three always add back to the song), each lane through its gain, its
 * filter and its echo as [laneAt] says, the outgoing song stretched to the incoming one's tempo by
 * the same WSOLA the phone has, eased in over the beats before bar 1. The shared headroom is
 * worked out from the true peaks of the sum, then a soft limit.
 *
 * Output frames count from the outgoing song's region: before anything changes, output frame n is
 * that region's frame n, so what is heard there can be held against the song to the bit.
 */
class BandRender(
    private val rate: Int,
    /** The outgoing song's end, interleaved stereo, from [aFromMs] of its own clock. */
    private val a: ShortArray,
    private val aFromMs: Double,
    private val aGuide: FloatArray,
    private val aFactor: Int,
    /** The incoming song from its first frame, interleaved stereo. */
    private val b: ShortArray,
) {
    class Rendered(
        val mix: FloatArray,
        val heard: ShortArray,
        /** Output frame of [mix]'s first frame. */
        val from: Long,
        val changeFrom: Int,
        val changeTo: Int,
        val bStartFrame: Int,
        val o0Frame: Int,
        val headroomDb: Double,
        val limited: Int,
        val lockA: FloatArray?,
        val lockB: FloatArray?,
        val lockFrom: Int,
        val lockBeats: DoubleArray,
        val exactBefore: Boolean,
        val exactAfter: Boolean,
        /** Each song as it would sound on these frames without the change, at full scale 1.0. */
        val plainA: FloatArray,
        val plainB: FloatArray,
    ) {
        fun proof(rate: Int): Proof = Proof(rate, mix, changeFrom, changeTo, lockA, lockB, lockFrom, lockBeats, plainA, plainB)
    }

    private val aFrames = a.size / 2
    private val bFrames = b.size / 2
    private fun msToFrames(ms: Double) = ms * rate / 1000.0

    /** [spec] played with [preSeconds] before anything changes and [postSeconds] after it is over. */
    fun render(spec: TransitionSpec, beatLocked: Boolean, preSeconds: Double = 20.0, postSeconds: Double = 20.0): Rendered? {
        val barB = msToFrames(spec.b.barMs)
        val beatB = barB / spec.b.beatsPerBar
        val aAt = msToFrames(spec.a.atMs - aFromMs)
        val matched = spec.beatMatched
        val speed = if (matched) spec.tempoRatio() else 1.0
        val rampOut = if (matched && abs(speed - 1) > 1e-9) AutoPlanner.rampBeats(speed) * beatB else 0.0
        val rampStart = if (matched) aAt - rampOut * (1 + speed) / 2 else aAt
        val o0 = rampStart + rampOut
        val map = if (matched && abs(speed - 1) > 1e-9) TimeMap(rampStart, rampOut, speed) else TimeMap.IDENTITY
        val bStart = o0 - msToFrames(spec.b.atMs) + msToFrames(spec.bStartMs)
        if (bStart < 0 || rampStart < 0) return null

        val length = spec.lengthBars
        val firstU = minOf(spec.moves.values.minOf { it.u }, 0.0)
        // The echo rings past the change for up to two bars, as the review asked.
        val lastU = length + 2.0
        val changeStart = minOf(rampStart, o0 + firstU * barB, if (anyFromStart(spec)) bStart else Double.MAX_VALUE)
        val changeEnd = o0 + lastU * barB
        val from = max(0L, floor(changeStart - preSeconds * rate).toLong())
        val to = min(ceil(changeEnd + postSeconds * rate).toLong(), (bStart + bFrames).toLong())
        val aEnd = map.outAt(aFrames.toDouble())
        if (to <= from) return null
        val n = (to - from).toInt()

        // The outgoing song as heard, stretched where the map says, with its beats landed on time.
        val aHeard = FloatArray(2 * n)
        val stretchFrom = max(from, floor(rampStart).toLong())
        for (o in from until min(to, stretchFrom)) {
            if (o < aFrames) {
                aHeard[(2 * (o - from)).toInt()] = a[(2 * o).toInt()].toFloat()
                aHeard[(2 * (o - from) + 1).toInt()] = a[(2 * o + 1).toInt()].toFloat()
            }
        }
        if (stretchFrom < to) {
            // The outgoing song's beats on the line the change was planned on, through its anchor
            // at its tempo, for the stretch to land each one where the map puts it.
            val beatA = 60_000.0 / spec.a.bpm
            val anchors = (-64..(length * spec.a.beatsPerBar * 2).toInt() + 64)
                .map { msToFrames(spec.a.atMs + it * beatA - aFromMs) }
                .filter { it >= rampStart && it < aFrames }.toDoubleArray()
            val frames = Wsola(a, 2, rate, aGuide, aFactor, anchors).frames(map, stretchFrom, min(to, ceil(aEnd).toLong() + 1).coerceAtLeast(stretchFrom + 1))
            val f = DoubleArray(2)
            for (o in stretchFrom until min(to, ceil(aEnd).toLong())) {
                frames.frame(o, f)
                aHeard[(2 * (o - from)).toInt()] = f[0].toFloat()
                aHeard[(2 * (o - from) + 1).toInt()] = f[1].toFloat()
            }
        }
        val bHeard = FloatArray(2 * n)
        for (o in from until to) {
            val bf = (o - bStart.roundToLong())
            if (bf in 0 until bFrames) {
                bHeard[(2 * (o - from)).toInt()] = b[(2 * bf).toInt()].toFloat()
                bHeard[(2 * (o - from) + 1).toInt()] = b[(2 * bf + 1).toInt()].toFloat()
            }
        }

        val sum = DoubleArray(2 * n)
        for ((side, x) in listOf(Side.A to aHeard, Side.B to bHeard)) {
            val split = Splitter()
            val beatMs = if (side == Side.A && !matched) spec.a.barMs / spec.a.beatsPerBar else spec.b.barMs / spec.b.beatsPerBar
            val echo = Echo(msToFrames(Shapes.ECHO_BEATS * beatMs).roundToInt())
            val players = spec.lanes.lanes.map { LanePlayer(LaneId(side, it)) }
            val bands = DoubleArray(6)
            for (i in 0 until n) {
                val o = from + i
                val u = (o - o0) / barB
                split.step(x[2 * i].toDouble(), x[2 * i + 1].toDouble(), bands)
                var l = 0.0
                var r = 0.0
                var sendL = 0.0
                var sendR = 0.0
                for ((li, p) in players.withIndex()) {
                    if (i % BLOCK == 0) p.load(spec.laneAt(p.id, u))
                    val (yl, yr, send) = p.step(bands[2 * li], bands[2 * li + 1])
                    l += yl
                    r += yr
                    sendL += send * p.lastDryL
                    sendR += send * p.lastDryR
                }
                val (el, er) = echo.step(sendL, sendR)
                sum[2 * i] += l + el
                sum[2 * i + 1] += r + er
            }
        }

        // The shared headroom from the true peaks of the sum, never moving faster than a fade.
        val full = 32768.0
        val step = 512
        val knots = (n + step - 1) / step
        // Only the change is held down and limited: outside it each song plays as it is, peaks and all.
        val changeFrom = (changeStart - from).toInt().coerceIn(0, n)
        val changeTo = (changeEnd - from).toInt().coerceIn(changeFrom, n)
        val needed = DoubleArray(knots) { k ->
            if ((k + 1) * step <= changeFrom || k * step >= changeTo) return@DoubleArray 1.0
            var peak = 0.0
            for (i in k * step until min(n, (k + 1) * step)) peak = max(peak, max(abs(sum[2 * i]), abs(sum[2 * i + 1])) / full)
            if (peak <= CEILING) 1.0 else CEILING / peak
        }
        val radius = max(1, (0.2 * rate / step).toInt())
        val lowest = DoubleArray(knots) { k -> (max(0, k - radius)..min(knots - 1, k + radius)).minOf { needed[it] } }
        val smooth = DoubleArray(knots) { k ->
            val h = radius / 2
            (k - h..k + h).sumOf { if (it in 0 until knots) lowest[it] else 1.0 } / (2 * h + 1)
        }
        val mix = FloatArray(2 * n)
        val heard = ShortArray(2 * n)
        var limited = 0
        var deepest = 1.0
        for (i in 0 until n) {
            val pos = i.toDouble() / step
            val k = pos.toInt().coerceAtMost(knots - 1)
            val g = if (k + 1 < knots) smooth[k] + (smooth[k + 1] - smooth[k]) * (pos - k) else smooth[k]
            deepest = min(deepest, g)
            for (c in 0 until 2) {
                val v = sum[2 * i + c] * g / full
                mix[2 * i + c] = v.toFloat()
                val inside = i in changeFrom until changeTo
                val limitedV = if (inside) softLimit(v) else v
                if (inside && abs(v) > CEILING) limited++
                heard[2 * i + c] = (limitedV * full).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }

        // Held against each song outside the change and the shared headroom's lead and tail around it.
        val lead = (0.5 * rate).toInt()
        var exactBefore = true
        for (i in 0 until max(0, changeFrom - lead)) {
            val o = from + i
            if (o >= aFrames) break
            if (heard[2 * i] != a[(2 * o).toInt()] || heard[2 * i + 1] != a[(2 * o + 1).toInt()]) {
                exactBefore = false
                break
            }
        }
        var exactAfter = true
        for (i in min(n, changeTo + lead) until n) {
            val bf = from + i - bStart.roundToLong()
            if (bf !in 0 until bFrames) continue
            if (abs(heard[2 * i] - b[(2 * bf).toInt()]) > 1 || abs(heard[2 * i + 1] - b[(2 * bf + 1).toInt()]) > 1) {
                exactAfter = false
                break
            }
        }

        // Where both songs' beats are heard together, for the lock check: the change itself.
        var lockA: FloatArray? = null
        var lockB: FloatArray? = null
        var lockFrom = 0
        var lockBeats = DoubleArray(0)
        if (beatLocked) {
            val lf = (o0 - from).toInt().coerceIn(0, n)
            val lt = (o0 + length * barB - from).toInt().coerceIn(lf, n)
            lockFrom = lf
            lockA = FloatArray(lt - lf) { ((aHeard[2 * (lf + it)] + aHeard[2 * (lf + it) + 1]) / 2 / full).toFloat() }
            lockB = FloatArray(lt - lf) { ((bHeard[2 * (lf + it)] + bHeard[2 * (lf + it) + 1]) / 2 / full).toFloat() }
            val beatMsB = 60_000.0 / spec.b.bpm
            lockBeats = (0..(length * spec.b.beatsPerBar).toInt()).map { msToFrames(spec.b.atMs + it * beatMsB) + bStart - from - lf }
                .filter { it >= 0 && it < lt - lf }.toDoubleArray()
        }
        return Rendered(
            mix, heard, from, changeFrom, changeTo, (bStart - from).roundToInt(), (o0 - from).roundToInt(),
            20 * kotlin.math.log10(deepest), limited, lockA, lockB, lockFrom, lockBeats, exactBefore, exactAfter,
            aHeard.also { x -> for (i in x.indices) x[i] = (x[i] / full).toFloat() }, bHeard.also { x -> for (i in x.indices) x[i] = (x[i] / full).toFloat() },
        )
    }

    private fun anyFromStart(spec: TransitionSpec): Boolean = spec.moves.any { (id, m) -> id.side == Side.B && m.how == How.FROM_START }

    /** Lows, mids and highs of a stereo signal, a frame at a time; the mids are what the other two leave, so the three add back to it exactly. */
    private inner class Splitter {
        private val lp = Array(2) { Biquad(2).apply { set(FilterKind.LOW_PASS, Bands.LOWS_BELOW_HZ, Q, rate) } }
        private val hp = Array(2) { Biquad(2).apply { set(FilterKind.HIGH_PASS, Bands.HIGHS_ABOVE_HZ, Q, rate) } }

        /** Into [out]: lows left and right, mids, highs, in the order of LaneSet.BANDS. */
        fun step(left: Double, right: Double, out: DoubleArray) {
            for (c in 0 until 2) {
                val v = if (c == 0) left else right
                val l = lp[1].process(lp[0].process(v, c), c)
                val h = hp[1].process(hp[0].process(v, c), c)
                out[c] = l
                out[2 + c] = v - l - h
                out[4 + c] = h
            }
        }
    }

    /** One lane: its gain eased so a cut takes a few ms, its filters crossfaded in and out so nothing clicks. */
    private inner class LanePlayer(val id: LaneId) {
        var gain = if (id.side == Side.A) 1.0 else 0.0
        private var target = gain
        private var send = 0.0
        private val lowPass = Sweep(FilterKind.LOW_PASS)
        private val highPass = Sweep(FilterKind.HIGH_PASS)
        var lastDryL = 0.0
        var lastDryR = 0.0
        private val ease = 1.0 / (Shapes.CUT_MS / 1000.0 * rate / 3)

        fun load(s: LaneState) {
            target = s.gain
            send = s.echoSend
            lowPass.load(s.lowPassHz)
            highPass.load(s.highPassHz)
        }

        fun step(l: Double, r: Double): Triple<Double, Double, Double> {
            gain += (target - gain) * ease.coerceAtMost(1.0)
            if (abs(target - gain) < 1e-6) gain = target
            var yl = lowPass.process(l, 0)
            var yr = lowPass.process(r, 1)
            yl = highPass.process(yl, 0)
            yr = highPass.process(yr, 1)
            lowPass.tick()
            highPass.tick()
            lastDryL = yl
            lastDryR = yr
            return Triple(yl * gain, yr * gain, send)
        }
    }

    /** A 2nd-order filter whose cutoff follows the lane's state, block by block, faded in and out over 20 ms. */
    private inner class Sweep(val kind: FilterKind) {
        private val q = Biquad(2)
        private var wet = 0.0
        private var wanted = false
        private val fade = 1.0 / (0.02 * rate)

        fun load(hz: Double?) {
            if (hz == null) {
                wanted = false
                return
            }
            if (!wanted && wet <= 0.0) q.reset()
            wanted = true
            q.set(kind, hz.coerceIn(10.0, 0.45 * rate), Q, rate)
        }

        fun process(x: Double, c: Int): Double {
            if (!wanted && wet <= 0.0) return x
            val y = q.process(x, c)
            return x * (1 - wet) + y * wet
        }

        fun tick() {
            wet = if (wanted) min(1.0, wet + fade) else max(0.0, wet - fade)
        }
    }

    /** The echo: a delay of [delay] frames, half fed back through a band-pass of 300 Hz to 3.5 kHz. */
    private inner class Echo(private val delay: Int) {
        private val line = Array(2) { DoubleArray(max(1, delay)) }
        private var at = 0
        private val hp = Biquad(2).apply { set(FilterKind.HIGH_PASS, Shapes.ECHO_LOW_HZ, Q, rate) }
        private val lp = Biquad(2).apply { set(FilterKind.LOW_PASS, Shapes.ECHO_HIGH_HZ, Q, rate) }

        fun step(inL: Double, inR: Double): Pair<Double, Double> {
            val outL = line[0][at]
            val outR = line[1][at]
            val fl = lp.process(hp.process(outL, 0), 0)
            val fr = lp.process(hp.process(outR, 1), 1)
            line[0][at] = bandpassed(inL, 0) + Shapes.ECHO_FEEDBACK * fl
            line[1][at] = bandpassed(inR, 1) + Shapes.ECHO_FEEDBACK * fr
            at = (at + 1) % line[0].size
            return outL to outR
        }

        private val inHp = Biquad(2).apply { set(FilterKind.HIGH_PASS, Shapes.ECHO_LOW_HZ, Q, rate) }
        private val inLp = Biquad(2).apply { set(FilterKind.LOW_PASS, Shapes.ECHO_HIGH_HZ, Q, rate) }
        private fun bandpassed(x: Double, c: Int): Double = inLp.process(inHp.process(x, c), c)
    }

    companion object {
        private const val Q = 0.7071067811865476
        private const val BLOCK = 32
        private const val CEILING = 0.95

        /** Bends anything past [CEILING] into the room left under full scale, continuous in value and slope. */
        fun softLimit(v: Double): Double {
            val a = abs(v)
            if (a <= CEILING) return v
            val room = 1.0 - CEILING
            val over = a - CEILING
            val bent = CEILING + room * (over / (over + room))
            return if (v < 0) -bent else bent
        }
    }
}
