/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackTest {
    private var clock = 1_000_000L
    private val playback = RemotePlayback<String> { clock }

    private fun songs(vararg ids: String, durationMs: Long = 200_000) = ids.map { RemoteSong(it, durationMs, "item-$it") }

    /** Casting starts on b of a, b, c, 30 s in, playing, and the receiver is sent b. */
    private fun casting(repeat: Int = Player.REPEAT_MODE_OFF): RemoteCommand.Load<String> {
        playback.begin(songs("a", "b", "c"), startIndex = 1, startMs = 30_000, play = true, repeat = repeat)
        return playback.prepare().single() as RemoteCommand.Load<String>
    }

    private fun report(state: ReceiverState, positionMs: Long = 0, durationMs: Long = 200_000, error: String? = null) =
        playback.onReceiver(
            playback.ticket,
            ReceiverStatus(link = LinkState.CONNECTED, state = state, positionMs = positionMs, durationMs = durationMs, reportedAt = clock, error = error),
        )

    private fun later(ms: Long = RemotePlayback.ASK_GRACE_MS + 1) {
        clock += ms
    }

    @Test
    fun castingStartsWithTheSongWherePhoneWas() {
        val load = casting()
        assertEquals("b", load.entry.mediaId)
        assertEquals("item-b", load.entry.item)
        assertEquals(30_000, load.startMs)
        assertTrue(load.play)
        assertEquals(RemotePhase.BUFFERING, playback.phase)
        assertEquals(30_000, playback.positionMs())
    }

    @Test
    fun playingReportsCarryThePositionOnUpToTheEnd() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 31_000)
        assertEquals(RemotePhase.READY, playback.phase)
        clock += 4_000
        assertEquals(35_000, playback.positionMs())
        clock += 1_000_000
        assertEquals(200_000, playback.positionMs())
        val snapshot = playback.positionSnapshot()
        clock += 5_000
        assertEquals(200_000, snapshot.at(clock))
    }

    @Test
    fun theNextSongLoadsWhenOneEnds() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 190_000)
        val next = report(ReceiverState.ENDED, positionMs = 200_000).single() as RemoteCommand.Load<String>
        assertEquals("c", next.entry.mediaId)
        assertEquals(0, next.startMs)
        assertTrue(next.play)
        assertEquals(2, playback.index)
        assertTrue(playback.takeAutoTransition())
        assertFalse(playback.takeAutoTransition())
    }

    @Test
    fun theListEndsAfterItsLastSongWithRepeatOff() {
        casting()
        playback.seekTo(2, 0)
        later()
        report(ReceiverState.PLAYING, positionMs = 199_000)
        assertTrue(report(ReceiverState.ENDED, positionMs = 200_000).isEmpty())
        assertEquals(RemotePhase.ENDED, playback.phase)
        assertEquals(2, playback.index)
        assertEquals(200_000, playback.positionMs())
        // The same end said again, after the listener turned repeat on, starts nothing.
        playback.repeatMode = Player.REPEAT_MODE_ALL
        assertTrue(report(ReceiverState.ENDED, positionMs = 200_000).isEmpty())
        assertEquals(RemotePhase.ENDED, playback.phase)
    }

    @Test
    fun repeatOneSendsTheSameSongAgain() {
        casting(repeat = Player.REPEAT_MODE_ONE)
        later()
        val again = report(ReceiverState.ENDED, positionMs = 200_000).single() as RemoteCommand.Load<String>
        assertEquals("b", again.entry.mediaId)
        assertEquals(1, playback.index)
        assertTrue(playback.takeAutoTransition())
    }

    @Test
    fun repeatAllGoesBackToTheFirstSong() {
        casting(repeat = Player.REPEAT_MODE_ALL)
        playback.seekTo(2, 0)
        later()
        val first = report(ReceiverState.ENDED, positionMs = 200_000).single() as RemoteCommand.Load<String>
        assertEquals("a", first.entry.mediaId)
        assertEquals(0, playback.index)
    }

    @Test
    fun reportsAboutASongSkippedPastAreIgnored() {
        casting()
        val old = playback.ticket
        val next = playback.seekTo(2, 0).single() as RemoteCommand.Load<String>
        assertEquals("c", next.entry.mediaId)
        later()
        val stale = ReceiverStatus(link = LinkState.CONNECTED, state = ReceiverState.ENDED, positionMs = 200_000, durationMs = 200_000, reportedAt = clock)
        assertTrue(playback.onReceiver(old, stale).isEmpty())
        assertEquals(2, playback.index)
        assertEquals(RemotePhase.BUFFERING, playback.phase)
    }

    @Test
    fun aSeekInTheSongIsASeekThereNotAReload() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 40_000)
        val seek = playback.seekTo(1, 120_000).single() as RemoteCommand.Seek
        assertEquals(playback.ticket, seek.ticket)
        assertEquals(120_000, seek.positionMs)
        assertEquals(120_000, playback.positionMs())
        // A report made before the receiver got there is left alone...
        clock += 500
        report(ReceiverState.PLAYING, positionMs = 41_000)
        assertEquals(120_500, playback.positionMs())
        // ...and one from after it is believed.
        report(ReceiverState.PLAYING, positionMs = 120_400)
        assertEquals(120_400, playback.positionMs())
        later()
        report(ReceiverState.PLAYING, positionMs = 50_000)
        assertEquals(50_000, playback.positionMs())
    }

    @Test
    fun aSeekToTheDefaultPositionOfTheCurrentSongStartsItAgain() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 40_000)
        val seek = playback.seekTo(-1, Long.MIN_VALUE + 1).single() as RemoteCommand.Seek
        assertEquals(0, seek.positionMs)
    }

    @Test
    fun aSeekWhileIdleOnlyMovesAndPrepareLoadsThere() {
        playback.begin(songs("a", "b", "c"), startIndex = 0, startMs = 0, play = false, repeat = Player.REPEAT_MODE_OFF)
        assertTrue(playback.seekTo(2, 15_000).isEmpty())
        assertEquals(2, playback.index)
        val load = playback.prepare().single() as RemoteCommand.Load<String>
        assertEquals("c", load.entry.mediaId)
        assertEquals(15_000, load.startMs)
        assertFalse(load.play)
    }

    @Test
    fun pauseFreezesThePositionAndALateReportCannotUndoIt() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 60_000)
        clock += 1_500
        assertEquals(listOf(RemoteCommand.Pause), playback.setPlayWhenReady(false))
        assertEquals(61_500, playback.positionMs())
        clock += 800
        report(ReceiverState.PLAYING, positionMs = 62_000)
        assertFalse(playback.playWhenReady)
        assertEquals(61_500, playback.positionMs())
        report(ReceiverState.PAUSED, positionMs = 61_600)
        assertEquals(61_600, playback.positionMs())
        assertFalse(playback.changedOnReceiver)
    }

    @Test
    fun nextAndPreviousAreOnlyThereWhenThereIsASongToGoTo() {
        casting()
        assertTrue(playback.hasPrevious)
        assertTrue(playback.hasNext)
        playback.seekTo(2, 0)
        assertFalse(playback.hasNext)
        playback.repeatMode = Player.REPEAT_MODE_ONE
        assertFalse(playback.hasNext)
        playback.repeatMode = Player.REPEAT_MODE_ALL
        assertTrue(playback.hasNext)
        playback.seekTo(0, 0)
        assertTrue(playback.hasPrevious)
        playback.repeatMode = Player.REPEAT_MODE_OFF
        assertFalse(playback.hasPrevious)
        assertTrue(playback.hasNext)
        playback.setItems(emptyList(), 0, 0)
        assertFalse(playback.hasNext)
        assertFalse(playback.hasPrevious)
    }

    @Test
    fun playOrPausePressedOnTheReceiverIsFollowed() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 60_000)
        later()
        report(ReceiverState.PAUSED, positionMs = 62_000)
        assertFalse(playback.playWhenReady)
        assertTrue(playback.changedOnReceiver)
        later()
        report(ReceiverState.PLAYING, positionMs = 62_000)
        assertTrue(playback.playWhenReady)
        assertTrue(playback.changedOnReceiver)
        assertEquals(listOf(RemoteCommand.Pause), playback.setPlayWhenReady(false))
        assertFalse(playback.changedOnReceiver)
    }

    @Test
    fun aFailureStopsThePlayerUntilItIsPreparedAgain() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 45_000)
        assertTrue(report(ReceiverState.FAILED, positionMs = 45_000, error = "Unsupported").isEmpty())
        assertEquals(RemotePhase.IDLE, playback.phase)
        val first = playback.error
        assertEquals("Unsupported", first?.message)
        assertTrue(playback.playWhenReady)
        // Nothing more about that song counts.
        assertTrue(report(ReceiverState.PLAYING, positionMs = 50_000).isEmpty())
        assertEquals(RemotePhase.IDLE, playback.phase)
        val retry = playback.prepare().single() as RemoteCommand.Load<String>
        assertEquals("b", retry.entry.mediaId)
        assertEquals(45_000, retry.startMs)
        assertNull(playback.error)
        later()
        report(ReceiverState.FAILED, error = "Unsupported")
        assertNotNull(playback.error)
        assertNotSame(first, playback.error)
    }

    @Test
    fun aSongThatCouldNotBeSentFailsOnlyForItsOwnLoad() {
        val load = casting()
        playback.seekTo(2, 0)
        assertTrue(playback.onLoadFailed(load.ticket, "Offline").isEmpty())
        assertNull(playback.error)
        playback.onLoadFailed(playback.ticket, "Offline")
        assertEquals("Offline", playback.error?.message)
        assertEquals(RemotePhase.IDLE, playback.phase)
    }

    @Test
    fun stoppedOnTheReceiverHoldsThePlayerPausedWhereItWas() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 70_000)
        clock += 1_000
        report(ReceiverState.IDLE)
        assertEquals(RemotePhase.IDLE, playback.phase)
        assertFalse(playback.playWhenReady)
        assertEquals(71_000, playback.positionMs())
        assertTrue(playback.setPlayWhenReady(true).isEmpty())
        val load = playback.prepare().single() as RemoteCommand.Load<String>
        assertEquals(71_000, load.startMs)
        assertTrue(load.play)
    }

    @Test
    fun stopTakesTheSongOffAndForgetsItsReports() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 80_000)
        val old = playback.ticket
        assertEquals(listOf(RemoteCommand.Stop), playback.stop())
        assertEquals(RemotePhase.IDLE, playback.phase)
        val late = ReceiverStatus(link = LinkState.CONNECTED, state = ReceiverState.ENDED, reportedAt = clock)
        assertTrue(playback.onReceiver(old, late).isEmpty())
        assertTrue(playback.stop().isEmpty())
        val load = playback.prepare().single() as RemoteCommand.Load<String>
        assertEquals(80_000, load.startMs)
    }

    @Test
    fun removingSongsAroundTheCurrentOneLeavesItPlaying() {
        casting()
        assertTrue(playback.removeItems(0, 1).isEmpty())
        assertEquals(0, playback.index)
        assertEquals("b", playback.current?.mediaId)
        assertTrue(playback.removeItems(1, 2).isEmpty())
        assertEquals(listOf("b"), playback.entries.map { it.mediaId })
    }

    @Test
    fun removingTheCurrentSongPlaysTheOneAfterIt() {
        casting()
        val next = playback.removeItems(1, 2).single() as RemoteCommand.Load<String>
        assertEquals("c", next.entry.mediaId)
        assertEquals(1, playback.index)
    }

    @Test
    fun removingTheLastSongWhilePlayingItEndsTheList() {
        casting()
        playback.seekTo(2, 0)
        assertEquals(listOf(RemoteCommand.Stop), playback.removeItems(2, 3))
        assertEquals(RemotePhase.ENDED, playback.phase)
        assertEquals(1, playback.index)
        // Nothing is on the receiver any more, so emptying the list has nothing to stop.
        assertTrue(playback.setItems(emptyList(), -1, 0).isEmpty())
        assertEquals(-1, playback.index)
    }

    @Test
    fun removingTheLastSongOnRepeatAllGoesBackToTheFirst() {
        casting(repeat = Player.REPEAT_MODE_ALL)
        playback.seekTo(2, 0)
        val first = playback.removeItems(2, 3).single() as RemoteCommand.Load<String>
        assertEquals("a", first.entry.mediaId)
    }

    @Test
    fun addingAndMovingSongsFollowsTheCurrentOne() {
        casting()
        assertTrue(playback.addItems(0, songs("x", "y")).isEmpty())
        assertEquals(3, playback.index)
        assertTrue(playback.addItems(10, songs("z")).isEmpty())
        assertEquals(listOf("x", "y", "a", "b", "c", "z"), playback.entries.map { it.mediaId })
        assertTrue(playback.moveItems(3, 4, 0).isEmpty())
        assertEquals(0, playback.index)
        assertEquals("b", playback.current?.mediaId)
        assertTrue(playback.moveItems(1, 3, 4).isEmpty())
        assertEquals(listOf("b", "a", "c", "z", "x", "y"), playback.entries.map { it.mediaId })
        assertEquals(0, playback.index)
    }

    @Test
    fun songsAddedToAnEndedEmptyPlayerStartPlaying() {
        casting()
        playback.setItems(emptyList(), -1, 0)
        assertEquals(RemotePhase.ENDED, playback.phase)
        val load = playback.addItems(0, songs("n")).single() as RemoteCommand.Load<String>
        assertEquals("n", load.entry.mediaId)
        assertEquals(0, playback.index)
    }

    @Test
    fun theQueueBoardsSeamlessSwitchKeepsTheSongPlaying() {
        // QueueBoard.setCurrQueue, when the new queue's song is the one playing: the songs before
        // it are replaced, then the songs after it, and the song itself is never touched.
        casting()
        val key = playback.current?.key
        assertTrue(playback.replaceItems(0, 1, songs("p", "q", "r")).isEmpty())
        assertEquals(3, playback.index)
        assertTrue(playback.replaceItems(4, 5, songs("s", "t")).isEmpty())
        assertEquals(listOf("p", "q", "r", "b", "s", "t"), playback.entries.map { it.mediaId })
        assertEquals(key, playback.current?.key)
        assertEquals(RemotePhase.BUFFERING, playback.phase)
    }

    @Test
    fun replacingTheCurrentSongWithItselfKeepsItAndWithAnotherLoadsIt() {
        casting()
        val key = playback.current?.key
        assertTrue(playback.replaceItems(1, 2, songs("b")).isEmpty())
        assertEquals(key, playback.current?.key)
        val load = playback.replaceItems(1, 2, songs("d")).single() as RemoteCommand.Load<String>
        assertEquals("d", load.entry.mediaId)
        assertEquals(1, playback.index)
    }

    @Test
    fun aNewListWhilePlayingLoadsItsSongAndAnEmptyOneStops() {
        casting()
        val load = playback.setItems(songs("m", "n"), startIndex = 1, startMs = 5_000).single() as RemoteCommand.Load<String>
        assertEquals("n", load.entry.mediaId)
        assertEquals(5_000, load.startMs)
        assertEquals(listOf(RemoteCommand.Stop), playback.setItems(emptyList(), -1, 0))
        assertEquals(RemotePhase.ENDED, playback.phase)
        assertEquals(-1, playback.index)
    }

    @Test
    fun theReceiverFillsInALengthTheAppDidNotKnow() {
        playback.begin(listOf(RemoteSong("a", -1, "item-a")), 0, 0, play = true, repeat = Player.REPEAT_MODE_OFF)
        playback.prepare()
        val version = playback.version
        later()
        report(ReceiverState.PLAYING, positionMs = 1_000, durationMs = 180_000)
        assertEquals(180_000L, playback.current?.durationMs)
        assertTrue(playback.version > version)
        val again = playback.version
        report(ReceiverState.PLAYING, positionMs = 2_000, durationMs = 180_500)
        assertEquals(again, playback.version)
    }

    @Test
    fun bufferingAgainHoldsThePositionStill() {
        casting()
        later()
        report(ReceiverState.PLAYING, positionMs = 90_000)
        clock += 2_000
        report(ReceiverState.LOADING, positionMs = 92_000)
        assertEquals(RemotePhase.BUFFERING, playback.phase)
        clock += 5_000
        assertEquals(92_000, playback.positionMs())
    }

    @Test
    fun theSongAfterFollowsTheRepeatMode() {
        assertEquals(1, RemoteQueue.next(0, 3, Player.REPEAT_MODE_OFF))
        assertNull(RemoteQueue.next(2, 3, Player.REPEAT_MODE_OFF))
        assertEquals(0, RemoteQueue.next(2, 3, Player.REPEAT_MODE_ALL))
        assertEquals(2, RemoteQueue.next(2, 3, Player.REPEAT_MODE_ONE))
        assertNull(RemoteQueue.next(0, 0, Player.REPEAT_MODE_ALL))
        assertNull(RemoteQueue.next(5, 3, Player.REPEAT_MODE_OFF))
    }
}
