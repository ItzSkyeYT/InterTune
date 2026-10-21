/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What one lane does at one moment of a change: its gain from 0 to 1, a low-pass or high-pass
 * cutoff in Hz where it is filtered, and how much of it goes into the echo.
 */
data class LaneState(
    val gain: Double,
    val lowPassHz: Double? = null,
    val highPassHz: Double? = null,
    val echoSend: Double = 0.0,
)

/**
 * The sound of each [How], in one place: the editor draws from it, and the engine's lane plan and
 * the preview are made from it, so what is drawn is what plays (DESIGN.md 3.6).
 */
object Shapes {
    /** Muffle (out): the low-pass closes from here to [MUFFLE_TO_HZ] over the move. */
    const val MUFFLE_FROM_HZ = 20_000.0
    const val MUFFLE_TO_HZ = 600.0

    /** Muffled (in): the low-pass opens from here to [MUFFLED_TO_HZ] over the move. */
    const val MUFFLED_FROM_HZ = 300.0
    const val MUFFLED_TO_HZ = 20_000.0

    /** Thin (out): the high-pass rises from here to [THIN_OUT_TO_HZ] over the move. */
    const val THIN_OUT_FROM_HZ = 20.0
    const val THIN_OUT_TO_HZ = 600.0

    /** Thin (in): held high-passed here, then opened to [THIN_IN_OPEN_HZ] over the move's last beat, so a kick or a bass note lands on the bar. */
    const val THIN_IN_HELD_HZ = 300.0
    const val THIN_IN_OPEN_HZ = 20.0

    /** Echo: three quarters of a beat, half fed back, band-passed in its loop. */
    const val ECHO_BEATS = 0.75
    const val ECHO_FEEDBACK = 0.5
    const val ECHO_LOW_HZ = 300.0
    const val ECHO_HIGH_HZ = 3_500.0

    /** Cut: the few ms a cut takes, so it never clicks. The editor draws it as a step. */
    const val CUT_MS = 10.0

    /**
     * Muffle and Thin (out) fade the lane away over the second half of their move. A low-pass at
     * 600 Hz leaves a lane's bass at full, and a high-pass leaves its top, so ending on a step
     * would cut that off at once, which is heard as a cut, not as a filter.
     */
    const val FILTER_OUT_FADE_FROM = 0.5

    /**
     * Muffled (in) brings the lane up over the first quarter of its move: what passes a low-pass at
     * 300 Hz, a bass line, would otherwise land at full on the move's first frame.
     */
    const val MUFFLED_RISE_TO = 0.25

    /** A value eased between two keys on a raised cosine, as the mock and the engine's lane plan do. */
    fun eased(keys: List<Key>, u: Double): Double {
        if (keys.isEmpty()) return 1.0
        if (u <= keys.first().u) return keys.first().value
        for (i in 1 until keys.size) {
            val p = keys[i - 1]
            val q = keys[i]
            if (u <= q.u) {
                if (q.u <= p.u) return q.value
                val x = (u - p.u) / (q.u - p.u)
                return p.value + (q.value - p.value) * (1 - cos(PI * x)) / 2
            }
        }
        return keys.last().value
    }

    /** A cutoff eased the same way, evenly in log frequency, as a filter sweep is heard. */
    fun easedHz(keys: List<Key>, u: Double): Double? {
        if (keys.isEmpty()) return null
        return exp(eased(keys.map { Key(it.u, ln(it.value)) }, u))
    }

    /** From [from] Hz to [to] Hz at share [x] of the way, evenly in log frequency. */
    fun sweep(from: Double, to: Double, x: Double): Double = from * exp(ln(to / from) * x.coerceIn(0.0, 1.0))
}

/**
 * What lane [id] does at bar position [u] of this change, from its curve where it has one, or its
 * move. A muted lane is gone: A's from bar 1, B's until the change has ended. A plain change plays
 * A whole and none of B inside it.
 */
fun TransitionSpec.laneAt(id: LaneId, u: Double): LaneState {
    val length = lengthBars
    if (plain) return LaneState(if (id.side == Side.A) 1.0 else 0.0)
    if (id in muted) {
        return LaneState(if (id.side == Side.A) (if (u < 0) 1.0 else 0.0) else (if (u >= length) 1.0 else 0.0))
    }
    curves[id]?.takeIf { it.gain.isNotEmpty() }?.let { curve ->
        return LaneState(
            gain = Shapes.eased(curve.gain, u),
            lowPassHz = Shapes.easedHz(curve.lowPassHz, u),
            highPassHz = Shapes.easedHz(curve.highPassHz, u),
        )
    }
    val move = moves[id] ?: return LaneState(if (id.side == Side.A) 1.0 else 0.0)
    return if (id.side == Side.A) exitAt(move, u, length) else entryAt(move, u, beat)
}

private fun exitAt(m: Move, u: Double, length: Double): LaneState {
    if (m.how == How.KEEP) return LaneState(if (u < length) 1.0 else 0.0)
    if (m.how == How.CUT || m.len <= 0.0) return LaneState(if (u < m.u) 1.0 else 0.0)
    val p = (u - m.u) / m.len
    if (p <= 0.0) return LaneState(1.0)
    if (p >= 1.0) return LaneState(0.0)
    return when (m.how) {
        How.FADE -> LaneState(cos(PI / 2 * p))
        How.MUFFLE -> LaneState(filterOutGain(p), lowPassHz = Shapes.sweep(Shapes.MUFFLE_FROM_HZ, Shapes.MUFFLE_TO_HZ, p))
        How.THIN -> LaneState(filterOutGain(p), highPassHz = Shapes.sweep(Shapes.THIN_OUT_FROM_HZ, Shapes.THIN_OUT_TO_HZ, p))
        // The lane goes into the echo for the length of the move, then its dry sound stops and
        // the echo rings out on its own: the engine plays the tail past the change's end.
        How.ECHO -> LaneState(1.0, echoSend = 1.0)
        else -> LaneState(1.0)
    }
}

/** A filtered exit's level at share [p] of its move: full, then a cosine away over its second half. */
private fun filterOutGain(p: Double): Double {
    val from = Shapes.FILTER_OUT_FADE_FROM
    return if (p <= from) 1.0 else cos(PI / 2 * (p - from) / (1 - from))
}

private fun entryAt(m: Move, u: Double, beat: Double): LaneState {
    if (m.how == How.FROM_START) return LaneState(1.0)
    if (m.how == How.CUT || m.len <= 0.0) return LaneState(if (u >= m.u) 1.0 else 0.0)
    val p = (u - m.u) / m.len
    if (p <= 0.0) return LaneState(0.0)
    if (p >= 1.0) return LaneState(1.0)
    return when (m.how) {
        How.FADE -> LaneState(sin(PI / 2 * p))
        How.MUFFLED -> LaneState(
            if (p >= Shapes.MUFFLED_RISE_TO) 1.0 else sin(PI / 2 * p / Shapes.MUFFLED_RISE_TO),
            lowPassHz = Shapes.sweep(Shapes.MUFFLED_FROM_HZ, Shapes.MUFFLED_TO_HZ, p),
        )
        How.THIN -> {
            val open = min(beat, m.len)
            val hold = m.u + m.len - open
            if (u < hold) LaneState(1.0, highPassHz = Shapes.THIN_IN_HELD_HZ)
            else LaneState(1.0, highPassHz = Shapes.sweep(Shapes.THIN_IN_HELD_HZ, Shapes.THIN_IN_OPEN_HZ, (u - hold) / open))
        }
        else -> LaneState(1.0)
    }
}

/**
 * The bar position from which A's lane [id] is silent for good, its echo aside: where its move
 * or its curve ends. A kept lane lasts the whole change.
 */
fun TransitionSpec.goneAt(id: LaneId): Double {
    require(id.side == Side.A) { "only A's lanes leave" }
    if (plain) return Double.POSITIVE_INFINITY
    if (id in muted) return 0.0
    curves[id]?.gain?.takeIf { it.isNotEmpty() }?.let { keys ->
        for (i in keys.indices.reversed()) {
            if (keys[i].value > 0.001) return if (i + 1 < keys.size) keys[i + 1].u else lengthBars
        }
        return keys.first().u
    }
    val m = moves[id] ?: return lengthBars
    return when {
        m.how == How.KEEP -> lengthBars
        m.how == How.CUT -> m.u
        else -> m.end
    }
}

/** Where the last of A is gone, in bars of the change. */
fun TransitionSpec.aGoneAt(): Double = lanes.lanes.maxOf { goneAt(LaneId(Side.A, it)) }

/**
 * The music at the end of A that is never heard, in ms of A's own clock, for a song of
 * [aDurationMs]: "Wake Me Up loses its last 0:09".
 */
fun TransitionSpec.unheardMs(aDurationMs: Double): Double =
    if (plain) 0.0 else max(0.0, aDurationMs - aMsAt(aGoneAt()))
