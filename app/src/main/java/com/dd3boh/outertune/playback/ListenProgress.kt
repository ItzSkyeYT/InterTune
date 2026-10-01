/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.EndReason

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

    /**
     * How a play ended, for its listen row.
     *
     * [endedByPlayer] is the player's own count of plays that reached their end, a repeat
     * included. [failed] is a playback error the play never got past: it stopped on the error and
     * did not play again before it was left. [transition] is what the transition that moved on
     * from it left behind, or null when nothing moved on, as when the service is released.
     *
     * A play that died is an error, whatever moved on from it. Skip on error moves on by seeking,
     * which is the same transition as the listener pressing next, so a stream that died was
     * written as a skip: the engine took it as the listener rejecting the song, and Rest songs I
     * skip could rest a liked song for a week over a 403.
     *
     * A transition only leaves ENDED for a natural end or a repeat, and the player counts both of
     * those itself, so ENDED is never taken from a transition alone: without the player's count it
     * is a stop. When reasons were kept by song, a repeat paused and then closed with the service
     * took the ENDED its own repeat had left and was written as played to the end: 14 rows on his
     * phone on 28 Sep, every one straight after a repeat of the same song. [PlayBook] now keeps each
     * reason on its play, and this rule stays as the second guard.
     */
    fun endReason(endedByPlayer: Boolean, failed: Boolean, transition: Int?): Int = when {
        endedByPlayer -> EndReason.ENDED
        failed -> EndReason.ERROR
        transition == null || transition == EndReason.ENDED -> EndReason.STOPPED
        else -> transition
    }

    /** A play that starts within this of where an earlier one was left continues it. */
    const val RESUME_TOLERANCE_MS = 5_000L

    /** And only this long after that one ended. */
    const val RESUME_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * Whether a play starting at [startPositionMs], at [startedAt], carries on the latest earlier
     * play of the same song rather than being a new one: that play was cut off where it stood
     * (stopped, or stopped on a playback error), and this one starts where it was left, within the
     * day. A stop is not a verdict, so the two are one listen in two rows, linked.
     *
     * A failed play counts as cut off where it stood. Before failed plays were written as ERROR, one
     * still sitting on its error when the service went was written as STOPPED and linked like any
     * other stop; the listener coming back to it from the same spot is the same listen as before.
     */
    fun continues(
        previousEndReason: Int,
        previousEndPositionMs: Long,
        previousStartedAt: Long,
        previousEndedAt: Long,
        startPositionMs: Long,
        startedAt: Long,
    ): Boolean =
        (previousEndReason == EndReason.STOPPED || previousEndReason == EndReason.ERROR) &&
            previousEndPositionMs >= 0 &&
            kotlin.math.abs(startPositionMs - previousEndPositionMs) <= RESUME_TOLERANCE_MS &&
            startedAt - maxOf(previousEndedAt, previousStartedAt) <= RESUME_WINDOW_MS
}
