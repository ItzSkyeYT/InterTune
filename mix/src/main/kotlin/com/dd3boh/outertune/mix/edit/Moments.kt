/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlin.math.abs

/**
 * One thing that happens in a change, for the now line ("Bar 5 of 8 · Bass swap") and the
 * moments sheet: at bar position [u], what happens, and to which lane. The editor says it in
 * words; the words go to the owner for his yes before they go anywhere.
 */
data class Moment(val u: Double, val kind: Kind, val lane: Lane? = null, val how: How? = null) {

    enum class Kind(
        /** At one bar position, a swap is told first, then what A does, then what B does, then B starting. */
        val order: Int,
    ) {
        /** A's lane hands over to B's within a beat: "Bass swap". */
        SWAP(0),

        /** A's lane leaves, by [how]: "Wake Me Up's melody muffles away". */
        LEAVES(1),

        /** A's lane is muted: gone from bar 1. */
        A_MUTED(1),

        /** A's lane follows a curve from Adjust, from here. */
        A_CURVE(1),

        /** B's lane comes in, by [how]: "After Hours' hats come in" for a thin entry on the drums or the highs. */
        ENTERS(2),

        /** B's lane is muted: it waits until after the change. */
        B_WAITS(2),

        /** B's lane follows a curve from Adjust, from here. */
        B_CURVE(2),

        /** B starts under A, where lanes play from its first frame. */
        B_STARTS(3),
    }
}

/** The moments of a change, worked out from its spec as the 21 Oct mock does. */
object Moments {

    /** Every moment of [spec], in the order they happen; none for a plain change. */
    fun of(spec: TransitionSpec): List<Moment> {
        if (spec.plain) return emptyList()
        val beat = spec.beat
        val out = ArrayList<Moment>()
        var startsUnder = false
        for (lane in spec.lanes.lanes) {
            val ia = LaneId(Side.A, lane)
            val ib = LaneId(Side.B, lane)
            val a = spec.moves[ia] ?: continue
            val b = spec.moves[ib] ?: continue
            val aCurve = spec.curves[ia]?.takeIf { it.gain.isNotEmpty() }
            val bCurve = spec.curves[ib]?.takeIf { it.gain.isNotEmpty() }
            val aMuted = ia in spec.muted
            val bMuted = ib in spec.muted
            val plainA = aCurve == null && !aMuted && a.how != How.KEEP
            val plainB = bCurve == null && !bMuted && b.how != How.FROM_START
            if (plainA && plainB) {
                val aEnd = if (a.how == How.CUT) a.u else a.end
                val bFull = if (b.how == How.CUT) b.u else b.end
                // One lane handing over to the other within a beat, the leaving one quick: a swap.
                if (abs(aEnd - bFull) <= beat + EPS && (a.how == How.CUT || a.len <= beat + EPS)) {
                    out += Moment(bFull, Moment.Kind.SWAP, lane)
                    if (b.how != How.CUT && b.len > beat + EPS) out += Moment(b.u, Moment.Kind.ENTERS, lane, b.how)
                    continue
                }
            }
            when {
                aMuted -> out += Moment(0.0, Moment.Kind.A_MUTED, lane)
                aCurve != null -> out += Moment(aCurve.gain.first().u, Moment.Kind.A_CURVE, lane)
                a.how != How.KEEP -> out += Moment(a.u, Moment.Kind.LEAVES, lane, a.how)
            }
            when {
                bMuted -> out += Moment(spec.lengthBars, Moment.Kind.B_WAITS, lane)
                bCurve != null -> out += Moment(bCurve.gain.first().u, Moment.Kind.B_CURVE, lane)
                b.how == How.FROM_START -> startsUnder = true
                else -> out += Moment(b.u, Moment.Kind.ENTERS, lane, b.how)
            }
        }
        if (startsUnder) out += Moment((spec.bStartMs - spec.b.atMs) / spec.b.barMs, Moment.Kind.B_STARTS)
        return out.sortedWith(compareBy<Moment> { it.u }.thenBy { it.kind.order })
    }

    /**
     * What the now line names while bar position [u] plays: the latest moment at or before it,
     * from [from] on (the window the canvas shows), the swap first where several fall together.
     */
    fun latestAt(spec: TransitionSpec, u: Double, from: Double = Double.NEGATIVE_INFINITY): Moment? {
        var latest: Moment? = null
        for (m in of(spec)) {
            if (m.u > u + EPS || m.u < from - EPS) continue
            val l = latest
            if (l == null || m.u > l.u + EPS || (abs(m.u - l.u) <= EPS && m.kind.order < l.kind.order)) latest = m
        }
        return latest
    }

    private const val EPS = 1e-9
}
