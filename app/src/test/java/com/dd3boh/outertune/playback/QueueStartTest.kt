/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.zionhuang.innertube.models.BrowseEndpoint
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

    private fun song(id: String, title: String = id) = MediaMetadata(id = id, title = title, artists = emptyList(), duration = 180, genre = null)

    private val temp = QueueStart.PRELOAD_TITLE

    /** A queue whose answer arrives when the test says so. */
    private class LateQueue(
        override val preloadItem: MediaMetadata?,
        override val startShuffled: Boolean = false,
        override val songPicked: Boolean = false,
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

        /**
         * Whether the player means to play, as the player keeps it: a start sets it, and a test
         * takes it away where the listener would have paused.
         */
        override var wantsToPlay = false

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
            startFirst: Boolean,
        ): MultiQueueObject? {
            asked += "add '$title' ${items.map { it.id }} at $startIndex" +
                (if (shuffled) " shuffled" else "") + (if (startFirst) ", picked song first" else "") +
                (if (replace) " replace" else "") +
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

        override fun hasQueue(title: String) = queues.any { it.title == title }

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
            wantsToPlay = playWhenReady
        }

        /** The shuffle button as the listener sees it. */
        override var shuffleOn = false

        /** The songs put in with Play next and still waiting, and how often they were asked for. */
        var waiting: PlayNextWaiting.Taken? = null
        var waitingAsked = 0

        /** Written down only when something waits, which it seldom does. */
        override fun playNextWaiting(): PlayNextWaiting.Taken? {
            waitingAsked++
            waiting?.let { asked += "Play next: ${it.songs.map { s -> s.id }} waiting in '${it.from.title}'" }
            return waiting
        }

        /** Puts them after the queue's song in its stored order, which is its play order here. */
        override fun keepPlayNext(queue: MultiQueueObject, taken: PlayNextWaiting.Taken) {
            asked += "keep ${taken.songs.map { it.id }} after ${queue.queue[queue.queuePos].id} in '${queue.title}'"
            queue.queue.addAll(queue.queuePos + 1, taken.songs)
        }

        /** The songs stored as related to a song, by its id, and those on YouTube's related page. */
        val stored = mutableMapOf<String, List<MediaMetadata>>()
        val relatedPage = mutableMapOf<String, List<MediaMetadata>>()

        /** Completed when the stored list is asked for. */
        val lookupAsked = CompletableDeferred<Unit>()

        /** Set by a test that wants that look to wait, and completed when it says. */
        var lookup: CompletableDeferred<Unit>? = null

        override suspend fun storedRelated(seed: MediaMetadata): List<MediaMetadata> {
            asked += "related to ${seed.id}: stored"
            lookupAsked.complete(Unit)
            lookup?.await()
            return stored[seed.id].orEmpty()
        }

        override suspend fun fetchRelated(seed: MediaMetadata, endpoint: BrowseEndpoint?): List<MediaMetadata> {
            asked += "related to ${seed.id}: YouTube's page" + (endpoint?.let { " from ${it.browseId}" } ?: "")
            return relatedPage[seed.id].orEmpty()
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

    // A pause made between the tap and the answer. The answer used to set playWhenReady again
    // whatever had happened since, so a song paused a second after it was tapped played on.

    @Test
    fun `a pause made while the answer was awaited is left standing`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        assertEquals(started, service.asked.last())

        service.wantsToPlay = false
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()

        // The rest of the queue still joins round the song and the player is still prepared.
        // Playing is the one thing not asked for again.
        assertEquals("load 'A Mix' [A, B] at 0 $tags", service.board().last())
        assertEquals(listOf(started, "player: prepare, playWhenReady false"), service.asked.filter { it.startsWith("player") })
    }

    @Test
    fun `a pause stands when the answer comes back empty as well`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()

        service.wantsToPlay = false
        radio.answer.complete(Queue.Status(null, emptyList(), 0))
        job.join()

        assertEquals("player: prepare, playWhenReady false", service.asked.last())
    }

    @Test
    fun `a song tapped while the player was paused starts, at the tap and at the answer`() = runBlocking {
        // The pause was the song before's. A tap is asking for this one.
        val service = Service().apply { wantsToPlay = false }
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        assertEquals(started, service.asked.last())

        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()
        assertEquals(listOf(started, started), service.asked.filter { it.startsWith("player") })
    }

    @Test
    fun `a list asked for while the player was paused starts when it answers`() = runBlocking {
        // No song of this queue was started early, so there is no pause of its own to respect.
        val service = Service().apply { wantsToPlay = false }
        val album = LateQueue(preloadItem = null)
        val job = launch { QueueStart(service).play(album) }
        album.asked.await()
        album.answer.complete(Queue.Status("Album", listOf(song("A"), song("B")), 0))
        job.join()

        assertEquals(started, service.asked.last())
    }

    @Test
    fun `a song the app stopped on an error still means to play, one the listener paused does not`() {
        // The two look the same to the player, playWhenReady false. With Skip on error a tapped
        // song whose stream fails has no next song yet, so it stops, and the answer's second try
        // is how that listener ends up with music.
        assertTrue(QueueStart.wantsToPlay(playWhenReady = true, stoppedByError = false))
        assertTrue(QueueStart.wantsToPlay(playWhenReady = false, stoppedByError = true))
        assertTrue(QueueStart.wantsToPlay(playWhenReady = true, stoppedByError = true))
        assertTrue(!QueueStart.wantsToPlay(playWhenReady = false, stoppedByError = false))
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

    // A radio started again while the queue it made last time is still on the board. The answer
    // names the same title, the board fills the queue that has it, and the queue of one that the
    // tap made used to be given that title too: two queues of one name, one of them a single song.

    @Test
    fun `a radio started again fills the queue of its title and leaves no second one`() = runBlocking {
        val service = Service()
        val first = LateQueue(song("X"))
        val before = launch { QueueStart(service).play(first) }
        first.asked.await()
        first.answer.complete(Queue.Status("You Mix", listOf(song("X"), song("Y")), 0))
        before.join()
        assertEquals(listOf("You Mix"), service.queues.map { it.title })
        service.asked.clear()

        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("You Mix", listOf(song("A"), song("B"), song("C")), 0))
        job.join()

        assertEquals(listOf("You Mix"), service.queues.map { it.title })
        assertEquals(listOf("A", "B", "C"), service.queues.single().queue.map { it.id })
        // The tapped song was never without a queue: the full one is loaded before the queue of
        // one goes, and nothing is heard but the song starting once.
        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming $tags",
                "add 'You Mix' [A, B, C] at 0 replace",
                "load 'You Mix' [A, B, C] at 0 $tags",
                "drop '$temp' [A]",
            ),
            service.board(),
        )
        assertEquals(listOf("X from the top", "A from the top"), service.heard)
    }

    @Test
    fun `a title nobody else has is still given to the queue of one, as before`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A"), song("B")), 0))
        job.join()

        assertEquals("rename '$temp' to 'A Mix'", service.board()[2])
        assertEquals(listOf("A Mix"), service.queues.map { it.title })
    }

    @Test
    fun `an empty answer under a title that is taken still names the queue of one`() = runBlocking {
        // Nothing will fill the older queue, so the song's own queue is the one to keep, and it is
        // better named after its radio than left under the name it was given for the wait.
        val service = Service()
        val first = LateQueue(song("X"))
        val before = launch { QueueStart(service).play(first) }
        first.asked.await()
        first.answer.complete(Queue.Status("You Mix", listOf(song("X"), song("Y")), 0))
        before.join()

        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("You Mix", emptyList(), 0))
        job.join()

        assertTrue(service.queues.none { it.title == temp })
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

    // A radio YouTube answers with nothing but the song it was started from. Nothing came after
    // that song: the queue held one song, and its next page would have been asked from that same
    // song, which answers the same again.

    private val relatedTab = BrowseEndpoint(browseId = "MPTRt_A")

    @Test
    fun `a radio answered with only its own song goes on from the songs stored as related to it`() = runBlocking {
        val service = Service()
        // As stored, with the song itself, a version of it and a song twice, which are left out.
        service.stored["A"] = listOf(song("B"), song("A"), song("C"), song("A2", "A (Live)"), song("B"), song("D"), song("E"))
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A")), 0, relatedEndpoint = relatedTab))
        job.join()

        val board = service.board()
        assertEquals(
            listOf("add '$temp' [A] at 0 replace", "load '$temp' [A] at 0 resuming $tags", "related to A: stored", "rename '$temp' to 'A Mix'"),
            board.take(4),
        )
        val full = board[4]
        assertTrue(full, full.startsWith("add 'A Mix' [A, B, C, D, E] at 0 replace then "))
        // The next page is asked from one of them, and never from A again.
        assertTrue(full, full.substringAfter(" then ") in listOf("B", "C", "D", "E"))
        assertEquals("load 'A Mix' [A, B, C, D, E] at 0 $tags", board[5])
        assertEquals(6, board.size)
        // Nothing was asked of YouTube, and the song played on as it was.
        assertEquals(listOf("A from the top"), service.heard)
    }

    @Test
    fun `with nothing stored, YouTube's related page is asked, by the answer's own link to it`() = runBlocking {
        val service = Service()
        service.relatedPage["A"] = listOf(song("F"), song("G"))
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A")), 0, relatedEndpoint = relatedTab))
        job.join()

        val board = service.board()
        assertEquals(
            listOf("related to A: stored", "related to A: YouTube's page from MPTRt_A"),
            board.filter { it.startsWith("related") },
        )
        assertTrue(board.toString(), board.any { it.startsWith("add 'A Mix' [A, F, G] at 0 replace then ") })
    }

    @Test
    fun `with nothing to go on, the radio of one is left as it always was`() = runBlocking {
        val service = Service()
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A")), 0))
        job.join()

        assertEquals(
            listOf(
                "add '$temp' [A] at 0 replace",
                "load '$temp' [A] at 0 resuming $tags",
                started,
                "related to A: stored",
                "related to A: YouTube's page",
                "rename '$temp' to 'A Mix'",
                "add 'A Mix' [A] at 0 replace then A",
                "load 'A Mix' [A] at 0 $tags",
                started,
            ),
            service.asked,
        )
    }

    @Test
    fun `only a radio is filled, and only when it holds nothing but its own song`() = runBlocking {
        val service = Service()
        service.stored["A"] = listOf(song("B"))
        service.stored["X"] = listOf(song("Y"))
        val start = QueueStart(service)

        // A song played as itself, not as a radio.
        val single = LateQueue(song("A"))
        val one = launch { start.play(single) }
        single.asked.await()
        single.answer.complete(Queue.Status("A", listOf(song("A")), 0))
        one.join()

        // A radio YouTube gave more than its own song.
        val radio = LateQueue(song("X"))
        val two = launch { start.play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("X Mix", listOf(song("X"), song("Z")), 0))
        two.join()

        assertTrue(service.asked.toString(), service.asked.none { it.startsWith("related") })
    }

    @Test
    fun `a tap made while the related songs were looked up takes the player`() = runBlocking {
        val service = Service().apply { lookup = CompletableDeferred() }
        service.stored["A"] = listOf(song("B"))
        val start = QueueStart(service)
        val first = LateQueue(song("A"))
        val one = launch { start.play(first, isRadio = true, runId = 7) }
        first.asked.await()
        first.answer.complete(Queue.Status("A Mix", listOf(song("A")), 0))
        service.lookupAsked.await()

        val second = LateQueue(song("Z"))
        val two = launch { start.play(second, runId = 8) }
        second.asked.await()
        val afterTap = service.asked.toList()
        service.lookup?.complete(Unit)
        one.join()
        // The first radio's songs come back to nothing: neither the board nor the player is asked.
        assertEquals(afterTap, service.asked)

        second.answer.complete(Queue.Status("Z Mix", listOf(song("Z"), song("Y")), 0))
        two.join()
        assertEquals(listOf("A from the top", "Z from the top"), service.heard)
        assertEquals(listOf("Z Mix"), service.queues.map { it.title })
    }

    @Test
    fun `nothing is asked of a service torn down while the related songs were looked up`() = runBlocking {
        val service = Service().apply { lookup = CompletableDeferred() }
        service.stored["A"] = listOf(song("B"))
        val radio = LateQueue(song("A"))
        val job = launch { QueueStart(service).play(radio, isRadio = true) }
        radio.asked.await()
        radio.answer.complete(Queue.Status("A Mix", listOf(song("A")), 0))
        service.lookupAsked.await()
        val before = service.asked.toList()

        service.destroyed = true
        service.lookup?.complete(Unit)
        job.join()

        assertEquals(before, service.asked)
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

    // A song picked out of a list (issue #42). With shuffle on, the list used to play in its own
    // order from the song, and shuffle went off with it, or stayed showing on over that order when
    // the list refilled a queue of its own title. The board turns these asks into the order
    // (MultiQueueObject.refill, QueueShuffleTest).

    private val list = listOf("A", "B", "C", "D", "E").map { song(it) }

    /** The fourth song of the playlist tapped, as the playlist screens ask for it. */
    private fun picked() = ListQueue(title = "Playlist", items = list, startIndex = 3, songPicked = true)

    @Test
    fun `a song picked while shuffle is on starts the list shuffled, from that song`() = runBlocking {
        val service = Service().apply { shuffleOn = true }
        QueueStart(service).play(picked())

        assertEquals(
            listOf("add 'Playlist' [A, B, C, D, E] at 3 shuffled, picked song first replace", "load 'Playlist' [A, B, C, D, E] at 3 $tags", started),
            service.asked,
        )
    }

    @Test
    fun `a song picked while shuffle is off plays the list in its order from that song`() = runBlocking {
        val service = Service()
        QueueStart(service).play(picked())

        assertEquals("add 'Playlist' [A, B, C, D, E] at 3 replace", service.board().first())
    }

    @Test
    fun `a list's Play button plays it in order, whatever shuffle says`() = runBlocking {
        val service = Service().apply { shuffleOn = true }
        QueueStart(service).play(ListQueue(title = "Playlist", items = list))

        assertEquals("add 'Playlist' [A, B, C, D, E] at 0 replace", service.board().first())
    }

    @Test
    fun `a list's Shuffle button starts on any song, whatever shuffle says`() = runBlocking {
        for (on in listOf(false, true)) {
            val service = Service().apply { shuffleOn = on }
            QueueStart(service).play(ListQueue(title = "Playlist", items = list, startShuffled = true))
            assertEquals("add 'Playlist' [A, B, C, D, E] at 0 shuffled replace", service.board().first())
        }
    }

    @Test
    fun `shuffle is read as it was at the tap`() = runBlocking {
        val service = Service().apply { shuffleOn = true }
        val pick = LateQueue(preloadItem = null, songPicked = true)
        val job = launch { QueueStart(service).play(pick) }
        pick.asked.await()
        // The queue before this one is still loaded meanwhile, and its shuffle can change.
        service.shuffleOn = false
        pick.answer.complete(Queue.Status("Playlist", list, 3))
        job.join()

        assertEquals("add 'Playlist' [A, B, C, D, E] at 3 shuffled, picked song first replace", service.board().first())
    }

    // Songs put in with Play next and not played yet. A pick used to leave them in the queue it
    // replaced, or wipe them with it when the list was that queue's own. Unreleased.PLAY_NEXT_STAYS
    // decides whether any are waiting; this holds where they go once some are.

    private fun waitingIn(title: String, vararg ids: String) = PlayNextWaiting.Taken(
        MultiQueueObject(99, title, ids.map { song(it) }.toMutableList(), queuePos = 0, index = 0),
        ids.map { song(it) },
    )

    @Test
    fun `songs waiting after Play next go on right after the picked song, in the queue before it is loaded`() = runBlocking {
        val service = Service().apply { waiting = waitingIn("Radio", "X", "Y") }
        QueueStart(service).play(picked())

        assertEquals(
            listOf(
                "Play next: [X, Y] waiting in 'Radio'",
                "add 'Playlist' [A, B, C, D, E] at 3 replace",
                "keep [X, Y] after D in 'Playlist'",
                "load 'Playlist' [A, B, C, D, X, Y, E] at 3 $tags",
                started,
            ),
            service.asked,
        )
    }

    @Test
    fun `they are taken before the list refills the queue they wait in`() = runBlocking {
        // The playlist playing, songs put in with Play next, and another of its songs tapped: the
        // refill wiped them with the playlist's old songs.
        val service = Service().apply { waiting = waitingIn("Playlist", "X", "Y") }
        QueueStart(service).play(picked())

        val asks = service.board()
        assertTrue(asks.toString(), asks.indexOf("Play next: [X, Y] waiting in 'Playlist'") < asks.indexOfFirst { it.startsWith("add ") })
    }

    @Test
    fun `with shuffle on they still come right after the picked song`() = runBlocking {
        val service = Service().apply { shuffleOn = true; waiting = waitingIn("Radio", "X", "Y") }
        QueueStart(service).play(picked())

        assertEquals(
            listOf(
                "Play next: [X, Y] waiting in 'Radio'",
                "add 'Playlist' [A, B, C, D, E] at 3 shuffled, picked song first replace",
                "keep [X, Y] after D in 'Playlist'",
                "load 'Playlist' [A, B, C, D, X, Y, E] at 3 $tags",
            ),
            service.board(),
        )
    }

    @Test
    fun `a list asked for whole leaves them where they wait`() = runBlocking {
        val service = Service().apply { waiting = waitingIn("Radio", "X", "Y") }
        QueueStart(service).play(ListQueue(title = "Playlist", items = list))
        QueueStart(service).play(ListQueue(title = "Playlist", items = list, startShuffled = true))

        assertEquals(0, service.waitingAsked)
        assertTrue(service.asked.none { it.startsWith("keep") })
    }

    @Test
    fun `a pick that gives way to a later tap keeps nothing`() = runBlocking {
        val service = Service().apply { waiting = waitingIn("Radio", "X", "Y") }
        val start = QueueStart(service)
        val first = LateQueue(preloadItem = null, songPicked = true)
        val one = launch { start.play(first, runId = 7) }
        first.asked.await()
        start.play(picked(), runId = 8)
        first.answer.complete(Queue.Status("Other list", listOf(song("P"), song("Q")), 1))
        one.join()

        assertEquals(listOf("keep [X, Y] after D in 'Playlist'"), service.asked.filter { it.startsWith("keep") })
        assertEquals(1, service.waitingAsked)
        assertEquals("load 'Playlist' [A, B, C, D, X, Y, E] at 3 (origin 3, slot 2, run 8)", service.board().last())
    }

    private fun <T> List<T>.repeat(times: Int) = List(times) { this }.flatten()
}
