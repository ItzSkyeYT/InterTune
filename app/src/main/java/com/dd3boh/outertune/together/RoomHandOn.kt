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
 * is not the host, else whoever has been in the room longest ([heirOf]). The host's word is its own Here naming that
 * phone twice (TogetherSession.handOnAndLeave): as the one in control ([TogetherMessage.Here.lead]), which every build
 * takes from its host, and as the room's host now ([TogetherMessage.Here.host]), which builds from 24 Nov read: they
 * take the new host's word from then on, on who leads, its settings, its close, whom it sends out.
 *
 * The host stays until that phone says it hosts the room, its own Here as host, and says its word again every couple of
 * seconds meanwhile ([handing]): said once, the word was lost on a phone whose connection dropped or that was restarting,
 * which then never hosted while the others waited on it (review of 0.13, 26 Nov 2026). A phone that has not taken it
 * within [ANSWER_MS], or that leaves, is passed over for the next one; with nobody left, the room is closed for everyone.
 * The others of this build take the word once the phone it names says it hosts the room ([hostAfter]): until then the
 * host that leaves is still theirs, and so is its next word, for another phone or the close.
 *
 * A build before this one never says it takes a room ([TogetherMessage.Here.takesRoom]), so it is never handed one. In a
 * room handed on it follows the new host as the one in control and takes its close (RoomEnds: the new host's Here says
 * it hosts the room), but none of its other words: such a host never gives it control ([canLead]), since it would never
 * lead, and when that host sends someone out it is left behind on the old code, where it hears nothing ("The host is
 * away") and plays on by itself until it leaves. A build of 24 Nov takes the first copy of the host's word it reads and
 * no other word of that host's after it, as it did: it answers as this one does when it is the one named.
 */
/**
 * What the host's Leave offers (TogetherSession.leaveChoice, RoomLeaveDialog): [heir], the name of whoever would get the
 * room, [heirKey] their key in it; null when nobody here can take it, and then [olderOnly] when others are there, all of
 * them on older builds.
 */
data class RoomLeaveChoice(val heir: String?, val olderOnly: Boolean, val heirKey: String? = null)

internal class RoomHandOn {
    /** When each phone in the room was first heard, for as long as it is heard: who has been there longest. */
    private val since = HashMap<String, Long>()

    /** Those whose Here says they take a room handed to them. */
    private val takers = HashSet<String>()

    /** Whose the room is, as each said it: the phone a key handed the room to, by its newest word. Kept whoever said it (see [hostAfter]). */
    private val handed = HashMap<String, Handed>()

    /** [to] is the room's host now, by a host's word given at [at] on its own clock ([TogetherMessage.Here.leadSince]). */
    private class Handed(val to: String, val at: Long?)

    /** This phone was handed the room by its host: see [canLead]. */
    var handedHere = false

    /**
     * This phone, the room's host, is leaving it and hands it to [Handing.to], in its word given at [Handing.since] on its
     * own clock, since [Handing.at] waiting for that phone to say it hosts the room. Null while nothing is handed on.
     */
    var handing: Handing? = null
        private set

    class Handing(val to: String, val since: Long, val at: Long)

    /** Those this phone handed the room to that did not take it in time, or left: never handed it again. */
    private val passed = HashSet<String>()

    /** The newest word on who leads this phone gave as it handed the room on: each word after it is newer. */
    private var lastSince = Long.MIN_VALUE

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

    /**
     * Who gets the room as this phone, [me], leaves it, with [leader] in control and each phone last heard as in
     * [lastHeard]: never one already handed it that did not take it.
     */
    fun heir(me: String, leader: String?, lastHeard: Map<String, Long>, now: Long): String? =
        heirOf(me, leader, since, takers - passed, lastHeard, now)

    /** Whether others are heard lately, none of whom can be handed the room: everyone else is on an older build. */
    fun olderOnly(me: String, lastHeard: Map<String, Long>, now: Long): Boolean =
        heirOf(me, null, since, takers, lastHeard, now) == null && lastHeard.any { (key, at) -> key != me && now - at <= FRESH_MS }

    /** [from] said, in its word given at [at] (its own clock), that the room is [to]'s now: its newest word counts. */
    fun handed(from: String, to: String, at: Long?) {
        if (from == to) return
        val before = handed[from]?.at
        if (before != null && at != null && at < before) return
        handed[from] = Handed(to, at)
    }

    /**
     * Who hosts the room after [host], its host as this phone knew it: of the phones that one handed it to, and on, the
     * last that says it hosts the room now ([hosts]), or this phone, [me], when it is the one named. A host waits for that
     * word as it leaves, and names another phone when none comes: until then the room is its own. Relays do not keep
     * order, so a word from a phone that is not the host yet is kept, and counts once that phone is (a host handed the
     * room on, the next one handed it on again, and the second word came first).
     */
    fun hostAfter(host: String, me: String, hosts: (String) -> Boolean): String {
        var at = host
        var taken = host
        val seen = hashSetOf(host)
        while (true) {
            val next = handed[at]?.to ?: break
            if (!seen.add(next)) break
            if (next == me || hosts(next)) taken = next
            at = next
        }
        return taken
    }

    /**
     * This phone, the host, hands the room to [to] at [now]: its word newer than [after], its last word on who leads, and
     * than every word it handed the room on with before, so that every build takes the newest.
     */
    fun handTo(to: String, now: Long, after: Long): Handing {
        val word = maxOf(now, after + 1, lastSince + 1)
        lastSince = word
        return Handing(to, word, now).also { handing = it }
    }

    /** The phone this one handed the room to did not take it, or left: the next one is asked, never it again. */
    fun passOver() {
        handing?.let { passed += it.to }
        handing = null
    }

    /** Whether the phone this one hands the room to has had [ANSWER_MS] to take it by [now], and has not. */
    fun unanswered(now: Long): Boolean = handing?.let { now - it.at >= ANSWER_MS } == true

    /**
     * Whether this phone, as host, may give control to [key]. A build before this one takes only the first host's word on
     * who leads: given control by a host that was handed the room, it would never lead, and nobody else would either.
     */
    fun canLead(key: String): Boolean = !handedHere || key in takers

    companion object {
        /** Heard within this long, or not handed the room: everyone says they are there every ten seconds. */
        const val FRESH_MS = 2 * TogetherMessage.HERE_MS

        /**
         * How long a host that leaves waits for the phone it hands the room to to say it hosts it, its word said again
         * every couple of seconds meanwhile: about a second on a working line, and time for a connection that dropped to
         * come back.
         */
        const val ANSWER_MS = 15_000L

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
