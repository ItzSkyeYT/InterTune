/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive queue's edits made to the queue board and the player in step (applyAdaptiveEdits,
 * what QueueBoard.applyAdaptive runs). A board and a player out of step are the worst this can do:
 * the next song change writes the player's place into the board, and the next load of the queue
 * starts the wrong song.
 */
class AdaptiveEditsTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    /** A player holding the board's own objects, as ExoPlayer's items carry them, that can be made to refuse a call. */
    private class FakePlayer(songs: List<MediaMetadata>, val refuse: String? = null) : EditablePlayer {
        val items = songs.toMutableList()
        override fun uidAt(index: Int) = items.getOrNull(index)?.composeUidWorkaround
        override fun replace(index: Int, song: MediaMetadata) { if (refuse == "replace") error("no"); items[index] = song }
        override fun remove(index: Int) { if (refuse == "remove") error("no"); items.removeAt(index) }
        override fun add(index: Int, songs: List<MediaMetadata>) { if (refuse == "add") error("no"); items.addAll(index, songs) }
    }

    private fun queue(shuffled: Boolean = false): MultiQueueObject {
        val songs = MutableList(12) { song("s$it").apply { shuffleIndex = if (shuffled) 11 - it else it } }
        return MultiQueueObject(id = 1, title = "Q", queue = songs, shuffled = shuffled, queuePos = if (shuffled) 9 else 2, index = 0)
    }

    private fun MultiQueueObject.order() = getCurrentQueueShuffled().map { it.id }

    private fun edits(q: MultiQueueObject): List<AdaptiveEdit> {
        val play = q.getCurrentQueueShuffled()
        return listOf(
            AdaptiveEdit.Replace(8, play[8].composeUidWorkaround, song("P1")),
            AdaptiveEdit.Drop(10, play[10].composeUidWorkaround),
            AdaptiveEdit.Insert(7, play[6].composeUidWorkaround, listOf(song("I1"), song("I2"))),
        )
    }

    @Test
    fun `the board and the player change in step, and the song playing stays current`() {
        for (shuffled in listOf(false, true)) {
            val q = queue(shuffled)
            val player = FakePlayer(q.getCurrentQueueShuffled())
            val current = q.getQueuePosShuffled()
            val done = applyAdaptiveEdits(q, player, edits(q), notBefore = 7, current = current)

            assertEquals(3, done.size)
            assertEquals(q.order(), player.items.map { it.id })
            assertEquals(12 - 1 + 2, q.getSize())
            assertEquals("shuffled=$shuffled", current, q.getQueuePosShuffled())
            for (i in player.items.indices) assertSame("the player holds the board's own object", q.getCurrentQueueShuffled()[i], player.items[i])
            assertTrue("P1" in q.order() && "I1" in q.order() && q.order().indexOf("I1") == 7)
        }
    }

    @Test
    fun `a player that refuses has the board edit undone, and the pass stops`() {
        for (refuse in listOf("replace", "remove", "add")) {
            val q = queue()
            val before = q.order()
            val player = FakePlayer(q.getCurrentQueueShuffled(), refuse)
            val done = applyAdaptiveEdits(q, player, edits(q), notBefore = 7, current = 2)

            assertEquals("refused $refuse", q.order(), player.items.map { it.id })
            assertEquals(2, q.getQueuePosShuffled())
            if (refuse == "remove") assertEquals("the drop comes first, from the highest place", before, q.order())
            assertTrue(done.none { (refuse == "add" && it is AdaptiveEdit.Insert) || (refuse == "remove" && it is AdaptiveEdit.Drop) })
        }
    }

    @Test
    fun `an edit whose song moved, or that falls in the locked head now, is left out and the rest go on`() {
        val q = queue()
        val player = FakePlayer(q.getCurrentQueueShuffled())
        val play = q.getCurrentQueueShuffled()
        val done = applyAdaptiveEdits(
            q, player,
            listOf(
                AdaptiveEdit.Replace(9, 0.123, song("Wrong")),             // planned for another song
                AdaptiveEdit.Drop(4, play[4].composeUidWorkaround),         // in the lock now
                AdaptiveEdit.Replace(11, play[11].composeUidWorkaround, song("P")),
                AdaptiveEdit.Insert(8, 0.456, listOf(song("I"))),          // its place moved
            ),
            notBefore = 7, current = 2,
        )
        assertEquals(listOf(11), done.map { it.index })
        assertEquals(q.order(), player.items.map { it.id })
        assertEquals("P", q.order()[11])
    }
}
