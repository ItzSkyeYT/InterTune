/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.TimeMap
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.goneAt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a change is played from besides its spec: where each song's anchor is in the music at
 * hand, and how much of it there is. The same for the phone, where A is its held end and B the
 * copy of its opening, and for the editor, where both are copies.
 */
class ChangeFacts(
    val sampleRate: Int,
    val channels: Int,
    /** A's anchor, bar 1's downbeat, in frames of A's music at hand: the held end, or the editor's copy of A's end. */
    val aBar1: Double,
    /** How many frames of A there are. */
    val aFrames: Long,
    /** B's anchor in frames of B's copy, whose frame 0 is B's first frame. */
    val bBar1: Double,
    /** How many frames B's copy holds. */
    val bFrames: Long,
    /** The first output frame on which anything may differ from A as it is: what has already gone out, and the time to get the plan in. */
    val earliest: Long = 0,
    /** A's time map, where the planner has worked it out already (Auto); otherwise it is made from the spec. */
    val aMap: TimeMap? = null,
    /** The frames of A's beats the stretch lands exactly, where they are known better than the spec's grid. */
    val anchors: DoubleArray? = null,
)

/**
 * A change as the engine plays it: the spec, and every frame it happens on. Output frames are
 * counted as A's frames are until A's tempo starts to move, so on the phone they are the held
 * end's own; B's frame n is heard at output frame [bStart] + n, and bar position u of the change
 * at [out] (u).
 */
class LanePlan internal constructor(
    val spec: TransitionSpec,
    val facts: ChangeFacts,
    /** Where each output frame takes A from, in frames of A's music. */
    val aMap: TimeMap,
    /** A's beats, in its own frames, for the stretch to land exactly where the map puts them. */
    val anchors: DoubleArray,
    /** Bar 1 of the change, as an output frame: where B's anchor is heard. */
    val bar1: Double,
    /** One of B's bars in output frames. B is never stretched, so the change runs on B's clock. */
    val barFrames: Double,
    /** The output frame of B's first frame. */
    val bStart: Long,
    /** The first output frame rendered: until here A plays as it is. */
    val mixFrom: Long,
    /** The output frame from which B plays as it is and nothing of A is left. */
    val mixTo: Long,
    /** From this output frame on nothing of A is heard, its echo rung out. */
    val aGone: Long,
    /** From this output frame on every lane of B is at full, unfiltered. */
    val bPlain: Long,
    /** Where both songs can be heard together: only there is the shared headroom worked out. */
    val bothFrom: Long,
    val bothTo: Long,
    /** The echo's delay in output frames, three quarters of B's beat. */
    val echoFrames: Int,
    /** A's speed through the change. */
    val speed: Double,
) {
    val sampleRate: Int get() = facts.sampleRate
    val channels: Int get() = facts.channels

    /** Where the grids the change is drawn on start: bar 1's frame, which is the same music in any copy. */
    val gridOrigin: Long = kotlin.math.floor(bar1).toLong()

    /** Bar position at output frame [o]. */
    fun u(o: Double): Double = (o - bar1) / barFrames

    /** Output frame at bar position [u]. */
    fun out(u: Double): Double = bar1 + u * barFrames

    /** Whether A's lane [id] leaves by an echo, so it needs one. */
    fun echoes(id: LaneId): Boolean = id.side == Side.A && echoingLanes.contains(id)

    internal var echoingLanes: Set<LaneId> = emptySet()

    /** Where the echoes are faded over their last repeat, so the last frame of A rendered is silence. */
    internal var echoFadeFrom: Long = Long.MAX_VALUE

    val seconds: Double get() = (mixTo - mixFrom).toDouble() / sampleRate

    fun describe(): String {
        val tempo = if (speed == 1.0) "A at its own speed" else "A at %.4f".format(speed)
        val name = if (spec.preset == Preset.AUTO) "Auto (${AutoSpec.label(spec)})" else AutoSpec.label(spec.copy(from = spec.preset))
        return "$name on ${spec.lanes.key}, $tempo, B at frame $bStart, A gone at frame $aGone"
    }
}

/** Turns a spec and the music at hand into the [LanePlan] the engine plays, or says why it cannot. */
object SpecPlan {

    sealed class Result {
        class Planned(val plan: LanePlan) : Result()
        class Refused(val why: String) : Result()
    }

    /** A's tempo moves to B's over four of its beats before bar 1, eight when it moves by more than 3%. */
    const val RAMP_BEATS = 4
    const val LONG_RAMP_BEATS = 8
    const val LONG_RAMP_ABOVE = 0.03

    fun plan(spec: TransitionSpec, facts: ChangeFacts): Result {
        if (spec.plain) return Result.Refused("a plain change")
        if (spec.lanes != LaneSet.BANDS) return Result.Refused("made on ${spec.lanes.key}, which this phone does not split into")
        spec.problems().takeIf { it.isNotEmpty() }?.let { return Result.Refused(it.joinToString("; ")) }
        // Neither is played yet: a change that asks for one is not played as something else.
        if (spec.introTwice > 0) return Result.Refused("the intro played twice is not played yet")
        if (spec.bStartMs > 0.0) return Result.Refused("a next song starting after its first frame is not played yet")
        val rate = facts.sampleRate
        val barFrames = spec.b.barMs * rate / 1000.0
        val beatFrames = barFrames * spec.beat
        val speed = if (spec.beatMatched) spec.tempoRatio() else 1.0

        // A's clock: as it is up to the ramp, then at B's tempo from bar 1 on.
        val map: TimeMap
        val aBeatFrames = 60.0 / spec.a.bpm * rate
        if (facts.aMap != null) {
            map = facts.aMap
        } else if (speed != 1.0) {
            val beats = if (abs(speed - 1) > LONG_RAMP_ABOVE) LONG_RAMP_BEATS else RAMP_BEATS
            val rampTail = beats * aBeatFrames
            val rampStart = facts.aBar1 - rampTail
            if (rampStart < 0) return Result.Refused("A's tempo would have to start moving before the music at hand")
            map = TimeMap(rampStart, 2.0 * rampTail / (1.0 + speed), speed)
        } else {
            map = TimeMap.IDENTITY
        }
        val bStart = (map.outAt(facts.aBar1) - facts.bBar1).roundToLong()
        // B's anchor is heard exactly on bar 1; A's lands within half a frame of it.
        val bar1 = bStart + facts.bBar1
        fun out(u: Double) = bar1 + u * barFrames

        val length = spec.lengthBars
        val aLanes = spec.lanes.lanes.map { LaneId(Side.A, it) }
        val bLanes = spec.lanes.lanes.map { LaneId(Side.B, it) }

        // A: from where its first lane moves, or its tempo does, to where its last lane is gone.
        val aFirstU = aLanes.minOf { firstMoveU(spec, it) }
        val echoing = aLanes.filter { id -> spec.curves[id] == null && id !in spec.muted && spec.moves[id]?.how == How.ECHO }.toSet()
        val echoFrames = max(1, (Shapes.ECHO_BEATS * beatFrames).roundToInt())
        val cutFrames = ceil(Shapes.CUT_MS / 1000.0 * rate).toLong()
        val dryGone = ceil(out(aLanes.maxOf { spec.goneAt(it) })).toLong() + cutFrames
        val aGone = if (echoing.isEmpty()) dryGone else {
            val rung = dryGone + Engine.ECHO_REPEATS.toLong() * echoFrames
            min(rung, ceil(out(length + Engine.ECHO_MAX_BARS_AFTER)).toLong()).coerceAtLeast(dryGone + echoFrames)
        }
        val aChangeFrom = min(floor(out(aFirstU)), if (map.isIdentity) Double.MAX_VALUE else floor(map.rampStart)).toLong()

        // B: from its first lane in to where all of them are at full and unfiltered.
        val bHeardFrom = max(bStart, floor(bLanes.minOf { firstHeardU(spec, it)?.let(::out) ?: Double.NEGATIVE_INFINITY }).toLong())
        val bypass = ceil(Engine.BYPASS_S * rate).toLong()
        val bPlain = max(bStart, ceil(out(bLanes.maxOf { plainFromU(spec, it) })).toLong() + bypass + cutFrames)

        val pad = ceil(Engine.HEADROOM_PAD_S * rate).toLong()
        val bothFrom = bHeardFrom
        val bothTo = aGone
        val mixFrom = minOf(aChangeFrom, bothFrom - pad, bStart)
        val mixTo = maxOf(aGone, bothTo + pad, bPlain)

        val anchors = facts.anchors ?: run {
            // A's beats on the spec's grid, from where its tempo starts to move to where it is gone.
            val first = if (map.isIdentity) facts.aBar1 else map.rampStart
            val last = map.tailAt(aGone.toDouble())
            val k0 = floor((first - facts.aBar1) / aBeatFrames).toInt() - 1
            val k1 = ceil((last - facts.aBar1) / aBeatFrames).toInt() + 1
            if (map.isIdentity) DoubleArray(0) else DoubleArray(k1 - k0 + 1) { facts.aBar1 + (k0 + it) * aBeatFrames }
        }

        when {
            mixFrom < facts.earliest -> return Result.Refused(
                "it starts %.1f s before it can be ready".format((facts.earliest - mixFrom).toDouble() / rate),
            )
            mixFrom < 0 -> return Result.Refused("it starts before the music at hand")
            bStart > facts.aFrames -> return Result.Refused("B would start after the end of A")
            mixTo - bStart > facts.bFrames -> return Result.Refused(
                "B's copy holds %.1f s, the change needs %.1f s of it".format(facts.bFrames.toDouble() / rate, (mixTo - bStart).toDouble() / rate),
            )
            (mixTo - mixFrom).toDouble() / rate > Engine.MAX_SPAN_S -> return Result.Refused(
                "it takes %.1f s".format((mixTo - mixFrom).toDouble() / rate),
            )
        }
        return Result.Planned(
            LanePlan(
                spec = spec,
                facts = facts,
                aMap = map,
                anchors = anchors,
                bar1 = bar1,
                barFrames = barFrames,
                bStart = bStart,
                mixFrom = mixFrom,
                mixTo = mixTo,
                aGone = aGone,
                bPlain = bPlain,
                bothFrom = bothFrom,
                bothTo = bothTo,
                echoFrames = echoFrames,
                speed = map.speed,
            ).also {
                it.echoingLanes = echoing
                if (echoing.isNotEmpty()) it.echoFadeFrom = max(dryGone, aGone - echoFrames)
            },
        )
    }

    /**
     * Where in A, in ms of its own clock, the first frame [spec] touches comes: its first move, or
     * the tempo ramp before bar 1, or the next song coming in, less the headroom's room. The end
     * has to be held from before it.
     */
    fun firstChangeMs(spec: TransitionSpec): Double {
        val aLanes = spec.lanes.lanes.map { LaneId(Side.A, it) }
        val bLanes = spec.lanes.lanes.map { LaneId(Side.B, it) }
        val aFirst = aLanes.minOf { firstMoveU(spec, it) }
        val bFirst = bLanes.minOf { firstHeardU(spec, it) ?: (-spec.b.atMs / spec.b.barMs) }
        var ms = spec.aMsAt(minOf(aFirst, bFirst))
        if (spec.beatMatched) {
            val beats = if (abs(spec.tempoRatio() - 1) > LONG_RAMP_ABOVE) LONG_RAMP_BEATS else RAMP_BEATS
            ms = minOf(ms, spec.a.atMs - beats * 60_000.0 / spec.a.bpm)
        }
        return ms - Engine.HEADROOM_PAD_S * 1000
    }

    /** Where A's lane [id] first differs from full, in bars. */
    private fun firstMoveU(spec: TransitionSpec, id: LaneId): Double {
        if (spec.plain) return Double.MAX_VALUE
        if (id in spec.muted) return 0.0
        spec.curves[id]?.takeIf { it.gain.isNotEmpty() }?.let { c ->
            val keys = c.gain + c.lowPassHz + c.highPassHz
            return keys.minOf { it.u }
        }
        val m = spec.moves[id] ?: return spec.lengthBars
        return if (m.how == How.KEEP) spec.lengthBars else m.u
    }

    /** Where B's lane [id] is first heard, in bars; null for a lane heard from B's first frame. */
    private fun firstHeardU(spec: TransitionSpec, id: LaneId): Double? {
        if (id in spec.muted) return spec.lengthBars
        spec.curves[id]?.takeIf { it.gain.isNotEmpty() }?.let { c ->
            if (c.gain.first().value > 0.0) return null
            return c.gain.firstOrNull { it.value > 0.0 }?.let { k -> c.gain[max(0, c.gain.indexOf(k) - 1)].u } ?: spec.lengthBars
        }
        val m = spec.moves[id] ?: return spec.lengthBars
        return if (m.how == How.FROM_START) null else m.u
    }

    /** Where B's lane [id] is at full and unfiltered for good, in bars. */
    private fun plainFromU(spec: TransitionSpec, id: LaneId): Double {
        if (id in spec.muted) return spec.lengthBars
        spec.curves[id]?.takeIf { it.gain.isNotEmpty() }?.let { c ->
            return (c.gain + c.lowPassHz + c.highPassHz).maxOf { it.u }
        }
        val m = spec.moves[id] ?: return spec.lengthBars
        return when {
            m.how == How.FROM_START -> Double.NEGATIVE_INFINITY
            m.how == How.CUT || m.len <= 0.0 -> m.u
            else -> m.end
        }
    }
}
