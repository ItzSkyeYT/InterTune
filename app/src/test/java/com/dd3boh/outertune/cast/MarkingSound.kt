/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's sound as the cast tests stand it in: every sample it shapes turned into its negative,
 * which a test undoes ([undo]), so what a receiver got shows whether it went through once, never
 * or twice. It notes the songs it was asked to prepare and to shape, its stages and their resets.
 */
class MarkingSound : PcmSound {
    val prepared = CopyOnWriteArrayList<String>()
    val shapedSongs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val stages = AtomicInteger()
    val resets = AtomicInteger()

    override fun prepare(mediaId: String) {
        prepared += mediaId
    }

    override fun stage(rate: Int, channels: Int): PcmStage {
        stages.incrementAndGet()
        return object : PcmStage {
            override fun shape(mediaId: String, samples: ShortArray, from: Int, out: ShortArray, to: Int, frames: Int) {
                shapedSongs += mediaId
                for (i in 0 until frames * channels) out[to + i] = (-samples[from + i]).toShort()
            }

            override fun reset() {
                resets.incrementAndGet()
            }
        }
    }

    companion object {
        /** [samples] as they were before the marking: negating twice gives every 16-bit value back. */
        fun undo(samples: ShortArray): ShortArray = ShortArray(samples.size) { (-samples[it]).toShort() }
    }
}
