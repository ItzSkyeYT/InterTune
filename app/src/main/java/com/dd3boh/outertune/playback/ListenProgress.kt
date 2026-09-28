/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * How a checkpoint's playedMs is kept, apart from the service so the arithmetic can be tested
 * without a player or a database.
 */
object ListenProgress {

    /**
     * The playedMs to write at a checkpoint tick, as a running total rather than a snapshot
     * against the play's start. Only the forward distance since the last checkpoint (or the last
     * seek) is added, and a backward jump adds nothing rather than going negative, so rewinding to
     * relisten to an earlier part of the song can never erase time already spent on it.
     */
    fun accumulate(playedMsSoFar: Long, lastCheckpointPositionMs: Long, newPositionMs: Long): Long =
        playedMsSoFar + (newPositionMs - lastCheckpointPositionMs).coerceAtLeast(0L)

    /**
     * The running total after a seek inside the same song: what was played since the last
     * checkpoint, up to where the seek left from, is credited, and the jump itself is not. A play
     * whose row has not opened yet (loaded, not started) has heard nothing, so it credits nothing.
     */
    fun creditBeforeSeek(opened: Boolean, playedMsSoFar: Long, lastCheckpointPositionMs: Long, oldPositionMs: Long): Long =
        if (opened) accumulate(playedMsSoFar, lastCheckpointPositionMs, oldPositionMs) else playedMsSoFar
}
