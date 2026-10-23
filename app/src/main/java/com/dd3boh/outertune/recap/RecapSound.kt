/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/**
 * The piece of a song that plays under a slide: a third of the way in, as long as the slide
 * stays up and at least six seconds, all of a song shorter than that. The mock's rule (setStrip).
 */
data class RecapPiece(val fromMs: Long, val lengthMs: Long) {
    companion object {
        const val SHORTEST_MS = 6_000L

        /** For a song [durationS] long, under a slide up for [slideMs] (0 for one that stays). */
        fun of(durationS: Int, slideMs: Long): RecapPiece {
            val duration = (if (durationS > 0) durationS else 180) * 1000L
            val wanted = maxOf(SHORTEST_MS, if (slideMs > 0) slideMs else 12_000L)
            val length = minOf(duration, wanted)
            val from = minOf(duration - length, duration / 3)
            return RecapPiece(from, length)
        }
    }
}

/**
 * What plays under the Recap's slides: the hook, and nothing behind it yet.
 *
 * PHASE 1 IS SILENT. The slides already say which song is theirs and which piece of it (the pill
 * at the bottom, [RecapPiece]), and call [play] as each comes up, [pause] while a finger holds the
 * screen, and [stop] when the Recap closes. Phase 2 puts a player behind this: a piece of each
 * song, faded in and out, without touching the queue that was playing, and the music paused
 * meanwhile. Until then [Silent] is all there is, and the speaker button only shows the choice.
 */
interface RecapSound {
    fun play(songId: String, piece: RecapPiece)
    fun pause()
    fun resume()
    fun stop()

    /** Phase 1: nothing plays. */
    object Silent : RecapSound {
        override fun play(songId: String, piece: RecapPiece) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
    }
}
