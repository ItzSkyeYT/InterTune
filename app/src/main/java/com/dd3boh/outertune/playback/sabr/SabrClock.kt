/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the player is in the song that is playing, for SABR's pacing. A SABR stream asks for the
 * next stretch of a song by where the player says it is. Told nothing, InnerTubeX asks as fast as
 * the server answers and reports a player far ahead of the real one; on 16 Oct 2026 YouTube kept
 * its stream protection "pending" through six such answers (50 s ahead of a player 10 s in), and
 * the library then gave the stream up as needing attestation. Set on the main thread by
 * MusicService, which may not be read from a loader's thread.
 *
 * Set only while a SABR read paced by the player is open ([reading]), in every build: on an
 * ordinary day no song is read over SABR and nothing wakes for it. It was set four times a second
 * through all playback, and in debug builds only, so a release would have streamed SABR unpaced.
 */
object SabrClock {
    @Volatile
    private var mediaId: String? = null

    @Volatile
    private var positionMs: Long = 0

    private var openReads = 0
    private val open = MutableStateFlow(false)

    /** Whether a SABR read paced by the player is open: MusicService sets the clock while it is, and only then. */
    val reading: StateFlow<Boolean> = open

    fun update(id: String?, position: Long) {
        mediaId = id
        positionMs = position
    }

    /** Where the player is in [videoId], or 0 for a song that is not the one playing yet. */
    fun positionIn(videoId: String): Long = if (mediaId == videoId) positionMs else 0

    /** A read paced by the player opened: the clock runs from now. */
    @Synchronized
    fun opened() {
        openReads++
        open.value = true
    }

    /** One closed: the clock sleeps after the last. */
    @Synchronized
    fun closed() {
        openReads = (openReads - 1).coerceAtLeast(0)
        open.value = openReads > 0
    }

    /** For the tests, each of which starts with no read open. */
    @Synchronized
    internal fun forgetReads() {
        openReads = 0
        open.value = false
    }
}
