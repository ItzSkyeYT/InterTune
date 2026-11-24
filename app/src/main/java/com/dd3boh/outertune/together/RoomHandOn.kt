/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * A host that leaves without closing its room (his find of 23 Nov 2026, hosting three testers: "there's no way to leave
 * the room without closing it"). Its Leave offers two ways: hand the room on and leave, the others carrying on, or close
 * it for everyone as before.
 *
 * Handed on, the room goes to one phone, which hosts it from then on and is in control: whoever is in control when that
 * is not the host, else whoever has been in the room longest ([heirOf]). The host's last word is its own Here naming that
 * phone twice (TogetherSession.handOnAndLeave): as the one in control ([TogetherMessage.Here.lead]), which every build
 * takes from its host, and as the room's host now ([TogetherMessage.Here.host]), which builds from this one read: they
 * take the new host's word from then on, on who leads, its settings, its close, whom it sends out.
 *
 * A build before this one never says it takes a room ([TogetherMessage.Here.takesRoom]), so it is never handed one. In a
 * room handed on it follows the new host as the one in control and takes its close (RoomEnds: the new host's Here says
 * it hosts the room), but none of its other words: such a host never gives it control ([canLead]), since it would never
 * lead, and when that host sends someone out it is left behind on the old code, where it hears nothing ("The host is
 * away") and plays on by itself until it leaves.
 */
/**
 * What the host's Leave offers (TogetherSession.leaveChoice, RoomLeaveDialog): [heir], the name of whoever would get the
 * room; null when nobody here can take it, and then [olderOnly] when others are there, all of them on older builds.
 */
data class RoomLeaveChoice(val heir: String?, val olderOnly: Boolean)

internal class RoomHandOn {
    /** When each phone in the room was first heard, for as long as it is heard: who has been there longest. */
    private val since = HashMap<String, Long>()

    /** Those whose Here says they take a room handed to them. */
    private val takers = HashSet<String>()

    /** Whose the room is, as each said it: the phone a key handed the room to. Kept whoever said it (see [hostAfter]). */
    private val handed = HashMap<String, String>()

    /** This phone was handed the room by its host: see [canLead]. */
    var handedHere = false

    /** A word from [key] at [now]. */
    fun heard(key: String, now: Long) {
        since.putIfAbsent(key, now)
    }

    /** [key]'s Here says it takes a room handed to it, or not. */
    fun said(key: String, takes: Boolean) {
        if (takes) takers += key else takers -= key
    }

    /** Only the phones still heard, [keys]: one heard again after a while away has been there since it came back. */
    fun keep(keys: Set<String>) {
        since.keys.retainAll(keys)
        takers.retainAll(keys)
    }

    /** Who gets the room as this phone, [me], leaves it, with [leader] in control and each phone last heard as in [lastHeard]. */
    fun heir(me: String, leader: String?, lastHeard: Map<String, Long>, now: Long): String? =
        heirOf(me, leader, since, takers, lastHeard, now)

    /** Whether others are heard lately, none of whom can be handed the room: everyone else is on an older build. */
    fun olderOnly(me: String, lastHeard: Map<String, Long>, now: Long): Boolean =
        heir(me, null, lastHeard, now) == null && lastHeard.any { (key, at) -> key != me && now - at <= FRESH_MS }

    /** [from] said the room is [to]'s now. */
    fun handed(from: String, to: String) {
        if (from != to) handed[from] = to
    }

    /**
     * Who hosts the room after [host], its host as this phone knew it: the phone that one handed it to, and on. Relays do
     * not keep order, so a word from a phone that is not the host yet is kept, and counts once that phone is (a host
     * handed the room on, the next one handed it on again, and the second word came first).
     */
    fun hostAfter(host: String): String {
        var now = host
        val seen = HashSet<String>()
        while (seen.add(now)) now = handed[now] ?: break
        return now
    }

    /**
     * Whether this phone, as host, may give control to [key]. A build before this one takes only the first host's word on
     * who leads: given control by a host that was handed the room, it would never lead, and nobody else would either.
     */
    fun canLead(key: String): Boolean = !handedHere || key in takers

    companion object {
        /** Heard within this long, or not handed the room: everyone says they are there every ten seconds. */
        const val FRESH_MS = 2 * TogetherMessage.HERE_MS

        /**
         * Who gets the room as [me] leaves it: [leader], in control, when that is someone else who can take it; else, of
         * those who can ([takers]), the one first heard ([since]). Only one heard within [FRESH_MS]: handed to a phone that
         * has gone, the room would have no host. Null for nobody.
         */
        fun heirOf(me: String, leader: String?, since: Map<String, Long>, takers: Set<String>, lastHeard: Map<String, Long>, now: Long): String? {
            val ready = since.keys.filter { key -> key != me && key in takers && lastHeard[key]?.let { now - it <= FRESH_MS } == true }
            if (leader != null && leader in ready) return leader
            return ready.minWithOrNull(compareBy<String>({ since.getValue(it) }, { it }))
        }

        /** [text] when it is a phone's key in a room (64 hex characters, as Nostr writes them), else null. */
        fun keyOf(text: String?): String? = text?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }
}
