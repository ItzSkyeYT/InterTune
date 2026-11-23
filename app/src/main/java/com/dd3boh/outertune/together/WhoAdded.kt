/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * Who put each song of the room's queue in it, as this phone knows it, so that only this phone's own songs teach its
 * recommendations (his word of 23 Nov 2026, in a room with a tester: "for songs added to queue by users make it not
 * count towards your engine"). A song someone else put in, a guest's while this phone is in control, the host's or
 * another guest's while it follows, a proposal someone made, is heard here and goes into the history, Stats and the
 * Recap, and teaches nothing: its listen is written as one not to learn from (MusicService.noteTransition), and the
 * adaptive queue's run takes no keep, skip or like from it (AdaptiveQueue.Heard). A song this phone put in itself
 * teaches as ever.
 *
 * By song, as a room knows its queue by song (RoomArrange), and by the key each phone signs as in the room, [me] for
 * this one. A song with no word on it never came through the room: this phone's own pick or its own radio. One that is
 * this phone's stays so for the room's life: a later word from the room never takes it away, and a song put in both
 * by this phone and by someone else is its own too.
 */
class WhoAdded(private val me: String, private val kept: Int = KEPT) {
    /** Song to the key of the phone that put it in, [me] for this one, the newest word last. */
    private val by = LinkedHashMap<String, String>()

    /** The phone with [key] put [id] in the room's queue: kept, unless it is this phone's own already. */
    fun put(id: String, key: String) {
        val owner = if (by[id] == me) me else key
        by.remove(id)
        by[id] = owner
        while (by.size > kept) by.remove(by.keys.first())
    }

    /** This phone put [ids] in itself: a guest's Play next or Add to queue, the song it proposes. */
    fun mine(ids: Collection<String>) = ids.forEach { put(it, me) }

    /**
     * The room's [songs] came into this phone's queue in a word of [sender]'s: each put in by the phone its word names, or
     * by the sender itself when it names none, as it never does for its own songs and builds before this one never do
     * for any (an older member's song is not this phone's, unless this phone put it in too).
     */
    fun came(songs: Collection<TogetherMessage.Song>, sender: String) = songs.forEach { put(it.id, it.by ?: sender) }

    /**
     * The room plays [id], in a word of [sender]'s that names nobody (its playback): the sender's, unless a word already
     * said whose it is, as the queue it came in did.
     */
    fun playing(id: String, sender: String) {
        if (id !in by) put(id, sender)
    }

    /**
     * This phone sends [id] to the room, in its queue or a proposal: whom to name for it, null when it is this phone's own.
     * A song with no word on it is, from now on.
     */
    fun sent(id: String): String? {
        val owner = by[id]
        if (owner == null) put(id, me)
        return owner?.takeIf { it != me }
    }

    /** Whether [id] teaches this phone's recommendations: its own, or a song that never came through the room. */
    fun learns(id: String): Boolean = by[id].let { it == null || it == me }

    /** What a room that moves to a new code takes with it: this phone's own songs and the others', whose keys change. */
    fun carried(): Carried = Carried(
        mine = by.filterValues { it == me }.keys.toList(),
        theirs = by.filterValues { it != me }.keys.toList(),
    )

    /** Takes over what the room had before its move ([carried]): the others' songs as someone's whose new key is not known. */
    fun takeOver(carried: Carried) {
        carried.theirs.forEach { put(it, SOMEONE) }
        mine(carried.mine)
    }

    /** The songs of a room before its move, by whose they were. */
    data class Carried(val mine: List<String>, val theirs: List<String>)

    companion object {
        /** Songs remembered, the oldest word forgotten first: a long evening of thirty-song queues. */
        const val KEPT = 2_000

        /** Someone else in the room whose key is not known here: a room moved to a new code has new keys for everyone. */
        const val SOMEONE = "-"

        /** What a word may name as whoever put a song in: a phone's key in the room, or [SOMEONE]. */
        private val KEY = Regex("[0-9a-f]{64}")

        /** [by] as read from a word, or null for anything that names nobody (TogetherMessage.Song.by). */
        fun named(by: String?): String? = by?.takeIf { it == SOMEONE || KEY.matches(it) }
    }
}
