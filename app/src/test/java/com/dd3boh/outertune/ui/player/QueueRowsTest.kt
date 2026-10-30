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
        // In the effect that fills the list, both behind the check of what the list was last put there
        // for, and only once its rows are laid out (26 Oct: before then the scroll went to the top).
        assertEquals(2, Regex("""lazySongsListState\.scrollToWhenLaidOut\((currentWindowIndex|at)\)""").findAll(queue).count())
        // The saved queues' panel opening, which is somebody asking for it, scrolls at once.
        assertEquals(1, Regex("""lazySongsListState\.scrollToItem\(currentWindowIndex\)""").findAll(queue).count())
        assertEquals(2, Regex("""scrolledFor != showing""").findAll(queue).count())
        assertEquals(2, Regex("""scrolledFor = showing""").findAll(queue).count())
    }

    // His note of 30 Oct 2026: "When locking and unlocking phone while intertune is foreground,
    // queue flashes future results". The far end of a radio changes while the phone is locked, and
    // the first frame back put the new fold over the songs the sheet held from before the lock.

    private val e = song("e", 0.55)

    @Test
    fun `the frame the player's list changes in shows the player's songs, not the ones kept from before`() {
        val kept = QueueRows.of(listOf(a, b, c, d))
        val player = QueueRows.of(listOf(a, b, c, e))
        assertEquals(player, QueueRows.onShow(kept, player, caughtUp = false, preview = false))
    }

    @Test
    fun `once filled from the player's list the sheet shows its own copy, a drag or a swipe ahead of the player`() {
        val player = QueueRows.of(listOf(a, b, c, d))
        val dragged = QueueRows.of(listOf(a, c, b, d))
        assertEquals(dragged, QueueRows.onShow(dragged, player, caughtUp = true, preview = false))
        val swiped = QueueRows.of(listOf(a, c, d))
        assertEquals(swiped, QueueRows.onShow(swiped, player, caughtUp = true, preview = false))
    }

    @Test
    fun `a preview of a queue the player does not hold is the sheet's own`() {
        val preview = QueueRows.of(listOf(a, b))
        assertEquals(preview, QueueRows.onShow(preview, emptyList(), caughtUp = false, preview = true))
    }

    @Test
    fun `the fold is laid over the songs the list shows, and the list says which of the player's lists it holds`() {
        val queue = code("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt")
        assertTrue("val songs = QueueRows.onShow(mutableSongs, playerSongs, caughtUp = songsFrom === queueWindows" in queue)
        assertTrue("songs.take(" in queue)
        assertTrue("val hiddenTailCount = if (isSearching) 0 else songs.size - visibleSongs.size" in queue)
        assertFalse("the fold over the sheet's own copy is the flash", "mutableSongs.take(" in queue)
        // Filled and marked in one go, in the effect that fills the list from the player's.
        val effect = queue.substringAfter("val shown = playerSongs").substringBefore("val showing = \"playing \"")
        assertTrue(effect, "songsFrom = queueWindows" in effect)
    }
}
