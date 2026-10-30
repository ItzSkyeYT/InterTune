/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Add to queue in a radio (his note of 30 Oct 2026): the song plays after his own songs ahead and
 * before the radio's, and the radio stays a radio. In a playlist, an album or a list made by hand
 * it still goes at the end.
 */
class AddToQueueTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private val radio = (0 until 40).map { "r$it" }

    @Test
    fun `in a radio a song added goes right after the song playing when nothing of his waits`() {
        assertEquals(3, AddToQueue.place(radio, playing = 2, radio = true, byHand = emptySet()))
    }

    @Test
    fun `in a radio it goes after his own songs that follow the song playing, before the radio's`() {
        val ids = listOf("r0", "r1", "mine1", "mine2", "r2", "r3")
        assertEquals(4, AddToQueue.place(ids, playing = 1, radio = true, byHand = setOf("mine1", "mine2")))
    }

    @Test
    fun `his songs further down do not pull it there`() {
        // A song he added before this rule went to the far end of the radio: that one does not
        // decide where the next goes.
        val ids = listOf("r0", "r1", "r2", "r3", "old")
        assertEquals(2, AddToQueue.place(ids, playing = 1, radio = true, byHand = setOf("old")))
    }

    @Test
    fun `a song he moved by hand to come next counts as his`() {
        val ids = listOf("r0", "moved", "r1", "r2")
        assertEquals(2, AddToQueue.place(ids, playing = 0, radio = true, byHand = setOf("moved")))
    }

    @Test
    fun `at the end of a radio's list it goes at its end`() {
        val ids = listOf("r0", "r1", "mine")
        assertEquals(3, AddToQueue.place(ids, playing = 1, radio = true, byHand = setOf("mine")))
        assertEquals(2, AddToQueue.place(listOf("r0", "r1"), playing = 1, radio = true, byHand = emptySet()))
    }

    @Test
    fun `in a list somebody chose it goes at the end, as always`() {
        assertEquals(40, AddToQueue.place(radio, playing = 2, radio = false, byHand = emptySet()))
    }

    @Test
    fun `before play after a restart it goes after the song the queue stopped on`() {
        // The player holds nothing then, and the queue's own place is the one to go by.
        val q = MultiQueueObject(id = 1, title = "Radio", queue = radio.map { song(it) }.toMutableList(), shuffled = false, queuePos = 5, index = 0)
        val at = AddToQueue.place(q.getCurrentQueueShuffled().map { it.id }, q.getQueuePosShuffled(), radio = true, byHand = emptySet())
        q.insertAtPlayIndex(at, listOf(song("mine")))
        assertEquals(listOf("r4", "r5", "mine", "r6"), q.getCurrentQueueShuffled().map { it.id }.subList(4, 8))
        assertEquals("r5", q.queue[q.queuePos].id)
    }

    @Test
    fun `in a shuffled radio it goes after the song playing in the order heard`() {
        val songs = radio.take(6).mapIndexed { i, id -> song(id).apply { shuffleIndex = 5 - i } }.toMutableList()
        val q = MultiQueueObject(id = 1, title = "Radio", queue = songs, shuffled = true, queuePos = 4, index = 0)
        // Play order r5 r4 r3 r2 r1 r0, playing r4, the second.
        val at = AddToQueue.place(q.getCurrentQueueShuffled().map { it.id }, q.getQueuePosShuffled(), radio = true, byHand = emptySet())
        q.insertAtPlayIndex(at, listOf(song("mine")))
        assertEquals(listOf("r5", "r4", "mine", "r3", "r2", "r1", "r0"), q.getCurrentQueueShuffled().map { it.id })
        assertEquals("r4", q.queue[q.queuePos].id)
    }

    @Test
    fun `the queue sheet shows his songs that follow the song playing, however many`() {
        val ids = listOf("r0", "r1", "m1", "m2", "m3", "m4", "m5", "m6", "r2", "r3", "r4")
        val mine = setOf("m1", "m2", "m3", "m4", "m5", "m6")
        // Four after the song playing would end at m4, and m5 and m6 went under the fold.
        assertEquals(8, AdaptiveQueue.shownInSheet(settled = 1, playing = 1, queue = ids, id = { it }, byHand = mine, ahead = 4))
    }

    @Test
    fun `with nothing of his the sheet shows four after the furthest song reached, as before`() {
        val ids = (0 until 20).map { "r$it" }
        assertEquals(10, AdaptiveQueue.shownInSheet(settled = 5, playing = 2, queue = ids, id = { it }, byHand = emptySet(), ahead = 4))
        assertEquals(20, AdaptiveQueue.shownInSheet(settled = 18, playing = 18, queue = ids, id = { it }, byHand = emptySet(), ahead = 4))
    }

    @Test
    fun `a song of his far down a radio stays folded with the radio's`() {
        val ids = listOf("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "old")
        assertEquals(6, AdaptiveQueue.shownInSheet(settled = 1, playing = 1, queue = ids, id = { it }, byHand = setOf("old"), ahead = 4))
    }

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val board = File("src/main/java/com/dd3boh/outertune/playback/QueueBoard.kt").readText()

    @Test
    fun `the swipe's add to the end goes by the rule and notes the songs as his`() {
        val end = service.substringAfter("fun enqueueEnd(items: List<MediaItem>)").substringBefore("\n    }\n")
        assertTrue(end, end.contains("addToQueue("))
        val add = service.substringAfter("fun addToQueue(").substringBefore("\n    }\n")
        assertTrue(add, add.contains("AddToQueue.place("))
        // Found before the songs are noted, or a song of the radio's that is also being added
        // would count as his and the place would move past it.
        assertTrue(add, add.indexOf("AddToQueue.place(") < add.indexOf("noteByHand("))
    }

    @Test
    fun `the queue sheet folds after his own songs, not over them`() {
        val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        assertTrue(queue.contains("val byHandInQueue by playerConnection.service.byHandInQueue.collectAsState()"))
        assertTrue(queue.contains("songs.take(AdaptiveQueue.shownInSheet(settled, playing, songs, { it.id }, byHandInQueue, ADAPTIVE_VISIBLE_AHEAD))"))
        // Told whenever the run changes, with what the queue has reached.
        val changed = service.substringAfter("private fun adaptiveChanged()").substringBefore("\n    }\n")
        assertTrue(changed, changed.contains("byHandInQueue.value = heard.byHand.toSet()"))
    }

    @Test
    fun `the menu's Add to queue keeps a radio a radio`() {
        val add = board.substringAfter("fun addQueue(").substringBefore("\n    fun enqueueEnd(")
        val radioBranch = add.substringAfter("match.playlistId != null").substringBefore("} else if")
        assertTrue(radioBranch, radioBranch.contains("player.addToQueue(match"))
        assertTrue(radioBranch, radioBranch.contains("return match"))
    }
}
