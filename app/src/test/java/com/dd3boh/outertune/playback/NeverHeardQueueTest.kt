/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.engine.HeardSet
import com.dd3boh.outertune.engine.KnownSong
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.Queue
import com.zionhuang.innertube.models.BrowseEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Discover pick's queue holds only songs never heard (his word of 21 Nov 2026: a "Take It All Mix"
 * from a new song held a liked, downloaded one). Any other start keeps its queue as it was.
 */
class NeverHeardQueueTest {

    private fun song(id: String, title: String = "Title $id", artist: String = "Artist $id") =
        MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 180, genre = null)

    private fun known(vararg ids: String) = HeardSet.of(ids.map { KnownSong(it, "Title $it", "Artist $it") })

    @Test
    fun `a Discover pick is a card of Discover something new, or of Quick picks' row built under Discover`() {
        assertTrue(NeverHeardQueue.isPick(PlayOrigin.DISCOVER, discoverRow = false))
        assertTrue(NeverHeardQueue.isPick(PlayOrigin.QUICK_PICKS, discoverRow = true))
        assertFalse(NeverHeardQueue.isPick(PlayOrigin.QUICK_PICKS, discoverRow = false))
        // Every other start keeps its queue, whatever it is told.
        for (origin in PlayOrigin.entries.filter { it != PlayOrigin.DISCOVER && it != PlayOrigin.QUICK_PICKS }) {
            assertFalse(origin.name, NeverHeardQueue.isPick(origin, discoverRow = false))
            assertFalse(origin.name, NeverHeardQueue.isPick(origin, discoverRow = true))
        }
    }

    @Test
    fun `known songs and their other uploads are passed over, the song tapped stays where it starts`() {
        val heard = known("liked", "tapped")
        val page = listOf(song("a"), song("liked"), song("tapped"), song("video", "Title liked (Official Video)", "Artist liked"), song("b"))
        val kept = NeverHeardQueue.keep(page, heard, keep = "tapped")
        assertEquals(listOf("a", "tapped", "b"), kept.songs.map { it.id })
        assertEquals(2, kept.passedOver)
        val (status, passed) = NeverHeardQueue.status(Queue.Status("Mix", page, mediaItemIndex = 2), heard, "tapped")
        assertEquals(listOf("a", "tapped", "b"), status.items.map { it.id })
        assertEquals(1, status.mediaItemIndex); assertEquals(2, passed)
        assertEquals("Discover queue, a top-up: 3 never heard kept, 2 heard passed over, 1 more pages asked", NeverHeardQueue.said("a top-up", 3, 2, 1))
    }

    @Test
    fun `the queues kept to it outlive a restart by id, and read their set once`() {
        val saved = mutableListOf<Set<Long>>()
        val queues = NeverHeardQueues { saved += it }
        val heard = known("x")
        queues.set(1, heard); queues.set(2, heard); queues.set(1, heard)
        assertEquals(listOf(setOf(1L), setOf(1L, 2L)), saved)
        assertTrue(queues.isOn(1)); assertFalse(queues.isOn(3))
        var reads = 0
        runBlocking { assertSame(heard, queues.heardFor(1) { reads++; HeardSet.EMPTY }) }
        assertEquals(0, reads)
        // A start of another kind refilling the same queue: no longer kept.
        queues.set(2, null)
        assertEquals(setOf(1L), saved.last()); assertFalse(queues.isOn(2))
        runBlocking { assertNull(queues.heardFor(2) { reads++; heard }) }
        // After a restart: the ids come back from the settings for the queues back on the board,
        // and each set is read again the first time, once.
        val restarted = NeverHeardQueues { saved += it }
        restarted.restore(setOf(1L, 9L), present = listOf(1L, 2L))
        assertTrue(restarted.isOn(1)); assertFalse(restarted.isOn(9))
        assertEquals(setOf(1L), saved.last())
        val read = known("y")
        runBlocking {
            assertSame(read, restarted.heardFor(1) { reads++; read })
            assertSame(read, restarted.heardFor(1) { reads++; HeardSet.EMPTY })
        }
        assertEquals(1, reads)
        // Persistent queue off: no queue comes back, and nothing is kept.
        restarted.restore(setOf(1L), present = emptyList())
        assertFalse(restarted.isOn(1)); assertEquals(emptySet<Long>(), saved.last())
    }

    @Test
    fun `only the latest few sets are held, and an older queue reads its own again`() {
        val queues = NeverHeardQueues { }
        val sets = (1L..4L).associateWith { known("s$it") }
        sets.forEach { (id, set) -> queues.set(id, set) }
        var reads = 0
        val again = known("again")
        runBlocking {
            assertSame(sets[4], queues.heardFor(4) { reads++; HeardSet.EMPTY })
            assertSame(sets[2], queues.heardFor(2) { reads++; HeardSet.EMPTY })
            // The first is past the latest NeverHeardQueues.KEPT_SETS: read once more, still kept to the rule.
            assertSame(again, queues.heardFor(1) { reads++; again })
        }
        assertEquals(1, reads)
        assertTrue((1L..4L).all { queues.isOn(it) })
    }

    /** A radio whose answer and next pages are the test's. */
    private class Radio(override val preloadItem: MediaMetadata, private val first: List<MediaMetadata>, private val pages: MutableList<List<MediaMetadata>>) : Queue {
        override val playlistId: String? = null
        override val startShuffled = false
        var pagesAsked = 0
        override suspend fun getInitialStatus() = Queue.Status("Take It All Mix", listOf(preloadItem) + first, 0)
        override fun hasNextPage() = pages.isNotEmpty()
        override suspend fun nextPage(): List<MediaMetadata> { pagesAsked++; return pages.removeAt(0) }
    }

    /** Stands where MusicService does, with only what a start asks of it. */
    private class Board(var known: HeardSet = HeardSet.EMPTY) : QueueStart.Target {
        override var destroyed = false
        override val untitled = "Queue"
        override val shuffleOn = false
        override val wantsToPlay = true
        val queues = mutableListOf<MultiQueueObject>()
        var loaded: MultiQueueObject? = null
        val kept = HashMap<Long, HeardSet?>()
        var reads = 0
        val related = mutableListOf<MediaMetadata>()
        private var made = 0L
        override suspend fun boardReady() {}
        override fun addQueue(title: String, items: List<MediaMetadata>, shuffled: Boolean, replace: Boolean, startIndex: Int, continuationEndpoint: String?, startFirst: Boolean): MultiQueueObject? {
            if (items.isEmpty()) return null
            val match = queues.firstOrNull { it.title == title }
            if (match != null) { match.queue.clear(); match.queue += items; match.queuePos = startIndex; return match }
            return MultiQueueObject(++made, title, items.toMutableList(), queuePos = startIndex, index = queues.size).also { queues += it }
        }
        override fun playNextWaiting(): PlayNextWaiting.Taken? = null
        override fun keepPlayNext(queue: MultiQueueObject, taken: PlayNextWaiting.Taken) {}
        override fun hasQueue(title: String) = queues.any { it.title == title }
        override fun dropQueue(title: String) { queues.removeAll { it.title == title } }
        override fun renameQueue(queue: MultiQueueObject, title: String) { queue.title = title }
        override fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean) { loaded = queue }
        override fun start(playWhenReady: Boolean) {}
        override suspend fun storedRelated(seed: MediaMetadata): List<MediaMetadata> = related
        override suspend fun fetchRelated(seed: MediaMetadata, endpoint: BrowseEndpoint?): List<MediaMetadata> = emptyList()
        override suspend fun knownSongs(): HeardSet { reads++; return known }
        override fun keepNeverHeard(queue: MultiQueueObject, heard: HeardSet?) { kept[queue.id] = heard }
    }

    private fun QueueStart.radio(queue: Queue, neverHeard: Boolean) = runBlocking {
        run(queue, playWhenReady = true, shouldResume = false, replace = true, isRadio = true, title = null,
            origin = PlayOrigin.QUICK_PICKS.code, originSlot = 0, runId = 1, neverHeard = neverHeard)
    }

    private fun mix(): Radio {
        val tapped = song("tapped")
        // Most of the first page is known, an official video of a known song among it.
        val first = (1..8).map { song("k$it") } + song("video", "Title k1 (Official Video)", "Artist k1") + listOf(song("n1"), song("n2"))
        val pages = mutableListOf((listOf(song("k9")) + (3..6).map { song("n$it") }), (7..12).map { song("n$it") }, (13..20).map { song("n$it") })
        return Radio(tapped, first, pages)
    }

    @Test
    fun `a Discover pick's start keeps to songs never heard, and asks for more pages while too few pass`() {
        // The song tapped is playing by the time the set is read, so the set knows it; it stays.
        val heard = known("tapped", *(1..9).map { "k$it" }.toTypedArray())
        val board = Board(heard)
        val radio = mix()
        assertTrue(QueueStart(board).radio(radio, neverHeard = true))
        val queue = board.loaded!!
        assertEquals(listOf("tapped") + (1..12).map { "n$it" }, queue.queue.map { it.id })
        assertEquals(0, queue.queuePos)
        // Two more pages, and no more once ten songs never heard were in.
        assertEquals(2, radio.pagesAsked)
        // Read once, and kept with the queue for its top-ups and the adaptive picks.
        assertEquals(1, board.reads)
        assertSame(heard, board.kept[queue.id])
    }

    @Test
    fun `with too few never heard in the radio's pages, the songs related to the one tapped fill in, never a known one`() {
        val heard = known("tapped", "r1")
        val board = Board(heard).apply { related += listOf(song("r1"), song("r2"), song("n1")) }
        val tapped = song("tapped")
        val radio = Radio(tapped, listOf(song("n1")), mutableListOf())
        assertTrue(QueueStart(board).radio(radio, neverHeard = true))
        assertEquals(listOf("tapped", "n1", "r2"), board.loaded!!.queue.map { it.id })
    }

    @Test
    fun `any other start is left whole, reads nothing, and the queue it refills is no longer kept`() {
        val heard = known("tapped", *(1..9).map { "k$it" }.toTypedArray())
        val board = Board(heard)
        val start = QueueStart(board)
        assertTrue(start.radio(mix(), neverHeard = true))
        val queue = board.loaded!!
        assertSame(heard, board.kept[queue.id])
        // The same mix again from a card of another row: the same queue, refilled whole.
        assertTrue(start.radio(mix(), neverHeard = false))
        assertSame(queue, board.loaded)
        assertEquals(listOf("tapped") + (1..8).map { "k$it" } + listOf("video", "n1", "n2"), queue.queue.map { it.id })
        assertTrue(board.kept.containsKey(queue.id)); assertNull(board.kept[queue.id])
        assertEquals(1, board.reads)
    }
}
