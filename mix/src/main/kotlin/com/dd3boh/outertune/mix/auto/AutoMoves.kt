/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Side

/**
 * The moves Auto writes on bands (lows, mids, highs), in bars of the incoming song from bar 1.
 *
 * [fade], [rise], [blend], [echoOut] and [cut] are DESIGN.md 3.7's rules for those presets, so a
 * change Auto makes with them is the same one the preset chip makes. [echoOn] and [cutOn] are
 * Auto's own, for two songs whose tempos are too far apart to lock: the preset rules lay the two
 * songs over each other for half the change, which with two beats at different speeds is a
 * trainwreck, and they mute the incoming song's first bars. Here the outgoing song leaves on bar 1
 * itself, its last line echoing away (or cut), and the incoming song comes in clean on its own
 * first downbeat. [introRise] is the DJ's intro mix: the incoming song's beatless opening rises
 * muffled under the outgoing song from its first sound, and its beat drops on bar [length] + 1,
 * the moment the outgoing song is gone.
 */
object AutoMoves {

    private val LOWS_A = LaneId(Side.A, Lane.LOWS)
    private val MIDS_A = LaneId(Side.A, Lane.MIDS)
    private val HIGHS_A = LaneId(Side.A, Lane.HIGHS)
    private val LOWS_B = LaneId(Side.B, Lane.LOWS)
    private val MIDS_B = LaneId(Side.B, Lane.MIDS)
    private val HIGHS_B = LaneId(Side.B, Lane.HIGHS)

    /** "Swap at u": A's exit and B's entry over the beat before [u], so the basses change on the bar line. */
    private fun MutableMap<LaneId, Move>.swapLows(u: Double, beat: Double) {
        this[LOWS_A] = Move(u - beat, beat, How.FADE)
        this[LOWS_B] = Move(u - beat, beat, How.FADE)
    }

    /** "Swap at u" for the mids, where the voices live on bands: one voice at a time, meeting over the beat before [u]. */
    private fun MutableMap<LaneId, Move>.swapMids(u: Double, beat: Double) {
        this[MIDS_A] = Move(u - beat, beat, How.FADE)
        this[MIDS_B] = Move(u - beat, beat, How.FADE)
    }

    /**
     * One smooth handover, one bassline at a time: hats first, the basses swap halfway, the
     * outgoing song fades over the second half. With [oneVoice] the mids swap with the basses
     * instead of crossing over the whole change, so two voices never sing over each other for
     * more than a beat (CONVENTIONS.md: no two singers at once).
     */
    fun fade(length: Double, beat: Double, oneVoice: Boolean = false): Map<LaneId, Move> = buildMap {
        put(HIGHS_B, Move(0.0, length / 4, How.THIN))
        if (oneVoice) swapMids(length / 2, beat) else {
            put(MIDS_B, Move(0.0, length / 2, How.FADE))
            put(MIDS_A, Move(length / 2, length / 2, How.FADE))
        }
        put(HIGHS_A, Move(length / 2, length / 2, How.FADE))
        swapLows(length / 2, beat)
    }

    /** The incoming song opens up from muffled while the outgoing song thins out; the basses swap a bar before the end. */
    fun rise(length: Double, beat: Double): Map<LaneId, Move> = buildMap {
        put(HIGHS_B, Move(0.0, length, How.MUFFLED))
        put(MIDS_B, Move(0.0, length, How.MUFFLED))
        put(HIGHS_A, Move(length / 2, length / 2, How.THIN))
        put(MIDS_A, Move(length / 2, length / 2, How.THIN))
        swapLows(length - 1, beat)
    }

    /** Both songs blend evenly, the basses swap halfway. */
    fun blend(length: Double, beat: Double): Map<LaneId, Move> = buildMap {
        put(HIGHS_B, Move(0.0, length, How.FADE))
        put(MIDS_B, Move(0.0, length, How.FADE))
        put(HIGHS_A, Move(0.0, length, How.FADE))
        put(MIDS_A, Move(0.0, length, How.FADE))
        swapLows(length / 2, beat)
    }

    /** The preset rule: the outgoing song's mids echo away from halfway while the incoming song comes in over the first half. */
    fun echoOut(length: Double, beat: Double): Map<LaneId, Move> = buildMap {
        put(HIGHS_B, Move(0.0, length / 2, How.FADE))
        put(MIDS_B, Move(0.0, length / 2, How.FADE))
        swapLows(length / 2, beat)
        put(MIDS_A, Move(length / 2, length / 4, How.ECHO))
        put(HIGHS_A, Move(length / 2, length / 4, How.FADE))
    }

    /** The preset rule: every lane changes at once, halfway. */
    fun cut(length: Double): Map<LaneId, Move> = buildMap {
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) {
            put(LaneId(Side.A, lane), Move(length / 2, 0.0, How.CUT))
            put(LaneId(Side.B, lane), Move(length / 2, 0.0, How.CUT))
        }
    }

    /**
     * Auto's echo for far tempos: the outgoing song's mids go into the echo over its last
     * [sendBars] before bar 1 and stop there, its highs fade over the last beat, its lows stop on
     * the line; the incoming song plays from its first frame ([incoming]), so its first downbeat
     * lands on bar 1 with nothing of the outgoing song's beat left under it.
     */
    fun echoOn(beat: Double, sendBars: Double = 2 * beat, incoming: Map<LaneId, Move> = fromStart()): Map<LaneId, Move> = buildMap {
        put(MIDS_A, Move(-sendBars, sendBars, How.ECHO))
        put(HIGHS_A, Move(-beat, beat, How.FADE))
        put(LOWS_A, Move(0.0, 0.0, How.CUT))
        putAll(incoming)
    }

    /** Auto's cut for far tempos: the outgoing song stops dead on bar 1, where the incoming song's first downbeat lands. */
    fun cutOn(incoming: Map<LaneId, Move> = fromStart()): Map<LaneId, Move> = buildMap {
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) put(LaneId(Side.A, lane), Move(0.0, 0.0, How.CUT))
        putAll(incoming)
    }

    /** The incoming song in on bar 1, nothing of it heard before: an opening left out, its beat landing on the line. */
    fun cutIn(): Map<LaneId, Move> = buildMap {
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) put(LaneId(Side.B, lane), Move(0.0, 0.0, How.CUT))
    }

    /** The incoming song as it is, from its first frame. */
    fun fromStart(): Map<LaneId, Move> = buildMap {
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) put(LaneId(Side.B, lane), Move(0.0, 0.0, How.FROM_START))
    }

    /**
     * The incoming song rising into bar 1 from its first sound at [fromU] (a fraction of a bar
     * before it, at most), so a pickup or a riser into its first downbeat is heard and comes up
     * under the outgoing song's last moment instead of starting at full.
     */
    fun risingInto(fromU: Double): Map<LaneId, Move> = buildMap {
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) put(LaneId(Side.B, lane), Move(fromU, -fromU, How.FADE))
    }

    /**
     * The intro mix: the incoming song's mids and highs rise muffled from its first sound at
     * [fromU] (at or before bar 1) to its drop at [length]; the outgoing song thins out over the
     * second half and its lows give way a bar before the drop, so the drop lands on a bar line
     * with only the incoming song's bass under it.
     */
    fun introRise(length: Double, beat: Double, fromU: Double): Map<LaneId, Move> = buildMap {
        val from = minOf(0.0, fromU)
        put(HIGHS_B, Move(from, length - from, How.MUFFLED))
        put(MIDS_B, Move(from, length - from, How.MUFFLED))
        put(HIGHS_A, Move(length / 2, length / 2, How.THIN))
        put(MIDS_A, Move(length / 2, length / 2, How.THIN))
        swapLows(length - 1, beat)
    }
}
