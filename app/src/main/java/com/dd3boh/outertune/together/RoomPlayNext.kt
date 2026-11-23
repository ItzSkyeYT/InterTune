package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata

/**
 * One Play next (and one Add to queue) for a guest following a room: the songs go to the room, not to its own copy of
 * the queue (which the person in control's next queue overwrites). The songs of one action travel as one batch, each
 * message carrying its place in it, so a host sorts them whatever order the relays deliver them in.
 */
object RoomPlayNext {
    /** A message each: more than this from one album or playlist would flood the room's relays. */
    const val MAX = 30

    class Plan(
        /** To send, in the order they should play in. */
        val toRoom: List<MediaMetadata>,
        /** Songs from files on this phone: the others cannot play them, so they are not added anywhere. */
        val localSkipped: Int,
        /** Songs left out because of MAX. */
        val dropped: Int,
    )

    // lt-local is working on playing such songs from YouTube Music by their tags: when that lands, the files
    // can go to the room as those songs and localSkipped goes away.
    fun plan(songs: List<MediaMetadata>): Plan {
        val online = songs.filter { !it.isLocal }
        val kept = online.take(MAX)
        return Plan(kept, songs.size - online.size, online.size - kept.size)
    }

    /**
     * The order to send in. An older host goes by arrival and puts each song right after the current one, so the
     * "next" songs go last-first for it; at the end they go first-first. A newer host sorts by place.
     */
    fun sendOrder(playOrder: List<MediaMetadata>, atEnd: Boolean): List<IndexedValue<MediaMetadata>> =
        playOrder.withIndex().toList().let { if (atEnd) it else it.reversed() }

    /**
     * The host's side: collects the songs of one batch until all [count] came (or [flush] when the wait is over),
     * and gives them back in play order. Keyed by sender and batch; a sender holds at most [MAX_OPEN] open batches.
     */
    class Collector<T> {
        private class Open<T>(val count: Int, val items: MutableMap<Int, T> = mutableMapOf())
        private val open = LinkedHashMap<String, Open<T>>()

        /** True when this is the first message of its batch (so the caller starts the wait). */
        fun isNew(key: String) = key !in open

        /** The whole batch in play order when it is complete, else null. */
        fun add(key: String, index: Int, count: Int, item: T): List<T>? {
            val o = open.getOrPut(key) {
                while (open.size >= MAX_OPEN) open.remove(open.keys.first())
                Open(count)
            }
            o.items[index] = item
            return if (o.items.size >= o.count) take(key) else null
        }

        /** What came of the batch so far, in play order, and forgets it. */
        fun flush(key: String): List<T>? = take(key)

        private fun take(key: String): List<T>? = open.remove(key)?.items?.toSortedMap()?.values?.toList()
    }

    private const val MAX_OPEN = 16
}
