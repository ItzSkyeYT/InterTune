/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Why a change stays plain, as its pill says it ("Plain · short song"). */
enum class AutoPlain(val key: String, val words: String) {
    SAME_SONG("same_song", "same song"),
    SHORT_SONG("short_song", "short song"),
    RUN_TOGETHER("run_together", "they run together"),
    FORMATS("formats", "different formats"),
    NOT_READ("not_read", "not ready in time"),
    NOTHING_TO_MIX("nothing_to_mix", "nothing to mix over"),

    /** Nothing Auto knows fits these two: no beat to leave on, and two beats that would slide against each other in a blend. */
    NO_FIT("no_fit", "no change fits"),

    /** Every change Auto could make was rendered and refused by [ProofCheck]. */
    NO_CLEAN_MIX("no_clean_mix", "no clean mix found"),
}

/** The kind of change Auto made, for the trial's counts and the logs. */
enum class AutoKind {
    /** Both beats trusted and locked: the outgoing song stretched to the incoming one's tempo, their kicks laid on each other. */
    BEAT_LOCKED,

    /** The incoming song's beatless intro rises under the outgoing song, locked to its tempo, and its beat drops as the outgoing song leaves. */
    INTRO_MIX,

    /** The outgoing song leaves on a line (echo or cut) and the incoming song's first downbeat lands on it: nothing of two beats overlaps. */
    HAND_OVER,

    /** A blend timed in seconds, where at most one of the songs keeps a beat through it. */
    BLEND,

    PLAIN,
}

/**
 * Everything Auto plans one change from.
 *
 * [a] is the outgoing song's end, [b] the incoming song's opening, each measured on its own copy.
 * [fixA] and [fixB] are the listener's fixes to each song's beat, which Auto uses as the editor
 * does. [earliestMs] is where in the outgoing song a change may start at the earliest (the start of
 * what can be read of its end); [recent] the moves of the last changes, most recent first.
 */
class AutoInput(
    val a: SongEnd,
    val b: SongEnd,
    val aId: String,
    val bId: String,
    val aAlbum: String? = null,
    val bAlbum: String? = null,
    val fixA: BeatFix? = null,
    val fixB: BeatFix? = null,
    val earliestMs: Double = a.fromMs,
    val recent: List<Preset> = emptyList(),
)

/**
 * One change as Auto would make it: the move, its length, where bar 1 sits in each song, and why.
 * [spec] is what the engine plays and the editor opens.
 */
class AutoPick(
    val kind: AutoKind,
    /** The preset the moves come from (Fade, Rise, Blend, Echo out, Cut), for the pill: "Fade · 8 bars". */
    val move: Preset,
    val plain: AutoPlain?,
    val bars: Int,
    val seconds: Double,
    val beatMatch: Boolean,
    val a: Anchor,
    val b: Anchor,
    val moves: Map<LaneId, Move>,
    /** The outgoing song's bar 1 (the line it leaves on, for a hand-over; the line the drop lands on, for an intro mix) sits on a phrase line of 4 bars or longer. */
    val onPhrase: Boolean,
    /** The longest phrase that line opens: 16, 8, 4, 2, 1, or 0 where the bars are not known. */
    val phraseBars: Int,
    /** The incoming song comes in on its own first bar line, the start of its first phrase. */
    val bOnBar: Boolean,
    /** How far apart the two keys are on the Camelot wheel, where both are sure. */
    val keyDistance: Int?,
    /** The outgoing song's speed through the change, 1 when it keeps its own. */
    val speed: Double,
    /** The outgoing song's music never heard after the change, in ms. */
    val unheardMs: Double,
    val score: Double,
    val why: String,
) {
    val isPlain: Boolean get() = plain != null

    /** What the pill says: "Fade · 8 bars", "Blend · 6 s", "Plain · short song". */
    val pill: String
        get() = when {
            plain != null -> "Plain · ${plain.words}"
            bars > 0 -> "${name(move)} · $bars bars"
            else -> "${name(move)} · ${seconds.roundToInt()} s"
        }

    /**
     * The change as a spec on bands: Auto's own pick, so its preset is [Preset.AUTO], with [move]
     * as the preset it began from, which the pill names.
     */
    fun spec(): TransitionSpec = TransitionSpec(
        a = a,
        b = b,
        lanes = LaneSet.BANDS,
        bars = bars,
        seconds = if (bars > 0) 0.0 else seconds,
        beatMatch = beatMatch,
        preset = Preset.AUTO,
        from = move,
        plain = plain != null,
        moves = moves,
    )

    fun describe(): String = "$pill ($kind): $why"

    companion object {
        fun name(p: Preset): String = when (p) {
            Preset.FADE -> "Fade"
            Preset.RISE -> "Rise"
            Preset.BLEND -> "Blend"
            Preset.ECHO_OUT -> "Echo out"
            Preset.CUT -> "Cut"
            Preset.RIFF_RISE -> "Riff rise"
            else -> p.key
        }
    }
}

/**
 * Auto: the change a good DJ would make between two songs, on bands, from what was measured.
 *
 * In order of preference, each scored on its own merits and the best kept:
 *
 * 1. Beat locked. Both songs' beats and bars are trusted and their tempos are within reach (6% for
 *    up to 4 bars, 4% beyond, double or half time folded): the outgoing song is brought to the
 *    incoming song's tempo over the beats before bar 1, bar 1 lands on a phrase line of the
 *    outgoing song and on the incoming song's first downbeat, and the change runs whole phrases,
 *    8 bars by default, 16 when both ends are sparse, 4 when they are busy. Fade (hats first, the
 *    basses swapped on the middle bar line) unless the incoming song opens without a kick, which
 *    Rise brings up muffled instead.
 * 2. Intro mix. The incoming song opens with no beat to lock to, then drops: its opening rises
 *    under the outgoing song, at its tempo, so that the drop lands on the outgoing song's phrase
 *    line just as it leaves.
 * 3. Hand-over. The tempos are too far apart, or one song's bars are not trusted: the outgoing song
 *    leaves on its phrase line, its last line echoing away (or a hard cut where both sides are at
 *    full), and the incoming song's first downbeat lands on that line. Two beats at different speeds
 *    are never laid over each other.
 * 4. Blend, in seconds, where at most one of the two keeps a beat through it.
 * 5. Plain, only with its reason.
 *
 * The key is a tie breaker: where both keys are sure and clash on the Camelot wheel, a long overlap
 * of two melodies is scored down, so a shorter change, a muffled rise or a hand-over wins.
 *
 * With a [Proof] renderer, each candidate in turn is rendered and checked ([ProofCheck]): one that
 * clips, drifts off the beat or leaves silence is refused and the next is tried.
 *
 * Pure and deterministic: the same input always gives the same pick.
 */
object AutoPlanner {

    /** What a change may take from the outgoing song's end on bands: the overlap plus whatever is never heard after it. */
    const val BUDGET_MS = 30_000.0

    const val MIN_SONG_MS = 60_000.0

    /** The incoming song's first downbeat must come this soon after its first sound for its bar 1 to be its first bar. */
    const val FIRST_DOWNBEAT_MS = 2_000.0

    /** A pickup this short before the incoming song's first downbeat is heard as it is; a longer one rises into it. */
    const val PICKUP_MS = 400.0

    /** The lengths a beat-locked change may run, the longest first. */
    val LENGTHS = intArrayOf(16, 8, 4, 2)

    /** The seconds a blend may run without a trusted grid. */
    val BLEND_SECONDS = doubleArrayOf(4.0, 6.0, 8.0, 12.0)

    /** Onsets a second over which an end counts as busy, and under which as sparse. */
    const val BUSY = 6.0
    const val SPARSE = 2.5

    /**
     * A change counts its bars in four beats of the incoming song's grid, whatever the analysis
     * calls a bar there: a grid at twice a half-time song's beat has bars of eight, and so does a
     * song whose pattern turns every two bars; in both, four grid beats keep every bar line of the
     * change on one of the song's own, and the editor shows the grid's tempo with them.
     */
    const val CHANGE_BEATS = 4

    /** The ramp before bar 1 over which the outgoing song's tempo moves, in beats: twice as long above 3%. */
    fun rampBeats(speed: Double): Int = if (abs(speed - 1) > 0.03) 8 else 4

    /** How many candidates are rendered and checked at most before a change stays plain: each costs a render. */
    const val MAX_PROOFS = 10

    /**
     * The best candidate whose proof passes, trying them in order; [heard] is told each verdict,
     * for the trial. Without a renderer, the best candidate as it stands.
     */
    fun pick(
        input: AutoInput,
        proof: Proof.Renderer? = null,
        limits: ProofCheck.Limits = ProofCheck.Limits(),
        maxProofs: Int = MAX_PROOFS,
        heard: ((AutoPick, ProofCheck.Verdict) -> Unit)? = null,
    ): AutoPick {
        val ranked = candidates(input)
        if (proof == null) return ranked.first()
        val refused = ArrayList<String>()
        var proofs = 0
        for (c0 in ranked) {
            if (proofs >= maxProofs) break
            if (c0.isPlain) return if (refused.isEmpty()) c0 else plain(input, AutoPlain.NO_CLEAN_MIX, refused.joinToString("; "))
            var c = c0
            // A beat-locked change whose kicks sit apart by the same amount all through is lined
            // up again, as a DJ nudges the platter: the outgoing song's bar 1 moved by what was
            // heard, and heard again.
            for (nudge in 0..MAX_NUDGES) {
                val p = proof.render(c) ?: return c.withWhy(refused)
                proofs++
                val verdict = ProofCheck.check(p, limits)
                heard?.invoke(c, verdict)
                if (verdict.passes) return c.withWhy(refused)
                val lock = verdict.lock
                val onlyDrift = verdict.clipped <= limits.maxClipped && verdict.longestSilenceMs <= limits.maxSilenceMs && verdict.sagDb <= limits.maxSagDb
                if (nudge < MAX_NUDGES && c.kind == AutoKind.BEAT_LOCKED && onlyDrift && lock != null && lock.spreadMs <= NUDGE_SPREAD_MS && abs(lock.lateMs) in 2.0..40.0) {
                    refused += "${c.pill} beats %.1f ms %s, lined up again".format(abs(lock.lateMs), if (lock.lateMs > 0) "late" else "early")
                    c = c.nudged(lock.lateMs * c.speed)
                    continue
                }
                refused += "${c.pill} refused: ${verdict.why}"
                break
            }
        }
        return plain(input, AutoPlain.NO_CLEAN_MIX, refused.joinToString("; "))
    }

    /** How many times a change's kicks are lined up again before it is given up. */
    const val MAX_NUDGES = 2

    /**
     * Kicks are lined up again only where they sit apart by much the same amount on every beat:
     * within this of their median, which allows for the onsets' own 11.6 ms steps. The change is
     * heard again after, so a nudge that makes it worse is refused like any other.
     */
    const val NUDGE_SPREAD_MS = 15.0

    /** The same change with the outgoing song's bar 1 [ms] later in its own clock, so its music is heard that much earlier. */
    private fun AutoPick.nudged(ms: Double): AutoPick = AutoPick(
        kind, move, plain, bars, seconds, beatMatch, a.copy(atMs = a.atMs + ms), b, moves, onPhrase, phraseBars, bOnBar, keyDistance, speed, unheardMs, score,
        why + "; lined up by %+.1f ms".format(ms),
    )

    private fun AutoPick.withWhy(refused: List<String>): AutoPick =
        if (refused.isEmpty()) this else AutoPick(kind, move, plain, bars, seconds, beatMatch, a, b, moves, onPhrase, phraseBars, bOnBar, keyDistance, speed, unheardMs, score, why + "; " + refused.joinToString("; "))

    /** Every change Auto would consider for the pair, best first, ending with plain. */
    fun candidates(input: AutoInput): List<AutoPick> {
        guard(input)?.let { return listOf(plain(input, it.first, it.second)) }
        val c = Context(input)
        val out = ArrayList<AutoPick>()
        out += beatLocked(c)
        out += introMix(c)
        out += handOvers(c)
        out += blends(c)
        val sorted = out.sortedWith(compareByDescending<AutoPick> { it.score }.thenBy { it.bars }.thenBy { it.a.atMs })
        val why = if (c.notes.isEmpty()) "nothing fits" else c.notes.distinct().joinToString("; ")
        return sorted + plain(input, AutoPlain.NO_FIT, why)
    }

    /** Why the pair stays plain whatever the music, or null. */
    fun guard(input: AutoInput): Pair<AutoPlain, String>? {
        val a = input.a
        val b = input.b
        if (input.aId == input.bId) return AutoPlain.SAME_SONG to "the same song again"
        if (a.songMs < MIN_SONG_MS) return AutoPlain.SHORT_SONG to "this song is under a minute"
        if (b.songMs < MIN_SONG_MS) return AutoPlain.SHORT_SONG to "the next song is under a minute"
        if (a.sampleRate != b.sampleRate || a.channels != b.channels) return AutoPlain.FORMATS to "the songs' formats differ"
        if (a.trueEndMs <= a.firstSoundMs || b.trueEndMs <= b.firstSoundMs) return AutoPlain.NOTHING_TO_MIX to "one of the ends is silent"
        val album = input.aAlbum?.takeIf { it.isNotBlank() }
        if (album != null && album == input.bAlbum && runTogether(a, b)) return AutoPlain.RUN_TOGETHER to "two songs of one album that run into each other"
        return null
    }

    /**
     * Whether two songs of one album run into each other: the first is still loud in its last
     * half second and the second is loud in its first, with no silence between, as a live album or
     * a DJ mix cut into tracks is. Two songs of an album that end and start apart are mixed.
     */
    fun runTogether(a: SongEnd, b: SongEnd): Boolean {
        val aTail = a.meanDb(a.toMs - 500, a.toMs)
        val bHead = b.meanDb(b.fromMs, b.fromMs + 500)
        val aEndsAtEnd = a.toMs - a.trueEndMs < 150
        val bStartsAtStart = b.firstSoundMs - b.fromMs < 150
        return aEndsAtEnd && bStartsAtStart && aTail >= a.medianDb - 9 && bHead >= b.medianDb - 9
    }

    private fun plain(input: AutoInput, reason: AutoPlain, why: String): AutoPick {
        val a = Anchor(input.aId, max(0.0, input.a.trueEndMs), TransitionSpec.NOMINAL_BPM)
        val b = Anchor(input.bId, 0.0, TransitionSpec.NOMINAL_BPM)
        // A plain change keeps a move for every lane, for when it is turned back into a mix.
        val moves = AutoMoves.cutOn()
        return AutoPick(AutoKind.PLAIN, Preset.CUT, reason, 2, 0.0, false, a, b, moves, false, 0, false, null, 1.0, 0.0, Double.NEGATIVE_INFINITY, why)
    }

    private class Context(val input: AutoInput) {
        val a: SongEnd = input.a.with(input.fixA)
        val b: SongEnd = input.b.with(input.fixB)
        val notes = ArrayList<String>()
        val keyDistance: Int? = run {
            val ka = a.key
            val kb = b.key
            if (ka != null && kb != null && ka.sure && kb.sure) ka.key.distanceTo(kb.key) else null
        }
        /** The incoming song's first bar line, its bar 1, and how far after its first sound. */
        val bBar1: Int? = b.line?.let { l ->
            var k = l.downbeatAtOrAfter(l.kNear(b.firstSoundMs) - 1)
            while (l.msOf(k) < b.firstSoundMs - 30) k += l.beatsPerBar
            k
        }

        fun note(s: String) {
            notes += s
        }
    }

    /** B's tempo over A's, folded by two the way that brings it nearest 1, and the fold used (A's beats per B beat). */
    private fun ratio(a: BeatLine, b: BeatLine): Pair<Double, Double> {
        var best = b.bpm / a.bpm
        var perB = 1.0
        for (m in doubleArrayOf(2.0, 0.5)) {
            val r = b.bpm / (a.bpm * m)
            if (abs(1 - r) < abs(1 - best)) {
                best = r
                perB = 1.0 / m
            }
        }
        return best to perB
    }

    private fun phraseScore(level: Int): Double = when {
        level >= 16 -> 0.8
        level >= 8 -> 0.6
        level >= 4 -> 0.3
        level >= 2 -> -0.4
        level >= 1 -> -0.8
        else -> -1.0
    }

    /**
     * A change of [length] bars starting on a line that opens a phrase of [level] bars: best on a
     * phrase at least as long as the change (4 to 16), so an 8-bar change starts an 8-bar phrase;
     * less on a shorter one; scored down between phrases.
     */
    private fun startScore(level: Int, length: Int): Double {
        val want = length.coerceIn(4, 16)
        return when {
            level >= want -> 0.6 + if (level >= 16) 0.1 else 0.0
            level >= 4 -> 0.6 - 0.3 * (Integer.numberOfTrailingZeros(want) - Integer.numberOfTrailingZeros(level))
            level >= 2 -> -0.4
            level >= 1 -> -0.8
            else -> -1.0
        }
    }

    /** The same for the line the change ends on, where the outgoing song's phrase should close. */
    private fun endScore(level: Int, length: Int): Double = when {
        level >= length.coerceIn(4, 8) -> 0.25
        level >= 4 -> 0.1
        level >= 2 -> -0.1
        else -> -0.2
    }

    private fun unheardPenalty(ms: Double): Double = -0.03 * max(0.0, ms - 2_000.0) / 1000.0

    private fun keyScore(distance: Int?, lengthBars: Double): Double = when (distance) {
        null -> 0.0
        0, 1 -> 0.15
        2 -> 0.0
        else -> -0.3 * min(1.0, lengthBars / 8.0)
    }

    private fun varietyPenalty(c: Context, move: Preset): Double =
        if (c.input.recent.size >= 2 && c.input.recent.take(2).all { it == move }) -0.1 else 0.0

    /** The outro: from where the end settles 6 dB down for good, if it does so well before the true end. */
    private fun outroOf(a: SongEnd): Double? = a.outroStartMs.takeIf { a.trueEndMs - it >= 4_000 }

    private fun outroScore(a: SongEnd, atMs: Double, barMs: Double): Double {
        val outro = outroOf(a) ?: return 0.0
        return when {
            abs(atMs - outro) <= barMs * 0.6 -> 0.25
            atMs < outro -> -min(0.4, 0.1 * (outro - atMs) / barMs)
            else -> 0.0
        }
    }

    // 1. Beat locked.
    private fun beatLocked(c: Context): List<AutoPick> {
        val a = c.a
        val b = c.b
        val la = a.line
        val lb = b.line
        if (la == null || lb == null) return emptyList()
        if (!la.barsTrusted) {
            c.note("not beat locked: this song's bars are not trusted (${a.trustNote})")
            return emptyList()
        }
        if (!lb.barsTrusted) {
            c.note("not beat locked: the next song's bars are not trusted (${b.trustNote})")
            return emptyList()
        }
        val (speed0, perB) = ratio(la, lb)
        if (abs(speed0 - 1) > Planner.BEAT_MAX_SPEED) {
            c.note("not beat locked: tempos %.1f%% apart".format(abs(speed0 - 1) * 100))
            return emptyList()
        }
        // A's bars must line up with B's: the same length heard, or one twice the other.
        val heardBarA = la.barMs / speed0
        val barRatio = heardBarA / lb.barMs
        val aligned = listOf(0.5, 1.0, 2.0).any { abs(barRatio - it) < 0.02 * it }
        if (!aligned) {
            c.note("not beat locked: bars of %d and %d beats do not line up".format(la.beatsPerBar, lb.beatsPerBar))
            return emptyList()
        }
        val k1 = c.bBar1 ?: return emptyList()
        if (lb.msOf(k1) - b.firstSoundMs > FIRST_DOWNBEAT_MS) {
            c.note("not beat locked: the next song's first downbeat comes %.1f s after its first sound".format((lb.msOf(k1) - b.firstSoundMs) / 1000))
            return emptyList()
        }
        val phrases = a.phrases
        val out = ArrayList<AutoPick>()
        val beatB = 1.0 / CHANGE_BEATS
        for (length in LENGTHS) {
            val limit = if (length <= 4) Planner.BEAT_MAX_SPEED else Planner.BEAT_EIGHT_BAR_MAX_SPEED
            if (abs(speed0 - 1) > limit + 1e-9) {
                c.note("not $length bars: tempos %.1f%% apart".format(abs(speed0 - 1) * 100))
                continue
            }
            val kEndB = k1 + length * CHANGE_BEATS
            // B's beats must hold from its bar 1 to the end of the change, where they are laid on A's.
            if (k1 < lb.firstK - lb.beatsPerBar || kEndB > lb.lastK + 1) {
                c.note("not $length bars: the next song's beats are not trusted for $length bars")
                continue
            }
            if (lb.msOf(kEndB) > b.toMs - 500) {
                c.note("not $length bars: they run past what was read of the next song")
                continue
            }
            val bLocal = b.lineFor(k1, kEndB) ?: lb
            // The change in A's beats: one of B's beats is perB of A's.
            val beatsA = (length * CHANGE_BEATS * perB).roundToInt()
            var tried = 0
            for (j in 0 until (phrases?.beats?.size ?: 0)) {
                val kS = phrases!!.beats[j]
                val kE = kS + beatsA
                val ramp = rampBeats(speed0)
                val kR = kS - ramp
                val sMs = la.msOf(kS)
                // A's grid must hold from the ramp to the end of the change, the ramp inside what
                // can still change, and the change inside the budget.
                if (kR < la.firstK || kE > la.lastK + 1) continue
                if (la.msOf(kR) < c.input.earliestMs) continue
                if (a.trueEndMs - sMs > BUDGET_MS) continue
                if (la.msOf(kE) > a.trueEndMs + la.beatMs) continue
                tried++
                val aLocal = a.lineFor(kR, kE) ?: la
                val speed = ratio(aLocal, bLocal).first
                if (abs(speed - 1) > limit + 1e-9) continue
                val level = phrases.level[j]
                val endLevel = phrases.levelOf(kE)
                val unheard = max(0.0, a.trueEndMs - aLocal.msOf(kE))
                val bBarMs = bLocal.beatMs * CHANGE_BEATS
                val bStartMs = bLocal.msOf(k1)
                val sparseA = a.density < SPARSE
                val sparseB = b.density < SPARSE
                val busy = max(a.density, b.density) > BUSY
                // Whether B's opening keeps a kick through the first half of the change: without
                // one, it is brought up muffled, as a rise, and its beat is the drop.
                val bLowFirstHalf = b.meanLowDb(bStartMs, bStartMs + bBarMs * length / 2)
                val bKickless = bLowFirstHalf < b.medianLowDb - 9
                val aLow = a.meanLowDb(sMs, aLocal.msOf(kE))
                val aKickless = aLow < a.medianLowDb - 9
                val move = when {
                    bKickless && aKickless -> Preset.BLEND
                    bKickless -> Preset.RISE
                    c.keyDistance == 3 -> Preset.RISE
                    else -> Preset.FADE
                }
                var score = 3.0 + startScore(level, length) + endScore(endLevel, length) + 0.15 * phrases.strength[j]
                score += unheardPenalty(unheard)
                // DESIGN.md 2.2: 8 bars when both beats are trusted, 4 when the music is busy, 16
                // when both ends are sparse.
                score += when (length) {
                    16 -> if (sparseA && sparseB) 0.45 else -0.15
                    8 -> if (busy) 0.0 else 0.3
                    4 -> if (busy) 0.45 else 0.1
                    else -> -0.2
                }
                score -= 10.0 * abs(speed - 1)
                score += dropScore(b, bLocal, k1, length)
                score += keyScore(c.keyDistance, length.toDouble())
                score += outroScore(a, sMs, la.barMs)
                score += varietyPenalty(c, move)
                if (!phrases.heard) score -= 0.1
                val moves = when (move) {
                    Preset.RISE -> AutoMoves.rise(length.toDouble(), beatB)
                    Preset.BLEND -> AutoMoves.blend(length.toDouble(), beatB)
                    else -> AutoMoves.fade(length.toDouble(), beatB)
                }
                val anchorA = Anchor(c.input.aId, aLocal.msOf(kS), aLocal.bpm, CHANGE_BEATS)
                val anchorB = Anchor(c.input.bId, bStartMs, bLocal.bpm, CHANGE_BEATS)
                val why = "%d bars on the %s, in at %s, A %.2f to B %.2f BPM (A at %.4f)%s%s%s".format(
                    length, phraseWords(level, phrases.heard), clock(sMs), aLocal.bpm, bLocal.bpm, speed,
                    keyWords(c), if (move != Preset.FADE) ", ${moveWhy(move)}" else "",
                    if (lb.barsFromFirstBeat) ", the next song's bars from its first beat" else "",
                )
                out += AutoPick(
                    AutoKind.BEAT_LOCKED, move, null, length, length * bBarMs / 1000.0, true, anchorA, anchorB, moves,
                    onPhrase = level >= 4, phraseBars = level, bOnBar = true, keyDistance = c.keyDistance,
                    speed = speed, unheardMs = unheard, score = score, why = why,
                )
            }
            if (tried == 0) c.note("not $length bars: no bar line of this song fits them in the last %.0f s".format(BUDGET_MS / 1000))
        }
        return out
    }

    private fun moveWhy(move: Preset): String = when (move) {
        Preset.RISE -> "the next song opens without a kick, so it rises in muffled"
        Preset.BLEND -> "neither keeps a kick here"
        else -> ""
    }

    /**
     * How well the incoming song's own first big rise sits with a change of [length] bars: best
     * where it lands just as the change ends, fine after it, and scored down where it comes while
     * the outgoing song is still playing, so two drops never meet.
     */
    private fun dropScore(b: SongEnd, lb: BeatLine, k1: Int, length: Int): Double {
        val rise = b.introEndMs ?: return 0.0
        val d = (rise - lb.msOf(k1)) / (lb.beatMs * CHANGE_BEATS)
        return when {
            abs(d - length) <= 0.5 -> 0.35
            d > length -> 0.15
            d >= length / 2.0 -> -0.25
            d > 0.5 -> -0.5
            else -> 0.0
        }
    }

    // 2. The intro mix.
    private fun introMix(c: Context): List<AutoPick> {
        val a = c.a
        val b = c.b
        val la = a.line ?: return emptyList()
        val lb = b.line ?: return emptyList()
        if (!la.barsTrusted || !lb.barsTrusted) return emptyList()
        // The drop: the first bar line of B's trusted beats, well after its first sound.
        val kD = lb.downbeatAtOrAfter(lb.firstK)
        val dropMs = lb.msOf(kD)
        val introMs = dropMs - b.firstSoundMs
        if (introMs <= FIRST_DOWNBEAT_MS) return emptyList()
        // The intro must be quiet underneath: no kick and bass at the level the song keeps after.
        if (b.meanLowDb(b.firstSoundMs, dropMs) > b.medianLowDb - 6) {
            c.note("no intro mix: the next song's opening already has its kick, but its beats are not trusted until %.1f s".format(dropMs / 1000))
            return emptyList()
        }
        val (speed0, perB) = ratio(la, lb)
        val introBars = introMs / (lb.beatMs * CHANGE_BEATS)
        val out = ArrayList<AutoPick>()
        val phrases = a.phrases ?: return emptyList()
        val beatB = 1.0 / CHANGE_BEATS
        for (length in LENGTHS) {
            if (length > introBars + 0.25) continue
            val limit = if (length <= 4) Planner.BEAT_MAX_SPEED else Planner.BEAT_EIGHT_BAR_MAX_SPEED
            if (abs(speed0 - 1) > limit) {
                c.note("no intro mix of $length bars: tempos %.1f%% apart".format(abs(speed0 - 1) * 100))
                continue
            }
            val k1 = kD - length * CHANGE_BEATS
            val bAt = lb.msOf(k1)
            if (bAt < 0) {
                c.note("no intro mix of $length bars: the next song's opening is shorter than that")
                continue
            }
            // B's intro rises from its first sound, however long before bar 1 that is: the
            // budget below says whether this song's end can carry all of it.
            val fromU = ((b.firstSoundMs - bAt) / (lb.beatMs * CHANGE_BEATS)).coerceAtMost(0.0)
            val beatsA = (length * CHANGE_BEATS * perB).roundToInt()
            val preBeatsA = (-fromU * CHANGE_BEATS * perB).roundToInt()
            for (j in phrases.beats.indices) {
                val kS = phrases.beats[j]
                val kE = kS + beatsA
                val ramp = rampBeats(speed0)
                val kR = kS - max(ramp, preBeatsA)
                if (kR < la.firstK || kE > la.lastK + 1) continue
                if (la.msOf(kR) < c.input.earliestMs) continue
                val sMs = la.msOf(kS)
                if (a.trueEndMs - la.msOf(kS - preBeatsA) > BUDGET_MS) continue
                if (la.msOf(kE) > a.trueEndMs + la.beatMs) continue
                val aLocal = a.lineFor(kR, kE) ?: la
                val bLocal = b.lineFor(kD, kD + 4 * lb.beatsPerBar) ?: lb
                val speed = ratio(aLocal, bLocal).first
                if (abs(speed - 1) > limit + 1e-9) continue
                val level = phrases.level[j]
                val endLevel = phrases.levelOf(kE)
                val unheard = max(0.0, a.trueEndMs - aLocal.msOf(kE))
                // What a DJ lines up in an intro mix is the drop: it must land on the outgoing
                // song's phrase line. Where the change starts matters less.
                var score = 2.8 + startScore(endLevel, length) + 0.5 * endScore(level, length) + 0.15 * phrases.strengthOf(kE)
                score += unheardPenalty(unheard)
                score += if (abs(introBars - length) <= 0.5) 0.3 else 0.0
                score -= 10.0 * abs(speed - 1)
                score += keyScore(c.keyDistance, length.toDouble() / 2)
                score += outroScore(a, sMs, la.barMs)
                score += varietyPenalty(c, Preset.RISE)
                if (!phrases.heard) score -= 0.1
                val bAnchorMs = bLocal.msOf(kD) - length * bLocal.beatMs * CHANGE_BEATS
                val anchorA = Anchor(c.input.aId, aLocal.msOf(kS), aLocal.bpm, CHANGE_BEATS)
                val anchorB = Anchor(c.input.bId, bAnchorMs, bLocal.bpm, CHANGE_BEATS)
                val why = "intro mix: the next song's %.1f-bar opening rises under this one for %d bars, its beat drops at %s on the %s as this one leaves, A %.2f to B %.2f BPM (A at %.4f)%s".format(
                    introBars, length - minOf(0.0, fromU).roundToInt(), clock(la.msOf(kE)), phraseWords(endLevel, phrases.heard), aLocal.bpm, bLocal.bpm, speed, keyWords(c),
                )
                out += AutoPick(
                    AutoKind.INTRO_MIX, Preset.RISE, null, length, length * bLocal.beatMs * CHANGE_BEATS / 1000.0, true, anchorA, anchorB,
                    AutoMoves.introRise(length.toDouble(), beatB, fromU),
                    onPhrase = endLevel >= 4, phraseBars = endLevel, bOnBar = true, keyDistance = c.keyDistance,
                    speed = speed, unheardMs = unheard, score = score, why = why,
                )
            }
        }
        return out
    }

    // 3. The hand-over: echo out or cut on a line, the incoming song's first downbeat on it.
    private fun handOvers(c: Context): List<AutoPick> {
        val a = c.a
        val b = c.b
        val la = a.line
        val lb = b.line
        // Where B comes in: its first bar line when its bars are trusted, its first beat when only
        // its beats are, its first sound otherwise.
        val (bAtMs, bWhat) = when {
            lb != null && lb.barsTrusted && c.bBar1 != null && lb.msOf(c.bBar1) - b.firstSoundMs <= FIRST_DOWNBEAT_MS -> lb.msOf(c.bBar1) to "its first downbeat"
            lb != null && lb.beatsTrusted -> {
                var k = lb.kNear(b.firstSoundMs)
                while (lb.msOf(k) < b.firstSoundMs - 30) k++
                if (lb.msOf(k) - b.firstSoundMs <= FIRST_DOWNBEAT_MS) lb.msOf(k) to "its first beat" else b.firstSoundMs to "its first sound"
            }
            else -> b.firstSoundMs to "its first sound"
        }
        val bBarMs = lb?.takeIf { it.beatsTrusted }?.let { it.beatMs * CHANGE_BEATS } ?: (240_000.0 / TransitionSpec.NOMINAL_BPM)
        val bBpm = lb?.takeIf { it.beatsTrusted }?.bpm ?: TransitionSpec.NOMINAL_BPM
        val bBeats = CHANGE_BEATS
        val beatB = 1.0 / bBeats
        val pickupMs = bAtMs - b.firstSoundMs
        val incoming = if (pickupMs <= PICKUP_MS) AutoMoves.fromStart() else AutoMoves.risingInto(-min(pickupMs, bBarMs) / bBarMs)

        // Lines A may leave on, each with its phrase level: phrase lines when its bars are
        // trusted, beats in its last seconds when only its beats are.
        class Line(val ms: Double, val level: Int, val strength: Double, val beatMs: Double)
        val lines = ArrayList<Line>()
        if (la != null && la.barsTrusted && a.phrases != null) {
            val p = a.phrases
            for (j in p.beats.indices) if (la.trusts(p.beats[j]) || la.trusts(p.beats[j] - 1)) lines += Line(la.msOf(p.beats[j]), p.level[j], p.strength[j], la.beatMs)
        } else if (la != null && la.beatsTrusted) {
            // Its steady beat is carried on past the trusted stretch to its end: an echo hides the
            // few ms a line can wander there.
            val last = if (a.steadyTempo) max(la.lastK + 1, la.kNear(a.trueEndMs)) else la.lastK + 1
            for (k in la.firstK..last) {
                val ms = la.msOf(k)
                if (a.trueEndMs - ms <= 12_000) lines += Line(ms, 0, 0.0, la.beatMs)
            }
            c.note("hand-over on a beat only: this song's bars are not trusted (${a.trustNote})")
        } else {
            c.note("no hand-over on the beat: this song's beats are not trusted (${a.trustNote})")
        }
        val out = ArrayList<AutoPick>()
        for (line in lines) {
            val h = line.ms
            if (h < c.input.earliestMs + 2_000) continue
            if (h > a.trueEndMs - line.beatMs * 0.5) continue
            val unheard = a.trueEndMs - h
            if (unheard > BUDGET_MS) continue
            // A must still be playing up to the line: an echo of a quiet bar is nothing, and a cut
            // into a fade's last whisper loses nothing worth a move.
            val before = a.meanDb(h - line.beatMs * 4, h)
            if (before < a.medianDb - 15) continue
            val loudBefore = before >= a.medianDb - 6
            val bLoud = b.meanDb(bAtMs, bAtMs + bBarMs) >= b.medianDb - 3
            val tier = if (line.level >= 4) 2.0 else if (line.level >= 1) 1.5 else 1.2
            var base = tier + (if (line.level > 0) phraseScore(line.level) * 0.75 else -0.3) + 0.15 * line.strength
            base += unheardPenalty(unheard)
            base += outroScore(a, h, line.beatMs * 4)
            if (a.phrases?.heard == false && line.level > 0) base -= 0.1
            // A cut where both sides are at full: drop to drop. An echo where the outgoing song has
            // something left to echo.
            val echoScore = base + (if (loudBefore) 0.15 else -0.2) + varietyPenalty(c, Preset.ECHO_OUT)
            val cutScore = base + (if (loudBefore && bLoud) 0.1 else -0.15) + varietyPenalty(c, Preset.CUT)
            val anchorA = Anchor(c.input.aId, h, la?.bpm ?: TransitionSpec.NOMINAL_BPM, CHANGE_BEATS)
            val anchorB = Anchor(c.input.bId, bAtMs, bBpm, bBeats)
            val tempo = if (la != null && lb != null) "A %.2f, B %.2f BPM".format(la.bpm, lb.bpm) else "no tempo on one side"
            val where = if (line.level > 0) "the " + phraseWords(line.level, a.phrases?.heard == true) else "a beat"
            for ((move, score) in listOf(Preset.ECHO_OUT to echoScore, Preset.CUT to cutScore)) {
                val moves = if (move == Preset.ECHO_OUT) AutoMoves.echoOn(beatB, incoming = incoming) else AutoMoves.cutOn(incoming)
                out += AutoPick(
                    AutoKind.HAND_OVER, move, null, 2, 2 * bBarMs / 1000.0, false, anchorA, anchorB, moves,
                    onPhrase = line.level >= 4, phraseBars = line.level, bOnBar = bWhat == "its first downbeat", keyDistance = c.keyDistance,
                    speed = 1.0, unheardMs = unheard, score = score,
                    why = "%s on %s at %s, %.1f s before the end, into %s; %s%s".format(
                        if (move == Preset.ECHO_OUT) "echo out" else "cut", where, clock(h), unheard / 1000, bWhat, tempo, keyWords(c),
                    ),
                )
            }
        }
        return out
    }

    // 4. Blends in seconds.
    private fun blends(c: Context): List<AutoPick> {
        val a = c.a
        val b = c.b
        // Two steady beats at different speeds slide apart within a bar: no blend over both. A
        // song with a steady pulse is taken to keep it at its end, unless its trusted beats stop
        // before a quiet outro; and at its start, unless its first big rise comes well after its
        // first sound, past a quiet intro. The quiet stretch has to cover the whole blend, as the
        // prototype's planner had it: one longer than the outro or the intro lays the two beats
        // over each other for the rest of it.
        val outro = outroOf(a)?.takeIf { from -> a.line?.takeIf { it.beatsTrusted }?.let { it.msOf(it.lastK) <= from + it.beatMs } ?: true }
        val rise = b.introEndMs?.takeIf { at ->
            at - b.firstSoundMs >= 6_000 && (b.line?.takeIf { it.beatsTrusted }?.let { it.msOf(it.firstK) >= at - it.beatMs } ?: true)
        }
        val out = ArrayList<AutoPick>()
        val fade = a.fadeMs?.let { it / 1000.0 }
        val bothLoud = a.meanDb(a.trueEndMs - 10_000, a.trueEndMs - 500) >= a.medianDb - 3 &&
            b.meanDb(b.firstSoundMs, b.firstSoundMs + 10_000) >= b.medianDb - 3
        for (seconds in BLEND_SECONDS) {
            val end = a.trueEndMs
            val start = end - seconds * 1000
            if (start < c.input.earliestMs + 1_000) continue
            val aBeat = a.steadyTempo && (outro == null || outro > start)
            val bBeat = b.steadyTempo && (rise == null || rise < b.firstSoundMs + seconds * 1000)
            if (aBeat && bBeat) {
                c.note("no blend: both songs keep a steady beat through it, which would slide against the other's")
                continue
            }
            val barMs = 240_000.0 / TransitionSpec.NOMINAL_BPM
            val length = seconds * 1000 / barMs
            val beat = 0.25
            var score = 0.8
            score += when {
                fade != null -> -abs(seconds - fade.coerceIn(4.0, 12.0)) * 0.05
                seconds == 6.0 -> 0.05
                else -> -0.02 * abs(seconds - 6.0)
            }
            score += keyScore(c.keyDistance, length * 2)
            val move = if (bothLoud && seconds >= 8.0) Preset.RISE else Preset.BLEND
            score += varietyPenalty(c, move)
            val moves = if (move == Preset.RISE) AutoMoves.rise(length, beat) else AutoMoves.blend(length, beat)
            val anchorA = Anchor(c.input.aId, start, TransitionSpec.NOMINAL_BPM)
            val anchorB = Anchor(c.input.bId, b.firstSoundMs, TransitionSpec.NOMINAL_BPM)
            out += AutoPick(
                AutoKind.BLEND, move, null, 0, seconds, false, anchorA, anchorB, moves,
                onPhrase = false, phraseBars = 0, bOnBar = false, keyDistance = c.keyDistance, speed = 1.0, unheardMs = 0.0, score = score,
                why = "%.0f s %s to this song's end%s; %s".format(
                    seconds, if (move == Preset.RISE) "rise" else "blend",
                    if (fade != null) ", its own fade is %.1f s".format(fade) else "",
                    if (aBeat || bBeat) "only one of them keeps a beat here" else "no trusted beat on either side",
                ),
            )
        }
        return out
    }

    private fun phraseWords(level: Int, heard: Boolean): String = when {
        level >= 4 -> "$level-bar phrase line" + if (heard) "" else " (counted)"
        level >= 1 -> "bar line, between phrases"
        else -> "beat"
    }

    private fun keyWords(c: Context): String {
        val ka = c.a.key ?: return ""
        val kb = c.b.key ?: return ""
        if (!ka.sure || !kb.sure) return ""
        return ", keys %s to %s".format(ka.key.camelot, kb.key.camelot) + if (c.keyDistance == 3) " clash" else ""
    }

    private fun clock(ms: Double): String {
        val hundredths = (ms / 10).roundToInt()
        return "%d:%02d.%02d".format(hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
    }
}
