/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.daos.closeShuffleGaps
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three faults found by reading the playback code before 0.13 (experiments/codebase-scan/
 * 2026-11-04-playback.md), each held here by reading the source, as NoWaitingInTransactionsTest
 * does, because each one only shows on a phone in a particular state.
 *
 * - A write that fails on Room's threads, or a settings write on a scope with no handler, had
 *   nowhere to go and ended the app: on a phone with full storage every like, counted play and
 *   queue save does fail (SQLiteFullException, or an IOException from DataStore).
 * - Play next and Add to queue dropped the songs, while their snackbar said they went in, when the
 *   queue board was up with no queue on it: a fresh install before anything played, every restart
 *   with Persistent queue off, every queue deleted.
 * - Shuffle with nothing loaded (the widget's, or a controller's over the media session) stored the
 *   empty player's index 0 as the queue's place, so the queue came back on its first song.
 * - A shuffled queue that lost a song in the database (the local scan's duplicate sweep) came back
 *   with a gap in its shuffle numbers, so it resumed on the wrong song or threw at every load.
 */
class PlaybackFaultsTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    /** The body of the function [name] in [text], up to the next function at the same depth. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("$name is there", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        error("$name has no end")
    }

    @Test
    fun `a job on the database's own threads that fails is logged, not left to end the app`() {
        val db = code("db/MusicDatabase.kt")
        for (name in listOf("query", "transaction")) {
            val fn = body(db, name)
            assertTrue("$name catches what its block throws", "catch (e: Exception)" in fn)
            assertTrue("$name says so in the log, where Report a problem finds it", "Log.e(" in fn)
        }
        // The transaction's catch sits outside runInTransaction, so a failed transaction still rolls back.
        val transaction = body(db, "transaction")
        assertTrue(transaction.indexOf("try {") in 0 until transaction.indexOf("runInTransaction {"))
    }

    @Test
    fun `the queue's saves run on a scope that logs a failure`() {
        val board = code("playback/QueueBoard.kt")
        assertTrue("val coroutineScope = CoroutineScope(saveDispatcher + saveFailed)" in board)
        assertTrue("CoroutineExceptionHandler" in board)
    }

    @Test
    fun `the service's settings writes on scopes with no handler are caught`() {
        val service = code("playback/MusicService.kt")
        for (key in listOf("PlayerVolumeKey", "SleepScheduleLastNightKey", "RelatedRefreshDayKey", "RepeatModeKey")) {
            val at = service.indexOf("settings[$key]").takeIf { it >= 0 }
                ?: service.indexOf("it[$key]").takeIf { it >= 0 }
                ?: service.indexOf("prefs[$key]")
            assertTrue("$key is written", at >= 0)
            val before = service.substring(maxOf(0, at - 260), at)
            assertTrue("the write of $key is inside a runCatching", "runCatching {" in before)
        }
    }

    @Test
    fun `Play next and Add to queue play the songs as a new queue when there is no queue`() {
        val service = code("playback/MusicService.kt")
        for (name in listOf("enqueueNext", "enqueueEnd")) {
            val fn = body(service, name)
            assertTrue(
                "$name takes a board with no queue for a board that is down",
                "if (!qbInit.value || queueBoard.getCurrentQueue() == null)" in fn,
            )
        }
    }

    @Test
    fun `shuffle with nothing loaded leaves the queue's place as it is`() {
        val fn = body(code("playback/MusicService.kt"), "triggerShuffle")
        val guard = "if (activePlayer.currentMediaItem != null) queueBoard.setCurrQueuePosIndex(oldIndex)"
        assertTrue(guard in fn)
        assertEquals("the place is stored nowhere else", 1, Regex("setCurrQueuePosIndex").findAll(fn).count())
    }

    private fun song(id: String, shuffle: Int) =
        MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null).also { it.shuffleIndex = shuffle }

    @Test
    fun `a shuffled queue that lost a song is numbered 0 to n-1 again, in the same order`() {
        val songs = mutableListOf(song("a", 3), song("b", 0), song("c", 4), song("d", 1)) // 2 was deleted
        closeShuffleGaps(songs)
        assertEquals(listOf(2, 0, 3, 1), songs.map { it.shuffleIndex })
        assertEquals(listOf("b", "d", "a", "c"), songs.sortedBy { it.shuffleIndex }.map { it.id })
        assertEquals("both reads of a saved queue renumber", 2, Regex("closeShuffleGaps\\(").findAll(code("db/daos/QueueDao.kt")).count() - 1)
    }

    @Test
    fun `looking a song up leaves the queue as it is, even with its place out of range`() {
        val songs = mutableListOf(song("a", 1), song("b", 0), song("c", 2))
        val q = MultiQueueObject(id = 1, title = "q", queue = songs, shuffled = true, queuePos = 7, index = 0)
        assertSame(songs[1], q.findSong("b"))
        assertEquals(null, q.findSong("z"))
        assertTrue("the shuffle is still on", q.shuffled)
        assertEquals("the place is untouched", 7, q.queuePos)
        assertFalse("no repair renumbered the shuffle", songs.map { it.shuffleIndex } == listOf(0, 1, 2))
    }
}
