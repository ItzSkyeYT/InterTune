/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.daos.QueueDao
import com.dd3boh.outertune.db.daos.playingQueueAt
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSong
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MultiQueueObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue playing comes back after a restart, wherever it stands in the list of queues
 * (experiments/codebase-scan/2026-11-04-playback.md, finding 4).
 *
 * The list keeps the order it is dragged into (QueueBoard.move), and three places took its last
 * queue for the one playing: the board built at start, the saved queue a resume plays (the mini
 * player after a restart, a headset's play, the car) and the save at teardown, which wrote where
 * the music stopped. A queue dragged below the one playing came back in its place. The queue
 * playing is noted by its id now (PlayingQueueKey), and the last queue is taken only when none is
 * noted, as on the first start after the update.
 *
 * QueueBoard needs a live MusicService to construct, so its part is held by the functions it
 * calls, tested here, and by reading the source, as PlaybackFaultsTest does.
 */
class QueueResumeTest {

    /** The queue table as Room keeps it: rows by id, read back in their saved order. */
    private class Table : QueueDao {
        val rows = LinkedHashMap<Long, QueueEntity>()
        val songs = HashMap<Long, List<QueueSong>>()
        override fun getAllQueues(): Flow<List<QueueEntity>> = flowOf(rows.values.sortedBy { it.index })
        override fun getQueueSongs(queueId: Long): Flow<List<QueueSong>> = flowOf(songs[queueId].orEmpty())
        override fun insert(queue: QueueEntity) { rows[queue.id] = queue }
        override fun insert(queueSong: QueueSongMap) = error("not used here")
        override fun update(queue: QueueEntity) { if (queue.id in rows) rows[queue.id] = queue }
        override fun delete(mq: QueueEntity) { rows.remove(mq.id) }
        override fun deleteAllQueues() = rows.clear()
        override fun deleteAllQueueSongs(id: Long) { songs.remove(id) }
        override fun deleteQueue(id: Long) { rows.remove(id) }
        override fun nukeAliens(ids: List<Long>) { rows.keys.retainAll(ids.toSet()) }

        /** [queues] saved as the board saves a new queue, in the order given. */
        fun save(vararg queues: MultiQueueObject) {
            queues.forEachIndexed { i, q ->
                q.index = i
                insert(QueueEntity(id = q.id, title = q.title, queuePos = q.queuePos, index = q.index))
                songs[q.id] = listOf(QueueSong(Song(SongEntity(id = "${q.title}-song", title = q.title, localPath = null), emptyList()), 0))
            }
        }
    }

    private fun queue(id: Long, title: String) =
        MultiQueueObject(id = id, title = title, queue = mutableListOf(), queuePos = 0, index = 0)

    private val a = queue(1, "A")
    private val b = queue(2, "B")
    private val playing = queue(3, "P")

    /** P played last, so bubbleUp left it at the bottom; then A is dragged below it (move). */
    private fun dragged(): Table = Table().apply {
        save(a, b, playing)
        // What move saves: the order as it is now shown, P still the queue playing.
        updateAllQueues(listOf(b, playing, a))
    }

    @Test
    fun `a queue dragged below the one playing does not take its place after a restart`() = runBlocking {
        val table = dragged()
        assertEquals("the list keeps the order it was dragged into", listOf("B", "P", "A"), table.getAllQueues().first().map { it.title })
        assertEquals("P", table.getResumptionQueue(playingId = playing.id)?.title)
    }

    @Test
    fun `with no queue noted, or one that is gone, the last queue is resumed as before`() = runBlocking {
        val table = dragged()
        assertEquals("A", table.getResumptionQueue(playingId = null)?.title)
        assertEquals("A", table.getResumptionQueue(playingId = 99)?.title)
        assertNull(Table().getResumptionQueue(playingId = playing.id))
    }

    @Test
    fun `the queue playing is found by its id wherever it stands`() {
        val ids = listOf(2L, 3L, 1L)
        assertEquals(1, playingQueueAt(ids, 3))
        assertEquals(0, playingQueueAt(ids, 2))
        assertEquals("none noted: the last", 2, playingQueueAt(ids, null))
        assertEquals("gone: the last", 2, playingQueueAt(ids, 99))
        assertEquals("no queue at all", -1, playingQueueAt(emptyList(), 3))
    }

    @Test
    fun `a board for fewer queues keeps the one playing where it was dragged, and the list's order`() {
        val c = queue(4, "C")
        val d = queue(5, "D")
        // P dragged to the top, then Max queues lowered to 3.
        val saved = listOf(playing, a, b, c, d)
        assertEquals(listOf("P", "C", "D"), queuesKept(saved, 3, playing.id).map { it.title })
        // Among the last ones anyway, or not noted: the last ones, as before.
        assertEquals(listOf("B", "C", "D"), queuesKept(listOf(a, b, c, d), 3, b.id).map { it.title })
        assertEquals(listOf("B", "C", "D"), queuesKept(saved, 3, null).map { it.title })
        assertEquals(saved, queuesKept(saved, 19, playing.id))
        assertEquals(listOf("P"), queuesKept(saved, 1, playing.id).map { it.title })
        assertTrue(queuesKept(saved, 0, playing.id).isEmpty())
    }

    // ---- the wiring, read from the source ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    /** The body of the function [name] in [text], braces matched. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("$name is there", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        error("$name has no end")
    }

    @Test
    fun `the board starts on the queue playing by its id`() {
        val board = code("playback/QueueBoard.kt")
        val init = board.substringAfter("    init {").substringBefore("\n    }\n")
        assertTrue(init, "masterQueues.addAll(queuesKept(queues, maxQueues, playingId))" in init)
        assertTrue(init, "masterIndex = playingQueueAt(masterQueues.map { it.id }, playingId)" in init)
        assertFalse(init, "masterIndex = masterQueues.size - 1" in init)

        val service = code("playback/MusicService.kt")
        val initQueue = body(service, "initQueue")
        val noted = "val playingId = queueBoard.getCurrentQueue()?.id ?: dataStore[PlayingQueueKey]"
        assertTrue("initQueue takes the board's own queue first, then the noted one", noted in initQueue)
        val built = initQueue.indexOf("QueueBoard(this, queueBoard.masterQueues, database.readQueue().toMutableList(), maxQueues, playingId)")
        assertTrue("the saved queues are built on it", built > initQueue.indexOf(noted))
    }

    @Test
    fun `the board notes the queue playing when it saves the list and when a delete moves it`() {
        val board = code("playback/QueueBoard.kt")
        val note = body(board, "notePlaying")
        assertTrue(note, "if (!player.persistentQueue) return" in note)
        assertTrue(note, "player.dataStore.edit { it[PlayingQueueKey] = id }" in note)
        assertTrue("on the save scope, whose handler logs a failed write", "coroutineScope.launch {" in note)

        val saveAll = body(board, "saveAllQueues")
        assertTrue("noted before the list's save is asked for", saveAll.indexOf("notePlaying()") in 0 until saveAll.indexOf("updateAllQueues("))
        val delete = body(board, "deleteQueue")
        assertTrue(delete.indexOf("notePlaying()") > delete.indexOf("masterIndexAfterDelete("))
        // bubbleUp and move, the two that change the order, both save through saveAllQueues.
        assertTrue("saveAllQueues(masterQueues)" in body(board, "bubbleUp"))
        assertTrue("saveAllQueues(masterQueues)" in board.substringAfter("fun move(fromIndex: Int, toIndex: Int)").substringBefore("\n    }\n"))
    }

    @Test
    fun `the save at teardown writes the stop point into the queue playing, noted before the save`() {
        val save = body(code("playback/MusicService.kt"), "saveQueueToDisk")
        assertFalse(save, "data.last()" in save)
        assertTrue(save, "val playing = data[playingQueueAt(data.map { it.id }, queueBoard.getCurrentQueue()?.id)]" in save)
        assertTrue(save, "playing.lastSongPos = ResumePoint.onStop(" in save)
        // The empty board's early return still comes first: updateAllQueues with no queue wipes them all.
        assertTrue(save.indexOf("if (data.isEmpty()) return") in 0 until save.indexOf("playingQueueAt("))
        assertTrue(save.indexOf("notePlayingQueue(playing.id)") in 0 until save.indexOf("database.updateAllQueues(data)"))
        val note = body(code("playback/MusicService.kt"), "notePlayingQueue")
        assertTrue("a failed write does not stop the save", "catch (e: Exception)" in note)
        assertTrue(note, "withContext(NonCancellable)" in note)
    }

    @Test
    fun `every resume reads the saved queue by the noted id`() {
        val calls = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> Regex("""getResumptionQueue\(([^)]*)\)""").findAll(file.readText()).map { file.name to it.groupValues[1] } }
            .filter { (_, args) -> !args.startsWith("playingId:") }
            .toList()
        // The mini player, the headset's button, and the car's three.
        assertEquals(calls.toString(), 5, calls.size)
        for ((file, args) in calls) assertTrue("$file: $args", args.endsWith("dataStore[PlayingQueueKey]"))
    }
}
