/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * Who said they made the room and who said it is over, for the words that come before this phone knows who made it
 * (21 Nov 2026). Relays hand on what they kept in any order, the newest often first: a guest heard the owner's close
 * before the owner's word that it made the room, passed the close over as a stranger's, then took the owner's older
 * words for a room that was alive. Only the one who made the room can close it, so a close from someone not known yet
 * is kept, and ends the room once that someone turns out to be the one who made it.
 */
internal class RoomEnds {
    private val owners = HashSet<String>()
    private val enders = HashSet<String>()

    /** [key] says it made the room. True when it is also one that said the room is over. */
    fun owner(key: String): Boolean {
        owners += key
        return key in enders
    }

    /** [key] says the room is over. True when it is one that said it made the room. */
    fun end(key: String): Boolean {
        enders += key
        return key in owners
    }
}

/**
 * When a guest that joins now gives up on a room that does not answer (his word of 21 Nov 2026: "the join button has to
 * serve as the last check if anyone is in the room"). The host answers a newcomer at once, the leader sends where it is
 * every two seconds and the host says it is there every ten, so a live room answers within seconds of a relay coming
 * up. [WAIT_MS] of silence with a relay up is a room that is closed, a host whose phone is off or asleep, or a wrong
 * code. The wait counts only while a relay answers: no network is not an empty room.
 */
internal object JoinCheck {
    const val WAIT_MS = 15_000L

    /** Nobody answered: looked for since [lookingSince] (moved on while no relay answers), [relays] answering now. */
    fun nobody(now: Long, lookingSince: Long, relays: Int): Boolean = relays > 0 && now - lookingSince > WAIT_MS
}
