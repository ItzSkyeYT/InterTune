/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import com.dd3boh.outertune.constants.EndReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's bookkeeping of plays, driven the way the player drives it: transitions, errors,
 * state changes, and the stats of each play arriving afterwards.
 */
class PlayBookTest {
    private val auto = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    private val repeat = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
    private val seek = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    private val newQueue = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

    private val book = PlayBook<PlayEnd>()

    /** A transition to a new play of [id], which it returns. */
    private fun play(reason: Int, id: String): PlayEnd = PlayEnd().also { book.transition(reason, id, it) }

    /** The stats of the oldest open play of [id] arrive, with no reason still to come. */
    private fun close(id: String, ended: Boolean = false): Int = runBlocking { book.close(id, ended) {}.endReason }

    // A reason belongs to one play

    @Test
    fun `a song left by a seek after its end gives its skip to no later play`() {
        play(newQueue, "x")
        // x reached the end of its queue (STATE_ENDED, no transition), then the listener picked
        // another song: a seek out of a play that had already ended.
        play(seek, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
        // x again, later, left paused when the service went: no transition moved on from it.
        play(seek, "x")
        assertEquals(EndReason.STOPPED, close("x"))
    }

    @Test
    fun `a song left by a new queue after its end gives its replacement to no later play`() {
        play(newQueue, "x")
        play(newQueue, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
        play(seek, "x")
        assertEquals(EndReason.STOPPED, close("x"))
    }

    @Test
    fun `a repeat paused and closed with the service was stopped, not played to the end`() {
        play(newQueue, "x")
        play(repeat, "x")                       // the second play starts before the first one's stats
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.STOPPED, close("x"))
    }

    @Test
    fun `stats that arrive before their transition still get its reason, and only theirs`() {
        play(newQueue, "x")
        // x's stats first: the reason arrives while they wait for it.
        val closed = runBlocking { book.close("x", endedByPlayer = false) { play(seek, "y") } }
        assertEquals(EndReason.SKIPPED, closed.endReason)
        // And it went on that play of x alone: y has the reason of its own leaving, and the next
        // play of x, which nothing has moved on from, has none.
        play(newQueue, "x")
        assertEquals(EndReason.REPLACED, close("y"))
        assertEquals(EndReason.STOPPED, close("x"))
    }

    @Test
    fun `each play of a song keeps its own reason, oldest closed first`() {
        play(newQueue, "x")
        play(seek, "y")                          // the first play of x was skipped
        play(newQueue, "x")                      // the queue was replaced under y, and x again
        play(auto, "z")                          // which played to the end
        assertEquals(EndReason.SKIPPED, close("x"))
        assertEquals(EndReason.REPLACED, close("y"))
        assertEquals(EndReason.ENDED, close("x", ended = true))
    }

    @Test
    fun `the player emptied ends the play in progress, and nothing is in progress after it`() {
        play(newQueue, "x")
        book.transition(newQueue, null, null)
        assertEquals(EndReason.REPLACED, close("x"))
        // Nothing to give the next reason to: the play after the empty player starts clean.
        play(newQueue, "y")
        assertEquals(EndReason.STOPPED, close("y"))
    }

    @Test
    fun `a play with no record closes as a stop without waiting`() {
        var waited = false
        val closed = runBlocking { book.close("never", endedByPlayer = false) { waited = true } }
        assertNull(closed.play)
        assertEquals(EndReason.STOPPED, closed.endReason)
        assertFalse(waited)
    }

    @Test
    fun `the current play is the newest, and the stats take the oldest`() {
        val first = play(newQueue, "x")
        val second = play(repeat, "x")
        assertSame(second, book.current("x"))
        assertSame(first, book.takeOldest("x"))
        assertSame(second, book.takeOldest("x"))
        assertNull(book.takeOldest("x"))
        assertNull(book.current("x"))
    }

    // Stats with no play time

    @Test
    fun `the stats of a next track loaded ahead take nothing from the new queue's play of it`() {
        // The last stretch of an album track: Media3 starts loading the next track, which gives it
        // a session of its own, though no play in the book is that track.
        val x = play(newQueue, "x").apply { opened = true }
        // The listener taps that next track in the album screen, which builds a new queue. Its play
        // begins, and then the session of the track loaded ahead finishes with no play time.
        val y = play(newQueue, "y")
        assertNull(book.takeUnplayed("y"))
        assertSame(y, book.current("y"))         // so sound opens this play's row
        y.opened = true
        assertSame(x, runBlocking { book.close("x", endedByPlayer = false) {} }.play)
        // Its own stats close it, with the record it started with and its own reason: a skip...
        play(seek, "z")
        val skipped = runBlocking { book.close("y", endedByPlayer = false) {} }
        assertSame(y, skipped.play)
        assertEquals(EndReason.SKIPPED, skipped.endReason)
        // ...or an error, when the same happens and its stream then dies.
        val again = play(newQueue, "y")
        assertNull(book.takeUnplayed("y"))
        again.opened = true
        book.playerError("y")
        play(seek, "w")
        val failed = runBlocking { book.close("y", endedByPlayer = false) {} }
        assertSame(again, failed.play)
        assertEquals(EndReason.ERROR, failed.endReason)
    }

    @Test
    fun `stats with no play time take nothing that sounded, even once it was left`() {
        // The stats of the track loaded ahead can be read late, after the new play sounded and the
        // listener moved on: that play's own stats are still to come.
        val y = play(newQueue, "y").apply { opened = true }
        play(seek, "z")
        assertNull(book.takeUnplayed("y"))
        assertSame(y, book.takeOldest("y"))
    }

    @Test
    fun `stats with no play time take the oldest play that never sounded`() {
        val sounded = play(newQueue, "y").apply { opened = true }   // played, its stats not in yet
        val loaded = play(newQueue, "y")                            // a new queue, left before it started
        play(seek, "x")
        val current = play(seek, "y")                               // y again, in progress, not sounded yet
        assertSame(loaded, book.takeUnplayed("y"))
        // The one that sounded and the one in progress stay, for their own stats.
        assertNull(book.takeUnplayed("y"))
        assertSame(sounded, book.takeOldest("y"))
        assertSame(current, book.takeOldest("y"))
    }

    // The radio anchor

    @Test
    fun `a song let finish stays the anchor after its listen closes`() {
        play(newQueue, "x")
        play(auto, "y")
        assertTrue(book.leftAtItsEnd("x"))
        close("x", ended = true)
        assertTrue(book.leftAtItsEnd("x"))
        // A repeat is let finish too.
        play(repeat, "y")
        assertTrue(book.leftAtItsEnd("y"))
    }

    @Test
    fun `a song skipped or replaced, or never left, is no anchor`() {
        play(newQueue, "x")
        play(seek, "y")
        play(newQueue, "z")
        assertFalse(book.leftAtItsEnd("x"))
        assertFalse(book.leftAtItsEnd("y"))
        assertFalse(book.leftAtItsEnd("z"))
        // Its latest play decides: x let finish, then x again, skipped.
        play(auto, "x")
        play(auto, "w")
        assertTrue(book.leftAtItsEnd("x"))
        play(seek, "x")
        play(seek, "y")
        assertFalse(book.leftAtItsEnd("x"))
    }

    // A play that failed

    @Test
    fun `a play that stopped on an error ended in it, however it was left`() {
        play(newQueue, "x")
        book.playerError("x")
        play(seek, "y")                          // skip on error, or the listener pressing next
        assertEquals(EndReason.ERROR, close("x"))

        play(newQueue, "z")
        book.playerError("z")
        assertEquals(EndReason.ERROR, close("z"))   // still on its error when the service went
    }

    @Test
    fun `an error the play got over is forgotten once it plays again`() {
        play(newQueue, "x")
        book.playerError("x")
        // Retrying is not playing: buffering after an error can fail the same way, and idle is
        // where the error left it.
        book.playbackState("x", Player.STATE_BUFFERING)
        book.playbackState("x", Player.STATE_IDLE)
        assertTrue(book.current("x")!!.failed)
        book.playbackState("x", Player.STATE_READY)
        assertFalse(book.current("x")!!.failed)
        // Then the listener skipped it, which is a real skip.
        play(seek, "y")
        assertEquals(EndReason.SKIPPED, close("x"))
    }

    @Test
    fun `an error the play got over and then played to the end is an end`() {
        play(newQueue, "x")
        book.playerError("x")
        book.playbackState("x", Player.STATE_READY)
        play(auto, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
    }

    @Test
    fun `the error lands on the play in progress, not an earlier play of the same song`() {
        play(newQueue, "x")
        play(repeat, "x")
        book.playerError("x")
        play(seek, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.ERROR, close("x"))
    }

    @Test
    fun `sound from another song clears nothing on the one that failed`() {
        play(newQueue, "x")
        book.playerError("x")
        play(seek, "y")
        book.playbackState("y", Player.STATE_READY)
        assertEquals(EndReason.ERROR, close("x"))
        assertEquals(EndReason.STOPPED, close("y"))
    }

    @Test
    fun `the next song failing to load fails its own play, not the one that was playing`() {
        // Media3 1.8.0 reports an error preparing or loading the next item only once that item is
        // current: after its transition, which for one met while reading ahead the player makes
        // itself, as an automatic one. So the play before it ended, and the error is the next one's.
        play(newQueue, "x")
        play(auto, "y")
        book.playerError("y")
        play(seek, "z")                          // skip on error
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.ERROR, close("y"))
        assertEquals(EndReason.STOPPED, close("z"))
    }

    @Test
    fun `an error with no song current marks nothing`() {
        book.playerError(null)
        book.playbackState(null, Player.STATE_READY)
        play(newQueue, "x")
        assertEquals(EndReason.STOPPED, close("x"))
    }

    @Test
    fun `each transition says what it says about the play it left`() {
        assertEquals(EndReason.ENDED, PlayBook.endReasonOf(auto))
        assertEquals(EndReason.ENDED, PlayBook.endReasonOf(repeat))
        assertEquals(EndReason.SKIPPED, PlayBook.endReasonOf(seek))
        assertEquals(EndReason.REPLACED, PlayBook.endReasonOf(newQueue))
        assertEquals(EndReason.UNKNOWN, PlayBook.endReasonOf(42))
    }
}
