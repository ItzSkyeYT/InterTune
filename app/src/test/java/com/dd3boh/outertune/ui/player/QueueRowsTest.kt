/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The queue's rows keep their names when the queue changes round them, and the menu its state when the song does. */
class QueueRowsTest {
    private fun song(id: String, uid: Double) = MediaMetadata(
        id = id, title = "Song $id", artists = emptyList(), duration = 200, thumbnailUrl = null, genre = null,
        composeUidWorkaround = uid,
    )

    private val a = song("a", 0.11)
    private val b = song("b", 0.22)
    private val c = song("c", 0.33)
    private val d = song("d", 0.44)

    private fun keys(rows: List<MediaMetadata>) = rows.map { QueueRows.key(it) }

    @Test
    fun `a song keeps its row when one before it is removed`() {
        val before = QueueRows.of(listOf(a, b, c, d))
        val after = QueueRows.of(listOf(a, c, d))
        assertEquals(keys(before).filterIndexed { i, _ -> i != 1 }, keys(after))
    }

    @Test
    fun `a song keeps its row when it is moved, and so do the ones it passes`() {
        val before = QueueRows.of(listOf(a, b, c, d))
        val after = QueueRows.of(listOf(a, d, b, c))
        assertEquals(keys(before).toSet(), keys(after).toSet())
        assertEquals(keys(before)[3], keys(after)[1])
        assertEquals(keys(before)[1], keys(after)[2])
    }

    @Test
    fun `every row has a key of its own, also for one song queued twice as the same entry`() {
        val rows = QueueRows.of(listOf(a, b, a, c, a))
        assertEquals(5, keys(rows).toSet().size)
        assertEquals("the first of them is the song as it was", a, rows[0])
        // And the same list read again gives the same keys.
        assertEquals(keys(rows), keys(QueueRows.of(listOf(a, b, a, c, a))))
    }

    @Test
    fun `a row is still itself when its song is liked`() {
        val rows = QueueRows.of(listOf(a, b))
        assertEquals(QueueRows.key(rows[1]), QueueRows.key(rows[1].copy(liked = true)))
    }

    @Test
    fun `a list that comes back as it was is the same list, and one with a song less is not`() {
        val shown = QueueRows.of(listOf(a, b, c))
        assertTrue(QueueRows.same(QueueRows.of(listOf(a, b, c)), shown))
        assertFalse(QueueRows.same(QueueRows.of(listOf(a, c)), shown))
        assertFalse(QueueRows.same(QueueRows.of(listOf(a, c, b)), shown))
        assertTrue(QueueRows.same(emptyList(), emptyList()))
    }

    // No screen runs on the JVM, so these two read the source, as SolverPageIsShutTest does.

    private fun code(path: String) = File(path).readLines()
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/**") }.joinToString("\n")

    @Test
    fun `the menu's state is made once, not with every composition`() {
        val main = code("src/main/java/com/dd3boh/outertune/MainActivity.kt")
        assertFalse("made where it is handed down, so made again whenever that is composed again", Regex("""provides\s+MenuState\(""").containsMatchIn(main))
        assertTrue(Regex("""remember\(menuSheetState\)\s*\{\s*MenuState\(menuSheetState\)\s*\}""").containsMatchIn(main))
        assertTrue("LocalMenuState provides menuState" in main)
    }

    @Test
    fun `the queue's list is put at the playing song once for a queue, and its rows are not named by their place`() {
        val queue = code("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt")
        assertFalse("a row named by its position is a new row whenever one before it goes", "composeUidWorkaround = index" in queue)
        // Told apart by QueueRows.key in the list, in the row a drag holds, and where a drag looks its rows up.
        assertTrue("key = { _, item -> QueueRows.key(item) }" in queue)
        assertTrue("key = QueueRows.key(window)" in queue)
        assertEquals(2, Regex("""mutableSongs\.indexOfFirst \{ QueueRows\.key\(it\) == (from|to)\.key \}""").findAll(queue).count())
        val scrolls = Regex("""lazySongsListState\.scrollToItem\((currentWindowIndex|it\.getQueuePosShuffled\(\))\)""").findAll(queue).toList()
        // In the effect that fills the list, both behind the check of what the list was last put there for;
        // the third is the saved queues' panel opening, which is somebody asking for it.
        assertEquals(3, scrolls.size)
        assertEquals(2, Regex("""scrolledFor != showing""").findAll(queue).count())
        assertEquals(2, Regex("""scrolledFor = showing""").findAll(queue).count())
    }
}
