/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Auto's change the way the hand-made reels do it, decided from the two whole songs.
 *
 * A plays at full volume to one of its own section lines and is cut there. B is started so that its
 * drop, where its full beat and bass arrive, lands on that line: the two share one 8-bar box with
 * one bass at a time (swapped on the box's bar 5), and B is stretched to A's tempo. First the pair
 * is checked against that move: tempos within 6% (70 and 140 count as one), a grid that holds where
 * the box falls in both songs, an entry in B (a sure drop or a first full phrase), and voices kept
 * apart. Where nothing fits, a simpler move on one of A's phrase lines: an echo out, a high-pass
 * sweep, a clean cut into B's first full phrase, or, with no trusted beat at any of A's lines, a
 * 4 s fade at A's natural end.
 *
 * The rules are the PC prototype's round 3 (experiments/features/automix/party-2010s/tools/decide.py),
 * ported decision for decision: the tests hold its 23 frozen decisions. Where Python's arithmetic
 * differs from Kotlin's, the port follows Python ([Py]). Nothing here is used by the player yet.
 */
object ReelsWay {

    /** How far B's tempo (or its double or half) may sit from A's for the two to share 8 bars. */
    const val TEMPO_TOL = 0.06

    /** A bar has a beat on the grid from this pulse on (pulse.py's z-score). */
    const val PULSE_MIN = 1.0

    /** How far the median bar may sit off where the grid sits elsewhere: Mixxx's inaudible offset. */
    const val LAG_OK_MS = 25.0

    /** How widely the bars' lags may spread, 10th to 90th percentile. */
    const val SPREAD_OK_MS = 40.0

    /** How well Beat This!'s bar 1 must agree with its own downbeats over the whole song. */
    const val BAR_AGREE_MIN = 0.5

    /** The level both songs are matched to in the mix, as the prototype's renders do, LUFS. */
    const val MIX_LUFS = -14.0

    /** The change for A into B. */
    fun decide(a: ReelsSong, b: ReelsSong, rules: ReelsRules = ReelsRules()): ReelsDecision {
        val pa = Profile(a)
        val pb = Profile(b)
        val why = ArrayList<String>()

        // Tempo, with octaves: the first of x1, x2 and x0.5 nearest 1 (min() keeps the first of a tie).
        var m = 1.0
        var ratio = a.grid.bpm / (1.0 * b.grid.bpm)
        for (mm in doubleArrayOf(2.0, 0.5)) {
            val r = a.grid.bpm / (mm * b.grid.bpm)
            if (abs(r - 1) < abs(ratio - 1)) {
                m = mm
                ratio = r
            }
        }
        val q = 1.0 / m
        val tempoOff = ratio - 1
        val tempoOk = abs(tempoOff) <= TEMPO_TOL
        why += "Tempo: A %.2f, B %.2f BPM%s: %+.1f%%%s".fmt(
            a.grid.bpm, b.grid.bpm, if (m != 1.0) " (B counted x${octaveText(m)})" else "", tempoOff * 100,
            if (tempoOk) ", within 6%" else ", more than 6% apart",
        )

        // B's entry: its sure drops, or else its first full phrase.
        val cands = drops(pb)
        val bOpts = ArrayList<BOption>()
        val sure: Boolean
        val entryOk: Boolean
        if (cands.isNotEmpty()) {
            val top = cands.maxOf { it.rise }
            for (c in cands) {
                bOpts += BOption(c.bar, (if (c.rise >= top - 2) top else min(c.rise, 20.0)) / 4 - 0.01 * c.bar, c)
            }
            sure = true
            entryOk = true
            why += "B's drops (bar, rise dB): " + cands.joinToString { "${it.bar} (+%.1f)".fmt(it.rise) }
        } else {
            val lines = listOf(0) + b.lines
            val loud = Py.percentile(pb.lvl, 80.0)
            val firstFound = lines.firstOrNull { l ->
                l < 0.45 * pb.n && pb.lvl[l] >= loud - 6 && Py.median(pb.low, l, l + 4) >= pb.full - 4
            }
            val first = firstFound ?: 0
            bOpts += BOption(first + Py.round(8 * q), 0.0, null)
            sure = false
            entryOk = firstFound != null
            why += if (entryOk) "No sure drop in B: it enters on its first full phrase, bar $first"
            else "No sure drop in B and no full phrase in its first 45%: no entry for the reels' move"
        }

        // A's exit lines: a section line in its second half where it lifts, or its end.
        val linesA = HashSet<Int>().apply { addAll(a.lines); addAll(a.peaks); add(pa.endLine) }
        val aCands = ArrayList<ExitCandidate>()
        for (l in max(8, (0.5 * pa.n).toInt())..pa.endLine) {
            if (l !in linesA) continue
            val lift = if (l < pa.endLine) pa.low[l] - pa.low[l - 1] else 0.0
            val sus = l < pa.endLine && Py.median(pa.low, l, l + 4) >= pa.full - 4
            val isLift = lift >= 5 && sus
            if (!(isLift || l == pa.endLine)) continue
            val boxLow = Py.median(pa.low, l - 8, l)
            val thin = boxLow <= pa.full - 10
            val tL = if (l < a.bars.size) a.bars[l].t else a.duration
            val tE = if (pa.endLine < a.bars.size) a.bars[pa.endLine].t else a.duration
            val left = max(0.0, tE - tL)
            val score = (if (isLift) min(lift, 20.0) / 4 else 0.0) + (if (thin) 3.0 else 0.0) +
                (if (l == pa.endLine) 0.5 else 0.0) + (if ((l - 8) in linesA) 0.5 else 0.0) - left / 40
            aCands += ExitCandidate(l, tL, lift, isLift, boxLow - pa.full, thin, l == pa.endLine, left, score)
        }
        val nB = Py.round(8 * q)
        val placing = Placing(nB, q, pb.sung)

        // Every placement weighed: A's points and B's, less the bars where both sing.
        val combos = ArrayList<Combo>()
        for (o in bOpts) {
            val gB = gridCheck(b.pulse, (o.drop until o.drop + 4).toList())
            for (c in aCands) {
                val (early, last) = placing.bothSing(pa.sung, c.bar, o.drop)
                val gA = gridCheck(a.pulse, (c.bar - 16 until c.bar + 4).toList(), minCount = 4)
                combos += Combo(c.score + o.score - 1.0 * early - 0.25 * last, o, c, early, last, gA, gB,
                    early <= 1 || !rules.voiceGate)
            }
        }
        val sorted = Py.sortedByKey(combos) { -it.score }
        val fits = if (tempoOk && entryOk) sorted.filter { it.gridA.ok && it.gridB.ok && it.voicesOk } else emptyList()
        for (x in sorted.take(4)) {
            why += "A ${x.a.bar} / B ${x.b.drop}: %.2f (both sing ${x.early}+${x.last}; grid A ${ok(x.gridA.ok)}, grid B ${ok(x.gridB.ok)}, voices ${ok(x.voicesOk)})".fmt(x.score)
        }
        val best = sorted.firstOrNull()
        val fitBase = ReelsFit(
            tempo = tempoOk, tempoOffPct = Py.round(tempoOff * 100, 2), octave = m, entry = entryOk,
            anyPlacement = fits.isNotEmpty(), voiceGate = rules.voiceGate,
            best = best?.let { BestPlacement(it.a.bar, it.b.drop, it.gridA, it.gridB, it.early, it.last) },
            why = null,
        )
        val head = Head(a, b)
        if (fits.isNotEmpty()) {
            why += "Fits the reels' move: A's line ${fits[0].a.bar}, B's drop ${fits[0].b.drop}."
            return reelsMove(head, fitBase, a, b, pa, pb, fits[0], aCands, sorted, sure, q, m, ratio, cands, placing, rules, why)
        }
        val reasons = ArrayList<String>()
        if (!tempoOk) reasons += "tempo"
        if (!entryOk) reasons += "no usable entry in B"
        if (tempoOk && entryOk && aCands.isEmpty()) {
            reasons += "no exit line in A's second half"
        } else if (tempoOk && entryOk) {
            if (sorted.none { it.gridA.ok }) reasons += "A's grid not trusted at any exit"
            if (sorted.none { it.gridB.ok }) reasons += "B's grid not trusted at its drop"
            if (sorted.none { it.voicesOk }) reasons += "voices not kept apart by any placement"
            if (reasons.isEmpty()) reasons += "no placement meets grid and voices together"
        }
        why += "Does not fit the reels' move: " + reasons.joinToString() + "."
        return fallback(head, fitBase.copy(why = reasons), a, b, pa, pb, aCands, tempoOk, ratio, why)
    }

    private fun reelsMove(
        head: Head, fit: ReelsFit, a: ReelsSong, b: ReelsSong, pa: Profile, pb: Profile, chosen: Combo,
        aCands: List<ExitCandidate>, combos: List<Combo>, sure: Boolean, q: Double, m: Double, ratio: Double,
        drops: List<DropCandidate>, placing: Placing, rules: ReelsRules, why: MutableList<String>,
    ): ReelsDecision {
        val d = chosen.b.drop
        val best = chosen.a
        // Every candidate carries its both-sing counts at the chosen drop (the first combo that has it).
        val candidates = aCands.map { c ->
            val x = combos.firstOrNull { it.a === c && it.b.drop == d }
            c.copy(
                bothSungEarly = x?.early ?: 0, bothSungLast = x?.last ?: 0,
                bothSung = if (c === best) chosen.early + chosen.last else null,
            )
        }
        val (e, leadOff) = placing.place(d)
        if (leadOff != 0) why += "B's drop is only $d bars in: it starts at its bar $e on box bar ${leadOff + 1}."
        val l = best.bar
        why += "A's line: bar $l; the box is A's bars ${l - 8} to ${l - 1}."
        val lead = (e until min(d, pb.n)).toList()
        val leadBody = if (lead.size > 2) lead.dropLast(1) else lead
        val dLow = Py.median(pb.low, d, d + 4) - Py.median(DoubleArray(leadBody.size) { pb.low[leadBody[it]] })
        val dLevel = Py.median(pb.lvl, d, d + 4) - Py.median(DoubleArray(leadBody.size) { pb.lvl[leadBody[it]] })
        val shape = if (dLow <= 6) LeadShape.BEAT else if (dLow <= 18 && dLevel <= 8) LeadShape.INTRO else LeadShape.BUILD
        val swap = dLow <= 15
        val stretch = abs(ratio - 1) > 0.002
        var echo = best.end
        why += "B's lead-in: bass %.1f dB and level %.1f dB under its drop: ${shape.label}, ".fmt(dLow, dLevel) +
            (if (swap) "the basses swap on bar 5" else "A keeps its bass to the line") +
            (if (stretch) "; B x%.4f".fmt(ratio) else "") + (if (echo) "; an echo (A's line is its end)" else "")

        // B's lead-in and the echo, as the rules ask; the frozen rules change nothing.
        var entry = e
        var leadOffset = leadOff
        var heard: Double? = null
        var late = false
        rules.echo?.let { echo = it }
        rules.leadOffsetBars?.let { want -> moveLater(entry, leadOffset, want, q, pb.n)?.let { (en, lo) -> entry = en; leadOffset = lo } }
        if (rules.lateEntryWhenBuried && shape != LeadShape.BUILD && swap) {
            heard = heardDiff(a, b, pa, pb, l, e, d)
            if (heard == null) {
                why += "Late entry: not weighed, a song's loudness is not known."
            } else if (heard <= ReelsRules.BURIED_DB) {
                late = true
                echo = false
                if (leadOffset < ReelsRules.LATE_LEAD) {
                    moveLater(entry, leadOffset, ReelsRules.LATE_LEAD, q, pb.n)?.let { (en, lo) -> entry = en; leadOffset = lo }
                }
                why += "Late entry: B's lead-in sits %.1f dB under A's box as heard: B enters on box bar ${leadOffset + 1}, A cut dead on its line.".fmt(-heard)
            } else {
                why += "Late entry: B's lead-in sits %.1f dB from A's box as heard, not buried.".fmt(heard)
            }
        }
        val bSung = (0 until 8).map { kk -> kk >= leadOffset && placing.bBars(entry, leadOffset, kk).any { pb.sung[it] } }
        val aSung = (0 until 8).map { kk -> pa.sung[l - 8 + kk] }
        val firstB = (0 until 8).firstOrNull { bSung[it] }?.let { it + 1 }
        return ReelsDecision(
            head = head, fit = fit, move = ReelsMove.REELS,
            exitBar = l, exitT = best.t, entryBarB = entry, entryT = b.bars[entry].t,
            ratio = ratio, stretch = stretch, octave = m, aEndLine = pa.endLine, aCandidates = candidates,
            boxStartBarA = l - 8, boxStartT = a.bars[l - 8].t,
            dropBar = d, dropSure = sure, dropT = if (d < b.bars.size) b.bars[d].t else null,
            leadOffsetBars = leadOffset, shape = shape, swap = swap, swapBoxBar = if (swap) 5 else null, echo = echo,
            dLow = dLow, dLevel = dLevel, bCandidates = drops,
            aSungBoxBars = (0 until 8).filter { aSung[it] }.map { it + 1 },
            bSungBoxBars = (0 until 8).filter { bSung[it] }.map { it + 1 },
            rule7 = firstB == null || firstB >= 8,
            heardDiffDb = heard, lateEntry = late, why = why,
        )
    }

    /**
     * B's entry moved later, to box bar [want] + 1, its drop still on the line: [want] - [lead] box
     * bars later, that many times [q] of B's own bars. Where that is not a whole number of B's bars
     * (B counted x2), the nearest earlier bar line of B. Null where it would not move.
     */
    private fun moveLater(entry: Int, lead: Int, want: Int, q: Double, bars: Int): Pair<Int, Int>? {
        if (want <= lead || want > 7) return null
        val shiftB = floor((want - lead) * q + 1e-9).toInt()
        if (shiftB <= 0 || entry + shiftB >= bars) return null
        return Pair(entry + shiftB, lead + Py.round(shiftB / q))
    }

    /**
     * How B's lead-in sits under A's box as heard in the mix, dB: the median bar level of B's bars
     * [e] to [d] - 1 and of A's box, each with the gain that brings its song to [MIX_LUFS].
     */
    private fun heardDiff(a: ReelsSong, b: ReelsSong, pa: Profile, pb: Profile, l: Int, e: Int, d: Int): Double? {
        val la = a.lufs ?: return null
        val lb = b.lufs ?: return null
        return (Py.median(pb.lvl, e, d) + (MIX_LUFS - lb)) - (Py.median(pa.lvl, l - 8, l) + (MIX_LUFS - la))
    }

    private fun fallback(
        head: Head, fit: ReelsFit, a: ReelsSong, b: ReelsSong, pa: Profile, pb: Profile, aCands: List<ExitCandidate>,
        tempoOk: Boolean, ratio: Double, why: MutableList<String>,
    ): ReelsDecision {
        // B's entry: its first full phrase (its start, a section line or a sure drop in its first 45%
        // at full level and full bass), as a DJ cuts into it; without one, its first downbeat.
        val loudB = Py.percentile(pb.lvl, 80.0)
        val firstDown = (0 until pb.n).firstOrNull { pb.lvl[it] >= loudB - 20 } ?: 0
        val starts = (setOf(0) + b.lines + drops(pb).map { it.bar }).sorted()
        val fullPhrase = starts.firstOrNull { l ->
            l < 0.45 * pb.n && pb.lvl[l] >= loudB - 6 && Py.median(pb.low, l, l + 4) >= pb.full - 4
        }
        val fb = fullPhrase ?: firstDown
        val gB = gridCheck(b.pulse, (fb until fb + 4).toList())
        // A's line: the best exit candidate whose bars around it have a trusted beat.
        val ranked = Py.sortedByKey(aCands) { -it.score }
        val grids = HashMap<Int, GridCheck>()
        var lineA: ExitCandidate? = null
        for (c in ranked) {
            val g = gridCheck(a.pulse, (c.bar - 16 until c.bar + 4).toList(), minCount = 4)
            grids[c.bar] = g
            if (g.ok && lineA == null) lineA = c
        }
        val candidates = aCands.map { c -> grids[c.bar]?.let { c.copy(grid = it) } ?: c }
        why += "B enters on its bar $fb (" + (if (fullPhrase != null) "its first full phrase" else "its first downbeat") +
            "), its beat there ${if (gB.ok) "trusted" else "not trusted"}."
        val tE = if (pa.endLine < a.bars.size) a.bars[pa.endLine].t else a.duration
        if (lineA == null) {
            why += "Fade: no exit line of A has a trusted beat; 4 s at A's natural end, bar ${pa.endLine}."
            return ReelsDecision(
                head = head, fit = fit, move = ReelsMove.FADE, exitBar = pa.endLine, exitT = tE,
                entryBarB = fb, entryT = b.bars[fb].t, ratio = 1.0, stretch = false, octave = 1.0, aEndLine = pa.endLine,
                aCandidates = candidates, fadeS = 4.0, firstDownbeatB = firstDown, fullPhraseB = fullPhrase,
                bGrid = gB, tempoRatioIfMatched = ratio, why = why,
            )
        }
        val l = lineA.bar
        val aSings = pa.sung[l - 1]
        val bSings = pb.sung[fb] || (fb + 1 < pb.n && pb.sung[fb + 1])
        val aFull = Py.median(pa.low, l - 2, l) >= pa.full - 4
        val bFull = pb.low[fb] >= pb.full - 6
        val move = if ((aSings && !bSings) || !gB.ok) ReelsMove.ECHO
        else if (!tempoOk && aFull && bFull) ReelsMove.SWEEP
        else ReelsMove.CUT
        why += when (move) {
            ReelsMove.ECHO -> if (aSings && !bSings) "Echo out: A sings into its line, B starts without a voice." else "Echo out: B has no trusted beat at its start."
            ReelsMove.SWEEP -> "High-pass sweep: the tempos are apart and both are at full bass where they meet."
            else -> "Cut on A's line, bar $l, into B's bar $fb."
        }
        return ReelsDecision(
            head = head, fit = fit, move = move, exitBar = l, exitT = lineA.t,
            entryBarB = fb, entryT = b.bars[fb].t, ratio = 1.0, stretch = false, octave = 1.0, aEndLine = pa.endLine,
            aCandidates = candidates, firstDownbeatB = firstDown, fullPhraseB = fullPhrase, bGrid = gB,
            tempoRatioIfMatched = ratio, aSingsLast = aSings, bSingsStart = bSings, aFullBass = aFull, bFullBass = bFull,
            why = why,
        )
    }

    /** B's sure drops in its first 45% (at most bar 112): its low band rising 9 dB into full bass after a thin bar. */
    internal fun drops(p: Profile): List<DropCandidate> {
        val hi = min((0.45 * p.n).toInt(), 112)
        val out = ArrayList<DropCandidate>()
        for (i in 1 until hi) {
            val r = p.low[i] - p.low[i - 1]
            if (r >= 9 && p.low[i] >= p.full - 4 && p.low[i - 1] <= p.full - 8 && Py.median(p.low, i, i + 4) >= p.full - 4) {
                out += DropCandidate(i, r, p.low[i - 1] - p.full, Py.median(p.low, i, i + 4) - p.full)
            }
        }
        return out
    }

    /**
     * Whether the grid holds over [bars]: enough bars with a beat (a share of [need], or [minCount]
     * of them), their lags within [LAG_OK_MS] in the median and [SPREAD_OK_MS] in spread, and the
     * song's bar 1 agreeing with its own downbeats.
     */
    internal fun gridCheck(p: PulseCheck, bars: List<Int>, need: Double = 0.5, minCount: Int? = null): GridCheck {
        val rows = bars.filter { it >= 0 && it < p.bars.size }.map { p.bars[it] }
        val pul = rows.filter { it.pulse >= PULSE_MIN && it.lagRelMs != null }
        val share = pul.size.toDouble() / max(1, rows.size)
        val from = bars.min()
        val to = bars.max()
        if (pul.isEmpty()) return GridCheck(false, 0.0, null, null, null, from, to)
        val lags = DoubleArray(pul.size) { pul[it].lagRelMs!! }
        val med = Py.median(DoubleArray(lags.size) { abs(lags[it]) })
        val spread = Py.percentile(lags, 90.0) - Py.percentile(lags, 10.0)
        val whole = (p.barAgreement ?: 0.0) >= BAR_AGREE_MIN
        val enough = if (minCount != null && minCount != 0) pul.size >= minCount else share >= need
        val ok = enough && med <= LAG_OK_MS && spread <= SPREAD_OK_MS && whole
        return GridCheck(ok, Py.round(share, 2), Py.round(med, 1), Py.round(spread, 1), p.barAgreement, from, to)
    }

    private fun ok(b: Boolean) = if (b) "ok" else "NO"
    private fun octaveText(x: Double) = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString()
    private fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)

    /** One song's bars as the rules read them. */
    internal class Profile(song: ReelsSong) {
        val n = song.bars.size
        val low = DoubleArray(n) { song.bars[it].low150 }
        val lvl = DoubleArray(n) { song.bars[it].level }
        val sung = BooleanArray(n) { song.bars[it].voice == BarVoice.SUNG }

        /** The low band's full level: the 80th percentile of the bars within 12 dB of the loud ones. */
        val full: Double

        /** The line after the last sounding bar, or the last bar itself: where A's music ends. */
        val endLine: Int

        init {
            val loud = Py.percentile(lvl, 80.0)
            val snd = BooleanArray(n) { lvl[it] > loud - 12 }
            full = Py.percentile(low.filterIndexed { i, _ -> snd[i] }.toDoubleArray(), 80.0)
            val last = snd.lastIndexOf(true)
            endLine = if (last == n - 1) last else last + 1
        }
    }

    /** Where B sits in the box for a drop on the line: it enters [nB] of its bars before the drop, [q] of its bars to each of A's. */
    internal class Placing(private val nB: Int, private val q: Double, private val sungB: BooleanArray) {

        /** B's entry bar for a drop at [d], and the box bar (from 0) it plays from. */
        fun place(d: Int): Pair<Int, Int> {
            var e = d - nB
            if (e >= 0) return Pair(e, 0)
            e = 0
            var off = 8 - d / q
            if (abs(off - Math.rint(off)) > 1e-6) {
                // B at half tempo with an odd drop bar: start a bar in
                e = 1
                off = 8 - (d - 1) / q
            }
            return Pair(e, Py.round(off))
        }

        /** B's bars under box bar [kk] (from 0); none before B plays. */
        fun bBars(e: Int, off: Int, kk: Int): List<Int> {
            if (kk < off) return emptyList()
            val s = e + (kk - off) * q
            val out = (floor(s + 1e-9).toInt() until ceil(s + q - 1e-9).toInt()).filter { it >= 0 && it < sungB.size }
            return out.ifEmpty { listOf(min(sungB.size - 1, s.toInt())) }
        }

        /** The box bars where both sing for A's line [l] and B's drop [d]: before the box's last bar, and on it. */
        fun bothSing(sungA: BooleanArray, l: Int, d: Int): Pair<Int, Int> {
            val (e, off) = place(d)
            var early = 0
            var last = 0
            for (kk in 0 until 8) {
                if (kk < off) continue
                if (sungA[l - 8 + kk] && bBars(e, off, kk).any { sungB[it] }) {
                    if (kk < 7) early++ else last++
                }
            }
            return Pair(early, last)
        }
    }

    private class BOption(val drop: Int, val score: Double, val c: DropCandidate?)

    private class Combo(
        val score: Double, val b: BOption, val a: ExitCandidate, val early: Int, val last: Int,
        val gridA: GridCheck, val gridB: GridCheck, val voicesOk: Boolean,
    )
}

/** What a bar's voice part does: a sung line, a looped hook or riff, or nothing. */
enum class BarVoice(val label: String) {
    SUNG("sung"), RIFF("riff"), NONE("-");

    companion object {
        fun of(label: String?): BarVoice = entries.firstOrNull { it.label == label } ?: NONE
    }
}

/** Beat This!'s grid laid on one tempo, as Mixxx does: beat k at [t0] + k * 60 / [bpm] s, bar j on beat 4j + [phase]. */
data class ReelsGrid(val bpm: Double, val t0: Double, val phase: Int)

/** One bar: where it starts (s), its level and its level under 150 Hz (dB), and its voice part. */
data class ReelsBar(val t: Double, val level: Double, val low150: Double, val voice: BarVoice)

/**
 * One bar of the local pulse check ([ReelsPulse]): how far the onsets on its beats stand above the
 * bar's onsets in general (a z-score, about 0 with no beat), and where they sit against where the
 * grid sits elsewhere in the song, ms (null where no beat could be read).
 */
data class PulseBar(val pulse: Double, val lagRelMs: Double?)

/** The local pulse check of a song, and how well the grid's bar 1 agrees with its own downbeats over the song (null where not known). */
data class PulseCheck(val barAgreement: Double?, val bars: List<PulseBar>)

/** A whole song as the reels' way reads it. [lufs] is its integrated loudness (EBU R128), null where not known. */
data class ReelsSong(
    val id: String,
    val grid: ReelsGrid,
    val duration: Double,
    val bars: List<ReelsBar>,
    /** Section lines, bar numbers counted the way a DJ counts (every 8 bars from each strong change). */
    val lines: List<Int>,
    /** The strongest changes, bar numbers. */
    val peaks: List<Int>,
    val pulse: PulseCheck,
    val lufs: Double? = null,
)

/**
 * The choices left open in the reels' move. The defaults are the frozen round 3, which the tests hold.
 */
data class ReelsRules(
    /** Two voices may share at most one bar before the box's last. */
    val voiceGate: Boolean = true,
    /** B enters no earlier than this box bar (from 0), its drop still on the line; null as decided. */
    val leadOffsetBars: Int? = null,
    /** The echo over A's last bars, on or off; null as decided (only where A's line is its own end). */
    val echo: Boolean? = null,
    /**
     * Picked in the listening test of 6 Nov 2026 (h2), off by default: in the reels' move, where B
     * plays at full volume under A (a beat or an intro, not a build) and the basses swap, and B's
     * lead-in as heard in the mix sits [BURIED_DB] or more under A's box, B enters on box bar 4, one
     * bar before the swap, and A is cut dead on its line with no echo. Tuned on one pair.
     */
    val lateEntryWhenBuried: Boolean = false,
) {
    companion object {
        const val BURIED_DB = -6.0
        const val LATE_LEAD = 3
    }
}

enum class ReelsMove(val label: String) { REELS("reels"), CUT("cut"), ECHO("echo"), SWEEP("sweep"), FADE("fade") }

/** How B's lead-in plays under A: at full volume with its bass killed, with its highs opening too, or rising from quiet. */
enum class LeadShape(val label: String) { BEAT("beat"), INTRO("intro"), BUILD("build") }

/** Whether the grid holds over bars [from] to [to], and the numbers that said so (rounded as logged). */
data class GridCheck(
    val ok: Boolean,
    val share: Double,
    val medAbsLagMs: Double?,
    val spreadMs: Double?,
    val barAgreement: Double?,
    val from: Int,
    val to: Int,
)

/** A sure drop of B: its bar, how far its low band rises over the bar before, where that bar sat and the 4 bars from it sit against full (dB). */
data class DropCandidate(val bar: Int, val rise: Double, val before: Double, val sustain: Double)

/** One of A's exit lines and its points. */
data class ExitCandidate(
    val bar: Int,
    val t: Double,
    val lift: Double,
    val isLift: Boolean,
    /** The box's low band against full, dB. */
    val boxLow: Double,
    val thin: Boolean,
    val end: Boolean,
    val leftS: Double,
    val score: Double,
    /** The reels' move: the bars where both sing at the chosen drop, before the box's last bar and on it. */
    val bothSungEarly: Int? = null,
    val bothSungLast: Int? = null,
    val bothSung: Int? = null,
    /** The fallbacks: A's grid around this line. */
    val grid: GridCheck? = null,
)

/** The best placement weighed for the reels' move, fitting or not. */
data class BestPlacement(val exitBar: Int, val dropBar: Int, val gridA: GridCheck, val gridB: GridCheck, val bothEarly: Int, val bothLast: Int)

/** Whether the pair fits the reels' move, and why not. */
data class ReelsFit(
    val tempo: Boolean,
    val tempoOffPct: Double,
    val octave: Double,
    val entry: Boolean,
    val anyPlacement: Boolean,
    val voiceGate: Boolean,
    val best: BestPlacement?,
    val why: List<String>?,
)

/** The two songs' grids as the decision read them. */
class Head(songA: ReelsSong, songB: ReelsSong) {
    val a = songA.id
    val b = songB.id
    val aBpm = songA.grid.bpm
    val bBpm = songB.grid.bpm
    val aT0 = songA.grid.t0
    val aPhase = songA.grid.phase
    val bT0 = songB.grid.t0
    val bPhase = songB.grid.phase
}

/**
 * The change Auto chose. Times are each song's own, s. In the reels' move, A's box is its bars
 * [boxStartBarA] to [exitBar] - 1 and B plays from [entryBarB] on the box's bar [leadOffsetBars] + 1,
 * so that its [dropBar] lands on A's line; in the others, A stops on its line [exitBar] (for a fade,
 * its natural end) and B plays from [entryBarB] at its own tempo.
 */
class ReelsDecision(
    val head: Head,
    val fit: ReelsFit,
    val move: ReelsMove,
    val exitBar: Int,
    val exitT: Double,
    val entryBarB: Int,
    val entryT: Double,
    /** A's tempo over B's (counted at [octave]); B is stretched by it through the box when [stretch]. */
    val ratio: Double,
    val stretch: Boolean,
    val octave: Double,
    val aEndLine: Int,
    val aCandidates: List<ExitCandidate>,
    val boxStartBarA: Int? = null,
    val boxStartT: Double? = null,
    val dropBar: Int? = null,
    val dropSure: Boolean? = null,
    val dropT: Double? = null,
    val leadOffsetBars: Int? = null,
    val shape: LeadShape? = null,
    val swap: Boolean? = null,
    val swapBoxBar: Int? = null,
    val echo: Boolean? = null,
    val dLow: Double? = null,
    val dLevel: Double? = null,
    val bCandidates: List<DropCandidate>? = null,
    val aSungBoxBars: List<Int>? = null,
    val bSungBoxBars: List<Int>? = null,
    val rule7: Boolean? = null,
    /** How B's lead-in sits under A's box as heard (dB), where the late-entry rule weighed it. */
    val heardDiffDb: Double? = null,
    /** The late-entry rule moved B's entry and took the echo off. */
    val lateEntry: Boolean = false,
    val fadeS: Double? = null,
    val firstDownbeatB: Int? = null,
    val fullPhraseB: Int? = null,
    val bGrid: GridCheck? = null,
    val tempoRatioIfMatched: Double? = null,
    val aSingsLast: Boolean? = null,
    val bSingsStart: Boolean? = null,
    val aFullBass: Boolean? = null,
    val bFullBass: Boolean? = null,
    /** Why, in a few lines, for the logs. */
    val why: List<String>,
) {
    /** The decision in the prototype's JSON form (decisions/N-A-B.json, without its titles and texts), for logs and the tests. */
    fun toJson(): JsonObject = buildJsonObject {
        put("a", head.a)
        put("b", head.b)
        put("aBpm", head.aBpm)
        put("bBpm", head.bBpm)
        put("aT0", head.aT0)
        put("aPhase", head.aPhase)
        put("bT0", head.bT0)
        put("bPhase", head.bPhase)
        put("fit", fitJson(fit))
        put("move", move.label)
        put("exitBar", exitBar)
        put("exitT", exitT)
        put("entryBarB", entryBarB)
        put("entryT", entryT)
        put("ratio", ratio)
        put("stretch", stretch)
        put("octave", octave)
        put("aEndLine", aEndLine)
        put("aCandidates", JsonArray(aCandidates.map { candidateJson(it) }))
        if (move == ReelsMove.REELS) {
            put("boxStartBarA", boxStartBarA)
            put("boxStartT", boxStartT)
            put("dropBar", dropBar)
            put("dropSure", dropSure)
            put("dropT", dropT)
            put("leadOffsetBars", leadOffsetBars)
            put("shape", shape?.label)
            put("swap", swap)
            put("swapBoxBar", swapBoxBar)
            put("echo", echo)
            put("dLow", dLow)
            put("dLevel", dLevel)
            put("bCandidates", JsonArray(bCandidates.orEmpty().map { dropJson(it) }))
            put("voices", buildJsonObject {
                put("aSungBoxBars", JsonArray(aSungBoxBars.orEmpty().map { JsonPrimitive(it) }))
                put("bSungBoxBars", JsonArray(bSungBoxBars.orEmpty().map { JsonPrimitive(it) }))
                put("rule7", rule7)
            })
            heardDiffDb?.let { put("heardDiffDb", it) }
            if (lateEntry) put("lateEntry", true)
        } else {
            put("firstDownbeatB", firstDownbeatB)
            put("fullPhraseB", fullPhraseB)
            put("bGridOk", bGrid?.ok)
            put("bGridInfo", bGrid?.let { gridJson(it) } ?: JsonNull)
            put("tempoRatioIfMatched", tempoRatioIfMatched)
            if (move == ReelsMove.FADE) {
                put("fadeS", fadeS)
            } else {
                put("aSingsLast", aSingsLast)
                put("bSingsStart", bSingsStart)
                put("aFullBass", aFullBass)
                put("bFullBass", bFullBass)
            }
        }
    }

    private companion object {
        fun gridJson(g: GridCheck): JsonObject = buildJsonObject {
            put("share", g.share)
            put("medAbsLagMs", g.medAbsLagMs)
            put("spreadMs", g.spreadMs)
            if (g.medAbsLagMs != null) put("barAgreement", g.barAgreement)
            put("bars", JsonArray(listOf(JsonPrimitive(g.from), JsonPrimitive(g.to))))
        }

        fun fitJson(f: ReelsFit): JsonObject = buildJsonObject {
            put("tempo", f.tempo)
            put("tempoOffPct", f.tempoOffPct)
            put("octave", f.octave)
            put("entry", f.entry)
            put("anyPlacement", f.anyPlacement)
            put("voiceGate", f.voiceGate)
            put("best", f.best?.let { bp ->
                buildJsonObject {
                    put("A", bp.exitBar)
                    put("D", bp.dropBar)
                    put("gridA", bp.gridA.ok)
                    put("gridAInfo", gridJson(bp.gridA))
                    put("gridB", bp.gridB.ok)
                    put("gridBInfo", gridJson(bp.gridB))
                    put("bothEarly", bp.bothEarly)
                    put("bothLast", bp.bothLast)
                }
            } ?: JsonNull)
            f.why?.let { put("why", JsonArray(it.map { w -> JsonPrimitive(w) })) }
        }

        fun candidateJson(c: ExitCandidate): JsonObject = buildJsonObject {
            put("L", c.bar)
            put("t", c.t)
            put("lift", c.lift)
            put("isLift", c.isLift)
            put("boxLow", c.boxLow)
            put("thin", c.thin)
            put("end", c.end)
            put("leftS", c.leftS)
            put("score", c.score)
            c.bothSungEarly?.let { put("bothSungEarly", it) }
            c.bothSungLast?.let { put("bothSungLast", it) }
            c.bothSung?.let { put("bothSung", it) }
            c.grid?.let {
                put("gridOk", it.ok)
                put("gridInfo", gridJson(it))
            }
        }

        fun dropJson(d: DropCandidate): JsonObject = buildJsonObject {
            put("bar", d.bar)
            put("rise", d.rise)
            put("before", d.before)
            put("sustain", d.sustain)
        }
    }
}

/**
 * Python's and NumPy's arithmetic where Kotlin's differs, so the port decides exactly as the
 * prototype did. The quirks it keeps: Python's round() goes half to even (Kotlin's roundToInt goes
 * half up); round(x, n) rounds x's exact binary value in decimal; np.median of an even count is
 * (a + b) / 2 of the two middle values and NaN for none; np.percentile is linear between the two
 * nearest ranks with NumPy's two-sided lerp; Python's sort is stable and its < puts -0.0 and 0.0
 * level (Kotlin's sortedBy on a boxed Double would not).
 */
internal object Py {

    /** round(x): to the nearest whole number, half to even. */
    fun round(x: Double): Int = Math.rint(x).toInt()

    /** round(x, digits), as Python rounds a float: correctly rounded in decimal, half to even. */
    fun round(x: Double, digits: Int): Double {
        if (!x.isFinite()) return x
        return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString().toDouble()
    }

    /** np.median(v). */
    fun median(v: DoubleArray): Double {
        val n = v.size
        if (n == 0) return Double.NaN
        val s = v.copyOf()
        s.sort()
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** np.median(v[from:to]), with Python's slice: a negative bound counts from the end, both are clipped. */
    fun median(v: DoubleArray, from: Int, to: Int): Double {
        val n = v.size
        val a = (if (from < 0) from + n else from).coerceIn(0, n)
        val b = (if (to < 0) to + n else to).coerceIn(0, n)
        return if (b <= a) Double.NaN else median(v.copyOfRange(a, b))
    }

    /** np.percentile(v, q), the default "linear" method. */
    fun percentile(v: DoubleArray, q: Double): Double {
        val n = v.size
        if (n == 0) return Double.NaN
        val s = v.copyOf()
        s.sort()
        val vi = (n - 1) * (q / 100.0)
        if (vi >= n - 1) return s[n - 1]
        if (vi < 0) return s[0]
        val lo = floor(vi).toInt()
        val t = vi - lo
        val a = s[lo]
        val b = s[lo + 1]
        val d = b - a
        return if (t >= 0.5) b - d * (1 - t) else a + d * t
    }

    /** sorted(items, key=...) for a Double key: stable, ordered by the primitive <, as Python's sort. */
    fun <T> sortedByKey(items: List<T>, key: (T) -> Double): List<T> =
        items.sortedWith { x, y ->
            val kx = key(x)
            val ky = key(y)
            if (kx < ky) -1 else if (kx > ky) 1 else 0
        }
}
