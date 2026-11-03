/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneState
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.laneAt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Never two voices at once: his rule of 3 Nov 2026 ("what's kinda annoying is when two vocals
 * overlap... feel free to loop bars ... cutting vocals or muting them early is good as well").
 *
 * On bands a voice lives in the mids, so a song's voice is heard at a moment of a change where it
 * sings there ([SongEnd.voice]) and its mids lane lets enough of 300 Hz to 3 kHz through.
 * [together] adds up where both are heard. A change that would lay two voices over each other is
 * mended as a DJ would mend it:
 *
 * - [cutEarly]: the outgoing song's mids (its voice) and highs leave on the bar line before the
 *   incoming song's first word;
 * - a loop ([loopFor]): bars of the incoming song with no voice in them are looped in front of it, so
 *   its own first line comes later, once the outgoing one has finished (then cut early if not);
 * - [inLate]: the incoming song's mids come in only once the outgoing voice has gone, which loses
 *   its first words, so it is the last resort.
 *
 * A change that still has two voices together for more than [MAX_TOGETHER_S] is not made.
 */
object VoiceRule {

    /** A moment sings where its guess is at least this likely. */
    const val SINGS = 0.5

    /** A bar may be looped for having no voice where its guess stays under this all through. */
    const val FREE = 0.3

    /** A voice whose mids let through no more than this (-20 dB) is taken as gone. */
    const val HEARD_GAIN = 0.1

    /** How long two voices may meet in a change, in seconds: about the beat a mid swap takes. */
    const val MAX_TOGETHER_S = 0.6

    /** An echoed voice is heard for this many beats after its dry sound stops: three repeats, each 6 dB down. */
    const val ECHO_TAIL_BEATS = 3 * Shapes.ECHO_BEATS

    /** The steps a change is read on, in bars. */
    const val STEP_BARS = 1.0 / 32

    private val MIDS_A = LaneId(Side.A, Lane.MIDS)
    private val HIGHS_A = LaneId(Side.A, Lane.HIGHS)
    private val MIDS_B = LaneId(Side.B, Lane.MIDS)

    /** How much of a voice a lane in state [s] lets through, 0 to 1: its gain, less what a filter takes of 300 Hz to 3 kHz. */
    fun voiceThrough(s: LaneState): Double {
        var g = s.gain
        s.lowPassHz?.let { hz -> g *= ((ln(hz) - ln(400.0)) / (ln(3_000.0) - ln(400.0))).coerceIn(0.0, 1.0) }
        s.highPassHz?.let { hz -> g *= (1.0 - (ln(hz) - ln(1_000.0)) / (ln(4_000.0) - ln(1_000.0))).coerceIn(0.0, 1.0) }
        return g
    }

    /** Where a change is read: from a bar before its first move to a bar after it ends, its echo inside that. */
    private fun span(spec: TransitionSpec): ClosedFloatingPointRange<Double> {
        val first = spec.moves.values.minOfOrNull { it.u } ?: 0.0
        return min(0.0, first) - 1.0..spec.lengthBars + 1.0
    }

    /** Whether A's voice is heard at bar position [u] of [spec]; null where A's voice is not known. */
    fun heardA(spec: TransitionSpec, a: SongEnd, u: Double): Boolean? {
        val p = a.voiceAt(spec.aHeardMsAt(u)) ?: return null
        if (p >= SINGS && voiceThrough(spec.laneAt(MIDS_A, u)) >= HEARD_GAIN) return true
        // A voice sent into the echo goes on ringing after its dry sound has stopped.
        val move = spec.moves[MIDS_A]
        if (move != null && move.how == How.ECHO && spec.curves[MIDS_A] == null && MIDS_A !in spec.muted) {
            val beat = spec.beat
            if (u >= move.end && u < move.end + ECHO_TAIL_BEATS * beat) {
                return (a.mostVoice(spec.aHeardMsAt(move.end - beat), spec.aHeardMsAt(move.end)) ?: 0.0) >= SINGS
            }
        }
        return false
    }

    /** Whether B's voice is heard at bar position [u] of [spec]; null where B's voice is not known. */
    fun heardB(spec: TransitionSpec, b: SongEnd, u: Double): Boolean? {
        val ms = spec.bMsAt(u)
        val p = b.voiceAt(ms) ?: return null
        // Before B's first frame nothing of it plays.
        if (ms < spec.bStartMs) return false
        return p >= SINGS && voiceThrough(spec.laneAt(MIDS_B, u)) >= HEARD_GAIN
    }

    /** Seconds of [spec] where both voices are heard, by the guesses; 0 where either voice is not known. */
    fun together(spec: TransitionSpec, a: SongEnd, b: SongEnd): Double {
        if (a.voice == null || b.voice == null) return 0.0
        val r = span(spec)
        var u = r.start
        var n = 0
        while (u < r.endInclusive) {
            if (heardA(spec, a, u) == true && heardB(spec, b, u) == true) n++
            u += STEP_BARS
        }
        return n * STEP_BARS * spec.b.barMs / 1000.0
    }

    /** The first bar position from [from] on where B's voice is heard in [spec], or null where it never is. */
    fun firstHeardB(spec: TransitionSpec, b: SongEnd, from: Double = span(spec).start): Double? {
        val r = span(spec)
        var u = from
        while (u < r.endInclusive) {
            if (heardB(spec, b, u) ?: return null) return u
            u += STEP_BARS
        }
        return null
    }

    /** The last bar position where A's voice is heard in [spec], or null where it never is. */
    fun lastHeardA(spec: TransitionSpec, a: SongEnd): Double? {
        val r = span(spec)
        var u = r.endInclusive
        while (u >= r.start) {
            if (heardA(spec, a, u) ?: return null) return u
            u -= STEP_BARS
        }
        return null
    }

    /** Whether A's voice is heard anywhere in the beat before [at]: a cut there takes a line in the middle. */
    fun aSingsBefore(spec: TransitionSpec, a: SongEnd, at: Double): Boolean {
        var u = at - spec.beat
        while (u < at) {
            if (heardA(spec, a, u) == true) return true
            u += STEP_BARS
        }
        return false
    }

    /**
     * [spec] with A's mids (its voice) and highs gone by [at]: they leave over the beat before it,
     * or keep their own move where that has them gone sooner.
     */
    fun cutAt(spec: TransitionSpec, at: Double): TransitionSpec {
        val beat = spec.beat
        val moves = spec.moves.toMutableMap()
        for (id in listOf(MIDS_A, HIGHS_A)) {
            val m = moves[id] ?: continue
            val gone = if (m.how == How.CUT || m.len <= 0.0) m.u else m.end
            if (m.how == How.ECHO || gone > at) moves[id] = Move(at - beat, beat, How.FADE)
        }
        return spec.copy(moves = moves)
    }

    /**
     * [spec] mended by taking A's voice out early: its mids and highs gone by [cutLine]. Null where
     * nothing needs it or it does not help.
     */
    fun cutEarly(spec: TransitionSpec, a: SongEnd, b: SongEnd): TransitionSpec? {
        if (together(spec, a, b) <= MAX_TOGETHER_S) return null
        val at = cutLine(spec, b) ?: return null
        return cutAt(spec, at).takeIf { together(it, a, b) <= MAX_TOGETHER_S }
    }

    /** Where A's voice has to be gone by: the bar line at or before B's first heard word, or that word's own beat line where it comes late in its bar. */
    fun cutLine(spec: TransitionSpec, b: SongEnd): Double? {
        val uB = firstHeardB(spec, b) ?: return null
        val bar = floor(uB + 1e-9)
        return if (uB - bar < 0.5) bar else floor(uB / spec.beat + 1e-9) * spec.beat
    }

    /**
     * [spec] mended by bringing B's mids in only once A's voice has gone, on the next beat line.
     * B's first words are lost: the last resort. Null where it does not help.
     */
    fun inLate(spec: TransitionSpec, a: SongEnd, b: SongEnd): TransitionSpec? {
        if (together(spec, a, b) <= MAX_TOGETHER_S) return null
        val uA = lastHeardA(spec, a) ?: return null
        val beat = spec.beat
        val at = ceil((uA + STEP_BARS) / beat - 1e-9) * beat
        val m = spec.moves[MIDS_B] ?: return null
        if (at >= spec.lengthBars) return null
        val moves = spec.moves.toMutableMap()
        moves[MIDS_B] = Move(max(m.u, at), max(beat, min(m.len, spec.lengthBars - at)), How.FADE)
        return spec.copy(moves = moves).takeIf { together(it, a, b) <= MAX_TOGETHER_S }
    }

    /** One way of keeping a change's two voices apart: the change mended, what it costs the music in Auto's score, and what it does. */
    class Mend(val spec: TransitionSpec, val cost: Double, val what: String)

    /** What a mend costs: the outgoing voice cut before its time (more where it is mid-line), a loop, and last the next song's first words. */
    const val CUT_COST = 0.15
    const val CUT_MIDLINE_COST = 0.1
    const val LOOP_COST = 0.05
    const val IN_LATE_COST = 0.25

    /**
     * Every way of keeping [spec]'s voices apart, the cheapest first: [spec] itself at no cost where
     * they never meet; else the outgoing voice out early ([cutEarly]), a loop of the next song's
     * bars with no voice in them taking [loopBars] bars of the change at most (0 for none; then
     * the outgoing voice out early as well where it is still singing), and the next song's mids in
     * late ([inLate]). Without [canCut] the outgoing voice is never taken out early: an echo out
     * whose echo is taken away is no echo out. Empty where nothing keeps them apart.
     */
    fun mends(spec: TransitionSpec, a: SongEnd, b: SongEnd, loopBars: Int, canCut: Boolean = true): List<Mend> {
        val together = together(spec, a, b)
        if (together <= MAX_TOGETHER_S) return listOf(Mend(spec, 0.0, ""))
        val out = ArrayList<Mend>()
        val s = "%.1f".format(together)
        if (canCut) cutEarly(spec, a, b)?.let { m ->
            val at = cutLine(spec, b) ?: 0.0
            out += Mend(m, cutCost(spec, a, at), "this song's voice out on bar ${barName(at)}, before the next one sings ($s s of two voices)")
        }
        if (loopBars > 0) bridge(spec, a, b, loopBars, s, canCut)?.let { out += it }
        inLate(spec, a, b)?.let { m ->
            val at = m.moves[MIDS_B]?.u ?: 0.0
            out += Mend(m, IN_LATE_COST + 0.3 * (at / spec.lengthBars.coerceAtLeast(1.0)).coerceIn(0.0, 1.0),
                "the next song's mids in on bar ${barName(at)}, once this one stops singing ($s s of two voices)")
        }
        return out.sortedBy { it.cost }
    }

    /** A cut of A's voice at [at] costs more the earlier it comes in the change, and more again where A is still singing just before it. */
    fun cutCost(spec: TransitionSpec, a: SongEnd, at: Double): Double {
        val length = spec.lengthBars.coerceAtLeast(1.0)
        val early = ((length - at) / length).coerceIn(0.0, 1.0)
        return CUT_COST * (0.5 + early) + if (aSingsBefore(spec, a, at)) CUT_MIDLINE_COST else 0.0
    }

    /**
     * The bridge: bars of B with no voice in them looped in front of it, so B's own first line
     * comes as late as A's last word, [maxBars] bars of the change at most; A's voice then out early
     * where it still sings when B's does. Not where B already has a loop or plays before bar 1 (a
     * pickup rising into it would land after the loop).
     */
    private fun bridge(spec: TransitionSpec, a: SongEnd, b: SongEnd, maxBars: Int, together: String, canCut: Boolean): Mend? {
        if (spec.introLoop != null || spec.introTwice > 0) return null
        if (spec.moves.filterKeys { it.side == Side.B }.values.any { it.u < 0.0 }) return null
        val uB = firstHeardB(spec, b) ?: return null
        val uA = lastHeardA(spec, a) ?: return null
        val need = (uA + spec.beat - uB).coerceAtLeast(spec.beat)
        val loop = loopFor(spec, b, min(need, maxBars.toDouble()), maxBars) ?: return null
        var looped = spec.copy(introLoop = loop)
        var cost = LOOP_COST * (1.0 + loop.bars * loop.times / 4.0)
        var what = "${loop.bars} of the next song's bars without a voice looped ${times(loop.times)} in front of it, so its first line comes after this one's ($together s of two voices)"
        if (together(looped, a, b) > MAX_TOGETHER_S) {
            if (!canCut) return null
            val at = cutLine(looped, b) ?: return null
            val cut = cutEarly(looped, a, b) ?: return null
            cost += 0.5 * cutCost(looped, a, at)
            what += ", and this song's voice out on bar ${barName(at)}"
            looped = cut
        }
        return Mend(looped, cost, what)
    }

    private fun times(n: Int): String = when (n) {
        1 -> "once"
        2 -> "twice"
        else -> "$n times"
    }

    /** Bar position [u] as the editor numbers bars: bar 1 starts at 0. */
    private fun barName(u: Double): String = if (kotlin.math.abs(u - kotlin.math.round(u)) < 1e-6) "${kotlin.math.round(u).toInt() + 1}" else "%.2f".format(u + 1)

    /**
     * A loop of B's own bars with no voice in them, to bridge the two singers: a run of 4, 2 or 1 of
     * B's bars from its bar 1 whose guess stays under [FREE] all through, played as often as it
     * takes to bring B's own bar 1 [needBars] later, [maxBars] bars of the change at most. Null where
     * B's first bar already sings, or its voice is not known.
     */
    fun loopFor(spec: TransitionSpec, b: SongEnd, needBars: Double, maxBars: Int): IntroLoop? {
        if (b.voice == null || needBars <= 0.0) return null
        val barMs = spec.b.barMs
        val free = ArrayList<Double>()
        var ms = spec.b.atMs
        // Each pass comes out of the bar after the looped ones, so that one has to be read too.
        while (ms + 2 * barMs <= b.toMs && free.size < 4) {
            if ((b.mostVoice(ms, ms + barMs) ?: 1.0) >= FREE) break
            free += ms
            ms += barMs
        }
        if (free.isEmpty()) return null
        for (bars in intArrayOf(4, 2, 1)) {
            if (bars > free.size) continue
            val times = ceil(needBars / bars - 1e-9).toInt().coerceAtLeast(1)
            if (times > IntroLoop.MAX_TIMES || bars * times > maxBars) continue
            return IntroLoop(bars, free[0], times)
        }
        return null
    }
}
