/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.db.ListenSql
import com.dd3boh.outertune.db.SchemaDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Undo of a song taken out of the queue by hand takes back what the removal said about it, not only its row
 * (review of 0.13, 18 Nov 2026): a removal gives its artists a skip, turns its title down, takes away its mark
 * as put in by hand and notes REMOVED_FROM_QUEUE in the listening log. Undone, the song came back on screen,
 * and in a radio or mix the next pass found it turned down and put a fresh pick in its place.
 */
class QueueUndoTakesBackTest {
    private var now = 1_000_000L
    private fun heard() = AdaptiveQueue.Heard(clock = { now })
    private fun heard(recentCap: Int) = AdaptiveQueue.Heard(recentCap = recentCap, clock = { now })

    private fun AdaptiveQueue.Heard.inRadio(): AdaptiveQueue.Heard = apply {
        note(1L, "A", "Alpha", listOf("Band"), 200, runId = 9L, radio = true)
    }

    /** All a pass reads of the run, without the times. */
    private fun AdaptiveQueue.Heard.read() = view().let { listOf(it.turnedDown, it.artistSkips, it.byHand, it.picks, it.dismissed) }

    @Test
    fun `a song taken out and put back is read as before, not turned down`() {
        val h = heard().inRadio()
        val before = h.read()
        val removal = h.removedByHand("S", "Paper Moon (Slowed)", listOf("Uploader"))
        assertTrue("paper moon" in h.view().turnedDown.songs)

        h.putBackByHand(removal)

        assertEquals(before, h.read())
        assertFalse("paper moon" in h.view().turnedDown.songs)
        assertTrue(h.view().artistSkips.isEmpty())
    }

    @Test
    fun `two songs of one artist taken out and put back leave the artist in the radio`() {
        val h = heard().inRadio()
        val first = h.removedByHand("S1", "One", listOf("Uploader"))
        val second = h.removedByHand("S2", "Two", listOf("Uploader"))
        assertEquals("two removals turn the artist down", setOf("uploader"), h.view().turnedDown.artists)

        // In the order Undo takes them back: the last removal first.
        h.putBackByHand(second)
        h.putBackByHand(first)

        assertTrue(h.view().turnedDown.artists.isEmpty())
        assertTrue(h.view().artistSkips.isEmpty())
        assertTrue(h.view().turnedDown.songs.isEmpty())
    }

    @Test
    fun `a pick and a song put in by hand come back as they were`() {
        val h = heard().inRadio()
        h.picked(mapOf("P" to "A"))
        h.putInByHand(1L, 9L, listOf("H"), radio = true)
        val before = h.read()

        val pick = h.removedByHand("P", "Picked", listOf("Someone"))
        val mine = h.removedByHand("H", "Mine", listOf("Other"))
        assertTrue("P" in h.view().dismissed)
        assertFalse("H" in h.byHand)

        h.putBackByHand(mine)
        h.putBackByHand(pick)

        assertEquals(before, h.read())
        assertTrue("a pass leaves a song put in by hand alone", "H" in h.byHand)
        assertTrue("the pick is a pick again, not one never to pick", "P" in h.view().picks && "P" !in h.view().dismissed)
    }

    @Test
    fun `a skip of the same artist after the removal still counts after Undo`() {
        val h = heard().inRadio()
        val removal = h.removedByHand("S", "Paper Moon", listOf("Uploader"))
        h.skipped(AdaptiveQueue.Song("T", "Another", listOf("Uploader"), 180))

        h.putBackByHand(removal)

        assertEquals(setOf("uploader"), h.view().artistSkips)
        assertTrue("one skip is not two", h.view().turnedDown.artists.isEmpty())
        assertEquals(setOf("another"), h.view().turnedDown.songs)
    }

    @Test
    fun `a title turned down before the removal stays turned down`() {
        val h = heard().inRadio()
        h.skipped(AdaptiveQueue.Song("V", "Paper Moon", listOf("Someone"), 180))
        val removal = h.removedByHand("S", "Paper Moon (Slowed)", listOf("Uploader"))

        h.putBackByHand(removal)

        assertEquals(setOf("paper moon"), h.view().turnedDown.songs)
    }

    @Test
    fun `a title the removal pushed out of a full list comes back`() {
        val h = heard(recentCap = 2).inRadio()
        h.skipped(AdaptiveQueue.Song("X", "Xylo", listOf("Ax"), 180))
        h.skipped(AdaptiveQueue.Song("Y", "Yarrow", listOf("Ay"), 180))
        val removal = h.removedByHand("S", "Song", listOf("As"))
        assertEquals(setOf("yarrow", "song"), h.view().turnedDown.songs)

        h.putBackByHand(removal)

        assertEquals(setOf("xylo", "yarrow"), h.view().turnedDown.songs)
    }

    @Test
    fun `Undo takes back the newest REMOVED_FROM_QUEUE of the song, and nothing else`() {
        val db = SchemaDb.open()
        db.use {
            val st = it.createStatement()
            fun signal(song: String, kind: Int, at: Long) = st.execute(
                "INSERT INTO listen_signal (listenId, songId, kind, positionMs, value, at) VALUES (NULL, '$song', $kind, -1, 0, $at)"
            )
            fun count(sql: String) = st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
            val t = 1_790_000_000_000L
            signal("s", SignalKind.REMOVED_FROM_QUEUE, t - 60_000) // taken out an hour ago, not undone
            signal("s", SignalKind.REMOVED_FROM_QUEUE, t + 5) // this removal
            signal("o", SignalKind.REMOVED_FROM_QUEUE, t + 6) // another song of the same removal
            signal("s", SignalKind.REMOVED_FROM_HISTORY, t + 7)

            st.execute(
                ListenSql.DROP_LAST_SIGNAL.replace(":songId", "'s'").replace(":kind", "${SignalKind.REMOVED_FROM_QUEUE}").replace(":since", "$t")
            )

            assertEquals(3L, count("SELECT COUNT(*) FROM listen_signal"))
            assertEquals(0L, count("SELECT COUNT(*) FROM listen_signal WHERE songId = 's' AND kind = ${SignalKind.REMOVED_FROM_QUEUE} AND at >= $t"))
            assertEquals("the older removal keeps its row", 1L, count("SELECT COUNT(*) FROM listen_signal WHERE songId = 's' AND kind = ${SignalKind.REMOVED_FROM_QUEUE}"))

            // Nothing noted since (the log paused): nothing goes.
            st.execute(
                ListenSql.DROP_LAST_SIGNAL.replace(":songId", "'s'").replace(":kind", "${SignalKind.REMOVED_FROM_QUEUE}").replace(":since", "${t + 1_000}")
            )
            assertEquals(3L, count("SELECT COUNT(*) FROM listen_signal"))
        }
    }

    @Test
    fun `the queue sheet's Undo takes back what each of its removals noted`() {
        val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        val putBack = queue.substringAfter("val putBackTaken:").substringBefore("val offerUndo:")
        assertTrue(putBack.contains("for (removal in notes.removals.asReversed()) playerConnection.service.notePutBackByHand(removal)"))
        assertTrue(putBack.contains("ActivityLog.takeBack(database, t.song.id, SignalKind.REMOVED_FROM_QUEUE, notes.at)"))
        // Every removal by hand keeps what the adaptive queue was told, for its Undo: the swipe and the pill.
        assertEquals(2, Regex("""notes\.removals \+= playerConnection\.service\.noteRemovedByHand\(""").findAll(queue).count())
        assertEquals(
            Regex("""noteRemovedByHand\(""").findAll(queue).count(),
            Regex("""notes\.removals \+= playerConnection\.service\.noteRemovedByHand\(""").findAll(queue).count(),
        )
        assertEquals(2, Regex("""offerUndo\(queueId, [^\n]*, notes\)""").findAll(queue).count())
    }
}
