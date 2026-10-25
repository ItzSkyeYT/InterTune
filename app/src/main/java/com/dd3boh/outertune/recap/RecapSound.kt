/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/**
 * The piece of a song that plays under a slide: a third of the way in, as long as the slide
 * stays up and at least six seconds, all of a song shorter than that. The mock's rule (setStrip).
 * With sound (RecapSound, phase 2) only its start is kept, for a song with no Most replayed heat
 * map: a song plays on until the next slide's takes over.
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
 * at the bottom, [RecapPiece]), and call [play] as each comes up, [pause] while the story is held
 * or paused, [resume] after, and [stop] when the Recap closes. Until phase 2, [Silent] is all there
 * is, and the speaker button only shows the choice.
 *
 * Phase 2, as decided on 25 Oct 2026:
 *  - Sound on. The music that was playing pauses when the Recap opens and resumes when it closes.
 *  - Each slide's song plays and keeps playing, not a short clip, until the next slide's song
 *    takes over. It starts at the highest point of YouTube's Most replayed heat map after the
 *    first 5% of the song, and at [RecapPiece.fromMs] when the song has none (under about 50,000
 *    views, a local file). The heat map is in the plain YouTube `next` (the WEB client, not
 *    YouTube Music's): `frameworkUpdates.entityBatchUpdate.mutations[].payload
 *    .macroMarkersListEntity.markersList` with markerType MARKER_TYPE_HEATMAP, 100 markers of
 *    startMillis, durationMillis and intensityScoreNormalized; with that path as the
 *    X-Goog-FieldMask header it is about 2 KB a song. The markers, not the "Most replayed" label.
 *  - The last slide's song plays on after the Recap is closed, put in the queue as the song
 *    playing now; when it ends, the queue that was playing carries on. Closed before the last
 *    slide, the music that was playing just resumes.
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
