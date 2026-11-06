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
 * After a restart the player holds nothing until play, and the queue sheet shows the stored queue.
 * Its rows had no drag handles until play; now they can be dragged there, and a move must keep the
 * queue on the song that will play. moveInPlayOrder is what QueueBoard.moveSong runs, with no
 * player index while the player is empty.
 */
class QueueMoveBeforePlayTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }
    private fun MultiQueueObject.playing() = getCurrentQueueShuffled()[getQueuePosShuffled()].id

    /** Ten songs s0 to s9 in order, restored on s7. */
    private fun restoredInOrder() =
        MultiQueueObject(id = 1, title = "Q", queue = MutableList(10) { song("s$it") }, shuffled = false, queuePos = 7, index = 0)

    /** Ten songs shuffled so the play order is the stored order reversed, restored on s2: seventh to play of ten. */
    private fun restoredShuffled(): MultiQueueObject {
        val songs = MutableList(10) { i -> song("s$i").apply { shuffleIndex = 9 - i } }
        return MultiQueueObject(id = 1, title = "Q", queue = songs, shuffled = true, queuePos = 2, index = 0)
    }

    @Test
    fun `a song dragged above the one that will play leaves it to play`() {
        val q = restoredInOrder()
        val place = moveInPlayOrder(q, fromIndex = 3, toIndex = 0, playerIndex = null)
        assertEquals(listOf("s3", "s0", "s1", "s2", "s4", "s5", "s6", "s7", "s8", "s9"), q.playOrder())
        assertEquals("s7", q.playing())
        assertEquals(7, place)
    }

    @Test
    fun `a song dragged from below to above the one that will play moves it down one`() {
        val q = restoredInOrder()
        val place = moveInPlayOrder(q, fromIndex = 9, toIndex = 2, playerIndex = null)
        assertEquals(listOf("s0", "s1", "s9", "s2", "s3", "s4", "s5", "s6", "s7", "s8"), q.playOrder())
        assertEquals("s7", q.playing())
        assertEquals(8, place)
    }

    @Test
    fun `the song that will play can be dragged itself`() {
        val q = restoredInOrder()
        val place = moveInPlayOrder(q, fromIndex = 7, toIndex = 1, playerIndex = null)
        assertEquals("s7", q.playing())
        assertEquals(1, place)
    }

    @Test
    fun `a shuffled queue keeps its shuffle and the song that will play`() {
        val q = restoredShuffled()
        assertEquals("s2", q.playing())
        // The first to play, s9, dragged to the end.
        moveInPlayOrder(q, fromIndex = 0, toIndex = 9, playerIndex = null)
        assertTrue("shuffle must stay on", q.shuffled)
        assertEquals(listOf("s8", "s7", "s6", "s5", "s4", "s3", "s2", "s1", "s0", "s9"), q.playOrder())
        assertEquals("s2", q.playing())
        assertEquals(6, q.getQueuePosShuffled())
        // The stored order is the playlist's own and does not change with a move.
        assertEquals((0..9).map { "s$it" }, q.queue.map { it.id })
    }

    @Test
    fun `an empty player's index 0 would have moved the queue off the song`() {
        // Why moveSong passes no index while the player is empty: its index reads 0 then.
        val q = restoredInOrder()
        moveInPlayOrder(q, fromIndex = 3, toIndex = 0, playerIndex = 0)
        assertEquals("s8", q.playing())
    }

    @Test
    fun `with the queue in the player, its index gives the same place`() {
        val q = restoredInOrder()
        val place = moveInPlayOrder(q, fromIndex = 3, toIndex = 0, playerIndex = 7)
        assertEquals("s7", q.playing())
        assertEquals(7, place)
    }

    @Test
    fun `the sheet shows handles on the stored queue and the player moves only what it holds`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        assertTrue(
            "the stored queue is the one the player will play, with nothing in the player",
            sheet.contains("val movesStoredQueue = detachedHead && detachedQueue != null && detachedQueue === fallbackSeed && queueWindows.isEmpty()")
        )
        assertTrue("its rows get handles", sheet.contains("if (!lockQueue && (!detachedHead || movesStoredQueue) && !isSearching) {"))
        assertTrue(
            "an empty player is not asked to move a song",
            sheet.contains("if (playerConnection.player.mediaItemCount > 0) {\n                        playerConnection.player.moveMediaItem(from, to)")
        )
        val board = File("src/main/java/com/dd3boh/outertune/playback/QueueBoard.kt").readText()
        assertTrue(
            "moveSong gives no index while the player is empty",
            board.contains("player.activePlayer.takeIf { it.mediaItemCount > 0 }?.currentMediaItemIndex")
        )
    }
}
