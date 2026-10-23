/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject

/**
 * The songs put in with Play next that have not played yet, so that a song picked out of a list
 * keeps them, as Spotify's "Next in queue" does (issue #42): they go on right after the picked
 * song, in the order they stood, and the rest of the list follows them. Before this a pick left
 * them behind in the queue it replaced, or wiped them with it when the list was that queue's own.
 * Behind Unreleased.PLAY_NEXT_STAYS; MusicService keeps one.
 *
 * Kept as the very songs the queue holds: Play next puts copies in, each its own object, so a
 * song put in that is also in the list, or put in twice, is told from its other copies. What has
 * played since, been skipped past, swiped out of the sheet or gone with its queue is simply no
 * longer found ahead of the song playing, so nothing has to be told when a song plays or goes.
 * The board read again from the database (a scan, a new Max queues) makes new objects of every
 * song: nothing is found then, and the songs stay where they are, as they always did. In memory
 * only: after a restart they are in the saved queue as before, and a pick no longer takes them.
 *
 * Only a pick takes them, not a list's Play or Shuffle button, nor a radio: those start something
 * new as they always have. Main thread only.
 */
internal class PlayNextWaiting {

    /** What a pick takes along: [songs], waiting in [from], in play order. */
    class Taken(val from: MultiQueueObject, val songs: List<MediaMetadata>)

    private var queueId: Long? = null
    private val songs = ArrayList<MediaMetadata>()

    /**
     * Play next has just put [count] songs into [queue] at [at] in play order (where
     * MultiQueueObject.insertAtPlayIndex put its copies). Songs noted for another queue are
     * forgotten: they stay in it, as before.
     */
    fun putIn(queue: MultiQueueObject, at: Int, count: Int) {
        if (queueId != queue.id) {
            songs.clear()
            queueId = queue.id
        }
        val order = queue.getCurrentQueueShuffled()
        val from = at.coerceIn(0, order.size)
        songs.addAll(order.subList(from, (from + count).coerceIn(from, order.size)))
    }

    /**
     * The songs still waiting in [queue], the queue playing, after its song at play place
     * [current], in the order they stand there; null when there are none, or when they were put
     * into another queue. What is no longer ahead is forgotten.
     */
    fun take(queue: MultiQueueObject?, current: Int): Taken? {
        if (queue == null || queue.id != queueId) return null
        val order = queue.getCurrentQueueShuffled()
        val ahead = order.subList((current + 1).coerceIn(0, order.size), order.size)
        val waiting = ahead.filter { s -> songs.any { it === s } }
        songs.retainAll { s -> waiting.any { it === s } }
        return if (waiting.isEmpty()) null else Taken(queue, waiting)
    }

    /** [kept], the copies of the songs taken, wait in [queue] now. */
    fun movedTo(queue: MultiQueueObject, kept: List<MediaMetadata>) {
        queueId = queue.id
        songs.clear()
        songs.addAll(kept)
    }

    companion object {
        /**
         * Of [taken], the songs that go on after the picked song [picked]: not the picked song
         * itself, which is playing now, as Play next wanted, and would otherwise play twice.
         */
        fun toKeep(taken: List<MediaMetadata>, picked: String?): List<MediaMetadata> = taken.filter { it.id != picked }
    }
}
