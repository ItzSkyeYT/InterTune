/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.Queue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What playQueue asks of the queue board and of the player, and in which order, for each way a
 * queue's answer can come.
 *
 * The board is what decides what ends up in the queue, its order, its shuffle and what is saved,
 * so what it is asked is written out in full for each case: anything that moves a step of
 * playQueue has these lists to answer to.
 *
 * QueueStart is the code under test, not a copy of it. The queue's answer is the test's to give
 * (the network), and the service is a stand-in that writes down what it is asked.
 */
class QueueStartTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private val temp = QueueStart.PRELOAD_TITLE

    /** A queue whose answer arrives when the test says so. */
    private class LateQueue(
        override val preloadItem: MediaMetadata?,
        override val startShuffled: Boolean = false,
    ) : Queue {
        /** Completed once the answer has been asked for, which is where the steps wait. */
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Queue.Status>()
        override val playlistId: String? = null
        override suspend fun getInitialStatus(): Queue.Status {
            asked.complete(Unit)
            return answer.await()
        }

        override fun hasNextPage() = false
        override suspend fun nextPage() = emptyList<MediaMetadata>()
    }

    /**
     * Stands where MusicService does. It keeps queues by title, which is the board's key, renames
     * as the board does (a copy takes the old one's place), and writes down every call.
     */
    private class Service : QueueStart.Target {
        val asked = mutableListOf<String>()
        val queues = mutableListOf<MultiQueueObject>()
        override var destroyed = false
        override val untitled = "Queue"

        /** What the board was asked, without the player's lines. */
        fun board() = asked.filter { !it.startsWith("player") }

        override suspend fun boardReady() {}

        override fun addQueue(
            title: String,
            items: List<MediaMetadata>,
            shuffled: Boolean,
            replace: Boolean,
            startIndex: Int,
            continuationEndpoint: String?,
        ): MultiQueueObject? {
            asked += "add '$title' ${items.map { it.id }} at $startIndex" +
                (if (shuffled) " shuffled" else "") + (if (replace) " replace" else "") +
                (continuationEndpoint?.let { " then $it" } ?: "")
            if (items.isEmpty()) return null
            val match = queues.firstOrNull { it.title == title }
            if (match == null) {
                return MultiQueueObject(queues.size + 1L, title, items.toMutableList(), queuePos = startIndex, index = queues.size)
                    .also { queues += it }
            }
            if (replace) match.queue.clear()
            match.queue += items.filter { new -> match.queue.none { it.id == new.id } }
            match.queuePos = match.queue.indexOfFirst { it.id == items[startIndex].id }
            return match
        }

        override fun renameQueue(queue: MultiQueueObject, title: String) {
            asked += "rename '${queue.title}' to '$title'"
            val at = queues.indexOf(queue)
            if (at >= 0) queues[at] = queue.copy(title = title)
        }

        override fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean) {
            asked += "load '${queue?.title}' ${queue?.queue?.map { it.id }} at ${queue?.queuePos}" +
                (if (shouldResume) " resuming" else "") +
                " (origin ${queue?.origin}, slot ${queue?.originSlot}, run ${queue?.runId})"
        }

        override fun start(playWhenReady: Boolean) {
            asked += "player: prepare, playWhenReady $playWhenReady"
        }
    }

    private suspend fun QueueStart.play(
        queue: Queue,
        replace: Boolean = true,
        isRadio: Boolean = false,
        title: String? = null,
        shouldResume: Boolean = false,
        playWhenReady: Boolean = true,
        runId: Long = 7,
    ) = run(
        queue = queue,
        playWhenReady = playWhenReady,
        shouldResume = shouldResume,
        replace = replace,
        isRadio = isRadio,
        title = title,
        origin = 3,
        originSlot = 2,
        runId = runId,
    )

    private val started = "player: prepare, playWhenReady true"
    private val tags = "(origin 3, slot 2, run 7)"

    @Test
    fun `a queue that names no song leaves the player alone until it answers`() = runBlocking {
        // An album, a playlist, an artist's radio. The player still holds the queue before this
        // one, and starting it here would play the old queue's song.
        val service = Service()
        val album = LateQueue(preloadItem = null)
        val job = launch { QueueStart(service).play(album) }

        album.asked.await()
        assertEquals(emptyList<String>(), service.asked)

        album.answer.complete(Queue.Status("Album", listOf(song("A"), song("B")), 1))
        job.join()
        assertEquals(
            listOf("add 'Album' [A, B] at 1 replace", "load 'Album' [A, B] at 1 (origin 3, slot 2, run 7)", started),
            service.asked,
        )
    }

    // The answer, each way it can come.

    @Test
    fun `an answer that starts on another song keeps the tapped one where playback starts`() = runBlocking {
        // A Home shelf song can carry a playlist and an index, and YouTube's copy of the tapped
        // song is not always the same video.
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("List", listOf(song("X"), song("Y"), song("Z")), 2))
        job.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming $tags",
                "rename '$temp' to 'List'",
                "add 'List' [X, Y, A] at 2 replace",
                "load 'List' [X, Y, A] at 2 $tags",
            ),
            service.board(),
        )
    }

    @Test
    fun `an empty answer leaves the tapped song as the whole queue`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status(null, emptyList(), 0))
        job.join()

        assertEquals(listOf("add '$temp' [A] at 0 replace", "load '$temp' [A] at 0 resuming $tags"), service.board())
        assertEquals(listOf("A"), service.queues.single().queue.map { it.id })
        assertEquals(started, service.asked.last())
    }

    @Test
    fun `an empty answer with a title still names the queue of one`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", emptyList(), 0))
        job.join()

        assertEquals(
            listOf("add '$temp' [A] at 0 replace", "load '$temp' [A] at 0 resuming $tags", "rename '$temp' to 'A Mix'"),
            service.board(),
        )
    }

    @Test
    fun `a title given by the caller is the queue's from the start`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, title = "Mine", replace = false, shouldResume = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()

        assertEquals(
            listOf(
                "add 'Mine' [A] at 0",
                "load 'Mine' [A] at 0 resuming $tags",
                // Replaced even though the caller did not ask: the queue of one is in the way.
                "add 'Mine' [A, B] at 0 replace",
                "load 'Mine' [A, B] at 0 resuming $tags",
            ),
            service.board(),
        )
    }

    @Test
    fun `a radio and a shuffled start reach the board as they did`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"), startShuffled = true)
        val job = launch { QueueStart(service).play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf("A", "B", "C", "D", "E", "F").map { song(it) }, 0))
        job.join()

        val board = service.board()
        assertEquals("add '$temp' [A] at 0 shuffled replace", board[0])
        // The next page is asked for from one of the last four songs, picked at random.
        val full = board[3]
        assertTrue(full, full.startsWith("add 'A Mix' [A, B, C, D, E, F] at 0 shuffled replace then "))
        assertTrue(full, full.substringAfter(" then ") in listOf("C", "D", "E", "F"))
    }

    @Test
    fun `nothing is asked of a service torn down while the answer was awaited`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        val before = service.asked.toList()

        service.destroyed = true
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()

        assertEquals(before, service.asked)
    }

    @Test
    fun `nothing is asked of a service torn down before the board was ready`() = runBlocking {
        val service = Service().apply { destroyed = true }
        val radio = LateQueue(song("A"))
        QueueStart(service).play(radio)

        assertEquals(emptyList<String>(), service.asked)
        if (radio.asked.isCompleted) fail("the queue was asked for its answer")
    }

    // Another tap before the answer. Neither order is what anyone would choose (the last answer
    // wins, not the last tap), and neither is changed here: the board is asked what it always was.

    @Test
    fun `two taps, answered in the order they were made`() = runBlocking {
        val service = Service()
        val first = LateQueue(song("A"))
        val second = LateQueue(song("B"))
        val start = QueueStart(service)
        val one = launch { start.play(first, runId = 7) }
        first.asked.await()
        val two = launch { start.play(second, runId = 8) }
        second.asked.await()

        first.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("A2")), 0))
        one.join()
        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming (origin 3, slot 2, run 7)",
                // The second tap finds the first one's queue of one under the same name.
                "add '$temp' [B] at 0 replace",
                "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)",
                "rename '$temp' to 'A Mix'",
                "add 'A Mix' [A, A2] at 0 replace",
                "load 'A Mix' [A, A2] at 0 (origin 3, slot 2, run 7)",
                "rename '$temp' to 'B Mix'",
                "add 'B Mix' [B, B2] at 0 replace",
                "load 'B Mix' [B, B2] at 0 (origin 3, slot 2, run 8)",
            ),
            service.board(),
        )
        assertEquals(listOf("A Mix", "B Mix"), service.queues.map { it.title })
    }

    @Test
    fun `two taps, the first answered last`() = runBlocking {
        val service = Service()
        val first = LateQueue(song("A"))
        val second = LateQueue(song("B"))
        val start = QueueStart(service)
        val one = launch { start.play(first, runId = 7) }
        first.asked.await()
        val two = launch { start.play(second, runId = 8) }
        second.asked.await()

        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()
        first.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("A2")), 0))
        one.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming (origin 3, slot 2, run 7)",
                "add '$temp' [B] at 0 replace",
                "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)",
                "rename '$temp' to 'B Mix'",
                "add 'B Mix' [B, B2] at 0 replace",
                "load 'B Mix' [B, B2] at 0 (origin 3, slot 2, run 8)",
                "rename '$temp' to 'A Mix'",
                "add 'A Mix' [A, A2] at 0 replace",
                "load 'A Mix' [A, A2] at 0 (origin 3, slot 2, run 7)",
            ),
            service.board(),
        )
    }
}
