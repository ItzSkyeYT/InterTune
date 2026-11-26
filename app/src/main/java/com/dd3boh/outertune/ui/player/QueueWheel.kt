/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset

/**
 * The queue's wheel (Ti-Die, #testers 26 Nov 2026: "the queue menu haptics (I will die on the spinning wheel idea hill)"):
 * a light tick each time a song's row crosses the middle of the list as the finger or a fling scrolls it, like the
 * detents of a picker wheel. At most one tick per row, none sooner than [MIN_GAP_MS] after the last, and none when the
 * app scrolls the list itself (the playing song brought into view, a row put back after a drag): [gate] is told only of
 * the finger's own scrolls.
 *
 * What is felt is the Vibration setting's: a low tick under its steps, Light's own, nothing under Off (Haptics).
 */
internal class QueueWheel {
    /** The row that was in the middle at the last look, ticked for or not; null before the first. */
    private var row: Int? = null
    private var lastTickAt = Long.MIN_VALUE / 2
    private var fingerAt = Long.MIN_VALUE / 2

    /** True while a drag or fling that [movesListFromHeldRow] runs is going: its scrolls are not reported by the list's nested scroll. */
    @Volatile
    var holding = false

    /** The finger, or a fling of it, moved the list at [now] (uptime ms). */
    fun byFinger(now: Long) {
        fingerAt = now
    }

    private fun fingerIsOn(now: Long) = holding || now - fingerAt <= FINGER_MS

    /**
     * Whether the list, whose middle row is now [middle], gives a tick at [now]. The row is always remembered, ticked
     * for or not, so a jump by the app does not tick later, as the next row in line.
     */
    fun crossed(middle: Int?, now: Long): Boolean {
        val before = row
        row = middle
        if (middle == null || before == null || middle == before) return false
        if (!fingerIsOn(now)) return false
        if (now - lastTickAt < MIN_GAP_MS) return false
        lastTickAt = now
        return true
    }

    /** The list's nested scroll as it is offered to a parent: every scroll that comes through it is the finger's or a fling's. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y != 0f) byFinger(android.os.SystemClock.uptimeMillis())
            return Offset.Zero
        }
    }

    /** A row of the list as it is laid out: its place in the list, where it starts and its height, in pixels. */
    data class Row(val index: Int, val offset: Int, val size: Int)

    companion object {
        /** A tick no sooner than this after the last: a fast fling crosses rows faster than a finger could tell. */
        const val MIN_GAP_MS = 40L

        /** A scroll of the finger's is still the finger's this long after the last it reported: a fling reports every frame. */
        const val FINGER_MS = 100L

        /** The row across the middle of the list's viewport, from [start] to [end], or null where none is. */
        fun middleRow(rows: List<Row>, start: Int, end: Int): Int? {
            val middle = (start + end) / 2
            return rows.firstOrNull { middle >= it.offset && middle < it.offset + it.size }?.index
        }
    }
}
