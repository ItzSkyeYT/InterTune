/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * Who is in control of a room, as this phone last told its person (testers, 22 Nov 2026: control changed hands and
 * nobody noticed; dvd, given it, asked "wait so do I just control it now or", and the host found out minutes later
 * that it had come back to him). Each phone tells its own person once, as the owner's word on who leads changes:
 * "You're in control now", or "dvd is in control now", the owner included. What a phone learns first is no change:
 * a guest that has just joined is told nothing, nor a room that comes back to the one it had (RoomMemory).
 */
internal class ControlNews(before: String? = null) {
    /** Who was in control as last told, or as first learned; null before anything is known. */
    var told: String? = before
        private set

    /** [key] is in control now: true when that is news, someone other than the one last told. */
    fun now(key: String): Boolean {
        val was = told
        told = key
        return was != null && was != key
    }
}

/**
 * The owner's word on who leads, by when the owner gave the lead ([TogetherMessage.Here.leadSince], its own clock).
 * Relays do not keep order and some hand on words they kept: an older word coming after a newer one gave the lead back
 * to the phone that had it before, two phones led at once, and control seemed to change hands by itself.
 */
internal class LeadWord {
    private var newest = Long.MIN_VALUE

    /** Whether a word on who leads, given at [since] (null from builds before 22 Nov 2026), is taken: never an older one. */
    fun take(since: Long?): Boolean {
        if (since == null) return true
        if (since < newest) return false
        newest = since
        return true
    }
}

/**
 * When the owner takes control back from the phone it gave it to: once that phone has said nothing at all for
 * [QUIET_MS], counted from when it got control, from its last word of any kind, and from when this phone could hear the
 * room again. Not when its playback alone went quiet, and never while no relay answers here: the silence is then this
 * phone's own, and control came back to the host from a phone still playing for everyone else, without anyone knowing.
 * A phone that has gone for good is taken from all the same, so that the room never waits on it.
 */
internal object LeaderGone {
    const val QUIET_MS = 25_000L

    /**
     * A look this long after the one before is a phone that slept, or was frozen in the background: what it did not hear
     * meanwhile is its own silence. The room looks every two seconds at most (lookEveryMs).
     */
    const val ASLEEP_MS = 10_000L

    fun now(nowMs: Long, givenAt: Long, heardAt: Long, hearingSince: Long, relaysAnswering: Int): Boolean =
        relaysAnswering > 0 && nowMs - maxOf(givenAt, heardAt, hearingSince) > QUIET_MS
}
