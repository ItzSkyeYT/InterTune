/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * Who is in the room, by when each phone was last heard (TogetherSession's members, their keys in the room). A phone that
 * says nothing for [timeoutMs] has gone, and comes off the list at the room's next look ([dropQuiet]), whether or not
 * anyone else speaks. Only a word coming in took anyone off before, so the last guest to leave stayed on the host's list
 * for good (Niko's room, 23 Nov 2026: "your ghost is in the room ... but its only for you", while Ti-Die's leave showed,
 * Mel still speaking after it). Each room has its own, empty until someone is heard in it live: a word a relay kept from
 * before never adds anyone (Incoming.live).
 */
internal class RoomPresence(
    private val timeoutMs: Long,
    private val lastHeard: MutableMap<String, Long> = LinkedHashMap(),
) : MutableMap<String, Long> by lastHeard {

    /** Takes off whoever was last heard more than [timeoutMs] before [now]; true when anyone was. */
    fun dropQuiet(now: Long): Boolean = lastHeard.entries.removeAll { now - it.value > timeoutMs }
}
