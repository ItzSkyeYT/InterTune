/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a sleep timer decides with no player in it: the times, the ends and the fade. */
class SleepTimerRulesTest {
    private val minute = SleepTimerRules.MINUTE_MS
    private val auto = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    private val repeat = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
    private val seek = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    private val changed = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

    // ---- the time is up

    @Test
    fun `at the time the music stops, or the song is let finish, or a timer the phone slept through is let go`() {
        assertEquals(SleepTimerRules.AtTimeUp.STOP, SleepTimerRules.atTimeUp(lateMs = 0, waitForSong = false))
        assertEquals(SleepTimerRules.AtTimeUp.STOP, SleepTimerRules.atTimeUp(lateMs = 800, waitForSong = false))
        assertEquals(SleepTimerRules.AtTimeUp.WAIT_FOR_SONG, SleepTimerRules.atTimeUp(lateMs = 0, waitForSong = true))
        // The job woke an hour late: the phone was asleep, and what plays was put on after the time.
        assertEquals(SleepTimerRules.AtTimeUp.LEAVE, SleepTimerRules.atTimeUp(lateMs = 60 * minute, waitForSong = false))
        assertEquals(SleepTimerRules.AtTimeUp.LEAVE, SleepTimerRules.atTimeUp(lateMs = 60 * minute, waitForSong = true))
        // Late by a few seconds is a busy main thread, not a sleeping phone.
        assertEquals(SleepTimerRules.AtTimeUp.STOP, SleepTimerRules.atTimeUp(SleepTimerRules.STALE_MS, waitForSong = false))
        assertEquals(SleepTimerRules.AtTimeUp.LEAVE, SleepTimerRules.atTimeUp(SleepTimerRules.STALE_MS + 1, waitForSong = false))
    }

    @Test
    fun `a timer of some minutes runs out that many minutes on`() {
        assertEquals(1_000L + 30 * minute, SleepTimerRules.triggerAfter(1_000L, 30))
        assertEquals(1_000L, SleepTimerRules.triggerAfter(1_000L, 0))
        assertEquals(1_000L, SleepTimerRules.triggerAfter(1_000L, -5))
    }

    // ---- the end time, with and without Let the song finish

    @Test
    fun `without Let the song finish the music stops at the time`() {
        val now = 10 * minute
        assertEquals(now + 30 * minute, SleepTimerRules.musicStopsAt(now, now + 30 * minute, false, songLeftMs = 2 * minute, followingMs = listOf(3 * minute)))
    }

    @Test
    fun `with it the music stops at the end of the song playing when the time comes`() {
        val now = 0L
        val trigger = 30 * minute
        // The song now playing runs past the time: it is the one.
        assertEquals(34 * minute, SleepTimerRules.musicStopsAt(now, trigger, true, songLeftMs = 34 * minute, followingMs = listOf(4 * minute)))
        // It ends first, and the next ones carry on: 2 + 3 + 4 + 5 = 14 minutes, then 20, 25, and one that spans the time.
        val following = listOf(3, 4, 5, 6, 7, 8).map { it * minute }
        // 2, 5, 9, 14, 20, 27, 35: the song that ends at minute 35 is the one playing at minute 30.
        assertEquals(35 * minute, SleepTimerRules.musicStopsAt(now, trigger, true, songLeftMs = 2 * minute, followingMs = following))
        // A song that ends exactly at the time is over by then, so the next one is let finish.
        assertEquals(40 * minute, SleepTimerRules.musicStopsAt(now, trigger, true, songLeftMs = 30 * minute, followingMs = listOf(10 * minute)))
    }

    @Test
    fun `with it the queue running out first stops the music there`() {
        assertEquals(9 * minute, SleepTimerRules.musicStopsAt(0, 30 * minute, true, songLeftMs = 2 * minute, followingMs = listOf(3 * minute, 4 * minute)))
    }

    @Test
    fun `with it a length that is not known leaves the end unknown`() {
        assertNull(SleepTimerRules.musicStopsAt(0, 30 * minute, true, songLeftMs = null, followingMs = emptyList()))
        // The unknown one would have to be counted: it is before the time.
        assertNull(SleepTimerRules.musicStopsAt(0, 30 * minute, true, songLeftMs = 2 * minute, followingMs = listOf(null, 40 * minute)))
        // Not reached: the song playing already covers the time.
        assertEquals(40 * minute, SleepTimerRules.musicStopsAt(0, 30 * minute, true, songLeftMs = 40 * minute, followingMs = listOf(null)))
    }

    @Test
    fun `the song playing is named only when it is the one playing at the time`() {
        assertTrue(SleepTimerRules.songAtTimeUp(nowMs = 0, triggerMs = 10 * minute, songLeftMs = 12 * minute))
        assertFalse(SleepTimerRules.songAtTimeUp(nowMs = 0, triggerMs = 10 * minute, songLeftMs = 3 * minute))
        assertFalse(SleepTimerRules.songAtTimeUp(nowMs = 0, triggerMs = 10 * minute, songLeftMs = 10 * minute))
    }

    // ---- extend

    @Test
    fun `a timer is moved by the minutes asked`() {
        val now = 100 * minute
        assertEquals(now + 35 * minute, SleepTimerRules.extended(now + 30 * minute, now, 5))
        assertEquals(now + 45 * minute, SleepTimerRules.extended(now + 30 * minute, now, 15))
        assertEquals(now + 60 * minute, SleepTimerRules.extended(now + 30 * minute, now, 30))
        // Moving nothing, or backwards, changes nothing.
        assertEquals(now + 30 * minute, SleepTimerRules.extended(now + 30 * minute, now, 0))
        assertEquals(now + 30 * minute, SleepTimerRules.extended(now + 30 * minute, now, -5))
    }

    @Test
    fun `a timer is never moved further than a day out`() {
        val now = 100 * minute
        val day = SleepTimerRules.MAX_AHEAD_MS
        assertEquals(now + day, SleepTimerRules.extended(now + day - 10 * minute, now, 30))
        assertEquals(now + day, SleepTimerRules.extended(now + day, now, 5))
    }

    // ---- lengths

    @Test
    fun `a faster song has less time left, a slower one more`() {
        assertEquals(60_000L, SleepTimerRules.wallLeft(60_000L, 1f))
        assertEquals(30_000L, SleepTimerRules.wallLeft(60_000L, 2f))
        assertEquals(120_000L, SleepTimerRules.wallLeft(60_000L, 0.5f))
        // A speed that makes no sense is no speed, and a position past the end is no time.
        assertEquals(60_000L, SleepTimerRules.wallLeft(60_000L, 0f))
        assertEquals(60_000L, SleepTimerRules.wallLeft(60_000L, Float.NaN))
        assertEquals(0L, SleepTimerRules.wallLeft(-5_000L, 1f))
    }

    @Test
    fun `the time to the end of an album or a queue is the song playing and those after it`() {
        assertEquals(10 * minute, SleepTimerRules.totalLeft(2 * minute, listOf(3 * minute, 5 * minute)))
        assertEquals(2 * minute, SleepTimerRules.totalLeft(2 * minute, emptyList()))
        // One length unknown and the total is unknown, never a total that looks exact and is short.
        assertNull(SleepTimerRules.totalLeft(2 * minute, listOf(3 * minute, null)))
        assertNull(SleepTimerRules.totalLeft(null, listOf(3 * minute)))
    }

    // ---- end of album

    @Test
    fun `two songs of one album are one album, and a song with none is its own`() {
        assertFalse(SleepTimerRules.albumOver("A", "A"))
        assertTrue(SleepTimerRules.albumOver("A", "B"))
        assertTrue(SleepTimerRules.albumOver("A", null))
        assertTrue(SleepTimerRules.albumOver(null, "A"))
        assertTrue(SleepTimerRules.albumOver(null, null))
        assertTrue(SleepTimerRules.albumOver("", ""))
    }

    @Test
    fun `the album ends when the queue moves on to another, by itself`() {
        assertFalse(SleepTimerRules.endsAt(SleepEnd.ALBUM, auto, false, "A", "A", false))
        assertTrue(SleepTimerRules.endsAt(SleepEnd.ALBUM, auto, false, "A", "B", false))
        assertTrue(SleepTimerRules.endsAt(SleepEnd.ALBUM, auto, false, "A", null, false))
    }

    @Test
    fun `a skip, a new queue or a seek never end an album, a queue or a song`() {
        for (end in SleepEnd.values()) for (reason in listOf(seek, changed)) {
            assertFalse("$end $reason", SleepTimerRules.endsAt(end, reason, false, "A", "B", false))
            assertFalse("$end $reason repeat all", SleepTimerRules.endsAt(end, reason, true, "A", "B", true))
        }
    }

    @Test
    fun `under repeat all the queue starting over ends the album and the queue`() {
        // One album, repeated: the album is over when the queue begins again.
        assertFalse(SleepTimerRules.endsAt(SleepEnd.ALBUM, auto, true, "A", "A", newIsFirst = false))
        assertTrue(SleepTimerRules.endsAt(SleepEnd.ALBUM, auto, true, "A", "A", newIsFirst = true))
        assertFalse(SleepTimerRules.endsAt(SleepEnd.QUEUE, auto, true, "A", "A", newIsFirst = false))
        assertTrue(SleepTimerRules.endsAt(SleepEnd.QUEUE, auto, true, "A", "A", newIsFirst = true))
        // A queue of one song under repeat all starts over with the song itself.
        assertTrue(SleepTimerRules.endsAt(SleepEnd.QUEUE, repeat, true, "A", "A", newIsFirst = false))
    }

    // ---- end of queue

    @Test
    fun `an album ending does not end the queue, which ends when it runs out`() {
        assertFalse(SleepTimerRules.endsAt(SleepEnd.QUEUE, auto, false, "A", "B", false))
        assertFalse(SleepTimerRules.endsAt(SleepEnd.QUEUE, auto, false, "A", null, false))
    }

    @Test
    fun `under repeat one only the end of the song ends`() {
        // The song starts over: that is the end of the song and nothing wider.
        assertTrue(SleepTimerRules.endsAt(SleepEnd.SONG, repeat, false, "A", "A", false))
        assertFalse(SleepTimerRules.endsAt(SleepEnd.ALBUM, repeat, false, "A", "A", false))
        assertFalse(SleepTimerRules.endsAt(SleepEnd.QUEUE, repeat, false, "A", "A", false))
    }

    // ---- the song that fades

    @Test
    fun `the song that fades is the last one the timer waits for`() {
        val off = Player.REPEAT_MODE_OFF
        val one = Player.REPEAT_MODE_ONE
        val all = Player.REPEAT_MODE_ALL
        // The end of the song: always the one playing.
        assertTrue(SleepTimerRules.finalSong(SleepEnd.SONG, off, hasNext = true, albumNow = "A", albumNext = "A"))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.SONG, one, hasNext = true, albumNow = "A", albumNext = "A"))
        // The album: when the next song is of another, or there is none.
        assertFalse(SleepTimerRules.finalSong(SleepEnd.ALBUM, off, hasNext = true, albumNow = "A", albumNext = "A"))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.ALBUM, off, hasNext = true, albumNow = "A", albumNext = "B"))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.ALBUM, off, hasNext = false, albumNow = "A", albumNext = null))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.ALBUM, all, hasNext = false, albumNow = "A", albumNext = null))
        // The queue: only the last one.
        assertFalse(SleepTimerRules.finalSong(SleepEnd.QUEUE, off, hasNext = true, albumNow = "A", albumNext = "B"))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.QUEUE, off, hasNext = false, albumNow = "A", albumNext = null))
        assertTrue(SleepTimerRules.finalSong(SleepEnd.QUEUE, all, hasNext = false, albumNow = "A", albumNext = null))
        // Under repeat one neither the album nor the queue ever ends, so nothing fades for them.
        assertFalse(SleepTimerRules.finalSong(SleepEnd.ALBUM, one, hasNext = false, albumNow = "A", albumNext = null))
        assertFalse(SleepTimerRules.finalSong(SleepEnd.QUEUE, one, hasNext = false, albumNow = "A", albumNext = null))
    }

    // ---- the fade

    @Test
    fun `a fade goes from full volume to nothing over the time it was given`() {
        assertEquals(1f, SleepTimerRules.fadeFactor(30_000, 30_000), 0f)
        assertEquals(0.5f, SleepTimerRules.fadeFactor(15_000, 30_000), 0.0001f)
        assertEquals(0f, SleepTimerRules.fadeFactor(0, 30_000), 0f)
        // Outside it, never above full volume or below nothing.
        assertEquals(1f, SleepTimerRules.fadeFactor(45_000, 30_000), 0f)
        assertEquals(0f, SleepTimerRules.fadeFactor(-100, 30_000), 0f)
        // No fade given, no change.
        assertEquals(1f, SleepTimerRules.fadeFactor(5_000, 0), 0f)
    }
}
