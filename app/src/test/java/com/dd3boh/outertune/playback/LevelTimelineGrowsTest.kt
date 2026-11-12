/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The living background's levels while AutoMix holds the end of a song: the decoder runs up to forty
 * seconds ahead of what is heard, and the timeline makes room for all of it rather than lose levels
 * nobody has heard yet. In ordinary playback it never grows, and a seek gives the room back.
 */
class LevelTimelineGrowsTest {

    private fun levels(x: Float) = floatArrayOf(x, x / 2, x / 4)

    @Test
    fun `levels far ahead of what is heard are all kept`() {
        val timeline = LevelTimeline(capacity = 50, growsTo = 400)
        val got = FloatArray(3)
        // Heard at the start, then 300 levels arrive at once, six seconds of them.
        timeline.add(0, levels(0f))
        assertTrue(timeline.read(0, got))
        for (i in 1..300) timeline.add(i * 20_000L, levels(i / 300f))
        for (i in listOf(1, 10, 150, 299, 300)) {
            assertTrue("level $i", timeline.read(i * 20_000L, got))
            assertArrayEquals(levels(i / 300f), got, 0f)
        }
    }

    @Test
    fun `in ordinary playback, levels just ahead of what is heard, it keeps its size`() {
        val timeline = LevelTimeline(capacity = 50, growsTo = 400)
        val got = FloatArray(3)
        for (i in 0 until 500) {
            timeline.add(i * 20_000L, levels(i / 500f))
            // heard a quarter of a second behind
            if (i > 12) timeline.read((i - 12) * 20_000L, got)
        }
        assertTrue(timeline.read(499 * 20_000L, got))
        assertFalse("only the last second is kept, as before", timeline.read(400 * 20_000L, got))
    }

    @Test
    fun `it grows no further than it may, and a seek gives the room back`() {
        val timeline = LevelTimeline(capacity = 10, growsTo = 40)
        val got = FloatArray(3)
        timeline.add(0, levels(0f))
        timeline.read(0, got)
        for (i in 1..100) timeline.add(i * 20_000L, levels(i / 100f))
        assertTrue(timeline.read(100 * 20_000L, got))
        assertFalse("forty at the most", timeline.read(50 * 20_000L, got))
        timeline.clear()
        for (i in 0 until 30) timeline.add(5_000_000L + i * 20_000L, levels(0.5f))
        assertFalse("ten again after the seek, with nothing heard yet", timeline.read(5_000_000L, got))
        assertTrue(timeline.read(5_000_000L + 29 * 20_000L, got))
    }
}
