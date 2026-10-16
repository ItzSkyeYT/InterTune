/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

/**
 * Where the player is in the song that is playing, for SABR's pacing. A SABR stream asks for the
 * next stretch of a song by where the player says it is. Told nothing, InnerTubeX asks as fast as
 * the server answers and reports a player far ahead of the real one; on 16 Oct 2026 YouTube kept
 * its stream protection "pending" through six such answers (50 s ahead of a player 10 s in), and
 * the library then gave the stream up as needing attestation. Set on the main thread by
 * MusicService, which may not be read from a loader's thread.
 */
object SabrClock {
    @Volatile
    private var mediaId: String? = null

    @Volatile
    private var positionMs: Long = 0

    fun update(id: String?, position: Long) {
        mediaId = id
        positionMs = position
    }

    /** Where the player is in [videoId], or 0 for a song that is not the one playing yet. */
    fun positionIn(videoId: String): Long = if (mediaId == videoId) positionMs else 0
}
