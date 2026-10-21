/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

/**
 * When AutoMix does each thing, by how much of the song playing is left, and the checks on what
 * it was handed. No Android in here, so it is tested; AutomixController reads the player for it.
 */
object AutomixSchedule {

    /** The next song's opening is read once this much of the song playing is left, or at once for a shorter one. */
    const val READ_LEFT_MS = 120_000L

    /** How much of the next song's opening is read and analysed. */
    const val HEAD_SECONDS = 30.0

    /** The end is held from here... */
    const val ARM_FROM_LEFT_MS = 40_000L

    /** ...until here. Later than this the change stays as it is: there would be no time to plan it. */
    const val ARM_UNTIL_LEFT_MS = 25_000L

    /** Room for the held end: the window's start, what the spatial stages add at the end of a song, and some to spare. */
    const val CAPACITY_SECONDS = 48

    /** The next song has to be loaded this far when its way in is held, so it starts on time. */
    const val NEXT_LOADED_MS = 5_000L

    /** A seek this close to the end is the listener choosing where to be, and that end is not mixed. */
    const val SEEK_LATE_MS = 60_000L

    /** How far the held end may be from what was left of the song when it began to be held. */
    const val TAIL_SLACK_MS = 1_500L

    /** What the first plan is given to be worked out in, before one has been timed on this phone. */
    const val FIRST_WORK_MS = 4_000L

    fun capacityFrames(sampleRate: Int): Int = CAPACITY_SECONDS * sampleRate

    enum class Step {
        /** Too early for anything. */
        WAIT,

        /** The next song's opening is to be read. */
        READ,

        /** The end may be held, once everything is ready. */
        ARM,

        /** Too late to hold the end: this change is as it is today. */
        TOO_LATE,
    }

    /**
     * What is due with [leftMs] of the song left, the end being held from [armFromMs] left: earlier
     * than usual for a saved change that starts further from the end (see [armFromFor]).
     */
    fun due(leftMs: Long, armFromMs: Long = ARM_FROM_LEFT_MS): Step = when {
        leftMs < ARM_UNTIL_LEFT_MS -> Step.TOO_LATE
        leftMs <= armFromMs -> Step.ARM
        leftMs <= READ_LEFT_MS -> Step.READ
        else -> Step.WAIT
    }

    /** The most a saved change may ask the end to be held from: the ring's room, less what the spatial stages add and a margin. */
    const val ARM_FROM_LEFT_MAX_MS = (CAPACITY_SECONDS - 2) * 1000L

    /**
     * How long before the first frame a saved change touches its end must be held: the time to
     * take the end in, which the decoder does at several times the speed it is played, and to work
     * the change out, with room to spare.
     */
    const val SAVED_LEAD_MS = 15_000L

    /** Where the end is held from for a saved change whose first frame is [changeLeftMs] before the song's end. */
    fun armFromFor(changeLeftMs: Long): Long = (changeLeftMs + SAVED_LEAD_MS).coerceIn(ARM_FROM_LEFT_MS, ARM_FROM_LEFT_MAX_MS)

    /**
     * How long to wait, at [speed], before looking again with [leftMs] left: until the next step is
     * due, and every half second through the window where the end may be held, since what it waits
     * for (the songs loaded, the opening read) arrives by itself. Null when nothing is left to do
     * for this song.
     */
    fun nextLookMs(leftMs: Long, speed: Float, armFromMs: Long = ARM_FROM_LEFT_MS): Long? {
        val s = speed.takeIf { it > 0.01f } ?: 1f
        val untilMs = when {
            leftMs > READ_LEFT_MS -> leftMs - READ_LEFT_MS
            leftMs > armFromMs -> leftMs - armFromMs
            leftMs >= ARM_UNTIL_LEFT_MS -> return WINDOW_LOOK_MS
            else -> return null
        }
        return (untilMs / s).toLong().coerceIn(WINDOW_LOOK_MS, MAX_WAIT_MS)
    }

    /**
     * Whether the player has what holding the end needs: the song playing loaded to its end, so
     * the decoder can run ahead without ever waiting on the network, and the next one loaded far
     * enough to start the moment it is due. [totalBufferedMs] runs from the position across both.
     */
    fun loaded(durationMs: Long, positionMs: Long, bufferedPositionMs: Long, totalBufferedMs: Long): Boolean {
        if (durationMs <= 0) return false
        val left = durationMs - positionMs
        return bufferedPositionMs >= durationMs - END_SLACK_MS && totalBufferedMs - left >= NEXT_LOADED_MS
    }

    /**
     * Whether a held end is the end of the song: as long as what was left of it when it began to be
     * held, give or take [TAIL_SLACK_MS]. The sink drains its chain whenever a stream is configured
     * again, and a stream configured again in the middle of a song would otherwise pass for its end.
     */
    fun tailComplete(heldMs: Long, expectedMs: Long): Boolean =
        heldMs >= expectedMs - TAIL_SLACK_MS && heldMs <= expectedMs + TAIL_SLACK_MS

    /**
     * The first frame a plan may change anything on, with [readMark] frames already given out when
     * the work starts and [workMs] expected for it: past what will be out by the time the plan is
     * in, with a second to spare, which is what the held end asks of a plan before it takes it.
     */
    fun earliestChange(readMark: Long, sampleRate: Int, workMs: Long): Long =
        readMark + (workMs + 1_000L) * sampleRate / 1_000L

    /** What the next plan is given, from how long the last one took: half as long again, two seconds at the least. */
    fun workAllowanceMs(lastWorkMs: Long?): Long =
        lastWorkMs?.let { (it * 3 / 2).coerceIn(MIN_WORK_MS, FIRST_WORK_MS * 2) } ?: FIRST_WORK_MS

    private const val WINDOW_LOOK_MS = 500L
    private const val MAX_WAIT_MS = 60_000L
    private const val END_SLACK_MS = 300L
    private const val MIN_WORK_MS = 2_000L
}
