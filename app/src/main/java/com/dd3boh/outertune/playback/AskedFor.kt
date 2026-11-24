/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * The songs somebody started a play of, each asked of YouTube once more even when the app has set
 * it aside as gone (StandIns' setAside).
 *
 * A song set aside is not asked again when the app comes to it by itself: the queue or a radio
 * reaching it, the next song read ahead, a try again of the app's own. That keeps YouTube from
 * being asked over and over for a song it has taken down. A play somebody starts is another
 * matter. On 23 Nov 2026 a tester in a listening room could not play a song the two others heard:
 * his phone had set it aside, and the room's song, its one more try and Retry all met that note
 * without asking anything ("not asked: set aside as gone" in his error report).
 *
 * So a tap on a song, Play next, Add to queue, the song a room moves to, a radio started from a
 * song and Retry on the player's error each give that song one ask: the player's next fetch of it
 * goes to YouTube whatever was noted. If YouTube serves it, the note goes (StandIns, GoneSongs).
 * If it still calls it gone, the player says so in plain words, and what comes after is the app's
 * own again: its tries are not asks.
 *
 * Given before the player is told, so that no read of the song can come first. One start, one
 * ask: a start that loads its song twice (alone at the tap, then with the rest of its queue,
 * QueueStart) names itself with the same start both times and gives one.
 *
 * In memory only, the last [max] songs: an ask never used costs one more question at most.
 */
internal class AskedFor(private val max: Int = MAX) {
    /** The songs with an ask not used yet, the oldest first. */
    private val waiting = LinkedHashSet<String>()

    /** For each song, the start that last gave it an ask, where the start named itself. */
    private val startOf = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > max
    }

    /**
     * Somebody started a play of [id]. [start] names the start, when one start can give the same
     * song twice: the second time gives nothing.
     */
    @Synchronized
    fun asked(id: String, start: Long? = null) {
        if (id.isEmpty()) return
        if (start != null) {
            if (startOf[id] == start) return
            startOf[id] = start
        }
        waiting.remove(id)
        waiting.add(id)
        while (waiting.size > max) waiting.remove(waiting.first())
    }

    /**
     * Somebody put [ids] in to play, in that order. Past [max], the first of them keep their ask,
     * as they play first.
     */
    @Synchronized
    fun asked(ids: List<String>) {
        for (id in ids.asReversed()) asked(id)
    }

    /** Whether [id] has an ask not used yet, and uses it: true once for each start. */
    @Synchronized
    fun take(id: String): Boolean = waiting.remove(id)

    companion object {
        /** Far more songs than are put in at once, Play next on a whole album included. */
        const val MAX = 500
    }
}

/**
 * The songs a phone following a listening room gives an ask ([AskedFor]): the one the room plays
 * and the one after it. The next one too, since the player reads it ahead near the end of a song
 * and moves on to it by itself as the leader does: a room's song is mostly reached that way, with
 * nothing of the room's moving the player. Each once while it is one of the two, so that a song
 * that failed here, and that the room tries once more a little later, is not asked again then.
 */
internal class RoomAsks {
    private var asked = emptySet<String>()

    /** The room plays [current], with [next] after it when known: the songs to give an ask now. */
    @Synchronized
    fun now(current: String, next: String?): List<String> {
        val both = setOfNotNull(current, next)
        return (both - asked).toList().also { asked = both }
    }
}
