/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Key
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneCurve
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.aGoneAt
import com.dd3boh.outertune.mix.edit.laneAt
import com.dd3boh.outertune.mix.edit.unheardMs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Why a drag or a length stopped where it did: the now line says it for 3 s. */
sealed interface Limit {
    /** "Only the last 1:12 of Wake Me Up can be used." */
    data class AReach(val reachMs: Double) : Limit

    /** "Wake Me Up ends at 5:08, before its last move." */
    data class AEnds(val durationMs: Double) : Limit

    /** "After Hours always starts at its first second." */
    data object BFirstSecond : Limit

    /** "After Hours' opening is read up to 1:00." */
    data class BReach(val reachMs: Double) : Limit

    /** "16 bars needs 0:52 of Wake Me Up's end; only 1:12 can be used." */
    data class ANeeds(val neededMs: Double, val reachMs: Double) : Limit
}

/** One line of the length sheet. */
data class LengthOption(
    val bars: Int,
    val seconds: Double,
    /** The change's length in ms. */
    val ms: Double,
    val lit: Boolean,
    /** Where bar 1 falls in A at this length, the change keeping its end. */
    val aAtMs: Double,
    /** What of A's end is never heard at this length. */
    val unheardMs: Double,
    /** Why it cannot be had; null when it can. */
    val refused: Limit?,
)

/** What happened to a tap on a preset chip. */
enum class PresetTap {
    DONE,
    NOTHING,

    /** Custom moves were replaced: "Custom moves replaced by Fade." with Undo. */
    REPLACED_CUSTOM,

    /** A parts preset on a phone that has only bands. */
    NEEDS_PARTS,
}

/** What Save does: forget the pair (Auto unchanged follows Auto as it improves) or keep a spec. */
sealed interface SaveAction {
    val fixes: Map<String, BeatFix>

    data class Forget(val pair: SongPair, override val fixes: Map<String, BeatFix>) : SaveAction
    data class Keep(val spec: TransitionSpec, override val fixes: Map<String, BeatFix>) : SaveAction
}

/** One moment of a change, for the now line and the moments sheet. [at] is in bars of the change. */
data class Moment(val at: Double, val priority: Int, val kind: Kind, val lane: Lane? = null, val how: How? = null) {
    enum class Kind {
        /** Both songs' lane change over on one beat: "Bass swap". */
        SWAP,
        ENTRY,
        EXIT,
        MUTED_A,
        MUTED_B,
        CURVE_A,
        CURVE_B,

        /** B plays from its start under A: "After Hours starts under Wake Me Up". */
        STARTS_UNDER,
    }
}

/**
 * The editor's state machine, pure: the spec on the page, Auto's, the one saved, undo and redo,
 * and every edit the page can make. The screen draws [spec] and calls these; a drag goes through
 * [live] while the finger moves and [commit] when it is lifted, so a gesture is one undo step.
 *
 * [auto] is the planner's pick written as a spec ([AutoSpec]); [saved] what the store holds for
 * the pair, if anything. The page opens on the saved spec, or Auto.
 */
class MixEditor(
    val facts: PairFacts,
    auto: TransitionSpec,
    saved: TransitionSpec?,
    /** The undo steps kept from before the process was ended, oldest first. */
    restoredUndo: List<TransitionSpec> = emptyList(),
    restoredSpec: TransitionSpec? = null,
) {
    /**
     * Auto's pick as the page takes it: made by Auto, whatever move wrote its moves. The phone's
     * own pick names its move there (a Fade's moves are Fade's), and a page that took it at its
     * word lit Fade for Auto's own change, so Back to Auto did nothing and Save could never forget
     * the pair. A preset tapped on purpose names itself and stays lit, the same moves or not.
     */
    val auto: TransitionSpec = auto.copy(from = Preset.AUTO)

    /** What Save compares the page with, its preset worked out as the page works it out. */
    val opened: TransitionSpec = normalised(saved ?: auto)

    var spec: TransitionSpec = restoredSpec?.let(::normalised) ?: opened
        private set

    private val undoStack = ArrayDeque(restoredUndo.takeLast(MAX_UNDO))
    private val redoStack = ArrayDeque<TransitionSpec>()

    /** The last custom spec, for the Custom chip to bring back after a preset was tapped over it. */
    var lastCustom: TransitionSpec? = spec.takeIf { presetOf(it) == Preset.CUSTOM && !it.plain }
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The undo steps, oldest first, for keeping across the process's end. */
    val undoSteps: List<TransitionSpec> get() = undoStack.toList()

    /** Whether Save has anything to do. */
    val dirty: Boolean get() = spec != opened || fixesChanged().isNotEmpty()

    // ---- the clock ----

    /** A's ms for one bar of the change. */
    fun perA(s: TransitionSpec = spec): Double = if (s.beatMatched) s.b.barMs * s.tempoRatio() else s.b.barMs

    val secondsMode: Boolean get() = spec.bars == 0

    /** Where the canvas starts and ends, in bars of the change: 2 bars either side, or 4 s. */
    fun fitStart(s: TransitionSpec = spec): Double =
        if (s.bars == 0) -SECONDS_AROUND_MS / s.b.barMs else -2.0 - max(0, s.introTwice - 2)

    fun fitEnd(s: TransitionSpec = spec): Double =
        s.lengthBars + if (s.bars == 0) SECONDS_AROUND_MS / s.b.barMs else 2.0

    /** Where B's first frame falls, in bars of the change. */
    fun bStartU(s: TransitionSpec = spec): Double = (s.bStartMs - s.b.atMs) / s.b.barMs

    /** Where A's end falls, in bars of the change. */
    fun aEndU(s: TransitionSpec = spec): Double = (facts.a.durationMs - s.a.atMs) / perA(s)

    /** The wall: A cannot be heard before this, in bars of the change. */
    fun aWallU(s: TransitionSpec = spec): Double = (facts.aReachStartMs - s.a.atMs) / perA(s)

    /** The wall: B is not read past this. */
    fun bWallU(s: TransitionSpec = spec): Double = (facts.bReachEndMs - s.b.atMs) / s.b.barMs

    /** One beat of B in bars, or half a second where the change is timed in seconds. */
    fun step(s: TransitionSpec = spec): Double = if (s.bars == 0) HALF_SECOND_MS / s.b.barMs else s.beat

    /** Each song's grid as it is now, its fix on it. */
    fun gridA(s: TransitionSpec = spec): SongGrid? = facts.a.gridFor(s.a.fix)
    fun gridB(s: TransitionSpec = spec): SongGrid? = facts.b.gridFor(s.b.fix)

    /** What of A's end is never heard. */
    fun unheardMs(s: TransitionSpec = spec): Double = s.unheardMs(facts.a.durationMs)

    // ---- which chip is lit ----

    /** The preset whose rule writes exactly these moves, Custom when none does; the plain flag aside. */
    fun presetOf(s: TransitionSpec): Preset {
        if (s.curves.isNotEmpty() || s.muted.isNotEmpty()) return Preset.CUSTOM
        val found = EditorPresets.ORDER.filter { p ->
            when {
                p == Preset.CUSTOM -> false
                p == Preset.AUTO -> sameAsAuto(s)
                p.needsParts && s.lanes != LaneSet.PARTS -> false
                else -> EditorPresets.moves(p, s.lengthBars, s.lanes, s.beat)?.let { EditorPresets.sameMoves(s.moves, it) } == true
            }
        }
        if (s.from in found) return s.from
        return found.firstOrNull { it != Preset.AUTO } ?: found.firstOrNull() ?: Preset.CUSTOM
    }

    /** The chip lit for [s]: none for a plain change of the listener's, Auto for Auto's own plain one. */
    fun lit(s: TransitionSpec = spec): Preset? = when {
        s.plain -> if (auto.plain && sameAsAuto(s)) Preset.AUTO else null
        else -> presetOf(s)
    }

    /** Whether [s] is Auto's spec as Auto wrote it: the same anchors, length, moves and switches. */
    fun sameAsAuto(s: TransitionSpec): Boolean =
        s.plain == auto.plain && s.bars == auto.bars && near(s.seconds, auto.seconds, 1e-3) &&
            near(s.a.atMs, auto.a.atMs, 0.5) && near(s.b.atMs, auto.b.atMs, 0.5) &&
            s.beatMatch == auto.beatMatch && s.introTwice == auto.introTwice &&
            s.curves.isEmpty() && s.muted.isEmpty() && s.a.fix == auto.a.fix && s.b.fix == auto.b.fix &&
            EditorPresets.sameMoves(s.moves, auto.moves)

    /** The preset Auto's moves are written by, for writing them again at another length. */
    private val autoRule: Preset = EditorPresets.ORDER.firstOrNull { p ->
        p != Preset.AUTO && p != Preset.CUSTOM && !p.needsParts &&
            EditorPresets.moves(p, auto.lengthBars, auto.lanes, auto.beat)?.let { EditorPresets.sameMoves(auto.moves, it) } == true
    } ?: Preset.FADE

    private fun normalised(s: TransitionSpec): TransitionSpec {
        val preset = presetOf(s)
        return if (s.preset == preset) s else s.copy(preset = preset)
    }

    // ---- undo ----

    /** The page while a finger moves: drawn and heard, not yet an undo step. */
    fun live(next: TransitionSpec) {
        spec = normalised(next)
    }

    /** Ends a gesture begun at [before]: one undo step if anything changed. */
    fun commit(before: TransitionSpec): Boolean {
        if (before == spec) return false
        undoStack.addLast(before)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        if (lit() == Preset.CUSTOM) lastCustom = spec
        return true
    }

    /** One finished edit. */
    fun apply(next: TransitionSpec): Boolean {
        val before = spec
        spec = normalised(next)
        return commit(before)
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(spec)
        spec = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(spec)
        spec = next
        return true
    }

    // ---- presets, Auto, plain ----

    fun tapPreset(p: Preset): PresetTap {
        if (p.needsParts && spec.lanes != LaneSet.PARTS) return PresetTap.NEEDS_PARTS
        val current = lit()
        if (p == Preset.CUSTOM) {
            val custom = lastCustom
            if (current == Preset.CUSTOM || custom == null) return PresetTap.NOTHING
            apply(custom)
            return PresetTap.DONE
        }
        if (current == p) return PresetTap.NOTHING
        val wasCustom = current == Preset.CUSTOM
        if (wasCustom) lastCustom = spec
        val next = if (p == Preset.AUTO) auto else written(spec, p, spec.lengthBars).copy(plain = false, from = p)
        apply(next)
        return if (wasCustom) PresetTap.REPLACED_CUSTOM else PresetTap.DONE
    }

    /** [s] with the moves [p] writes at [length] bars, and nothing of a curve or a mute left. */
    private fun written(s: TransitionSpec, p: Preset, length: Double): TransitionSpec {
        val rule = if (p == Preset.AUTO) autoRule else p
        val moves = EditorPresets.moves(rule, length, s.lanes, s.beat) ?: return s
        return s.copy(moves = moves, curves = emptyMap(), muted = emptySet())
    }

    fun backToAuto(): Boolean = if (lit() == Preset.AUTO) false else apply(auto)

    fun plainChange(): Boolean = if (spec.plain) false else apply(spec.copy(plain = true))

    // ---- length ----

    /** The lengths offered, in bars, then in seconds when the beats are not matched. */
    fun lengthOptions(): List<LengthOption> {
        val bars = if (facts.barsMode) TransitionSpec.LENGTHS_BARS.map { lengthOption(it, 0.0) } else emptyList()
        val seconds = if (!spec.beatMatched || !facts.barsMode) TransitionSpec.LENGTHS_SECONDS.map { lengthOption(0, it) } else emptyList()
        return bars + seconds
    }

    private fun lengthOption(bars: Int, seconds: Double): LengthOption {
        val s = withLength(spec, bars, seconds)
        val lit = if (bars > 0) spec.bars == bars else spec.bars == 0 && near(spec.seconds, seconds, 1e-3)
        return LengthOption(bars, seconds, s.lengthMs, lit, s.a.atMs, unheardMs(s), reachOf(s))
    }

    /** Why [s] cannot be had at all, or null. */
    fun reachOf(s: TransitionSpec): Limit? {
        val aFirst = s.aMsAt(fitStart(s))
        if (aFirst < facts.aReachStartMs - 0.5) return Limit.ANeeds(facts.a.durationMs - aFirst, facts.aReachMs)
        if (s.bMsAt(fitEnd(s)) > facts.bReachEndMs + 0.5) return Limit.BReach(facts.bReachEndMs)
        return null
    }

    /** Sets the length; the change keeps its end in A, and a preset is written again at the new length. */
    fun setLength(bars: Int, seconds: Double = 0.0): Boolean {
        val next = withLength(spec, bars, seconds)
        if (reachOf(next) != null) return false
        return apply(next)
    }

    private fun withLength(s: TransitionSpec, bars: Int, seconds: Double): TransitionSpec {
        val oldL = s.lengthBars
        val endA = s.aMsAt(oldL)
        var next = s.copy(bars = bars, seconds = if (bars > 0) 0.0 else seconds)
        val newL = next.lengthBars
        // The change keeps where it ends in A: bar 1 moves back by what was added.
        var aAt = endA - newL * perA(next)
        val grid = gridA(next)
        if (grid != null && bars > 0) aAt = grid.beatMs(grid.downbeatNear(aAt))
        next = next.copy(a = next.a.copy(atMs = max(0.0, aAt)))
        val current = lit(s)
        next = if (current != null && current != Preset.CUSTOM && !s.plain) {
            written(next, current, newL).copy(from = current)
        } else {
            scaled(next, newL / oldL)
        }
        return next
    }

    /** A custom spec's moves at the same share of a change [r] times as long, snapped to beats. */
    private fun scaled(s: TransitionSpec, r: Double): TransitionSpec {
        val st = step(s)
        fun snap(u: Double) = (u / st).roundToInt() * st
        val moves = s.moves.mapValues { (_, m) ->
            if (m.how == How.FROM_START || m.how == How.KEEP) m
            else Move(snap(m.u * r), if (m.len > 0) max(st, snap(m.len * r)) else 0.0, m.how)
        }
        val curves = s.curves.mapValues { (_, c) ->
            LaneCurve(c.gain.map { Key(snap(it.u * r), it.value) }, c.lowPassHz.map { Key(snap(it.u * r), it.value) }, c.highPassHz.map { Key(snap(it.u * r), it.value) })
        }
        return s.copy(moves = moves, curves = curves)
    }

    // ---- switches ----

    /**
     * Whether A can be locked to B's beat at all: it needs its own beats found. Without them its
     * anchor carries a stand-in tempo, which the spec's own rule would happily match.
     */
    val beatMatchPossible: Boolean get() = facts.a.grid != null && facts.barsMode

    fun setBeatMatch(on: Boolean): Boolean {
        if (on && (!spec.beatMatchAllowed() || !beatMatchPossible)) return false
        return apply(spec.copy(beatMatch = on))
    }

    fun setIntroTwice(bars: Int): Boolean {
        if (bars !in TransitionSpec.INTRO_TWICE_BARS) return false
        return apply(spec.copy(introTwice = bars))
    }

    // ---- anchors ----

    /**
     * The spec with [side]'s song moved under bar 1 by a finger's [deltaMs] of that song (right is
     * positive: an earlier moment comes to bar 1), snapped to its downbeats, or its beats when
     * [byBeats], and kept inside what may be heard. Starts from [from], the spec the drag began on.
     */
    fun anchorMoved(from: TransitionSpec, side: Side, deltaMs: Double, byBeats: Boolean): Pair<TransitionSpec, Limit?> {
        if (side == Side.B) {
            if (!facts.laterStartAllowed) return from to (if (abs(deltaMs) > from.b.barMs / 2) Limit.BFirstSecond else null)
            return bMoved(from, deltaMs, byBeats)
        }
        val per = perA(from)
        val earliest = facts.aReachStartMs - fitStart(from) * per
        val latest = facts.a.durationMs - from.aGoneAt() * per
        val wanted = from.a.atMs - deltaMs
        val grid = gridA(from)
        val snapped = when {
            grid != null -> grid.beatMs(if (byBeats) grid.beatNear(wanted) else grid.downbeatNear(wanted))
            else -> (wanted / HALF_SECOND_MS).roundToInt() * HALF_SECOND_MS
        }
        val stepMs = grid?.let { if (byBeats) it.periodMs else it.barMs } ?: HALF_SECOND_MS
        var at = snapped
        var limit: Limit? = null
        if (at < earliest - 0.5) {
            limit = Limit.AReach(facts.aReachMs)
            while (at < earliest - 0.5) at += stepMs
        }
        if (at > latest + 0.5) {
            limit = Limit.AEnds(facts.a.durationMs)
            while (at > latest + 0.5 && at - stepMs >= earliest - 0.5) at -= stepMs
        }
        return from.copy(a = from.a.copy(atMs = max(0.0, at))) to limit
    }

    private fun bMoved(from: TransitionSpec, deltaMs: Double, byBeats: Boolean): Pair<TransitionSpec, Limit?> {
        val grid = gridB(from)
        val wanted = from.b.atMs - deltaMs
        val at = grid?.let { it.beatMs(if (byBeats) it.beatNear(wanted) else it.downbeatNear(wanted)) } ?: wanted
        val first = grid?.let { g -> generateSequence(g.downbeatNear(facts.b.firstSoundMs)) { it + g.beatsPerBar }.map(g::beatMs).first { it >= 0 } } ?: 0.0
        if (at < first) return from.copy(b = from.b.copy(atMs = first), bStartMs = 0.0) to Limit.BFirstSecond
        val bEndU = from.lengthBars + 2
        if (at + bEndU * from.b.barMs > facts.bReachEndMs) return from to Limit.BReach(facts.bReachEndMs)
        return from.copy(b = from.b.copy(atMs = at), bStartMs = if (at > first) max(0.0, at - 2 * from.b.barMs) else 0.0) to null
    }

    // ---- handles ----

    /** A position snapped as a handle snaps: beats, with bar lines sticky within a third of a beat; half seconds in a timed change. */
    fun snap(u: Double, s: TransitionSpec = spec): Double {
        if (s.bars == 0) {
            val st = HALF_SECOND_MS / s.b.barMs
            return (u / st).roundToInt() * st
        }
        val bar = Math.round(u).toDouble()
        if (abs(u - bar) < s.beat / 3) return bar
        return (u / s.beat).roundToInt() * s.beat
    }

    /** [id]'s move with its start at [u], snapped and kept inside the canvas and, for B, after B's start. */
    fun handleMoved(from: TransitionSpec, id: LaneId, u: Double): TransitionSpec {
        val m = from.moves[id] ?: return from
        var move = m
        if (m.how == How.KEEP || m.how == How.FROM_START) move = Move(m.u, if (m.len <= 0) 1.0 else m.len, How.FADE)
        val lo = if (id.side == Side.A) fitStart(from) else max(fitStart(from), ceil(bStartU(from) / from.beat) * from.beat)
        val hi = min(from.lengthBars, fitEnd(from) - move.len)
        val at = snap(u, from).coerceIn(lo, max(lo, hi))
        return from.copy(moves = from.moves + (id to move.copy(u = at)))
    }

    /** At the finger's lift: a handle within a beat of the other song's on the same lane snaps into a swap. */
    fun handleReleased(id: LaneId): Boolean {
        val other = LaneId(if (id.side == Side.A) Side.B else Side.A, id.lane)
        val m = spec.moves[id] ?: return false
        val o = spec.moves[other] ?: return false
        if (other in spec.curves || o.how in FIXED || m.how == How.KEEP || m.how == How.FROM_START) return false
        val gap = abs(m.u - o.u)
        if (gap <= spec.beat + 1e-9 && gap > 1e-9) {
            live(spec.copy(moves = spec.moves + (id to m.copy(u = o.u))))
            return true
        }
        return false
    }

    /** The ramp lengths a handle's end steps through: 1 beat, 2 beats, 1, 2 and 4 bars, the whole change. */
    fun lengthSteps(s: TransitionSpec = spec): List<Double> {
        val l = s.lengthBars
        val b = s.beat
        return listOf(b, 2 * b, 1.0, 2.0, 4.0, l).filter { it <= l + 1e-9 }.distinct().sorted()
    }

    /** [id]'s ramp as long as the step nearest [len], not past the canvas. */
    fun rampMoved(from: TransitionSpec, id: LaneId, len: Double): TransitionSpec {
        val m = from.moves[id] ?: return from
        if (m.how in FIXED) return from
        val steps = lengthSteps(from).filter { m.u + it <= fitEnd(from) + 1e-9 }.ifEmpty { listOf(from.beat) }
        val best = steps.minBy { abs(it - len) }
        return from.copy(moves = from.moves + (id to m.copy(len = best)))
    }

    /** One step longer or shorter for every move of [ids], as the how bar's arrows do. */
    fun stepLength(ids: Collection<LaneId>, longer: Boolean): Boolean {
        val steps = lengthSteps()
        var moves = spec.moves
        for (id in ids) {
            val m = moves[id] ?: continue
            if (m.how in FIXED) continue
            var i = steps.indexOfFirst { it >= m.len - 1e-9 }
            if (i < 0) i = steps.size - 1
            if (longer && abs(steps[i] - m.len) > 1e-9) i--
            val len = steps[(i + if (longer) 1 else -1).coerceIn(0, steps.size - 1)]
            moves = moves + (id to m.copy(len = len))
        }
        return apply(spec.copy(moves = moves))
    }

    /** Every move of [ids] made [how]: a cut has no ramp; anything else gets a bar when it had none. */
    fun setHow(ids: Collection<LaneId>, how: How): Boolean {
        var moves = spec.moves
        var curves = spec.curves
        var muted = spec.muted
        for (id in ids) {
            if (!how.fits(id.side)) continue
            val m = moves[id] ?: continue
            val len = when {
                how == How.CUT -> 0.0
                how == How.KEEP || how == How.FROM_START -> m.len
                m.len <= 0 -> 1.0
                else -> m.len
            }
            moves = moves + (id to Move(m.u, len, how))
            curves = curves - id
            muted = muted - id
        }
        return apply(spec.copy(moves = moves, curves = curves, muted = muted))
    }

    /** Every exit of A (or entry of B) moved together by [du] bars from where the drag began, inside the canvas. */
    fun bracketMoved(from: TransitionSpec, side: Side, du: Double): TransitionSpec {
        val ids = bracketIds(from, side).filter { from.moves[it]!!.how !in FIXED_PLACE }
        if (ids.isEmpty()) return from
        var lo = Double.NEGATIVE_INFINITY
        var hi = Double.POSITIVE_INFINITY
        for (id in ids) {
            val m = from.moves[id]!!
            val first = if (side == Side.A) fitStart(from) else max(fitStart(from), bStartU(from))
            lo = max(lo, first - m.u)
            hi = min(hi, min(from.lengthBars, fitEnd(from) - m.len) - m.u)
        }
        val d = snap(du, from).coerceIn(lo, max(lo, hi))
        return from.copy(moves = from.moves + ids.associateWith { from.moves[it]!!.let { m -> m.copy(u = m.u + d) } })
    }

    /** The lanes a song's bracket moves: those with a move of their own, not a curve and not muted. */
    fun bracketIds(s: TransitionSpec, side: Side): List<LaneId> =
        s.lanes.lanes.map { LaneId(side, it) }.filter { it !in s.curves && it !in s.muted }

    // ---- lanes ----

    fun toggleMute(id: LaneId): Boolean = apply(spec.copy(muted = if (id in spec.muted) spec.muted - id else spec.muted + id))

    /** "Keep the whole time": A's lane plays the whole change, B's from its start. */
    fun keepWhole(id: LaneId): Boolean {
        val m = spec.moves[id] ?: return false
        val how = if (id.side == Side.A) How.KEEP else How.FROM_START
        return apply(spec.copy(moves = spec.moves + (id to m.copy(how = how)), curves = spec.curves - id, muted = spec.muted - id))
    }

    /** Adjust starts from a lane's move drawn as three points per ramp. */
    fun curveFromMove(id: LaneId, s: TransitionSpec = spec): LaneCurve {
        s.curves[id]?.let { return it }
        val m = s.moves[id] ?: Move(0.0, 1.0, How.FADE)
        val l = s.lengthBars
        val keys = if (id.side == Side.A) {
            when (m.how) {
                How.KEEP -> listOf(Key(l - 0.5, 1.0), Key(l - 0.25, 1.0), Key(l, 0.0))
                else -> {
                    val len = max(m.len, s.beat)
                    listOf(Key(m.u, 1.0), Key(m.u + len / 2, 0.7), Key(m.u + len, 0.0))
                }
            }
        } else {
            when (m.how) {
                How.FROM_START -> {
                    val at = (bStartU(s) * 4).roundToInt() / 4.0
                    listOf(Key(at, 1.0), Key(at + 0.25, 1.0), Key(at + 0.5, 1.0))
                }
                else -> {
                    val len = max(m.len, s.beat)
                    listOf(Key(m.u, 0.0), Key(m.u + len / 2, 0.7), Key(m.u + len, 1.0))
                }
            }
        }
        return LaneCurve(keys)
    }

    /** [id]'s gain curve set to [keys] (sorted, 0 to 1), unmuted. A live edit: Adjust commits on Done. */
    fun curveSet(from: TransitionSpec, id: LaneId, keys: List<Key>): TransitionSpec {
        val sorted = keys.sortedBy { it.u }.map { Key(it.u, it.value.coerceIn(0.0, 1.0)) }
        val old = from.curves[id]
        return from.copy(curves = from.curves + (id to LaneCurve(sorted, old?.lowPassHz.orEmpty(), old?.highPassHz.orEmpty())), muted = from.muted - id)
    }

    // ---- fix the beat ----

    /**
     * [side]'s song with [fix] on its beat: its anchor goes to the downbeat of the new grid nearest
     * where it was, so the change stays where it was heard. One undo step; kept for the song on Save.
     */
    fun setFix(side: Side, fix: BeatFix): Boolean {
        val songFacts = if (side == Side.A) facts.a else facts.b
        val grid = songFacts.gridFor(fix) ?: return false
        val anchor = if (side == Side.A) spec.a else spec.b
        val at = grid.beatMs(grid.downbeatNear(anchor.atMs))
        val fixed = anchor.copy(atMs = max(0.0, at), bpm = grid.bpm, fix = fix.copy(source = songFacts.source))
        return apply(if (side == Side.A) spec.copy(a = fixed) else spec.copy(b = fixed))
    }

    /** The fixes Save keeps for the songs: each song's, where it differs from what was kept. */
    fun fixesChanged(): Map<String, BeatFix> = buildMap {
        if (spec.a.fix.withoutSource() != facts.a.fix.withoutSource()) put(facts.a.id, spec.a.fix)
        if (spec.b.fix.withoutSource() != facts.b.fix.withoutSource()) put(facts.b.id, spec.b.fix)
    }

    private fun BeatFix.withoutSource() = copy(source = "")

    // ---- save ----

    /** What Save does: Auto unchanged forgets the pair, so it follows Auto as Auto improves. */
    fun saveAction(now: Long): SaveAction {
        val fixes = fixesChanged()
        return if (lit() == Preset.AUTO) SaveAction.Forget(spec.pair, fixes)
        else SaveAction.Keep(normalised(spec).copy(savedAt = now), fixes)
    }

    // ---- words the page builds from the spec ----

    /** The change as moments, in order: what happens on which bar. */
    fun moments(s: TransitionSpec = spec): List<Moment> {
        val out = ArrayList<Moment>()
        var started = false
        for (lane in s.lanes.lanes) {
            val ia = LaneId(Side.A, lane)
            val ib = LaneId(Side.B, lane)
            val a = s.moves[ia] ?: continue
            val b = s.moves[ib] ?: continue
            val aCurve = s.curves[ia]
            val bCurve = s.curves[ib]
            val aMuted = ia in s.muted
            val bMuted = ib in s.muted
            if (aCurve == null && bCurve == null && !aMuted && !bMuted && a.how != How.KEEP && b.how != How.FROM_START) {
                val aEnd = if (a.how == How.CUT) a.u else a.end
                val bFull = if (b.how == How.CUT) b.u else b.end
                if (abs(aEnd - bFull) <= s.beat + 1e-9 && (a.how == How.CUT || a.len <= s.beat + 1e-9)) {
                    out += Moment(bFull, 0, Moment.Kind.SWAP, lane)
                    if (b.how != How.CUT && b.len > s.beat + 1e-9) out += Moment(b.u, 2, Moment.Kind.ENTRY, lane, b.how)
                    continue
                }
            }
            when {
                aMuted -> out += Moment(0.0, 1, Moment.Kind.MUTED_A, lane)
                aCurve != null -> out += Moment(aCurve.gain.firstOrNull()?.u ?: 0.0, 1, Moment.Kind.CURVE_A, lane)
                a.how != How.KEEP -> out += Moment(a.u, 1, Moment.Kind.EXIT, lane, a.how)
            }
            when {
                bMuted -> out += Moment(s.lengthBars, 2, Moment.Kind.MUTED_B, lane)
                bCurve != null -> out += Moment(bCurve.gain.firstOrNull()?.u ?: 0.0, 2, Moment.Kind.CURVE_B, lane)
                b.how == How.FROM_START -> started = true
                else -> out += Moment(b.u, 2, Moment.Kind.ENTRY, lane, b.how)
            }
        }
        if (started) out += Moment(bStartU(s), 3, Moment.Kind.STARTS_UNDER)
        return out.sortedWith(compareBy<Moment> { it.at }.thenBy { it.priority })
    }

    /** The moment that last happened by [u], for the now line while it plays. */
    fun momentAt(u: Double, s: TransitionSpec = spec): Moment? {
        var latest: Moment? = null
        for (m in moments(s)) {
            if (m.at > u + 1e-6 || m.at < fitStart(s) - 1e-6) continue
            val l = latest
            if (l == null || m.at > l.at + 1e-9 || (abs(m.at - l.at) < 1e-9 && m.priority < l.priority)) latest = m
        }
        return latest
    }

    /** How long both songs' lows play at once with music in both, in bars: "Two basslines for 1 bar". */
    fun twoBasslines(s: TransitionSpec = spec): Double {
        if (s.plain) return 0.0
        val low = if (s.lanes == LaneSet.PARTS) Lane.BASS else Lane.LOWS
        val ia = LaneId(Side.A, low)
        val ib = LaneId(Side.B, low)
        var n = 0
        var u = fitStart(s)
        while (u < fitEnd(s)) {
            if (s.laneAt(ia, u).gain > 0.3 && s.laneAt(ib, u).gain > 0.3 &&
                facts.active(Side.A, low, s.aMsAt(u)) && facts.active(Side.B, low, s.bMsAt(u))
            ) n++
            u += 1.0 / 16
        }
        return (n / 4.0).roundToInt() / 4.0
    }

    companion object {
        /** Undo steps kept; DESIGN.md 3.12. */
        const val MAX_UNDO = 50

        /** The undo steps that outlive the process. */
        const val KEPT_UNDO = 20

        const val SECONDS_AROUND_MS = 4_000.0
        const val HALF_SECOND_MS = 500.0

        /** Hows with no ramp to drag. */
        val FIXED = setOf(How.CUT, How.KEEP, How.FROM_START)

        /** Hows with no place of their own: Keep lasts the change, From the start is B's first frame. */
        val FIXED_PLACE = setOf(How.KEEP, How.FROM_START)

        /** A plain anchor where nothing better is known. */
        fun anchor(song: SongFacts, atMs: Double, bpm: Double?): Anchor = Anchor(
            song = song.id,
            atMs = max(0.0, atMs),
            bpm = bpm ?: TransitionSpec.NOMINAL_BPM,
            source = song.source,
            analysis = song.analysis,
            fix = song.fix,
        )
    }
}
