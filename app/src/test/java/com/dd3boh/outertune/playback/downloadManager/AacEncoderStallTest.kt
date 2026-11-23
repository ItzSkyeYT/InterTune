/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An AAC encoder that stops taking audio is given up while the song is fed to it, as it already was
 * at its end (review of 0.13, S2). `add` waited for an input buffer for good: the conversion hung
 * with SongConverter's one-at-a-time lock and the hold that keeps the app up, every later conversion
 * and export waited behind it, and Cancel, asked between pieces, was never asked. Read from the
 * source: MediaCodec does not run in a unit test.
 */
class AacEncoderStallTest {
    private val encoder = File("src/main/java/com/dd3boh/outertune/playback/downloadManager/AacFileEncoder.kt").readText()

    @Test
    fun `feeding the encoder gives up after the stall time, as its end does`() {
        val add = encoder.substringAfter("override fun add(samples: ShortArray, frames: Int) {").substringBefore("\n    }\n")
        val noRoom = add.substringAfter("if (index < 0) {").substringBefore("continue")
        assertTrue(noRoom.contains("if (count != before) idle = System.nanoTime()"))
        assertTrue(noRoom.contains("else if (System.nanoTime() - idle > STALL_NS) throw IOException(\"the AAC encoder stopped taking audio\")"))
        // Each piece taken is progress.
        assertTrue(add.substringAfter("queueInputBuffer(").contains("idle = System.nanoTime()"))
        val finish = encoder.substringAfter("override fun finish() {").substringBefore("\n    }\n")
        assertTrue(finish.contains("System.nanoTime() - idle > STALL_NS"))
    }
}
