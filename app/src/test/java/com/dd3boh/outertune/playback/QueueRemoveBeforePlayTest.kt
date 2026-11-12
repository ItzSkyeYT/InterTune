/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tester (Discord, 9 Nov 2026) picked songs in the queue and found no Remove from queue in the
 * menu, after the app had been away and came back with the mini player showing a song it had not
 * started. The player holds nothing then, and the queue sheet shows the saved queue, which it had
 * left out of Remove from queue as it is out of a swipe: both were for the queue the player holds.
 *
 * The songs come out of the queue board alone there, as a drag moves them (QueueMoveBeforePlayTest),
 * and the board keeps the song that will play where it is. The places are the sheet's own, last
 * place first, and QueueBoard.removeSong gives removeAtPlayIndex the player's index, which reads 0
 * while the player holds nothing.
 */
class QueueRemoveBeforePlayTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }
    private fun MultiQueueObject.playing() = getCurrentQueueShuffled()[getQueuePosShuffled()].id

    /** Ten songs s0 to s9 in order, read back after a restart on s7, which had played to 1:12. */
    private fun restoredInOrder(on: Int = 7) =
        MultiQueueObject(id = 1, title = "Q", queue = MutableList(10) { song("s$it") }, shuffled = false, queuePos = on, lastSongPos = 72_000L, index = 0)

    /** Ten songs shuffled so the play order is the stored order reversed, restored on s2, the eighth to play (place 7). */
    private fun restoredShuffled(): MultiQueueObject {
        val songs = MutableList(10) { i -> song("s$i").apply { shuffleIndex = 9 - i } }
        return MultiQueueObject(id = 1, title = "Q", queue = songs, shuffled = true, queuePos = 2, lastSongPos = 72_000L, index = 0)
    }

    /** What the sheet does for the songs it has picked: the last place first, so the places to go keep their songs. */
    private fun MultiQueueObject.takeOut(vararg places: Int) =
        places.sortedDescending().forEach { removeAtPlayIndex(it, currentPlayIndex = 0) }

    @Test
    fun `songs picked above and below the one that will play go, and it stays the one that will play at its point`() {
        val q = restoredInOrder()
        q.takeOut(2, 5, 9)
        assertEquals(listOf("s0", "s1", "s3", "s4", "s6", "s7", "s8"), q.playOrder())
        assertEquals("s7", q.playing())
        assertEquals(72_000L, q.lastSongPos)
    }

    @Test
    fun `the song that will play, picked itself, hands over to the one after it, which starts from its top`() {
        val q = restoredInOrder()
        q.takeOut(7)
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s8", "s9"), q.playOrder())
        assertEquals("s8", q.playing())
        assertEquals("1:12 was s7's: the next song has no point to go back to", C.TIME_UNSET, q.lastSongPos)
    }

    @Test
    fun `the song that will play and the ones around it, picked together`() {
        val q = restoredInOrder()
        q.takeOut(3, 7, 8)
        assertEquals(listOf("s0", "s1", "s2", "s4", "s5", "s6", "s9"), q.playOrder())
        assertEquals("s9", q.playing())
        assertEquals(C.TIME_UNSET, q.lastSongPos)
    }

    @Test
    fun `the last song, being the one that will play, leaves the one before it`() {
        val q = restoredInOrder(on = 9)
        q.takeOut(9)
        assertEquals("s8", q.playing())
        assertEquals(C.TIME_UNSET, q.lastSongPos)
    }

    @Test
    fun `a shuffled queue keeps its shuffle and the song that will play`() {
        val q = restoredShuffled()
        assertEquals("s2", q.playing())
        // The first and the fifth to play, s9 and s5.
        q.takeOut(0, 4)
        assertTrue("shuffle must stay on", q.shuffled)
        assertEquals(listOf("s8", "s7", "s6", "s4", "s3", "s2", "s1", "s0"), q.playOrder())
        assertEquals("s2", q.playing())
        assertEquals(72_000L, q.lastSongPos)
    }

    @Test
    fun `a shuffled queue's song that will play, picked, hands over to the next one to play`() {
        val q = restoredShuffled()
        // s2 is the eighth to play (place 7), s1 the ninth.
        q.takeOut(7)
        assertTrue("shuffle must stay on", q.shuffled)
        assertEquals("s1", q.playing())
        assertEquals(C.TIME_UNSET, q.lastSongPos)
    }

    @Test
    fun `every song can be taken out, and nothing is left to throw on`() {
        val q = restoredInOrder()
        q.takeOut(*IntArray(10) { it })
        assertEquals(0, q.getSize())
        assertEquals(C.TIME_UNSET, q.lastSongPos)
        // What the sheet and the artwork strip read of an emptied queue.
        assertEquals(emptyList<String>(), q.playOrder())
        assertEquals(0, q.getQueuePosShuffled())
    }

    @Test
    fun `an empty player's index 0 changes nothing about the song that will play`() {
        // Why QueueBoard.removeSong needs no special case for an empty player, as moveSong does:
        // the index is read only when the song playing is the one that goes, and 0 is never before it.
        val withZero = restoredInOrder().apply { removeAtPlayIndex(7, currentPlayIndex = 0) }
        val withItsOwn = restoredInOrder().apply { removeAtPlayIndex(7, currentPlayIndex = 7) }
        assertEquals(withItsOwn.playing(), withZero.playing())
        assertEquals(withItsOwn.queuePos, withZero.queuePos)
    }

    @Test
    fun `the sheet offers it for the saved queue before play and not for another queue looked at`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        val body = sheet.substringAfter("val removeSelected").substringBefore("\n    }\n")
        assertTrue(
            "locked, or another queue looked at from the list, no; the saved queue before play, yes",
            sheet.contains("val removeSelected: (() -> Unit)? = if (lockQueue || (detachedHead && !movesStoredQueue)) null else {")
        )
        assertTrue(
            "the stored queue is the one the player will play, with nothing in the player",
            sheet.contains("val movesStoredQueue = detachedHead && detachedQueue != null && detachedQueue === fallbackSeed && queueWindows.isEmpty()")
        )
        assertTrue(
            "an empty player is not asked to remove a song, as it is not asked to move one",
            body.contains("val loaded = playerConnection.player.mediaItemCount > 0") &&
                body.contains("if (loaded) playerConnection.player.removeMediaItem(place)")
        )
        assertTrue(
            "the mini player and everything that reads the saved queue is told it changed",
            body.contains("if (!loaded) playerConnection.storedQueueEdited()")
        )
    }

    @Test
    fun `the connection reads the song shown from the saved queue again, only while the player holds nothing`() {
        val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
        val body = connection.substringAfter("    fun storedQueueEdited() {").substringBefore("\n    }")
        assertTrue("the player's own events say it once it holds the queue", body.contains("if (player.currentMediaItem != null) return"))
        assertTrue("the song shown is the queue's, none for a queue emptied", body.contains("mediaMetadata.value = song"))
        assertTrue("where it starts goes with it", body.contains("restoredPosition.value ="))
        assertTrue("the sheet and the artwork strip read the board again", body.contains("service.unloadedQueueChanged.value += 1"))
    }
}
