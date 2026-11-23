/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlin.math.abs

/**
 * How a room has gone lately, in one line for Report a problem, logged once a minute: whether this
 * phone made the room or joined it and whether it leads, how many relays answer, the round trip to
 * the leader, and how close it followed since the last line. Never the room's code or anyone's
 * name: a report can be posted in public, and the code lets anyone in. No Android in here, so it
 * is tested (RoomHealthTest).
 *
 * Why: his dad's report of 26 Oct 2026, from a room, said nothing about the room at all.
 */
class RoomHealth {
    /** How far from the leader at each look since the last line, in ms either way. */
    private val gaps = ArrayList<Long>()

    /** One look at a follower playing: [driftMs] from the leader, positive behind. */
    @Synchronized
    fun drift(driftMs: Long) {
        if (gaps.size < MAX_LOOKS) gaps += abs(driftMs)
    }

    /** The line, and a fresh start for the next one. */
    @Synchronized
    fun line(
        owner: Boolean,
        leading: Boolean,
        others: Int,
        relaysAnswering: Int,
        relays: Int,
        /** The fastest round trip to the leader so far, null before the first answer or when leading. */
        roundTripMs: Long?,
        waitingForLeader: Boolean = false,
        ownWay: Boolean = false,
        pausedHere: Boolean = false,
    ): String = buildString {
        append(if (owner) "made here" else "joined")
        append(
            when {
                leading -> ", leading"
                waitingForLeader -> ", waiting for the leader"
                pausedHere -> ", following, paused here"
                ownWay -> ", following, playing on its own"
                else -> ", following"
            }
        )
        append("; ").append(if (others == 1) "1 other" else "$others others")
        append("; $relaysAnswering of $relays relays answering")
        if (!leading && roundTripMs != null) append("; round trip $roundTripMs ms")
        if (gaps.isNotEmpty()) {
            val sorted = gaps.sorted()
            append("; half the time within ${sorted[(sorted.size - 1) / 2]} ms of the leader, ${sorted.last()} ms at worst")
        }
        gaps.clear()
    }

    companion object {
        /** A line a minute: a room of an hour is sixty lines, a song's worth of report. */
        const val EVERY_MS = 60_000L

        /** A minute of looks four times a second, and some to spare. */
        private const val MAX_LOOKS = 600
    }
}
