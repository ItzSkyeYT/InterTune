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
class PlanInput @JvmOverloads constructor(
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
    /**
     * Why no beat mix may go into the next song this time, for a reason its opening cannot show:
     * when it last came in under a beat mix, it did not line up with its opening.
     */
    val beatRefused: String? = null,
)

/**
 * Chooses how one song goes into the next, and works it out to the frame. Pure and deterministic:
 * the same input always gives the same plan, and any doubt gives today's gapless change.
 *
 * The ladder, first match wins: a guard that fails or an opening not read, gapless; both songs'
 * beats and bars trusted and their tempos within 6%, a beat-matched blend; trusted but too far
 * apart, a cut on a bar line; both songs' beats steady where they would meet but their bars not
 * trusted, a cut on a beat of the outgoing song; then, where at most one of them keeps a steady
 * beat there, the smooth blend with its filter sweep when both ends are loud and it was not used
 * for either of the last two transitions, and the plain blend otherwise. Two steady beats at
 * different speeds are never laid over each other for longer than a beat: they slide apart within
 * a bar, and two kicks that do not line up are a trainwreck, not a mix.
 */
object Planner {

    const val MIN_SONG_MS = 60_000L

    /** The overlap plus whatever of the outgoing song is never heard after it, at most. */
    const val BUDGET_S = 20.0

    const val BEAT_MAX_SPEED = 0.06
    const val BEAT_EIGHT_BAR_MAX_SPEED = 0.04
    const val DENSE_ONSETS = 6.0
    const val FIRST_DOWNBEAT_S = 2.0

    /**
     * A beat mix overlaps this many beats of the grids, the longer first: eight bars of four or
     * four of eight, then half that. Both are whole bars of either length, and so is half of each,
     * where the basses swap.
     */
    val OVERLAP_BEATS = intArrayOf(32, 16)

    /** The beats of the outgoing song over which its tempo moves to the incoming one's, before the overlap. */
    const val RAMP_BEATS = 4

    /**
     * How far the onsets heard on the beats a beat mix uses may sit from the line fitted through
     * only those beats, overall (root mean square) and at either end of them (the mean of the six
     * there): beyond that, the song's tempo still moves under the overlap.
     */
    const val LOCAL_JITTER_MS = 12.0
    const val LOCAL_ENDS_MS = 8.0

    const val BLEND_S = 6.0
    const val BLEND_MIN_S = 4.0
    const val BLEND_MAX_S = 10.0
    const val SMOOTH_S = 10.0
    const val SMOOTH_MIN_S = 8.0
    const val SMOOTH_LOUD_S = 15.0

    const val CUT_LATEST_S = 4.0
    const val CUT_WINDOW_S = 20.0

    /**
     * A cut on a beat leaves within this much of the outgoing song's true end. Without a phrase
     * line to leave on, a song is not cut short by more than a few bars: where its trusted beats
     * stop earlier, it plays out, and the next song comes in over its last beat.
     */
    const val BEAT_CUT_WINDOW_S = 8.0

    /** How far before the overlap the shared headroom may start to dip, and after it to recover. */
    const val HEADROOM_LEAD_S = 0.4

    /**
     * The incoming song's peaks are counted this much higher in the headroom: a little, where they
     * were measured through the filters the blend puts on it, and a fifth where they were not.
     */
    const val MEASURED_PEAK_MARGIN = 1.05
    const val UNMEASURED_PEAK_MARGIN = 1.2

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

        fun refuse(style: Style, why: String, what: String = style.name.lowercase()): MixPlan? {
            refusals += "not $what: $why"
            return null
        }
    }

    private fun clock(seconds: Double): String {
        val tenths = (seconds * 100).roundToLong()
        return "%d:%02d.%02d".format(tenths / 6000, tenths / 100 % 60, tenths % 100)
    }

    /**
     * Whether both songs keep a steady beat where an overlap of [length] frames would lay them
     * over each other: the outgoing one through the end of its music, the incoming one from its
     * first sound. A song with a steady pulse is taken to keep it there, a beat that stops being
     * regular enough to trust being a beat all the same, unless a quiet stretch covers the overlap
     * (an outro 6 dB under the song, an intro under what follows it) and its trusted beats do not
     * reach into it: a beatless intro or outro lets a blend through.
     */
    private fun beatsMeet(c: Context, length: Double): Boolean {
        val a = c.tail
        val b = c.head
        if (!a.gates.tempo || !b.gates.tempo) return false
        val aBeats = a.beatsConfident && a.beatFrame(a.grid!!.lastBeat) >= c.trueEndA - length
        val aRuns = aBeats || a.envelope.outroStart >= c.trueEndA - length
        val bBeats = b.beatsConfident && b.beatFrame(b.grid!!.firstBeat) <= c.firstSoundB + length
        val intro = b.envelope.introEnd
        val bRuns = bBeats || intro == null || intro <= c.firstSoundB + length
        return aRuns && bRuns
    }

    // The plain blend: equal power, ending at the outgoing song's true end, the incoming song's
    // first sound at its start. Six seconds, or the length of the outgoing song's own fade; a
    // single beat where both songs' beats would meet in it.
    private fun blend(c: Context): MixPlan? {
        val fade = c.tail.envelope.fadeFrames?.toDouble()
        var wanted = fade?.coerceIn(c.frames(BLEND_MIN_S), c.frames(BLEND_MAX_S)) ?: c.frames(BLEND_S)
        var shortest = c.frames(BLEND_MIN_S)
        var why = if (fade != null) "the ending's own fade of %.1f s".format(c.seconds(fade)) else "levels known, beats not trusted"
        if (beatsMeet(c, wanted)) {
            wanted = c.tail.bpm?.let { c.frames(60.0 / it) } ?: c.frames(0.5)
            shortest = wanted / 2
            why = "a beat long: both songs keep a steady beat, which would slide against the other's"
        }
        val (start, end) = fitEnding(c, wanted, shortest) ?: return c.refuse(Style.BLEND, "too little of this song is left to blend over")
        val gainA = Curve(1.0, listOf(Ramp(start, end, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, end, 0.0, 1.0, Shape.SINE_RISE)))
        return assemble(c, Style.BLEND, why, start, end, TimeMap.IDENTITY, gainA, gainB, emptyList(), emptyList(), bars = 0)
    }

    // The smooth blend: longer, the outgoing song held at full level for the first 40% and swept
    // down through a low-pass, the incoming one coming up through a high-pass that opens as it
    // rises, and the basses handed over as it opens. Only where both ends are loud, where a sweep
    // is heard as a sweep, never where both songs' beats would meet in it, and never twice in
    // three transitions, so it does not become the one trick every change plays.
    private fun smooth(c: Context): MixPlan? {
        if (Style.SMOOTH in c.input.lastStyles.take(2)) return c.refuse(Style.SMOOTH, "one of the last two transitions was smooth")
        if (beatsMeet(c, c.frames(SMOOTH_S))) return c.refuse(Style.SMOOTH, "both songs keep a steady beat, which would slide against the other's")
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
        val open = start + SMOOTH_OPEN * length
        val bStart = bStartFor(c, start - c.firstSoundB)
        val highPass = FilterMove(
            FilterKind.HIGH_PASS, 2, start = bStart, end = ceil(open).toLong() + bypass, fadeIn = 0, fadeOut = bypass,
            sweepFrom = start, sweepTo = open, fromHz = SMOOTH_FROM_HZ, toHz = SWEEP_FLOOR_HZ,
        )
        // The basses handed over: the outgoing song's bass goes as the incoming one's comes, while
        // the incoming high-pass falls from 120 to 40 Hz, so only one bassline plays at a time.
        val swapFrom = start + sweepPoint(BASS_SWAP_HZ) * length
        val swapTo = start + sweepPoint(SWAP_DONE_HZ) * length
        val bassOut = FilterMove(
            FilterKind.HIGH_PASS, 4, start = floor(swapFrom).toLong(), end = ceil(end).toLong() + 1, fadeIn = bypass, fadeOut = 0,
            sweepFrom = swapFrom, sweepTo = swapTo, fromHz = SWEEP_FLOOR_HZ, toHz = BASS_SWAP_HZ,
        )
        return assemble(c, Style.SMOOTH, "both ends loud", start, end, TimeMap.IDENTITY, gainA, gainB, listOf(lowPass, bassOut), listOf(highPass), bars = 0)
    }

    /** Where on the smooth blend's overlap, 0 to 1, the incoming high-pass passes [hz] on its way down. */
    private fun sweepPoint(hz: Double): Double =
        SMOOTH_OPEN * kotlin.math.ln(hz / SMOOTH_FROM_HZ) / kotlin.math.ln(SWEEP_FLOOR_HZ / SMOOTH_FROM_HZ)

    private const val SMOOTH_OPEN = 0.75
    private const val SMOOTH_FROM_HZ = 400.0
    private const val SWAP_DONE_HZ = 40.0

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

    /**
     * [r] with its beats on a line through only those from grid beat [fromK] to [toK], the ones a
     * beat mix lines up on, or null when too few of them are heard or the onsets still wander off
     * that line: a tempo that drifts over the region sits furthest off the line through all of it
     * at its ends, which is where a mix uses it.
     */
    private fun local(r: RegionAnalysis, fromK: Int, toK: Int): RegionAnalysis? {
        val line = r.grid?.lineBetween(fromK, toK) ?: return null
        if (line.jitterMs > LOCAL_JITTER_MS || line.endsMs > LOCAL_ENDS_MS) return null
        return r.withLine(line)
    }

    // The beat-matched blend. The overlap ends on a phrase boundary of the outgoing song, and the
    // incoming song's first bar lands on the downbeat that opens it. The outgoing song is brought to
    // the incoming one's tempo over the beats before, while it is still alone, so the song that
    // stays keeps an exact clock; the incoming song comes up over the first half with its bass cut,
    // the basses swap on the middle downbeat, and the outgoing song falls away over the second half.
    // Both songs' beats are put on lines fitted through only the beats the mix uses, so a tempo that
    // drifts a little over the region does not open a flam at the ends where they overlap.
    private fun beat(c: Context): MixPlan? {
        val a0 = c.tail
        val b0 = c.head
        c.input.beatRefused?.let { return c.refuse(Style.BEAT, it) }
        if (!a0.barsConfident) return c.refuse(Style.BEAT, "this song's bars are not trusted (${a0.gates.failures().joinToString(", ")})")
        if (!b0.barsConfident) return c.refuse(Style.BEAT, "the next song's bars are not trusted (${b0.gates.failures().joinToString(", ")})")
        val phrases = a0.phrases ?: return c.refuse(Style.BEAT, "no phrases found in this song's end")
        val barB = b0.downbeats!!.beatsPerBar
        val periodB0 = b0.periodFrames!!
        val speed0 = a0.periodFrames!! / periodB0
        if (abs(speed0 - 1) > BEAT_MAX_SPEED) return c.refuse(Style.BEAT, "tempos %.1f%% apart".format(abs(speed0 - 1) * 100))
        val firstDown = b0.firstDownbeat!!
        val downB0 = b0.beatFrame(firstDown)
        if (downB0 - c.firstSoundB > c.frames(FIRST_DOWNBEAT_S)) return c.refuse(Style.BEAT, "the next song's first downbeat comes %.1f s after its first sound".format(c.seconds(downB0 - c.firstSoundB)))

        var lastWhy = "no phrase boundary fits"
        for (beats in OVERLAP_BEATS) {
            val bars = beats / barB
            val longest = beats == OVERLAP_BEATS[0]
            if (longest && abs(speed0 - 1) > BEAT_EIGHT_BAR_MAX_SPEED) {
                lastWhy = "tempos too far apart for $bars bars"
                continue
            }
            if (longest) {
                val dense = max(
                    a0.onsetDensity((c.trueEndA - c.frames(BUDGET_S)).toLong(), c.trueEndA.toLong()),
                    b0.onsetDensity(downB0.toLong(), (downB0 + beats * periodB0).toLong()),
                )
                if (dense > DENSE_ONSETS) {
                    lastWhy = "dense, %.1f onsets a second".format(dense)
                    continue
                }
            }
            // The incoming song's grid and bars must hold through the whole overlap, and it must
            // be over before the incoming song's first big rise, so two drops never meet.
            val overlapEndB = downB0 + beats * periodB0
            if (firstDown + beats > b0.grid!!.lastBeat + 1) {
                lastWhy = "the next song's beats are not trusted for $bars bars"
                continue
            }
            if (overlapEndB > b0.frames - c.frames(0.5)) {
                lastWhy = "$bars bars run past the next song's analysed opening"
                continue
            }
            val introEnd = b0.envelope.introEnd?.toDouble()
            if (introEnd != null && overlapEndB > introEnd + periodB0) {
                lastWhy = "$bars bars run into the next song's first rise at %.1f s".format(c.seconds(introEnd))
                continue
            }
            val b = local(b0, firstDown, firstDown + beats)
            if (b == null) {
                lastWhy = "the next song's beats wander over its first $bars bars"
                continue
            }
            var wandered = false
            for (step in phrases.steps) {
                val choice = chooseLine(c, a0, phrases, step, beats, speed0, downB0) ?: continue
                val endBeat = phrases.barBeats[choice.line]
                val a = local(a0, endBeat - beats - RAMP_BEATS, endBeat)
                if (a == null) {
                    wandered = true
                    continue
                }
                val speed = a.periodFrames!! / b.periodFrames!!
                if (abs(speed - 1) > if (longest) BEAT_EIGHT_BAR_MAX_SPEED else BEAT_MAX_SPEED) continue
                val downB = b.beatFrame(firstDown)
                if (!fits(c, a, endBeat, beats, speed, downB)) continue
                return beatPlan(c, a, b, endBeat, beats, bars, speed, downB, step)
            }
            lastWhy = if (wandered) "this song's beats wander over the $bars bars it would leave on" else "no phrase boundary of this song fits $bars bars"
        }
        return c.refuse(Style.BEAT, lastWhy)
    }

    /**
     * The bar line on the [step] grid where an overlap of [beats] beats should end: the latest
     * that fits (see [fits]) and scores at least half the best.
     */
    private fun chooseLine(c: Context, a: RegionAnalysis, phrases: Phrases, step: Int, beats: Int, speed: Double, downB: Double): PhraseCandidate? {
        val valid = phrases.candidates.filter { cand -> cand.step == step && fits(c, a, phrases.barBeats[cand.line], beats, speed, downB) }
        if (valid.isEmpty()) return null
        val best = valid.maxOf { it.score }
        return valid.filter { it.score >= best * 0.5 || best <= 0 }.maxBy { phrases.barBeats[it.line] }
    }

    /**
     * Whether an overlap of [beats] beats ending on grid beat [beat] of [a] can be had: never after
     * the true end, inside the budget, its tempo ramp starting no earlier than allowed and on
     * trusted beats, and the incoming song, its first downbeat [downB] frames in, able to start in
     * time.
     */
    private fun fits(c: Context, a: RegionAnalysis, beat: Int, beats: Int, speed: Double, downB: Double): Boolean {
        val periodA = a.periodFrames!!
        val end = a.beatFrame(beat)
        val rampStart = a.beatFrame(beat - beats - RAMP_BEATS)
        val overlapStart = a.beatFrame(beat - beats)
        val startOut = rampStart + 2.0 * (overlapStart - rampStart) / (1.0 + speed)
        return end <= c.trueEndA + periodA * 0.25 &&
            c.trueEndA - overlapStart <= c.frames(BUDGET_S) &&
            rampStart >= c.earliest &&
            beat - beats - RAMP_BEATS >= a.grid!!.firstBeat &&
            startOut - downB - c.delay >= c.earliest
    }

    private fun beatPlan(
        c: Context, a: RegionAnalysis, b: RegionAnalysis, endBeat: Int, beats: Int, bars: Int, speed: Double,
        downB: Double, step: Int,
    ): MixPlan? {
        val rampStart = a.beatFrame(endBeat - beats - RAMP_BEATS)
        val overlapStartTail = a.beatFrame(endBeat - beats)
        // The ramp covers four beats of the tail; at a speed moving evenly from 1 to s it takes
        // 2 / (1 + s) of their length in output frames.
        val rampTail = overlapStartTail - rampStart
        val rampOut = 2.0 * rampTail / (1.0 + speed)
        val map = TimeMap(rampStart, rampOut, speed)
        val start = rampStart + rampOut
        val periodB = b.periodFrames!!
        val end = start + beats * periodB
        val bFrameZero = start - downB

        // The bass swap, over the beat before the middle downbeat: the incoming song's high-pass
        // falls from 120 to 20 Hz and opens, the outgoing song goes into one rising from 20 to 120.
        val middle = start + (beats / 2) * periodB
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
            bars, step, c.clockA(a.beatFrame(endBeat)), a.bpm, b.bpm,
        )
        // The outgoing song's beats from where its tempo starts to move to the end of the overlap,
        // on the line the mix lines up on, for the stretch to land each one where the map puts it.
        val anchors = DoubleArray(beats + RAMP_BEATS + 3) { a.beatFrame(endBeat - beats - RAMP_BEATS + it) }
        return assemble(
            c, Style.BEAT, why, start, end, map, gainA, gainB, listOf(highPassA), listOf(highPassB), bars,
            firstProcessed = floor(rampStart).toLong(), lockFrom = downB.toLong(), bFrameZero = bFrameZero,
            beats = beats, anchors = anchors,
        )
    }

    // The cut: the outgoing song plays to a bar line of its phrase grid in its last 20 s of music,
    // the latest at least 4 s before its true end, and the incoming song's first downbeat lands
    // exactly on it, over one beat of equal power. Too short to drift, so no tempo is matched. Where
    // the bars are not trusted at both ends, or no bar line fits, the same on a beat of the outgoing
    // song, as long as both songs' beats would meet in a blend.
    private fun cut(c: Context): MixPlan? = barCut(c) ?: beatCut(c)

    private const val BAR_CUT = "a cut on a bar line"
    private const val BEAT_CUT = "a cut on a beat"

    private fun barCut(c: Context): MixPlan? {
        val a = c.tail
        val b = c.head
        if (!a.barsConfident) return c.refuse(Style.CUT, "this song's bars are not trusted", BAR_CUT)
        if (!b.barsConfident) return c.refuse(Style.CUT, "the next song's bars are not trusted", BAR_CUT)
        val phrases = a.phrases ?: return c.refuse(Style.CUT, "no phrases found in this song's end", BAR_CUT)
        val periodA = a.periodFrames!!
        val firstDown = b.firstDownbeat!!
        val downB = b.beatFrame(firstDown)
        if (downB - c.firstSoundB > c.frames(FIRST_DOWNBEAT_S)) return c.refuse(Style.CUT, "the next song's first downbeat comes late", BAR_CUT)
        val latest = c.trueEndA - c.frames(CUT_LATEST_S)
        val earliestCut = c.trueEndA - c.frames(CUT_WINDOW_S)
        var line: Double? = null
        for (step in intArrayOf(phrases.steps.last(), 1)) {
            // The 4-bar phrase grid first (2 bars of eight), and only then any bar line counted back from the last strong one.
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
        val at = line ?: return c.refuse(Style.CUT, "no bar line between 20 and 4 s before this song's end", BAR_CUT)
        val start = at - periodA
        val gainA = Curve(1.0, listOf(Ramp(start, at, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, at, 0.0, 1.0, Shape.SINE_RISE)))
        val why = "on the bar line at %s, %.1f s before the end, A %.2f and B %.2f BPM".format(c.clockA(at), c.seconds(c.trueEndA - at), a.bpm, b.bpm)
        return assemble(c, Style.CUT, why, start, at, TimeMap.IDENTITY, gainA, gainB, emptyList(), emptyList(), bars = 0, bFrameZero = at - downB)
    }

    /**
     * The cut on a beat, for two songs whose beats are steady where they would meet but whose bars
     * are not trusted at both ends: the outgoing song leaves on the latest beat of its trusted
     * stretch in its last 8 s of music, and the incoming song comes in on it, with its first beat
     * when it has one within 2 s of its first sound, otherwise with its first sound.
     */
    private fun beatCut(c: Context): MixPlan? {
        val a = c.tail
        val b = c.head
        if (!a.beatsConfident) return c.refuse(Style.CUT, "this song's beats are not trusted", BEAT_CUT)
        if (!b.gates.tempo) return c.refuse(Style.CUT, "the next song keeps no steady beat", BEAT_CUT)
        if (!beatsMeet(c, c.frames(BLEND_S))) return c.refuse(Style.CUT, "the two songs' beats would not meet in a blend", BEAT_CUT)
        val grid = a.grid!!
        val periodA = a.periodFrames!!
        val entry = b.grid?.takeIf { b.beatsConfident }?.let { g ->
            var k = g.firstBeat
            while (k <= g.lastBeat && b.beatFrame(k) < c.firstSoundB - 0.03 * c.rate) k++
            b.beatFrame(k).takeIf { k <= g.lastBeat && it - c.firstSoundB <= c.frames(FIRST_DOWNBEAT_S) }
        } ?: c.firstSoundB
        var at: Double? = null
        var k = grid.lastBeat
        while (k >= grid.firstBeat) {
            val t = a.beatFrame(k)
            if (t < c.trueEndA - c.frames(BEAT_CUT_WINDOW_S)) break
            if (t <= c.trueEndA - 0.25 * periodA && t - periodA - c.frames(HEADROOM_LEAD_S) >= c.earliest && t - entry - c.delay >= c.earliest) {
                at = t
                break
            }
            k--
        }
        val cutAt = at ?: return c.refuse(Style.CUT, "no beat of this song in its last 8 s to leave on", BEAT_CUT)
        val start = cutAt - periodA
        val gainA = Curve(1.0, listOf(Ramp(start, cutAt, 1.0, 0.0, Shape.COSINE_FALL)))
        val gainB = Curve(0.0, listOf(Ramp(start, cutAt, 0.0, 1.0, Shape.SINE_RISE)))
        val why = "on a beat at %s, %.1f s before the end, bars not trusted, A %.2f and B %.2f BPM".format(
            c.clockA(cutAt), c.seconds(c.trueEndA - cutAt), a.bpm, b.bpm,
        )
        return assemble(c, Style.CUT, why, start, cutAt, TimeMap.IDENTITY, gainA, gainB, emptyList(), emptyList(), bars = 0, bFrameZero = cutAt - entry)
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
        beats: Int = 0,
        anchors: DoubleArray = DoubleArray(0),
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
        val (room, incoming) = headroom(c, map, gainA, gainB, filtersB, bStart.toDouble(), start, end)
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
            beats = beats,
            anchors = anchors,
        )
    }

    /**
     * The shared gain that keeps the sum of both songs under 0.95 of full scale through the
     * overlap, from the analysis: on each knot, the outgoing song's peak over 50 ms at its gain,
     * plus the incoming one's as its filters there let it through, a little higher since its live
     * frames are not the ones measured. The renderer works it out again from the outgoing song as
     * rendered, filters and all; the incoming song's load is kept for that.
     */
    private fun headroom(
        c: Context, map: TimeMap, gainA: Curve, gainB: Curve, filtersB: List<FilterMove>,
        bStart: Double, start: Double, end: Double,
    ): kotlin.Pair<Headroom, DoubleArray> {
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
                val pb = if (cb + half < 0) 0.0 else incomingPeak(c, filtersB, o, (cb - half).toLong().coerceAtLeast(0), (cb + half).toLong()) * c.input.attenuationB
                incoming[i] = pb * gainB.at(o)
                pa * gainA.at(o) + incoming[i]
            }
        }
        return Headroom.solve(from, loads, c.rate) to incoming
    }

    /**
     * The incoming song's peak between its frames [from] and [to], as it is heard at output frame
     * [o] through [filters], with its margin. A high-pass can raise a master's peaks well above its
     * own, so the peaks measured through the same filters are used: a 4th-order one held at 120 Hz
     * is the beat mix's before its bass comes in, exactly; a 2nd-order one sweeping down from
     * 400 Hz is the smooth blend's, bounded by the song through one at 400 Hz and as it is. Where
     * a filter moves through the bass or lets go, the song's own peaks come back as well.
     */
    private fun incomingPeak(c: Context, filters: List<FilterMove>, o: Double, from: Long, to: Long): Double {
        val head = c.head
        val raw = head.peakBetween(from, to).toDouble()
        val above120 = head.hopPeakAbove120
        val above400 = head.hopPeakAbove400
        if (above120 == null || above400 == null) return raw * UNMEASURED_PEAK_MARGIN
        var peak = -1.0
        for (f in filters) {
            val wet = f.wetAt(o.toLong())
            if (wet <= 0.0) continue
            val through = if (f.order == 4) head.peakBetween(from, to, above120).toDouble() else max(raw, head.peakBetween(from, to, above400).toDouble())
            val held = f.order == 4 && wet >= 1.0 && f.cutoffAt(o) >= BASS_SWAP_HZ - 1.0
            peak = max(peak, if (held) through else max(through, raw))
        }
        return (if (peak < 0) raw else peak) * MEASURED_PEAK_MARGIN
    }
}
