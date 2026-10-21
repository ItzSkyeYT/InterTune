/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

/**
 * Specs made by hand for the tests, on the first acceptance test's pair: Wake Me Up (A, taken at
 * 111 BPM here) into After Hours (B, 109 BPM). The ids are placeholders, never a real song's, and
 * the moves are the mock's Fade preset at 8 bars on bands.
 */
object Specs {

    const val A_ID = "wake-me-up"
    const val B_ID = "after-hours"

    val a = Anchor(song = A_ID, atMs = 281_340.0, bpm = 111.0, source = "251:4820311:312", analysis = 1)
    val b = Anchor(song = B_ID, atMs = 1_210.0, bpm = 109.0, source = "251:5791102:312", analysis = 1)

    private fun id(key: String) = LaneId.of(key)!!

    /** The mock's Fade at [bars]: B's highs in thin over a quarter, its mids over a half, A out over the second half, the lows swapped halfway. */
    fun fadeOnBands(bars: Int = 8): TransitionSpec {
        val l = bars.toDouble()
        val beat = 0.25
        return TransitionSpec(
            a = a,
            b = b,
            lanes = LaneSet.BANDS,
            bars = bars,
            preset = Preset.FADE,
            moves = mapOf(
                id("A.highs") to Move(l / 2, l / 2, How.FADE),
                id("A.mids") to Move(l / 2, l / 2, How.FADE),
                id("A.lows") to Move(l / 2 - beat, beat, How.FADE),
                id("B.highs") to Move(0.0, l / 4, How.THIN),
                id("B.mids") to Move(0.0, l / 2, How.FADE),
                id("B.lows") to Move(l / 2 - beat, beat, How.FADE),
            ),
        )
    }

    /** Section 5's example of DESIGN.md: Vocals over on parts, made custom, with a curve, a mute and a fix. */
    fun vocalsOverCustom(): TransitionSpec = TransitionSpec(
        a = a.copy(fix = BeatFix(bpm = 111.0, downbeatShift = 1, nudgeMs = -10, source = "251:4820311:312")),
        b = b,
        lanes = LaneSet.PARTS,
        bars = 8,
        beatMatch = true,
        preset = Preset.CUSTOM,
        from = Preset.VOCALS_OVER,
        moves = mapOf(
            id("A.vocals") to Move(7.0, 1.0, How.ECHO),
            id("B.vocals") to Move(8.0, 0.0, How.CUT),
            id("A.drums") to Move(2.0, 0.0, How.CUT),
            id("B.drums") to Move(0.0, 2.0, How.THIN),
            id("A.bass") to Move(3.75, 0.25, How.FADE),
            id("B.bass") to Move(3.75, 0.25, How.FADE),
            id("A.melody") to Move(4.0, 2.0, How.MUFFLE),
            id("B.melody") to Move(5.0, 1.0, How.FADE),
        ),
        curves = mapOf(
            id("A.melody") to LaneCurve(
                gain = listOf(Key(-1.0, 1.0), Key(4.0, 1.0), Key(4.5, 0.7), Key(6.0, 0.0)),
                lowPassHz = listOf(Key(4.0, 20_000.0), Key(6.0, 600.0)),
            ),
        ),
        muted = setOf(id("B.vocals")),
        introTwice = 2,
        savedAt = 1_792_600_000_000L,
    )
}
