/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.engine.HeardSet
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
import java.util.concurrent.ConcurrentHashMap

/**
 * The queue of a Discover pick holds only songs never heard, by Discover's own rule ([HeardSet]: no
 * play of any length, not liked, saved, in a playlist, downloaded or a local file, nor another upload
 * of one). His word of 21 Nov 2026: a "Take It All Mix" started from a new song held a liked,
 * downloaded one.
 *
 * A Discover pick is a card of Discover something new ([PlayOrigin.DISCOVER]), or of Quick picks
 * while the row on screen is the one the engine built under the Discover chip, which Home knows at
 * the tap and says ([isPick]): the row the card was in, never the chip as it stands later. Every
 * other start keeps its queue as it always was.
 *
 * The set is read once when the queue starts and kept with it ([NeverHeardQueues]). The radio's
 * first page, the pages that top it up and the adaptive queue's picks all pass through it; the song
 * tapped stays, heard or not, since it was chosen. While too few pass, the queue asks for more pages
 * ([ENOUGH], [MORE_PAGES]), and the log says how many songs it passed over.
 *
 * No Android in here, so it is tested.
 */
object NeverHeardQueue {
    /** Fewer songs than this never heard after the one tapped, and the start asks for another page. */
    const val ENOUGH = 10

    /** Fewer than this of a top-up's page never heard, and it asks for another page. */
    const val ENOUGH_TOP_UP = 5

    /** The most pages asked for at once while too few songs pass. */
    const val MORE_PAGES = 4

    /** Whether a start keeps its queue to songs never heard. [discoverRow]: the card's row was built under the Discover chip. */
    fun isPick(origin: PlayOrigin, discoverRow: Boolean): Boolean =
        origin == PlayOrigin.DISCOVER || (discoverRow && origin == PlayOrigin.QUICK_PICKS)

    fun knows(heard: HeardSet, song: MediaMetadata): Boolean = heard.knows(song.id, song.title, song.artists.firstOrNull()?.name)

    /** Songs kept and how many were passed over. */
    data class Kept(val songs: List<MediaMetadata>, val passedOver: Int)

    /** [songs] without those [heard] knows, in their order; [keep], the song tapped, stays wherever it is. */
    fun keep(songs: List<MediaMetadata>, heard: HeardSet, keep: String? = null): Kept {
        val kept = songs.filter { it.id == keep || !knows(heard, it) }
        return Kept(kept, songs.size - kept.size)
    }

    /**
     * A start's answer kept to [heard], with the song it starts from, [start] (the song tapped,
     * already playing on its own), still the one it starts from, and how many were passed over.
     */
    fun status(answer: Queue.Status, heard: HeardSet, start: String?): Pair<Queue.Status, Int> {
        val from = start ?: answer.items.getOrNull(answer.mediaItemIndex)?.id
        val kept = keep(answer.items, heard, from)
        val at = kept.songs.indexOfFirst { it.id == from }.takeIf { it >= 0 } ?: 0
        return answer.copy(items = kept.songs, mediaItemIndex = at) to kept.passedOver
    }

    /** The log line of a queue kept to songs never heard. */
    fun said(where: String, kept: Int, passedOver: Int, pages: Int): String =
        "Discover queue, $where: $kept never heard kept, $passedOver heard passed over" + if (pages > 0) ", $pages more pages asked" else ""
}

/**
 * The queues kept to songs never heard ([NeverHeardQueue]), by id, each with the set it is kept to.
 *
 * The ids outlive a restart in the settings ([save], NeverHeardQueuesKey) and are read back with the
 * queues ([restore]); the sets are not saved, and a restored queue reads its set again from the
 * database the first time it needs one ([heardFor]), by the same query. Only the latest few sets
 * are held ([KEPT_SETS]), a few megabytes each in a long-used library: an older queue played again
 * reads its own once more. Thread safe: the queue's start, its top-ups and the adaptive pass ask
 * from different threads.
 */
class NeverHeardQueues(private val save: (Set<Long>) -> Unit) {
    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val sets = object : LinkedHashMap<Long, HeardSet>(KEPT_SETS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HeardSet>?): Boolean = size > KEPT_SETS
    }

    /** Whether [queueId] is kept to songs never heard. */
    fun isOn(queueId: Long): Boolean = queueId in ids

    /** [queueId] kept to [heard] from now on, or, with null, no longer kept to anything: every start says which. */
    fun set(queueId: Long, heard: HeardSet?) {
        val changed = if (heard == null) {
            synchronized(sets) { sets.remove(queueId) }
            ids.remove(queueId)
        } else {
            synchronized(sets) { sets[queueId] = heard }
            ids.add(queueId)
        }
        if (changed) save(ids.toSet())
    }

    /**
     * The ids [saved] with the queues back on the board, [present], and those of this process
     * that are still there: a queue gone since, or one not restored at all, is forgotten.
     */
    fun restore(saved: Set<Long>, present: Collection<Long>) {
        val before = ids.toSet()
        val keep = (before + saved).filterTo(HashSet()) { it in present }
        ids.retainAll(keep); ids.addAll(keep)
        synchronized(sets) { sets.keys.retainAll(keep) }
        if (keep != saved) save(keep)
    }

    /** The set [queueId] is kept to, read with [read] once if a restart left it without one; null when it is not kept to one. */
    suspend fun heardFor(queueId: Long, read: suspend () -> HeardSet): HeardSet? {
        if (queueId !in ids) return null
        synchronized(sets) { sets[queueId] }?.let { return it }
        val heard = read()
        // Unless it stopped being kept to songs never heard while the set was being read.
        if (queueId !in ids) return null
        return synchronized(sets) { sets.getOrPut(queueId) { heard } }
    }

    companion object {
        /** The sets held at once: the queue playing and the few before it. */
        const val KEPT_SETS = 3
    }
}
