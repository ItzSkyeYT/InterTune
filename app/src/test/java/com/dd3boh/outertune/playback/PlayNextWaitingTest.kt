/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which songs put in with Play next are still waiting when a song is picked out of a list
 * (issue #42, Unreleased.PLAY_NEXT_STAYS). Play next is driven here as MusicService.enqueueNext
 * drives it: the songs go in at MultiQueueObject.playNextIndex, and the place is noted.
 */
class PlayNextWaitingTest {

    private fun songs(vararg ids: String) = ids.map { MediaMetadata(id = it, title = it, artists = emptyList(), duration = 180, genre = null) }

    private fun queue(id: Long, vararg ids: String, pos: Int = 0) =
        MultiQueueObject(id, "Q$id", songs(*ids).onEachIndexed { i, s -> s.shuffleIndex = i }.toMutableList(), queuePos = pos, index = 0)

    /** Play next on [ids] with the song at play place [current] playing, as enqueueNext does it. */
    private fun PlayNextWaiting.playNext(q: MultiQueueObject, current: Int, vararg ids: String) {
        val at = q.playNextIndex(current).coerceIn(0, q.getSize())
        q.insertAtPlayIndex(at, songs(*ids))
        putIn(q, at, ids.size)
    }

    private fun PlayNextWaiting.Taken?.ids() = this?.songs?.map { it.id }

    @Test
    fun `the songs put in are waiting after the song playing, in the order they stand`() {
        val q = queue(1, "A", "B", "C", "D", pos = 1)
        val waiting = PlayNextWaiting()
        waiting.playNext(q, 1, "X")
        // Play next goes right after the song playing, so the later one stands first.
        waiting.playNext(q, 1, "Y")
        assertEquals(listOf("A", "B", "Y", "X", "C", "D"), q.getCurrentQueueShuffled().map { it.id })

        val taken = waiting.take(q, 1)
        assertEquals(listOf("Y", "X"), taken.ids())
        assertSame(q, taken?.from)
        // The very songs the queue holds, the copies Play next put in.
        assertTrue(taken!!.songs.all { s -> q.queue.any { it === s } })
    }

    @Test
    fun `in a shuffled queue they are taken in the order they play`() {
        val q = queue(1, "A", "B", "C", "D", "E", pos = 2)
        q.shufflePlayOrder(keepCurrent = true, order = listOf(3, 1, 4, 0, 2))
        val waiting = PlayNextWaiting()
        waiting.playNext(q, 0, "X", "Y")

        assertEquals(listOf("C", "X", "Y"), q.getCurrentQueueShuffled().take(3).map { it.id })
        assertEquals(listOf("X", "Y"), waiting.take(q, 0).ids())
    }

    @Test
    fun `a song that has played, or been skipped past, is not waiting any more`() {
        val q = queue(1, "A", "B", "C", pos = 0)
        val waiting = PlayNextWaiting()
        waiting.playNext(q, 0, "X", "Y")
        // [A, X, Y, B, C]: X is playing now, and Y comes next.
        assertEquals(listOf("Y"), waiting.take(q, 1).ids())
        // Past both.
        assertNull(waiting.take(q, 3))
    }

    @Test
    fun `a song swiped out of the queue is not waiting`() {
        val q = queue(1, "A", "B", "C", pos = 0)
        val waiting = PlayNextWaiting()
        waiting.playNext(q, 0, "X", "Y")
        q.removeAtPlayIndex(1, 0)

        assertEquals(listOf("Y"), waiting.take(q, 0).ids())
    }

    @Test
    fun `the same song in the list is not taken for the one put in`() {
        val q = queue(1, "A", "B", "C", pos = 0)
        val waiting = PlayNextWaiting()
        waiting.playNext(q, 0, "C")
        // [A, C, B, C]: only the first C was put in.
        val taken = waiting.take(q, 0)
        assertEquals(listOf("C"), taken.ids())
        assertSame(q.getCurrentQueueShuffled()[1], taken!!.songs.single())
    }

    @Test
    fun `nothing waits in another queue`() {
        val first = queue(1, "A", "B")
        val other = queue(2, "P", "Q")
        val waiting = PlayNextWaiting()
        waiting.playNext(first, 0, "X")

        assertNull(waiting.take(other, 0))
        assertNull(waiting.take(null, 0))
        // Songs put into another queue later forget the first one's: they stay where they are.
        waiting.playNext(other, 0, "Y")
        assertNull(waiting.take(first, 0))
        assertEquals(listOf("Y"), waiting.take(other, 0).ids())
    }

    @Test
    fun `once kept in the new queue they wait there, and a later pick takes them again`() {
        val old = queue(1, "A", "B", "C", pos = 0)
        val waiting = PlayNextWaiting()
        waiting.playNext(old, 0, "X", "Y")
        val taken = waiting.take(old, 0)!!

        // As QueueBoard.putInAfterStart and takeOut do it for a pick of the second song of P.
        val new = queue(2, "P", "Q", "R", pos = 1)
        val at = new.playNextIndex(null)
        new.insertAtPlayIndex(at, PlayNextWaiting.toKeep(taken.songs, new.getCurrentSong()?.id))
        val kept = new.getCurrentQueueShuffled().subList(at, at + taken.songs.size).toList()
        old.takeOut(taken.songs)
        waiting.movedTo(new, kept)

        assertEquals(listOf("P", "Q", "X", "Y", "R"), new.getCurrentQueueShuffled().map { it.id })
        assertEquals(listOf("A", "B", "C"), old.getCurrentQueueShuffled().map { it.id })
        assertNull(waiting.take(old, 0))
        assertEquals(listOf("X", "Y"), waiting.take(new, 1).ids())
    }

    @Test
    fun `the picked song is not kept to play a second time`() {
        val taken = songs("X", "D", "Y")
        assertEquals(listOf("X", "Y"), PlayNextWaiting.toKeep(taken, "D").map { it.id })
        assertEquals(listOf("X", "D", "Y"), PlayNextWaiting.toKeep(taken, null).map { it.id })
    }

    @Test
    fun `a place past the end notes only what is there`() {
        val q = queue(1, "A", "B")
        val waiting = PlayNextWaiting()
        waiting.putIn(q, at = 5, count = 2)
        assertNull(waiting.take(q, 0))
    }

    // The songs taken back out of the queue they waited in (MultiQueueObject.takeOut, which
    // QueueBoard.takeOut runs for a queue still on the board).

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }
    private fun MultiQueueObject.playing() = getCurrentSong()?.id

    @Test
    fun `songs taken out leave the others in their order and the song playing where it was`() {
        val q = queue(1, "A", "B", "C", "D", pos = 1)
        val put = songs("X", "Y")
        q.insertAtPlayIndex(2, put, copy = false)
        assertEquals(listOf("A", "B", "X", "Y", "C", "D"), q.playOrder())

        assertTrue(q.takeOut(put))

        assertEquals(listOf("A", "B", "C", "D"), q.playOrder())
        assertEquals("B", q.playing())
    }

    @Test
    fun `in a shuffled queue too`() {
        val q = queue(1, "A", "B", "C", "D", "E", pos = 2)
        q.shufflePlayOrder(keepCurrent = true, order = listOf(3, 1, 4, 0, 2))
        val before = q.playOrder()
        val put = songs("X", "Y")
        q.insertAtPlayIndex(1, put, copy = false)

        assertTrue(q.takeOut(put))

        assertEquals(before, q.playOrder())
        assertEquals("C", q.playing())
        // Numbered 0 until the size in play order, with no gap and no number twice.
        assertEquals((0 until 5).toList(), q.queue.map { it.shuffleIndex }.sorted())
    }

    @Test
    fun `only the copy put in goes, not the same song in the list`() {
        val q = queue(1, "A", "B", "C", "D", pos = 0)
        val again = songs("C")
        q.insertAtPlayIndex(1, again, copy = false)
        assertEquals(listOf("A", "C", "B", "C", "D"), q.playOrder())

        q.takeOut(again)

        assertEquals(listOf("A", "B", "C", "D"), q.playOrder())
    }

    @Test
    fun `the song playing is never taken out, nor a song the queue does not hold`() {
        val q = queue(1, "A", "B", "C", pos = 1)
        val playing = q.queue[1]

        assertFalse(q.takeOut(listOf(playing) + songs("Z")))
        assertEquals(listOf("A", "B", "C"), q.playOrder())
        assertSame(playing, q.getCurrentSong())
    }
}
