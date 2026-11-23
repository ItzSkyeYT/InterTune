/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue

/**
 * What playing something does on the phone in control of a listening room (Ti-Die, #testers 23 Nov 2026: "someone can
 * click and it clears everything ... just make it functionally an 'add next + skip' ... that way the songs everyone
 * added after doesn't go away"). The room's queue is that phone's own (TogetherSession.ownQueue), and every start made a
 * new one: the songs the others had added stayed behind in the old queue, which nobody in the room sees.
 *
 * - A song tapped (a row of a list, a card, a search result, a song's own Play) plays as Play next and a skip to it: it
 *   goes right after the song playing and the room moves to it, and every song after stays where it was ([step]).
 * - A whole album, playlist, folder or radio (its Play or Shuffle, Start radio) replaces what the room plays, and the songs
 *   put in by hand that have not played yet, anyone's, go on right after its first song, a line saying so ([handAdded],
 *   [toPutBack]). That is how Spotify's queue works, a Jam's included: what was added by hand ("Next in queue") plays
 *   before the album or playlist playing ("Next from") and is kept when another one starts. Apple Music keeps its
 *   "Playing Next" too unless you choose to clear it.
 *
 * Outside rooms, and on a phone that follows someone (whose own tap takes it its own way), every start is as it was.
 */
internal object RoomTap {
    /**
     * The song [queue] is started on when the listener tapped that song: a row picked out of a list, the only song of a
     * list, the song a card or a search result starts its radio from (DontPlaySkip.startIsPick, the same three). Null for
     * a whole list or radio, and for Start radio asked for on a song ([radioAsked]), whose queue looks like a card's.
     */
    fun songOf(queue: Queue, radioAsked: Boolean): MediaMetadata? {
        if (radioAsked) return null
        queue.preloadItem?.let { return it }
        val list = queue as? ListQueue ?: return null
        return when {
            // Held to the list as the start holds it (Queue.Status.withPreload).
            list.songPicked -> list.items.getOrNull(list.startIndex.coerceIn(0, list.items.lastIndex.coerceAtLeast(0)))
            list.items.size == 1 -> list.items.first()
            else -> null
        }
    }

    /** Where a song tapped in a room plays from. */
    sealed interface Step {
        /** It is the song playing: it plays on from where it is, as a tap on it does anywhere. */
        data object Playing : Step

        /** It is further on in the queue already, at [from]: moved right after the song playing, to [to], and played. */
        data class Move(val from: Int, val to: Int) : Step

        /** It is not on its way: put in at [at], right after the song playing, and played. */
        data class Insert(val at: Int) : Step
    }

    /**
     * Where the song [tapped] plays from, in the queue [ids] (play order) whose song at [current] plays. A copy of it
     * after the song playing is the one taken, so it does not play twice; one before it has played and stays.
     */
    fun step(ids: List<String>, current: Int, tapped: String): Step {
        if (ids.getOrNull(current) == tapped) return Step.Playing
        val from = RoomArrange.placeOf(ids, tapped, current) ?: return Step.Insert(current + 1)
        return Step.Move(from, RoomArrange.moveTo(ids, from, after = null, current = current) ?: (current + 1))
    }

    /**
     * Of the queue [order] (play order) whose song at [current] plays, the songs after it put in by hand, [byHand] holding
     * their ids (Play next, Add to queue, a guest's song, a proposal played, a song dragged into place): each once, in the
     * order they stand.
     */
    fun <T : Any> handAdded(order: List<T>, current: Int, byHand: Set<String>, id: (T) -> String): List<T> {
        val seen = HashSet<String>()
        return order.drop((current + 1).coerceAtLeast(0)).filter { id(it) in byHand && seen.add(id(it)) }
    }

    /**
     * Of [kept], the songs a whole start left out of the queue that plays now, to go back in right after its song: not
     * one still among [ahead], the songs after the song playing, the very objects (a list of the same title that refills
     * the queue drops them, one added to it keeps them), nor the song playing itself, [playingId], which would play twice.
     */
    fun <T : Any> toPutBack(kept: List<T>, ahead: List<T>, playingId: String?, id: (T) -> String): List<T> =
        kept.filter { k -> id(k) != playingId && ahead.none { it === k } }

    /**
     * The whole starts made in a room while one is still waiting for its answer (a radio's, a playlist's from YouTube),
     * so that the songs kept from the queue the room played go where the last of them lands. A start dropped for a newer
     * one (QueueStart's last tap wins) puts nothing back: the newer one takes over what it kept, and the queue it came
     * from, which a radio's queue of one has replaced as the queue playing meanwhile. A song tapped ends every start still
     * waiting ([tap]). Main thread.
     */
    class Starts<Q : Any, S : Any> {
        private var number = 0L
        private var from: Q? = null
        private val kept = ArrayList<S>()

        /**
         * A start begins. In a room, [queue] is the queue playing and [songs] those kept from it; [songs] null for a start
         * outside one, which ends every start still waiting, nothing kept. The start's number, null outside a room.
         */
        fun begin(queue: Q?, songs: List<S>?): Long? {
            number++
            if (songs == null) {
                take()
                return null
            }
            if (from == null) from = queue
            songs.forEach { s -> if (kept.none { it === s }) kept += s }
            return number
        }

        /** The start numbered [n] has loaded or failed: the queue the room played and the songs kept, when it is the last start; else null. */
        fun end(n: Long): Pair<Q?, List<S>>? = if (n == number) take() else null

        /** A song tapped in the room: every start still waiting is over, and what they kept goes back after that song. */
        fun tap(): Pair<Q?, List<S>> {
            number++
            return take()
        }

        private fun take(): Pair<Q?, List<S>> = (from to kept.toList()).also {
            from = null
            kept.clear()
        }
    }
}
