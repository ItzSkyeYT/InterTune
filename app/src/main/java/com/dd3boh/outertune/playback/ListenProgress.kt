/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Rules for the listen log's progress and sessions, kept apart from the service so they can be
 * tested without a player or a database.
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

    /**
     * Which session a new listen row continues. A row still OPEN is a candidate for "the song that
     * just ended, whose close is queued behind an IO coroutine and has not landed yet", but it is
     * not trusted on its own: nothing closes a leaked row (one a crashed close, a discarded
     * zero-length play, or a slow transaction queue left behind) until the next process starts, so
     * an old open row could otherwise capture every later listen into one unbounded session. It is
     * held to the same [sessionGapMs] rule as the last closed play, using its own last known
     * progress ([openLastKnownAt], its startedAt plus the checkpointed playedMs) as the moment it
     * was last known to be running. Whichever of the open row and the last closed row reaches
     * closer to [startedAt] decides the session; a gap past [sessionGapMs] from both starts a new
     * one.
     */
    fun sessionIdFor(
        startedAt: Long,
        openSessionId: Long?,
        openLastKnownAt: Long?,
        lastEndedAt: Long?,
        lastSessionId: Long?,
        sessionGapMs: Long,
    ): Long {
        val useOpen = openSessionId != null && openLastKnownAt != null &&
            (lastEndedAt == null || openLastKnownAt >= lastEndedAt)
        val candidateEndedAt = if (useOpen) openLastKnownAt else lastEndedAt
        val candidateSessionId = if (useOpen) openSessionId else lastSessionId
        if (candidateEndedAt == null || candidateSessionId == null || startedAt - candidateEndedAt > sessionGapMs) {
            return startedAt
        }
        return candidateSessionId
    }
}
