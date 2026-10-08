/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.Queue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A tapped song starts loading at once, and the rest of its queue joins when the network answers.
 *
 * Measured on 7 Oct 2026: the first song tapped after a cold start sounded 5.0 and 6.5 s after
 * the tap where later ones took 2.1 to 3.0 s, and 1.7 s of that was the player waiting for the
 * radio's answer before it asked for the stream. playQueue had always put the tapped song in the
 * player first, but prepared the player only after the answer: a player that had played before
 * was prepared already and loaded at once, a new one sat idle.
 *
 * The first tests hold the start to that. The rest hold everything else as it was: what the queue
 * board is asked, and in which order, is written out for each way the answer can come, and is the
 * same list whether the player was started early or not. The board is what decides what ends up
 * in the queue, its order, its shuffle and what is saved, and it reads the player only for the
 * song it holds, never for whether it is prepared.
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
     *
     * It also keeps the one thing the board reads from the player, the song it holds, so that a
     * test can say what was heard: a queue loaded on the song the player already holds joins
     * round it and nothing is heard, and any other load starts its song (QueueBoard.setCurrQueue).
     */
    private class Service : QueueStart.Target {
        val asked = mutableListOf<String>()
        val queues = mutableListOf<MultiQueueObject>()
        override var destroyed = false
        override val untitled = "Queue"

        /** The song the player holds, and each song a load started, with where it started from. */
        var playing: String? = null
        val heard = mutableListOf<String>()

        /** Set by a test that wants the steps to wait for the board, and completed when it says. */
        var board: CompletableDeferred<Unit>? = null

        /** Every queue made gets an id of its own, as on the board. */
        private var made = 0L

        /** What the board was asked, without the player's lines. */
        fun board() = asked.filter { !it.startsWith("player") }

        override suspend fun boardReady() {
            board?.await()
        }

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
                return MultiQueueObject(++made, title, items.toMutableList(), queuePos = startIndex, index = queues.size)
                    .also { queues += it }
            }
            if (replace) match.queue.clear()
            match.queue += items.filter { new -> match.queue.none { it.id == new.id } }
            match.queuePos = match.queue.indexOfFirst { it.id == items[startIndex].id }
            return match
        }

        /** Written down only when there was a queue to drop, which there seldom is. */
        override fun dropQueue(title: String) {
            val at = queues.indexOfFirst { it.title == title }
            if (at < 0) return
            asked += "drop '$title' ${queues[at].queue.map { it.id }}"
            queues.removeAt(at)
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
            val song = queue?.queue?.getOrNull(queue.queuePos)?.id ?: return
            if (song == playing) return
            playing = song
            val from = if (shouldResume) queue.lastSongPos else C.TIME_UNSET
            heard += if (from == C.TIME_UNSET) "$song from the top" else "$song from $from ms"
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

    // The start

    @Test
    fun `the tapped song is in the player and loading before the queue has answered`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }

        radio.asked.await()
        assertEquals(
            listOf("add '$temp' [A] at 0 replace", "load '$temp' [A] at 0 resuming $tags", started),
            service.asked,
        )

        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B"), song("C")), 0))
        job.join()
        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming $tags",
                started,
                "rename '$temp' to 'A Mix'",
                "add 'A Mix' [A, B, C] at 0 replace",
                "load 'A Mix' [A, B, C] at 0 $tags",
                started,
            ),
            service.asked,
        )
    }

    @Test
    fun `a paused start is asked for as paused, early and late`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, playWhenReady = false) }

        radio.asked.await()
        assertEquals("player: prepare, playWhenReady false", service.asked.last())

        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()
        assertEquals(listOf("player: prepare, playWhenReady false").repeat(2), service.asked.filter { it.startsWith("player") })
    }

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

    // The answer, each way it can come. What the board is asked is the same list as before the
    // song was started early.

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
    fun `an answer that fails leaves the tapped song playing alone`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val failure = CompletableDeferred<Throwable?>()
        val job = launch {
            failure.complete(runCatching { QueueStart(service).play(radio) }.exceptionOrNull())
        }
        radio.asked.await()
        radio.answer.completeExceptionally(IOException("no network"))
        job.join()

        // The caller reports it, as it did.
        assertEquals("no network", failure.await()?.message)
        assertEquals(listOf("add '$temp' [A] at 0 replace", "load '$temp' [A] at 0 resuming $tags"), service.board())
        // It used to be left in a player that was never prepared: after a cold start a failed
        // answer meant no music at all, and after any other start the song played on by chance.
        assertEquals(listOf(started), service.asked.filter { it.startsWith("player") })
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

    // A tap after one whose answer never came. That one's song is still on the board alone, under
    // the temporary title, and the next tap used to find it there by the title and use it: its
    // own song was loaded from the place the board had kept for the other one, 1:35 into a song
    // tapped a moment ago, or past its end, where it ended as it started.

    /** Song A tapped, its answer failed, and A paused at 1:35, which the service writes on the queue. */
    private suspend fun leftAfterAFailedAnswer(service: Service, start: QueueStart, replace: Boolean = true): MultiQueueObject {
        val first = LateQueue(song("A"))
        first.answer.completeExceptionally(IOException("no network"))
        runCatching { start.play(first, replace = replace, runId = 7) }
        return service.queues.single().also { it.lastSongPos = 95_000 }
    }

    @Test
    fun `a song tapped after an answer that failed starts from the top, in a queue of its own`() = runBlocking {
        val service = Service()
        val start = QueueStart(service)
        val left = leftAfterAFailedAnswer(service, start)
        val before = service.board().size

        val second = LateQueue(song("B"))
        val two = launch { start.play(second, runId = 8) }
        second.asked.await()

        assertEquals(listOf("A from the top", "B from the top"), service.heard)
        assertEquals(
            listOf("drop '$temp' [A]", "add '$temp' [B] at 0 replace", "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)"),
            service.board().drop(before),
        )
        val made = service.queues.single()
        assertEquals(listOf("B"), made.queue.map { it.id })
        assertNotEquals(left.id, made.id)
        assertEquals(C.TIME_UNSET, made.lastSongPos)

        // And its own answer finds it, as on any other tap.
        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()
        assertEquals(listOf("A from the top", "B from the top"), service.heard)
        assertEquals(listOf("B Mix"), service.queues.map { it.title })
        assertEquals(listOf("B", "B2"), service.queues.single().queue.map { it.id })
    }

    @Test
    fun `a song started without replacing is not put behind the one left there`() = runBlocking {
        // Start radio in the notification asks this way. The song used to be added after the one
        // left there, [A, B], and loaded from A's place.
        val service = Service()
        val start = QueueStart(service)
        leftAfterAFailedAnswer(service, start, replace = false)

        val second = LateQueue(song("B"))
        val two = launch { start.play(second, replace = false, runId = 8) }
        second.asked.await()

        assertEquals(listOf("A from the top", "B from the top"), service.heard)
        assertEquals(listOf("B"), service.queues.single().queue.map { it.id })
        two.cancel()
    }

    @Test
    fun `the same song tapped again after an answer that failed plays on where it is`() = runBlocking {
        // The player already holds it, so nothing is loaded: a tap on the song that is playing
        // has never started it over, with or without a network.
        val service = Service()
        val start = QueueStart(service)
        leftAfterAFailedAnswer(service, start)

        val again = LateQueue(song("A"))
        val two = launch { start.play(again, runId = 8) }
        again.asked.await()

        assertEquals(listOf("A from the top"), service.heard)
        assertEquals(listOf("A"), service.queues.single().queue.map { it.id })
        assertEquals(started, service.asked.last())
        two.cancel()
    }

    @Test
    fun `a queue with a title of its own leaves the one under the temporary title alone`() = runBlocking {
        val service = Service()
        val start = QueueStart(service)
        leftAfterAFailedAnswer(service, start)

        val named = LateQueue(song("B"))
        val two = launch { start.play(named, title = "Mine", runId = 8) }
        named.asked.await()

        assertEquals(listOf(temp, "Mine"), service.queues.map { it.title })
        two.cancel()
    }

    // Another tap before the answer: the last tap wins. The earlier tap's answer used to be loaded
    // when it came, over the song tapped after it. Answered in order, that was the second song,
    // then the first from the top, then the second again from the top. Answered the other way
    // round, the first song played for good.

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
        val beforeAnswers = service.asked.toList()

        first.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("A2")), 0))
        one.join()
        // The first tap's answer asks nothing of the board or the player.
        assertEquals(beforeAnswers, service.asked)

        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming (origin 3, slot 2, run 7)",
                // The second tap makes a queue of its own, and the first one's goes.
                "drop '$temp' [A]",
                "add '$temp' [B] at 0 replace",
                "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)",
                "rename '$temp' to 'B Mix'",
                "add 'B Mix' [B, B2] at 0 replace",
                "load 'B Mix' [B, B2] at 0 (origin 3, slot 2, run 8)",
            ),
            service.board(),
        )
        // B is loaded once and its queue joins round it.
        assertEquals(listOf("A from the top", "B from the top"), service.heard)
        assertEquals(listOf("B Mix"), service.queues.map { it.title })
        // Each tap starts its song, and the one answer that counts starts the player once more.
        assertEquals(listOf(started).repeat(3), service.asked.filter { it.startsWith("player") })
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
        val afterSecond = service.asked.toList()
        first.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("A2")), 0))
        one.join()

        assertEquals(afterSecond, service.asked)
        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming (origin 3, slot 2, run 7)",
                "drop '$temp' [A]",
                "add '$temp' [B] at 0 replace",
                "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)",
                "rename '$temp' to 'B Mix'",
                "add 'B Mix' [B, B2] at 0 replace",
                "load 'B Mix' [B, B2] at 0 (origin 3, slot 2, run 8)",
            ),
            service.board(),
        )
        assertEquals(listOf("A from the top", "B from the top"), service.heard)
        assertEquals(listOf("B Mix"), service.queues.map { it.title })
    }

    @Test
    fun `a list asked for before a tapped song does not take the player when it answers`() = runBlocking {
        // An album tapped, then a song before the album has answered. The album names no song, so
        // nothing of it is in the player yet, and its answer used to load it over the song.
        val service = Service()
        val album = LateQueue(preloadItem = null)
        val radio = LateQueue(song("B"))
        val start = QueueStart(service)
        val one = launch { start.play(album, runId = 7) }
        album.asked.await()
        val two = launch { start.play(radio, runId = 8) }
        radio.asked.await()

        album.answer.complete(Queue.Status("Album", listOf(song("X"), song("Y")), 0))
        one.join()
        radio.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()

        assertEquals(listOf("B from the top"), service.heard)
        assertEquals(listOf("B Mix"), service.queues.map { it.title })
    }

    @Test
    fun `a tapped song gives way to a list asked for after it`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val album = LateQueue(preloadItem = null)
        val start = QueueStart(service)
        val one = launch { start.play(radio, runId = 7) }
        radio.asked.await()
        val two = launch { start.play(album, runId = 8) }
        album.asked.await()

        // The song plays alone meanwhile. Its queue does not join it: the list was asked for last.
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("A2")), 0))
        one.join()
        album.answer.complete(Queue.Status("Album", listOf(song("X"), song("Y")), 0))
        two.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming (origin 3, slot 2, run 7)",
                "add 'Album' [X, Y] at 0 replace",
                "load 'Album' [X, Y] at 0 (origin 3, slot 2, run 8)",
            ),
            service.board(),
        )
        assertEquals(listOf("A from the top", "X from the top"), service.heard)
    }

    @Test
    fun `an earlier tap's answer that fails has nothing to report once another tap was made`() = runBlocking {
        val service = Service()
        val first = LateQueue(song("A"))
        val second = LateQueue(song("B"))
        val start = QueueStart(service)
        val failure = CompletableDeferred<Throwable?>()
        val one = launch { failure.complete(runCatching { start.play(first, runId = 7) }.exceptionOrNull()) }
        first.asked.await()
        val two = launch { start.play(second, runId = 8) }
        second.asked.await()

        first.answer.completeExceptionally(IOException("no network"))
        one.join()
        assertNull(failure.await())

        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()
        assertEquals(listOf("A from the top", "B from the top"), service.heard)
    }

    @Test
    fun `of two taps made before the board is ready, only the second is loaded`() = runBlocking {
        val service = Service().apply { board = CompletableDeferred() }
        val first = LateQueue(song("A"))
        val second = LateQueue(song("B"))
        val start = QueueStart(service)
        val one = launch { start.play(first, runId = 7) }
        val two = launch { start.play(second, runId = 8) }
        // Both are waiting for the board now.
        yield()
        assertEquals(emptyList<String>(), service.asked)

        service.board?.complete(Unit)
        second.asked.await()
        // The first tap's song never reaches the board, so its stream is never asked for.
        assertEquals(
            listOf("add '$temp' [B] at 0 replace", "load '$temp' [B] at 0 resuming (origin 3, slot 2, run 8)"),
            service.board(),
        )
        one.join()
        if (first.asked.isCompleted) fail("the first tap's queue was asked for its answer")
        second.answer.complete(Queue.Status("B Mix", listOf(song("B"), song("B2")), 0))
        two.join()

        assertEquals(listOf("B from the top"), service.heard)
        assertEquals(listOf("B Mix"), service.queues.map { it.title })
    }

    private fun <T> List<T>.repeat(times: Int) = List(times) { this }.flatten()
}
