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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song put in the place of another one by the adaptive queue (MultiQueueObject.replaceAtPlayIndex,
 * what QueueBoard.applyAdaptive calls): the songs around it stay where they are, the current song
 * stays current, shuffled or not, and the queue holds the very object the player is handed.
 */
class QueueBoardReplaceSongTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private fun plain(): MultiQueueObject =
        MultiQueueObject(id = 1, title = "Q", queue = MutableList(8) { song("s$it").apply { shuffleIndex = it } }, queuePos = 2, index = 0)

    /** Stored s0 to s7, played in the order s7, s6 ... s0; current s5, third in play order. */
    private fun shuffled(): MultiQueueObject =
        MultiQueueObject(id = 1, title = "Q", queue = MutableList(8) { song("s$it").apply { shuffleIndex = 7 - it } }, shuffled = true, queuePos = 5, index = 0)

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }

    @Test
    fun `a song ahead is replaced in its place and the current song stays current`() {
        val q = plain()
        val pick = song("P")
        assertTrue(q.replaceAtPlayIndex(5, pick, currentPlayIndex = 2))

        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "P", "s6", "s7"), q.playOrder())
        assertEquals("s2", q.getCurrentSong()?.id)
        assertSame("the queue holds the object given, for the player to be handed the same", pick, q.getCurrentQueueShuffled()[5])
    }

    @Test
    fun `in a shuffled queue the play order and its numbering stay whole`() {
        val q = shuffled()
        assertEquals("s5", q.queue[q.queuePos].id)
        assertEquals(2, q.getQueuePosShuffled())
        assertTrue(q.replaceAtPlayIndex(5, song("P"), currentPlayIndex = 2))

        assertTrue(q.shuffled)
        assertEquals(listOf("s7", "s6", "s5", "s4", "s3", "P", "s1", "s0"), q.playOrder())
        assertEquals("s5", q.queue[q.queuePos].id)
        assertEquals((0 until 8).toList(), q.queue.map { it.shuffleIndex }.sorted())
    }

    @Test
    fun `the current song is never replaced, nor a place that is not there`() {
        val q = plain()
        assertFalse(q.replaceAtPlayIndex(2, song("P"), currentPlayIndex = 2))
        assertFalse(q.replaceAtPlayIndex(8, song("P"), currentPlayIndex = 2))
        assertEquals((0 until 8).map { "s$it" }, q.playOrder())
    }

    @Test
    fun `put back as it was, the queue is as it was`() {
        val q = shuffled()
        val before = q.getCurrentQueueShuffled()[6]
        q.replaceAtPlayIndex(6, song("P"), currentPlayIndex = 2)
        q.replaceAtPlayIndex(6, before, currentPlayIndex = 2)
        assertEquals(listOf("s7", "s6", "s5", "s4", "s3", "s2", "s1", "s0"), q.playOrder())
        assertEquals("s5", q.queue[q.queuePos].id)
    }

    @Test
    fun `songs put in as themselves are not copied`() {
        val q = plain()
        val a = song("A")
        q.insertAtPlayIndex(4, listOf(a), copy = false)
        assertSame(a, q.getCurrentQueueShuffled()[4])
        assertEquals("s2", q.getCurrentSong()?.id)
    }
}
