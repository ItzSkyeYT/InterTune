/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Everything one transition is planned from.
 *
 * [tail] is the outgoing song's held end, analysed exactly as it will be heard, and its frames are
 * the plan's clock. [head] is the incoming song's opening, or null when it was not read in time.
 */
class PlanInput(
    val tail: RegionAnalysis,
    val head: RegionAnalysis?,
    /** Output frames of the tail already handed on to be played: nothing before this can change. */
    val readMark: Long,
    /** The first output frame a change may start on, by default a second after [readMark]. */
    val earliestChange: Long = readMark + tail.sampleRate,
    /** The styles of the last two transitions, most recent first. */
    val lastStyles: List<Style> = emptyList(),
    val attenuationA: Double = 1.0,
    val attenuationB: Double = 1.0,
    /**
     * How much later the incoming song's frames reach the mixer than its own clock says: 21 ms
     * when the experimental ambience adds its delay line, otherwise none. The tail went through the
     * same chain, so only the incoming song's analysis needs moving.
     */
    val bDelayFrames: Long = 0,
    val aDurationMs: Long,
    val bDurationMs: Long,
    val aId: String? = null,
    val bId: String? = null,
    val aAlbum: String? = null,
    val bAlbum: String? = null,
    /** Where in the outgoing song the tail starts, only for the logs. */
    val tailStartMs: Long = 0,
    /** A style asked for by hand, from the developer row; still refused where it cannot be done. */
    val forced: Style? = null,
)

/**
 * Chooses how one song goes into the next, and works it out to the frame. Pure and deterministic:
 * the same input always gives the same plan, and any doubt gives today's gapless change.
 *
 * The ladder, first match wins: a guard that fails or an opening not read, gapless; both songs'
 * beats and bars trusted and their tempos within 6%, a beat-matched blend; trusted but too far
 * apart, a cut on a bar line; both ends loud and not on either of the last two transitions, the
 * smooth blend with its filter sweep; otherwise the plain blend.
 */
object Planner {

    const val MIN_SONG_MS = 60_000L

    /** The overlap plus whatever of the outgoing song is never heard after it, at most. */
    const val BUDGET_S = 20.0

    const val BEAT_MAX_SPEED = 0.06
    const val BEAT_EIGHT_BAR_MAX_SPEED = 0.04
    const val DENSE_ONSETS = 6.0
    const val FIRST_DOWNBEAT_S = 2.0

    const val BLEND_S = 6.0
    const val BLEND_MIN_S = 4.0
    const val BLEND_MAX_S = 10.0
    const val SMOOTH_S = 10.0
    const val SMOOTH_MIN_S = 8.0
    const val SMOOTH_LOUD_S = 15.0

    const val CUT_LATEST_S = 4.0
    const val CUT_WINDOW_S = 20.0

    /** How far before the overlap the shared headroom may start to dip, and after it to recover. */
    const val HEADROOM_LEAD_S = 0.4

    /** The crossfade between a song dry and filtered, either way. */
    const val BYPASS_S = 0.05

    const val LOCK_S = 2.0

    const val BASS_SWAP_HZ = 120.0
    const val SWEEP_FLOOR_HZ = 20.0

    fun plan(input: PlanInput): MixPlan {
        guard(input)?.let { return gapless(input, it) }
        val c = Context(input)
        input.forced?.let { forced ->
            if (forced == Style.GAPLESS) return gapless(input, "gapless asked for")
            return build(c, forced) ?: gapless(input, "${forced.name.lowercase()} asked for, but ${c.refusals.joinToString("; ")}")
        }
        for (style in listOf(Style.BEAT, Style.CUT, Style.SMOOTH, Style.BLEND)) {
            build(c, style)?.let { return it }
        }
        return gapless(input, c.refusals.joinToString("; "))
    }

    /** The plan for [style] alone, or null when it cannot be done; the trial renders every style a pair allows. */
    fun planStyle(input: PlanInput, style: Style): MixPlan? {
        if (guard(input) != null) return null
        if (style == Style.GAPLESS) return gapless(input, "gapless asked for")
        return build(Context(input), style)
    }

    /** Why there is no mix at all, or null when one may be tried. */
    fun guard(input: PlanInput): String? {
        val head = input.head ?: return "the next song's opening was not read in time"
        if (input.aDurationMs in 0 until MIN_SONG_MS) return "this song is under a minute"
        if (input.bDurationMs in 0 until MIN_SONG_MS) return "the next song is under a minute"
        if (input.aId != null && input.aId == input.bId) return "the same song again"
        val album = input.aAlbum?.takeIf { it.isNotBlank() }
        if (album != null && album == input.bAlbum) return "two songs of one album"
        if (head.sampleRate != input.tail.sampleRate || head.channels != input.tail.channels) return "the songs' formats differ"
        if (input.tail.envelope.trueEnd <= input.tail.envelope.firstSound) return "the end of this song is silent"
        if (head.envelope.trueEnd <= head.envelope.firstSound) return "the next song's opening is silent"
        return null
    }

    private fun gapless(input: PlanInput, reason: String) = MixPlan.gapless(
        input.tail.sampleRate, input.tail.channels, input.tail.frames, input.attenuationA, input.attenuationB, reason,
    )

    private fun build(c: Context, style: Style): MixPlan? = when (style) {
        Style.BEAT -> beat(c)
        Style.CUT -> cut(c)
        Style.SMOOTH -> smooth(c)
        Style.BLEND -> blend(c)
        Style.GAPLESS -> null
    }

    private class Context(val input: PlanInput) {
        val tail = input.tail
        val head = input.head!!
        val rate = tail.sampleRate
        val earliest = input.earliestChange.toDouble()
        val trueEndA = tail.envelope.trueEnd.toDouble()
        val firstSoundB = head.envelope.firstSound.toDouble()
        val delay = input.bDelayFrames.toDouble()
        val refusals = ArrayList<String>()

        fun frames(seconds: Double) = seconds * rate
        fun seconds(frames: Double) = frames / rate

        /** A tail frame as a time in the outgoing song, for the logs. */
        fun clockA(frame: Double): String = clock(input.tailStartMs / 1000.0 + frame / rate)

        fun refuse(style: Style, why: String): MixPlan? {
            refusals += "not ${style.name.lowercase()}: $why"
            return null
        }
    }

    private fun clock(seconds: Double): String {
        val tenths = (seconds * 100).roundToLong()
        return "%d:%02d.%02d".format(tenths / 6000, tenths / 100 % 60, tenths % 100)
    }

    // The plain blend: equal power, ending at the outgoing song's true end, the incoming song's
    // first sound at its start. Six seconds, or the length of the outgoing song's own fade.
    private fun blend(c: Context): MixPlan? {
        val fade = c.tail.envelope.fadeFrames?.toDouble()
        val wanted = fade?.coerceIn(c.frames(BLEND_MIN_S), c.frames(BLEND_MAX_S)) ?: c.frames(BLEND_S)
        val (start, end) = fitEnding(c, wanted, c.frames(BLEND_MIN_S)) ?: return c.refuse(Style.BLEND, "too little of this song is left to blend over")
        val gainA = Curve(1.0, listOf(Ramp(start, end, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, end, 0.0, 1.0, Shape.SINE_RISE)))
        val why = if (fade != null) "the ending's own fade of %.1f s".format(c.seconds(fade)) else "levels known, beats not trusted"
        return assemble(c, Style.BLEND, why, start, end, TimeMap.IDENTITY, gainA, gainB, emptyList(), emptyList(), bars = 0)
    }

    // The smooth blend: longer, the outgoing song held at full level for the first 40% and swept
    // down through a low-pass, the incoming one coming up through a high-pass that opens as it
    // rises. Only where both ends are loud, where a sweep is heard as a sweep, and never twice in
    // three transitions, so it does not become the one trick every change plays.
    private fun smooth(c: Context): MixPlan? {
        if (Style.SMOOTH in c.input.lastStyles.take(2)) return c.refuse(Style.SMOOTH, "one of the last two transitions was smooth")
        val envA = c.tail.envelope
        val envB = c.head.envelope
        if (!envA.staysLoud((c.trueEndA - c.frames(SMOOTH_LOUD_S)).toLong(), (c.trueEndA - c.frames(0.5)).toLong()))
            return c.refuse(Style.SMOOTH, "this song does not end loud")
        if (!envB.staysLoud(envB.firstSound, envB.firstSound + c.frames(SMOOTH_LOUD_S).toLong()))
            return c.refuse(Style.SMOOTH, "the next song does not start loud")
        val (start, end) = fitEnding(c, c.frames(SMOOTH_S), c.frames(SMOOTH_MIN_S)) ?: return c.refuse(Style.SMOOTH, "too little of this song is left")
        val length = end - start
        val hold = start + 0.4 * length
        val gainA = Curve(1.0, listOf(Ramp(hold, end, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, end, 0.0, 1.0, Shape.SINE_RISE)))
        val bypass = c.frames(BYPASS_S).toLong()
        val nyquistSafe = min(20_000.0, 0.45 * c.rate)
        val lowPass = FilterMove(
            FilterKind.LOW_PASS, 2, start = floor(start).toLong(), end = ceil(end).toLong() + 1, fadeIn = bypass, fadeOut = 0,
            sweepFrom = start, sweepTo = end, fromHz = nyquistSafe, toHz = 800.0,
        )
        val open = start + 0.75 * length
        val bStart = bStartFor(c, start - c.firstSoundB)
        val highPass = FilterMove(
            FilterKind.HIGH_PASS, 2, start = bStart, end = ceil(open).toLong() + bypass, fadeIn = 0, fadeOut = bypass,
            sweepFrom = start, sweepTo = open, fromHz = 400.0, toHz = SWEEP_FLOOR_HZ,
        )
        return assemble(c, Style.SMOOTH, "both ends loud", start, end, TimeMap.IDENTITY, gainA, gainB, listOf(lowPass), listOf(highPass), bars = 0)
    }

    /**
     * The latest overlap of [wanted] frames (at least [shortest]) that ends at the outgoing song's
     * true end and starts with the incoming song's first sound, given that nothing may change
     * before the earliest frame allowed: the outgoing song's headroom may dip a little before the
     * overlap, and the incoming song's leading silence plays before it.
     */
    private fun fitEnding(c: Context, wanted: Double, shortest: Double): Pair<Double, Double>? {
        val end = c.trueEndA
        val earliestStart = max(c.earliest + c.frames(HEADROOM_LEAD_S), c.earliest + c.firstSoundB + c.delay)
        val start = max(end - wanted, earliestStart)
        if (end - start < shortest) return null
        return start to end
    }

    private fun bStartFor(c: Context, bFrameZeroAt: Double): Long = (bFrameZeroAt - c.delay).roundToLong()

    // The beat-matched blend. The overlap ends on a phrase boundary of the outgoing song, and the
    // incoming song's first bar lands on the downbeat that opens it. The outgoing song is brought to
    // the incoming one's tempo over the bar before, while it is still alone, so the song that stays
    // keeps an exact clock; the incoming song comes up over the first half with its bass cut, the
    // basses swap on the middle downbeat, and the outgoing song falls away over the second half.
    private fun beat(c: Context): MixPlan? {
        val a = c.tail
        val b = c.head
        if (!a.barsConfident) return c.refuse(Style.BEAT, "this song's bars are not trusted (${a.gates.failures().joinToString(", ")})")
        if (!b.barsConfident) return c.refuse(Style.BEAT, "the next song's bars are not trusted (${b.gates.failures().joinToString(", ")})")
        val phrases = a.phrases ?: return c.refuse(Style.BEAT, "no phrases found in this song's end")
        val periodA = a.periodFrames!!
        val periodB = b.periodFrames!!
        val speed = periodA / periodB
        if (abs(speed - 1) > BEAT_MAX_SPEED) return c.refuse(Style.BEAT, "tempos %.1f%% apart".format(abs(speed - 1) * 100))
        val firstDown = b.firstDownbeat!!
        val downB = b.beatFrame(firstDown)
        if (downB - c.firstSoundB > c.frames(FIRST_DOWNBEAT_S)) return c.refuse(Style.BEAT, "the next song's first downbeat comes %.1f s after its first sound".format(c.seconds(downB - c.firstSoundB)))

        var lastWhy = "no phrase boundary fits"
        for (bars in intArrayOf(8, 4)) {
            if (bars == 8 && abs(speed - 1) > BEAT_EIGHT_BAR_MAX_SPEED) {
                lastWhy = "tempos too far apart for 8 bars"
                continue
            }
            val beatsB = 4 * bars
            if (bars == 8) {
                val dense = max(
                    a.onsetDensity((c.trueEndA - c.frames(BUDGET_S)).toLong(), c.trueEndA.toLong()),
                    b.onsetDensity(downB.toLong(), (downB + beatsB * periodB).toLong()),
                )
                if (dense > DENSE_ONSETS) {
                    lastWhy = "dense, %.1f onsets a second".format(dense)
                    continue
                }
            }
            // The incoming song's grid and bars must hold through the whole overlap, and it must
            // be over before the incoming song's first big rise, so two drops never meet.
            val overlapEndB = downB + beatsB * periodB
            if (firstDown + beatsB > b.grid!!.lastBeat + 1) {
                lastWhy = "the next song's beats are not trusted for $bars bars"
                continue
            }
            if (overlapEndB > b.frames - c.frames(0.5)) {
                lastWhy = "$bars bars run past the next song's analysed opening"
                continue
            }
            val introEnd = b.envelope.introEnd?.toDouble()
            if (introEnd != null && overlapEndB > introEnd + periodB) {
                lastWhy = "$bars bars run into the next song's first rise at %.1f s".format(c.seconds(introEnd))
                continue
            }
            for (step in Phrases.STEPS) {
                val choice = chooseLine(c, phrases, step, bars, speed, downB) ?: continue
                return beatPlan(c, phrases, choice, bars, speed, periodB, downB, step)
            }
            lastWhy = "no phrase boundary of this song fits $bars bars"
        }
        return c.refuse(Style.BEAT, lastWhy)
    }

    /**
     * The bar line on the [step] grid where an overlap of [bars] bars should end: never after the
     * true end, inside the budget, its tempo ramp starting no earlier than allowed and on trusted
     * beats, and the incoming song able to start in time; among those, the latest that scores at
     * least half the best.
     */
    private fun chooseLine(c: Context, phrases: Phrases, step: Int, bars: Int, speed: Double, downB: Double): PhraseCandidate? {
        val a = c.tail
        val periodA = a.periodFrames!!
        val valid = phrases.candidates.filter { cand ->
            if (cand.step != step) return@filter false
            val beat = phrases.barBeats[cand.line]
            val end = a.beatFrame(beat)
            val rampStart = a.beatFrame(beat - 4 * (bars + 1))
            val overlapStart = a.beatFrame(beat - 4 * bars)
            val startOut = rampStart + 2.0 * (overlapStart - rampStart) / (1.0 + speed)
            end <= c.trueEndA + periodA * 0.25 &&
                c.trueEndA - overlapStart <= c.frames(BUDGET_S) &&
                rampStart >= c.earliest &&
                beat - 4 * (bars + 1) >= a.grid!!.firstBeat &&
                startOut - downB - c.delay >= c.earliest
        }
        if (valid.isEmpty()) return null
        val best = valid.maxOf { it.score }
        return valid.filter { it.score >= best * 0.5 || best <= 0 }.maxBy { phrases.barBeats[it.line] }
    }

    private fun beatPlan(
        c: Context, phrases: Phrases, choice: PhraseCandidate, bars: Int, speed: Double,
        periodB: Double, downB: Double, step: Int,
    ): MixPlan? {
        val a = c.tail
        val endBeat = phrases.barBeats[choice.line]
        val rampStart = a.beatFrame(endBeat - 4 * (bars + 1))
        val overlapStartTail = a.beatFrame(endBeat - 4 * bars)
        // The ramp covers one bar of the tail; at a speed moving evenly from 1 to s it takes
        // 2 / (1 + s) of that bar's length in output frames.
        val rampTail = overlapStartTail - rampStart
        val rampOut = 2.0 * rampTail / (1.0 + speed)
        val map = TimeMap(rampStart, rampOut, speed)
        val start = rampStart + rampOut
        val end = start + 4 * bars * periodB
        val bFrameZero = start - downB

        // The bass swap, over the beat before the middle downbeat: the incoming song's high-pass
        // falls from 120 to 20 Hz and opens, the outgoing song goes into one rising from 20 to 120.
        val middle = start + (bars / 2) * 4 * periodB
        val swapFrom = middle - periodB
        val bypass = c.frames(BYPASS_S).toLong()
        val bStart = bStartFor(c, bFrameZero)
        val highPassB = FilterMove(
            FilterKind.HIGH_PASS, 4, start = bStart, end = ceil(middle).toLong() + bypass, fadeIn = 0, fadeOut = bypass,
            sweepFrom = swapFrom, sweepTo = middle, fromHz = BASS_SWAP_HZ, toHz = SWEEP_FLOOR_HZ,
        )
        val highPassA = FilterMove(
            FilterKind.HIGH_PASS, 4, start = floor(swapFrom).toLong(), end = ceil(end).toLong() + 1, fadeIn = bypass, fadeOut = 0,
            sweepFrom = swapFrom, sweepTo = middle, fromHz = SWEEP_FLOOR_HZ, toHz = BASS_SWAP_HZ,
        )
        val half = (start + end) / 2
        val gainB = Curve(0.0, listOf(Ramp(start, half, 0.0, 1.0, Shape.SINE_RISE)))
        val gainA = Curve(1.0, listOf(Ramp(half, end, 1.0, 0.0, Shape.COSINE_FALL)))
        val why = "%d bars on the %d-bar grid, out at %s, A %.2f to B %.2f BPM".format(
            bars, step, c.clockA(a.beatFrame(endBeat)), a.bpm, c.head.bpm,
        )
        return assemble(
            c, Style.BEAT, why, start, end, map, gainA, gainB, listOf(highPassA), listOf(highPassB), bars,
            firstProcessed = floor(rampStart).toLong(), lockFrom = downB.toLong(), bFrameZero = bFrameZero,
        )
    }

    // The cut: the outgoing song plays to a bar line of its phrase grid in its last 20 s of music,
    // the latest at least 4 s before its true end, and the incoming song's first downbeat lands
    // exactly on it, over one beat of equal power. Too short to drift, so no tempo is matched.
    private fun cut(c: Context): MixPlan? {
        val a = c.tail
        val b = c.head
        if (!a.barsConfident) return c.refuse(Style.CUT, "this song's bars are not trusted")
        if (!b.barsConfident) return c.refuse(Style.CUT, "the next song's bars are not trusted")
        val phrases = a.phrases ?: return c.refuse(Style.CUT, "no phrases found in this song's end")
        val periodA = a.periodFrames!!
        val firstDown = b.firstDownbeat!!
        val downB = b.beatFrame(firstDown)
        if (downB - c.firstSoundB > c.frames(FIRST_DOWNBEAT_S)) return c.refuse(Style.CUT, "the next song's first downbeat comes late")
        val latest = c.trueEndA - c.frames(CUT_LATEST_S)
        val earliestCut = c.trueEndA - c.frames(CUT_WINDOW_S)
        var line: Double? = null
        for (step in intArrayOf(4, 1)) {
            // The 4-bar phrase grid first, and only then any bar line counted back from the last strong one.
            var j = phrases.lastStrong
            while (j >= 0) {
                val at = a.beatFrame(phrases.barBeats[j])
                val beatBefore = at - periodA
                if (at <= latest && at >= earliestCut && beatBefore - c.frames(HEADROOM_LEAD_S) >= c.earliest &&
                    at - downB - c.delay >= c.earliest
                ) {
                    line = at
                    break
                }
                j -= step
            }
            if (line != null) break
        }
        val at = line ?: return c.refuse(Style.CUT, "no bar line between 20 and 4 s before this song's end")
        val start = at - periodA
        val gainA = Curve(1.0, listOf(Ramp(start, at, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, at, 0.0, 1.0, Shape.SINE_RISE)))
        val why = "on the bar line at %s, %.1f s before the end, A %.2f and B %.2f BPM".format(c.clockA(at), c.seconds(c.trueEndA - at), a.bpm, b.bpm)
        return assemble(c, Style.CUT, why, start, at, TimeMap.IDENTITY, gainA, gainB, emptyList(), emptyList(), bars = 0, bFrameZero = at - downB)
    }

    /** The parts every style shares: when the incoming song starts, the headroom, the edges of the shaping. */
    private fun assemble(
        c: Context,
        style: Style,
        why: String,
        start: Double,
        end: Double,
        map: TimeMap,
        gainA: Curve,
        gainB: Curve,
        filtersA: List<FilterMove>,
        filtersB: List<FilterMove>,
        bars: Int,
        firstProcessed: Long? = null,
        lockFrom: Long = 0,
        bFrameZero: Double = start - c.firstSoundB,
    ): MixPlan? {
        val bStart = bStartFor(c, bFrameZero)
        if (bStart < c.earliest) return c.refuse(style, "the next song would have to start before the plan can be in place")
        if (bStart > c.tail.frames) return c.refuse(style, "the next song would start after the end of this one")
        val lead = c.frames(HEADROOM_LEAD_S)
        // The outgoing song's rendered part must reach back to the incoming song's first frame:
        // from there on the mixer takes the outgoing song from that part, so a next song with a
        // quiet first second, which starts before the blend does, would otherwise meet silence.
        val aFirst = minOf(firstProcessed ?: Long.MAX_VALUE, floor(start - lead).toLong(), bStart)
        if (aFirst < c.earliest) return c.refuse(style, "it would change something before the plan can be in place")
        val aGone = ceil(end).toLong()
        val (room, incoming) = headroom(c, map, gainA, gainB, bStart.toDouble(), start, end)
        val shapedUntil = maxOf(aGone + lead.toLong(), filtersB.maxOfOrNull { it.end } ?: 0L, floor(start - lead).toLong() + incoming.size.toLong() * Headroom.STEP)
        return MixPlan(
            style = style,
            reason = if (c.refusals.isEmpty()) why else why + "; " + c.refusals.joinToString("; "),
            sampleRate = c.rate,
            channels = c.tail.channels,
            tailFrames = c.tail.frames,
            bStart = bStart,
            aMap = map,
            aFirstProcessed = aFirst,
            aGone = aGone,
            overlapStart = start,
            overlapEnd = end,
            gainA = gainA,
            gainB = gainB,
            filtersA = filtersA,
            filtersB = filtersB,
            headroom = room,
            incomingLoad = incoming,
            attenuationA = c.input.attenuationA,
            attenuationB = c.input.attenuationB,
            shapedUntil = shapedUntil,
            lockFrom = if (style == Style.BEAT) lockFrom else 0,
            lockTo = if (style == Style.BEAT) lockFrom + c.frames(LOCK_S).toLong() else 0,
            speed = map.speed,
            bars = bars,
        )
    }

    /**
     * The shared gain that keeps the sum of both songs under 0.95 of full scale through the
     * overlap, from the analysis: on each knot, the outgoing song's peak over 50 ms at its gain,
     * plus the incoming one's, taken 20% higher because its live frames are not the ones
     * measured. The renderer works it out again from the outgoing song as rendered, filters and
     * all; the incoming song's load is kept for that.
     */
    private fun headroom(c: Context, map: TimeMap, gainA: Curve, gainB: Curve, bStart: Double, start: Double, end: Double): kotlin.Pair<Headroom, DoubleArray> {
        val step = Headroom.STEP
        val lead = c.frames(HEADROOM_LEAD_S)
        val from = floor(start - lead).toLong()
        val to = ceil(end + lead).toLong()
        val n = ((to - from) / step + 1).toInt()
        val half = c.frames(0.025)
        val incoming = DoubleArray(n)
        val loads = DoubleArray(n) { i ->
            val o = from + i.toDouble() * step
            if (o < start || o > end) 0.0 else {
                val ta = map.tailAt(o)
                val pa = c.tail.peakBetween((ta - half).toLong(), (ta + half).toLong()) * c.input.attenuationA
                val cb = o - bStart - c.delay
                val pb = if (cb + half < 0) 0.0 else c.head.peakBetween((cb - half).toLong().coerceAtLeast(0), (cb + half).toLong()) * c.input.attenuationB
                incoming[i] = 1.2 * pb * gainB.at(o)
                pa * gainA.at(o) + incoming[i]
            }
        }
        return Headroom.solve(from, loads, c.rate) to incoming
    }
}
