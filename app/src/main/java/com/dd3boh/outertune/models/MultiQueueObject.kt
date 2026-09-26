package com.dd3boh.outertune.models

import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastSumBy
import androidx.media3.common.C

/**
 * @param title Queue title (and UID)
 * @param queue List of media items
 */
data class MultiQueueObject(
    val id: Long,
    var title: String,
    /**
     * The order of songs are dynamic. This should not be accessed from outside QueueBoard.
     */
    val queue: MutableList<MediaMetadata>,
    var shuffled: Boolean = false,
    var queuePos: Int = -1, // position of current song
    var lastSongPos: Long = C.TIME_UNSET,
    var index: Int, // order of queue
    /**
     * Song id to start watch endpoint
     */
    var playlistId: String? = null,
    /** A PlayOrigin code: how this queue was started. */
    var origin: Int = 0,
    var originSlot: Int = -1,
    /** False when the listener has asked that this queue not teach the engine. */
    var learn: Boolean = true,
    /** The current run: one play of this queue from the tap that started it. */
    var runId: Long = 0,
) {

    /**
     * Retrieve the current queue in list form, with shuffle state taken in account
     *
     * @return A copy of the Metadata list
     */
    fun getCurrentQueueShuffled(): MutableList<MediaMetadata> {
        return if (shuffled) {
            val shuffledQueue = ArrayList<MediaMetadata>()
            shuffledQueue.addAll(queue)
            shuffledQueue.sortBy { it.shuffleIndex }
            shuffledQueue
        } else {
            queue
        }
    }

    /**
     * Retrieve the song at current position in the queue
     */
    fun getCurrentSong(): MediaMetadata? {
        validateQueuePos()
        return queue[queuePos]
    }

    /**
     * Retrieve a song given a song ID. Returns null if no song is found
     */
    fun findSong(mediaId: String): MediaMetadata? {
        val currentSong = getCurrentSong()
        if (currentSong?.id == mediaId) {
            return currentSong
        }

        return queue.fastFirstOrNull { it.id == mediaId }
    }

    /**
     * Returns the index of current queue position considering shuffle state
     */
    fun getQueuePosShuffled(): Int {
        validateQueuePos()
        return if (shuffled) {
            queue[queuePos].shuffleIndex
        } else {
            queuePos
        }
    }

    fun setCurrentQueuePos(index: Int) {
        // An index that names no song here is left out rather than stored. It comes from a list
        // the player holds that is not this queue, such as one a car started, and stored it read
        // as corruption later: validateQueuePos then threw the shuffle away, and shuffling read
        // past the end of the list.
        if (index !in queue.indices) return
        if (getQueuePosShuffled() != index) {

            /**
             * queuePos will always track the index of the song in the unsorted queue, *even* if queue is shuffled.
             * To get the real queuePos of the song, look at the shuffleIndex value that equals the index provided
             */
            val newQueuePos = if (shuffled) {
                queue.indexOfFirst { it.shuffleIndex == index }
            } else {
                index
            }

            if (newQueuePos >= 0) queuePos = newQueuePos
        }
    }

    fun validateQueuePos() {
        if (queuePos < 0 || queuePos >= queue.size) { // I don't even...
            // possible issues with migrating some queues, notably from 0.7.4 to newer versions. Reset shuffle parts
            queue.fastForEachIndexed { index, s -> s.shuffleIndex = index }
            shuffled = false
            queuePos = 0
        }
    }

    /**
     * Retrieve the total duration of all songs
     *
     * @return Duration in seconds
     */
    fun getDuration(): Int {
        return queue.fastSumBy {
            it.duration // seconds
        }
    }

    /**
     * Get the length of the queue
     */
    fun getSize() = queue.size

    /**
     * This queue with its own copy of the song list, for a save that runs on another thread while
     * the player keeps changing the queue. Take it on the thread that changes the queue.
     *
     * The songs are copied too, not only the list: QueueBoard renumbers their shuffleIndex in
     * place when one is removed, and a save still writing shared songs could store the new
     * numbers against the old order, which restores as a shuffle with gaps or repeats.
     */
    fun snapshot() = copy(queue = songsSnapshot())

    /** The song list alone, copied the same way, for a save that reads the rest when it runs. */
    fun songsSnapshot(): MutableList<MediaMetadata> = queue.mapTo(ArrayList(queue.size)) { it.copy() }

    fun replaceAll(mediaList: List<MediaMetadata>) {
        queue.clear()
        queue.addAll(mediaList)
    }
}