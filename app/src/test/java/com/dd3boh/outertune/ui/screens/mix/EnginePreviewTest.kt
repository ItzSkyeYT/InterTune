/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.Copy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editor's preview off the audio thread: what it plays for a plain change of two songs at
 * different rates, and how it moves from one place to another while it plays, block by block.
 */
class EnginePreviewTest {

    private fun plain(aAtMs: Double) = TransitionSpec(
        a = Anchor("a", aAtMs, 120.0), b = Anchor("b", 0.0, 120.0), lanes = LaneSet.BANDS,
        bars = 8, preset = Preset.CUSTOM, plain = true, moves = emptyMap(),
    )

    @Test
    fun `a plain change plays the next song at its own speed when the two rates differ`() {
        // A: a 48 kHz stream's last 10 s, silent. B: a 44.1 kHz file whose every sample says which frame it is.
        val a = Copy(ShortArray(2 * 48_000 * 10), 48_000, 2, startMs = 100_000.0)
        val b = Copy(ShortArray(2 * 44_100 * 10) { ((it / 2) % 20_000).toShort() }, 44_100, 2)
        val sound = PreviewSound(a, b, null)
        // Bar 1 two seconds before A's end, two-second bars: B starts at bar 1 and is heard from there.
        val heard = sound.heardOf(PreviewSetup(plain(108_000.0), -1.0, 4.0, null))
        // Two seconds into B: its frame 88,200 at its own rate, 96,000 at A's.
        val i = heard.indexAt(2.0)
        val frame = heard.pcm[i * 2].toInt()
        assertEquals((88_200 % 20_000).toDouble(), frame.toDouble(), 2.0)
    }
}
