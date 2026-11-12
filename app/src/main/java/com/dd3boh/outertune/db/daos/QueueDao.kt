package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSong
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A queue's shuffle numbers made 0 to n-1 again, keeping their order. A song deleted from the
 * library takes its queue row with it (ON DELETE CASCADE, and the read joins the song table), so
 * the numbers read back can have a gap, and a shuffled queue's place read through them then named
 * another song or a place past the end of the list: the queue came back on the wrong song, or
 * every load of it threw IllegalSeekPositionException. The same list, renumbered in place.
 */
internal fun closeShuffleGaps(songs: MutableList<MediaMetadata>): MutableList<MediaMetadata> {
    songs.sortedBy { it.shuffleIndex }.forEachIndexed { i, song -> song.shuffleIndex = i }
    return songs
}

/**
 * Where the queue playing stands among [ids], the queues in the order the list of queues shows
 * them: at the queue whose id is [playingId] (PlayingQueueKey). The last one when no queue has that
 * id, as before any was noted, and -1 when there is no queue.
 *
 * The list keeps the order it is dragged into (QueueBoard.move), so the queue playing need not be
 * the last one. Taking the last one for it brought another queue back after a restart, in the
 * board, the mini player, a headset's play and the car, and wrote where the music stopped into it.
 */
internal fun playingQueueAt(ids: List<Long>, playingId: Long?): Int {
    val at = if (playingId == null) -1 else ids.indexOf(playingId)
    return if (at >= 0) at else ids.lastIndex
}

@Dao
interface QueueDao {

    // region Gets
    @Query("SELECT * from queue ORDER BY `index`")
    fun getAllQueues(): Flow<List<QueueEntity>>

    @Transaction
    @Query("SELECT song.*, queue_song_map.shuffledIndex from queue_song_map JOIN song ON queue_song_map.songId = song.id WHERE queueId = :queueId ORDER BY `index`")
    fun getQueueSongs(queueId: Long): Flow<List<QueueSong>>

    suspend fun readQueue(): List<MultiQueueObject> {
        val resultQueues = ArrayList<MultiQueueObject>()
        val queues = getAllQueues().first()

        queues.forEach { queue ->
            val shuffledSongs = getQueueSongs(queue.id).first()
            if (shuffledSongs.isEmpty()) return@forEach
            resultQueues.add(
                MultiQueueObject(
                    id = queue.id,
                    title = queue.title,
                    queue = closeShuffleGaps(shuffledSongs.map {
                        val s = it.song.toMediaMetadata()
                        s.shuffleIndex = it.shuffledIndex
                        s
                    }.toMutableList()),
                    shuffled = queue.shuffled,
                    queuePos = queue.queuePos,
                    origin = queue.origin,
                    originSlot = queue.originSlot,
                    learn = queue.learn,
                    runId = queue.runId,
                    lastSongPos = queue.lastSongPos,
                    index = queue.index,
                    playlistId = queue.playlistId
                )
            )
        }

        return resultQueues
    }

    /**
     * The saved queue a resume plays: the queue playing, [playingId] (PlayingQueueKey), or the last
     * one when none has that id (playingQueueAt). Null when there is no queue, or it has no songs.
     */
    suspend fun getResumptionQueue(playingId: Long?): MultiQueueObject? {
        val queues = getAllQueues().first()
        if (queues.isEmpty()) return null
        val q = queues[playingQueueAt(queues.map { it.id }, playingId)]
        val shuffledSongs = getQueueSongs(q.id).first()
        if (shuffledSongs.isEmpty()) return null

        return MultiQueueObject(
            id = q.id,
            title = q.title,
            queue = closeShuffleGaps(shuffledSongs.map {
                val s = it.song.toMediaMetadata()
                s.shuffleIndex = it.shuffledIndex
                s
            }.toMutableList()),
            shuffled = q.shuffled,
            queuePos = q.queuePos,
            origin = q.origin,
            originSlot = q.originSlot,
            learn = q.learn,
            runId = q.runId,
            lastSongPos = q.lastSongPos,
            index = q.index,
            playlistId = q.playlistId
        )
    }
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queue: QueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queueSong: QueueSongMap)
    // endregion

    // region Updates
    @Update
    fun update(queue: QueueEntity)

    @Transaction
    fun updateQueue(mq: MultiQueueObject) {
        update(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                origin = mq.origin,
                originSlot = mq.originSlot,
                learn = mq.learn,
                runId = mq.runId,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )
    }

    // Synchronous on purpose: saveQueueToDisk runs this at teardown and must not return before
    // every row has landed, and @Transaction only covers work done in this body. Both callers are
    // off the main thread (saveQueueToDisk on IO, QueueBoard's saves on its IO scope).
    //
    // mqs has to be a list that nothing changes while this runs, such as the copy QueueBoard takes
    // on the player's thread (queuesToSave). Copying it here instead, on IO, is itself a walk over
    // the list, and that walk is where the ConcurrentModificationException came from.
    @Transaction
    fun updateAllQueues(mqs: List<MultiQueueObject>) {
        mqs.forEachIndexed { index, q -> q.index = index }
        nukeAliens(mqs.map { it.id })
        mqs.forEach { updateQueue(it) }
    }

    // endregion

    // region Deletes
    @Delete
    fun delete(mq: QueueEntity)

    @Query("DELETE FROM queue")
    fun deleteAllQueues()

    @Query("DELETE FROM queue_song_map WHERE queueId = :id")
    fun deleteAllQueueSongs(id: Long)

    @Query("DELETE FROM queue WHERE id = :id")
    fun deleteQueue(id: Long)

    @Query("DELETE FROM queue WHERE id NOT IN (:ids)")
    fun nukeAliens(ids: List<Long>)
    // endregion
}