/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchTest {

    @Test
    fun `the words after parts set the run, and anything else is refused`() {
        assertEquals(BenchArgs(), BenchArgs.parse(emptyList()))
        val args = BenchArgs.parse("model=scnet-int8.ort in=/sdcard/x.wav segments=4 overlap=4 threads=2 sessions=3 lowmem=1 spin=0 nice=9 keep=2".split(" "))
        assertEquals(BenchArgs("scnet-int8.ort", "/sdcard/x.wav", 4, 4.0, 2, 3, lowMemory = true, spinning = false, nice = 9, keep = 2), args)
        for (bad in listOf("threads", "threads=", "colour=red", "segments=0", "overlap=1", "lowmem=yes", "nice=40")) {
            assertThrows(bad, IllegalArgumentException::class.java) { BenchArgs.parse(listOf(bad)) }
        }
    }

    @Test
    fun `warm-ups are left out of the steady figures, and the per-second figures follow the overlap`() {
        val s = 1_000_000_000L
        val times = listOf(
            SegmentTime(0, 0, 0, s, 20 * s, s),
            SegmentTime(1, 1, 0, s, 18 * s, s),
            SegmentTime(2, 0, 22 * s, s / 2, 8 * s, s / 2),
            SegmentTime(3, 1, 20 * s, s / 2, 8 * s, s / 2),
        )
        val summary = BenchSummary(times, wallNanos = 31 * s, frames = 31 * Scnet.RATE, sessions = 2)
        assertEquals(listOf(2, 3), summary.steady.map { it.index })
        assertEquals(1.0, summary.wallPerAudioSecond, 1e-9)
        // A steady segment: 1 s of transforms in 9 s.
        assertEquals(1.0 / 9, summary.kotlinShare, 1e-9)
        // 9 s a segment, two at a time, a segment bringing 11 s / 1.33 of new audio.
        assertEquals(9.0 / 2 / (Scnet.SEGMENT / 1.33 / Scnet.RATE), summary.steadyPerAudioSecond(1.33), 1e-9)
        assertEquals(8.0 / 2 / (Scnet.SEGMENT / 4.0 / Scnet.RATE), summary.steadyPerAudioSecond(4.0, withoutKotlin = true), 1e-9)
    }
}
