/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.MediaSource
import com.dd3boh.outertune.constants.EndReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
    fun `a play that failed closes without waiting for its transition`() {
        play(newQueue, "x")
        book.playerError("x", "x")
        var waited = false
        val closed = runBlocking { book.close("x", endedByPlayer = false) { waited = true } }
        assertEquals(EndReason.ERROR, closed.endReason)
        assertFalse(waited)
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
        book.playerError("y", "y")
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
    fun `the anchor's record keeps only the songs left most recently`() {
        val small = PlayBook<PlayEnd>(remembered = 2)
        fun next(id: String) = small.transition(auto, id, PlayEnd())
        next("x")
        next("y")                                // x left at its end
        next("x")                                // y left at its end
        next("w")                                // x left again, so it is the newest
        next("v")                                // w left, and the oldest, y, goes
        assertFalse(small.leftAtItsEnd("y"))
        assertTrue(small.leftAtItsEnd("x"))
        assertTrue(small.leftAtItsEnd("w"))
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
        book.playerError("x", "x")
        play(seek, "y")                          // skip on error, or the listener pressing next
        assertEquals(EndReason.ERROR, close("x"))

        play(newQueue, "z")
        book.playerError("z", "z")
        assertEquals(EndReason.ERROR, close("z"))   // still on its error when the service went
    }

    @Test
    fun `an error the play got over is forgotten once it plays again`() {
        play(newQueue, "x")
        book.playerError("x", "x")
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
        book.playerError("x", "x")
        book.playbackState("x", Player.STATE_READY)
        play(auto, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
    }

    @Test
    fun `the error lands on the play in progress, not an earlier play of the same song`() {
        play(newQueue, "x")
        play(repeat, "x")
        book.playerError("x", "x")
        play(seek, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.ERROR, close("x"))
    }

    @Test
    fun `sound from another song clears nothing on the one that failed`() {
        play(newQueue, "x")
        book.playerError("x", "x")
        play(seek, "y")
        book.playbackState("y", Player.STATE_READY)
        assertEquals(EndReason.ERROR, close("x"))
        assertEquals(EndReason.STOPPED, close("y"))
    }

    @Test
    fun `the next song failing to render fails its own play, not the one that was playing`() {
        // A renderer error met while reading ahead: the player first moves playback on to the item
        // being read, as an automatic transition, and the renderer holding its stream names it. So
        // the play before it ended, and the error is the next one's.
        play(newQueue, "x")
        play(auto, "y")
        book.playerError("y", "y")
        play(seek, "z")                          // skip on error
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.ERROR, close("y"))
        assertEquals(EndReason.STOPPED, close("z"))
    }

    @Test
    fun `an error with no song current, and naming none, marks nothing`() {
        book.playerError(null, null)
        book.playbackState(null, Player.STATE_READY)
        play(newQueue, "x")
        assertEquals(EndReason.STOPPED, close("x"))
    }

    // Which play an error is put on

    @Test
    fun `an error that names no item is put on the play in progress`() {
        play(newQueue, "x")
        play(repeat, "x")
        book.playerError(null, "x")
        play(seek, "y")
        assertEquals(EndReason.ENDED, close("x", ended = true))
        assertEquals(EndReason.ERROR, close("x"))
        assertEquals(EndReason.STOPPED, close("y"))
    }

    @Test
    fun `an error in the next track's track selection names the current item, and stops its play`() {
        // DECODER_QUERY_FAILED from MediaCodecRenderer.supportsFormat while the next track is being
        // loaded ahead: reported with the current item still current, and naming it, because the
        // renderer holds its stream. The player stops there, so the current play ended in the error.
        play(newQueue, "x")
        book.playerError("x", "x")
        play(seek, "y")                          // skip on error
        assertEquals(EndReason.ERROR, close("x"))
    }

    @Test
    fun `an error naming the item just left is put on that play, not the current one`() {
        // A renderer still holding the old stream when the player moved playback on to the item it
        // was reading ahead names the item just left.
        val x = play(newQueue, "x")
        val y = play(auto, "y")
        book.playerError("x", "y")
        assertTrue(x.failed)
        assertFalse(y.failed)
        play(seek, "z")
        assertEquals(EndReason.ENDED, close("x", ended = true))  // its own stats say it ended
        assertEquals(EndReason.SKIPPED, close("y"))
    }

    @Test
    fun `an error naming an item with no play in the book marks no play`() {
        val x = play(newQueue, "x")
        book.playerError("y", "x")
        assertFalse(x.failed)
        val y = play(auto, "y")
        assertFalse(y.failed)
    }

    @Test
    fun `the item an error names is the media item of its period in the timeline`() {
        val timeline = ListTimeline(listOf("x", "y", "z"))
        fun named(period: Any?): String? = PlayBook.itemOf(
            ExoPlaybackException.createForRenderer(
                IllegalStateException(), "audio", 1, null, C.FORMAT_HANDLED,
                period?.let { MediaSource.MediaPeriodId(it) }, false, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            ),
            timeline,
        )
        assertEquals("y", named(1))
        assertEquals("x", named(0))
        // Not given, or not in the timeline: the error names no item, and the current one takes it.
        assertNull(named(null))
        assertNull(named(7))
        assertNull(PlayBook.itemOf(ExoPlaybackException.createForSource(IOException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED), timeline))
        assertNull(PlayBook.itemOf(PlaybackException("remote", null, PlaybackException.ERROR_CODE_REMOTE_ERROR), timeline))
    }

    /** One window and one period per item, the period's uid its index. */
    private class ListTimeline(private val ids: List<String>) : Timeline() {
        override fun getWindowCount(): Int = ids.size

        override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
            window.mediaItem = MediaItem.Builder().setMediaId(ids[windowIndex]).build()
            window.firstPeriodIndex = windowIndex
            window.lastPeriodIndex = windowIndex
            return window
        }

        override fun getPeriodCount(): Int = ids.size

        override fun getPeriod(periodIndex: Int, period: Timeline.Period, setIds: Boolean): Timeline.Period {
            period.windowIndex = periodIndex
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int)?.takeIf { it in ids.indices } ?: C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
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
