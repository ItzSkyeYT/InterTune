/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext

/**
 * The sleep timer's ends and its time: when each kind stops the music, what Let the song finish
 * does at the time, and what moving the stop does. The player is made up (a queue of songs with
 * albums, an index, a state), and the clock is set by hand; the timer's job never runs, since its
 * scope parks everything, so every look is the test's own.
 */
class SleepTimerEndsTest {
    private val minute = 60_000L
    private val auto = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    private val repeat = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
    private val seek = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK

    /** A scope that takes every coroutine and never runs one: the test calls look() itself. */
    private val parked = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    })

    @After
    fun stop() {
        parked.cancel()
    }

    /** A queue: each song is "<album>-<number>", an album of "none" is no album, with a length in seconds. */
    private class Queue(vararg songs: Pair<String, Int>) : Timeline() {
        val ids = songs.map { it.first }
        private val lengthsUs = songs.map { it.second * 1_000_000L }
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                Window.SINGLE_WINDOW_UID, MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0L, lengthsUs[windowIndex], windowIndex, windowIndex, 0L,
            )
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) = period
        override fun getIndexOfPeriod(uid: Any) = 0
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    private class Fake {
        var queue: Timeline = Timeline.EMPTY
        var index = 0
        var repeatMode = Player.REPEAT_MODE_OFF
        var playWhenReady = true
        var state = Player.STATE_READY
        var durationMs = C.TIME_UNSET
        var positionMs = 0L
        var speed = 1f
        var paused = 0

        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getRepeatMode" -> repeatMode
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> state
                "isPlaying" -> playWhenReady && state == Player.STATE_READY
                "getCurrentTimeline" -> queue
                "getCurrentMediaItemIndex" -> index
                "getCurrentMediaItem" ->
                    if (queue.isEmpty) null else queue.getWindow(index, Timeline.Window()).mediaItem
                "getDuration" -> durationMs
                "getCurrentPosition" -> positionMs
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "pause" -> {
                    paused++
                    null
                }
                else -> error("unexpected call to ${method.name}")
            }
        } as Player
    }

    private val fake = Fake()
    private var now = 1_000_000L
    private var finished = 0

    private fun song(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun timer() = SleepTimer(
        parked, fake.player, clock = { now },
        albumOf = { item -> item?.mediaId?.substringBefore('-')?.takeIf { it != "none" } },
    ).apply {
        fadeEnabled = false
        onFinish = { finished++ }
    }

    private fun queueOf(vararg songs: Pair<String, Int>) {
        fake.queue = Queue(*songs)
    }

    // ---- the end of the album

    @Test
    fun `a song of the same album carries on, the first of another ends the album`() {
        queueOf("A-1" to 200, "A-2" to 200, "B-1" to 200)
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        assertEquals(SleepEnd.ALBUM, timer.endOf)
        timer.onMediaItemTransition(song("A-2"), auto)
        assertEquals(0, finished)
        assertTrue(timer.isActive)
        timer.onMediaItemTransition(song("B-1"), auto)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `a skip into another album carries the timer over to that album`() {
        queueOf("A-1" to 200, "A-2" to 200, "B-1" to 200, "B-2" to 200, "C-1" to 200)
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        // Next, pressed on A-2's turn: the timer follows to B, and ends with B.
        timer.onMediaItemTransition(song("B-1"), seek)
        assertEquals(0, finished)
        timer.onMediaItemTransition(song("B-2"), auto)
        assertEquals(0, finished)
        timer.onMediaItemTransition(song("C-1"), auto)
        assertEquals(1, finished)
    }

    @Test
    fun `a song with no album is an album of its own`() {
        queueOf("none-1" to 200, "none-2" to 200)
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        timer.onMediaItemTransition(song("none-2"), auto)
        assertEquals(1, finished)
    }

    @Test
    fun `repeat one never ends an album, and the queue running out always does`() {
        queueOf("A-1" to 200, "A-2" to 200)
        fake.repeatMode = Player.REPEAT_MODE_ONE
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        timer.onMediaItemTransition(song("A-1"), repeat)
        assertEquals(0, finished)
        assertTrue(timer.isActive)
        timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `under repeat all an album that is the whole queue ends when the queue starts over`() {
        queueOf("A-1" to 200, "A-2" to 200, "A-3" to 200)
        fake.repeatMode = Player.REPEAT_MODE_ALL
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        fake.index = 1
        timer.onMediaItemTransition(song("A-2"), auto)
        fake.index = 2
        timer.onMediaItemTransition(song("A-3"), auto)
        assertEquals(0, finished)
        fake.index = 0
        timer.onMediaItemTransition(song("A-1"), auto)
        assertEquals(1, finished)
    }

    // ---- the end of the queue

    @Test
    fun `a change of album does not end the queue, running out does, and cleanly`() {
        queueOf("A-1" to 200, "B-1" to 200)
        val timer = timer().apply { start(SleepEnd.QUEUE) }
        timer.onMediaItemTransition(song("B-1"), auto)
        assertEquals(0, finished)
        assertTrue(timer.isActive)
        timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, finished)
        // Nothing is left armed for the next queue that plays, and the volume is whole.
        assertFalse(timer.isActive)
        assertNull(timer.endOf)
        assertEquals(1f, timer.fadeFactor.value, 0f)
        // And the end is told once: a second ENDED from the player ends nothing.
        timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, finished)
    }

    @Test
    fun `under repeat all only the queue starting over ends it`() {
        queueOf("A-1" to 200, "B-1" to 200, "C-1" to 200)
        fake.repeatMode = Player.REPEAT_MODE_ALL
        val timer = timer().apply { start(SleepEnd.QUEUE) }
        fake.index = 1
        timer.onMediaItemTransition(song("B-1"), auto)
        fake.index = 2
        timer.onMediaItemTransition(song("C-1"), auto)
        assertEquals(0, finished)
        fake.index = 0
        timer.onMediaItemTransition(song("A-1"), auto)
        assertEquals(1, finished)
    }

    @Test
    fun `under repeat one the queue is never over, and a skip carries the timer on`() {
        queueOf("A-1" to 200, "A-2" to 200)
        fake.repeatMode = Player.REPEAT_MODE_ONE
        val timer = timer().apply { start(SleepEnd.QUEUE) }
        timer.onMediaItemTransition(song("A-1"), repeat)
        timer.onMediaItemTransition(song("A-2"), seek)
        assertEquals(0, finished)
        assertEquals(SleepEnd.QUEUE, timer.endOf)
    }

    // ---- the time, and Let the song finish

    @Test
    fun `at the time the music stops`() {
        val timer = timer().apply { start(30) }
        assertEquals(now + 30 * minute, timer.triggerTime)
        assertNull(timer.endOf)
        now += 29 * minute
        assertNotNull(timer.look(now))
        assertEquals(0, finished)
        now += minute
        assertNull(timer.look(now))
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `with Let the song finish the song playing at the time plays out, and stops there`() {
        val timer = timer().apply {
            letSongFinish = true
            start(30)
        }
        now += 30 * minute
        timer.look(now)
        // The time is up and nothing has stopped: the timer is now the one set to the end of the song.
        assertEquals(0, finished)
        assertTrue(timer.isActive)
        assertEquals(SleepEnd.SONG, timer.endOf)
        assertEquals(-1L, timer.triggerTime)
        // A skip carries it to the next song, as for any end of the song.
        timer.onMediaItemTransition(song("A-2"), seek)
        assertEquals(0, finished)
        timer.onMediaItemTransition(song("A-3"), auto)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `with Let the song finish the last song of the queue ends the timer as the queue ends`() {
        val timer = timer().apply {
            letSongFinish = true
            start(1)
        }
        now += minute
        timer.look(now)
        timer.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `with Let the song finish a pause at the time leaves nothing to wait for`() {
        fake.playWhenReady = false
        val timer = timer().apply {
            letSongFinish = true
            start(30)
        }
        now += 30 * minute
        timer.look(now)
        assertEquals(1, finished)
        assertFalse(timer.isActive)
    }

    @Test
    fun `a timer the phone slept through stops nothing`() {
        val timer = timer().apply { start(30) }
        // The job woke at the next song change, an hour late: whatever plays now was put on after it.
        now += 90 * minute
        assertNull(timer.look(now))
        assertEquals(0, finished)
        assertEquals(0, fake.paused)
        assertFalse(timer.isActive)
    }

    @Test
    fun `a switch flipped on a running timer counts at the time`() {
        val timer = timer().apply { start(30) }
        timer.letSongFinish = true
        now += 30 * minute
        timer.look(now)
        assertEquals(0, finished)
        assertEquals(SleepEnd.SONG, timer.endOf)
    }

    // ---- the fade

    @Test
    fun `a fade starts where the time minus the fade is, and runs down to the time`() {
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            start(10)
        }
        now += 10 * minute - 60_000L
        // A minute before: nothing yet, and the next look is at the start of the fade.
        assertEquals(30_000L, timer.look(now))
        assertEquals(1f, timer.fadeFactor.value, 0f)
        now += 30_000L
        timer.look(now)
        assertEquals(1f, timer.fadeFactor.value, 0.001f)
        now += 15_000L
        timer.look(now)
        assertEquals(0.5f, timer.fadeFactor.value, 0.001f)
        now += 15_000L
        assertNull(timer.look(now))
        assertEquals(1, finished)
        assertEquals(1f, timer.fadeFactor.value, 0f)
    }

    @Test
    fun `a timer set with less left than the fade fades from the volume it has, not from a third`() {
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            start(1)
        }
        // A minute from now, so the fade is the last 30 seconds. Look when 10 seconds are left, as
        // if the timer had been extended into the fade and this were the first look there.
        now += minute - 10_000L
        timer.look(now)
        assertEquals(1f, timer.fadeFactor.value, 0.001f)
        now += 5_000L
        timer.look(now)
        assertEquals(0.5f, timer.fadeFactor.value, 0.001f)
    }

    @Test
    fun `with Let the song finish the fade is the song's, not the time's`() {
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            letSongFinish = true
            start(10)
        }
        now += 10 * minute - 10_000L
        timer.look(now)
        assertEquals(1f, timer.fadeFactor.value, 0f)
    }

    @Test
    fun `the fade of an end is the last seconds of its last song only`() {
        queueOf("A-1" to 200, "A-2" to 200, "B-1" to 200)
        fake.durationMs = 200_000L
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            start(SleepEnd.ALBUM)
        }
        // A-1 is not the album's last song: its end does not fade.
        fake.index = 0
        fake.positionMs = 190_000L
        assertEquals(1_000L, timer.look(now))
        assertEquals(1f, timer.fadeFactor.value, 0f)
        // A-2 is: from 30 seconds before its end, the volume goes down.
        fake.index = 1
        fake.positionMs = 170_000L
        timer.look(now)
        assertEquals(1f, timer.fadeFactor.value, 0.001f)
        fake.positionMs = 185_000L
        timer.look(now)
        assertEquals(0.5f, timer.fadeFactor.value, 0.001f)
    }

    @Test
    fun `the fade is counted in real time, at the speed the song plays`() {
        fake.durationMs = 200_000L
        fake.speed = 2f
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            start(SleepEnd.SONG)
        }
        // 40 s of music left at twice the speed is 20 s: inside the 30 s fade already.
        fake.positionMs = 160_000L
        timer.look(now)
        assertEquals(1f, timer.fadeFactor.value, 0.001f)
        fake.positionMs = 180_000L
        timer.look(now)
        assertEquals(0.5f, timer.fadeFactor.value, 0.001f)
    }

    // ---- moving the stop

    @Test
    fun `plus minutes moves the time, and a fade in progress starts over from full volume`() {
        val timer = timer().apply {
            fadeEnabled = true
            fadeDurationMs = 30_000L
            start(10)
        }
        now += 10 * minute - 30_000L
        timer.look(now)
        now += 15_000L
        timer.look(now)
        assertEquals(0.5f, timer.fadeFactor.value, 0.001f)
        timer.extend(5)
        assertEquals(now + 15_000L + 5 * minute, timer.triggerTime)
        assertEquals(1f, timer.fadeFactor.value, 0f)
        assertTrue(timer.isActive)
    }

    @Test
    fun `plus minutes on an end of the song makes it a timer that stops that long after the song`() {
        queueOf("A-1" to 200)
        fake.durationMs = 200_000L
        fake.positionMs = 140_000L
        val timer = timer().apply { start(SleepEnd.SONG) }
        timer.extend(15)
        assertNull(timer.endOf)
        // 60 s of the song, then 15 minutes.
        assertEquals(now + 60_000L + 15 * minute, timer.triggerTime)
        assertTrue(timer.isActive)
    }

    @Test
    fun `plus minutes on the end of an album counts the songs to the end of it`() {
        queueOf("A-1" to 200, "A-2" to 300, "B-1" to 400)
        fake.durationMs = 200_000L
        fake.positionMs = 100_000L
        val timer = timer().apply { start(SleepEnd.ALBUM) }
        timer.extend(5)
        // 100 s left of A-1, 300 s of A-2; B-1 is another album. Then 5 minutes.
        assertEquals(now + 400_000L + 5 * minute, timer.triggerTime)
    }

    @Test
    fun `plus minutes on a timer that is not running starts nothing`() {
        val timer = timer()
        timer.extend(5)
        assertFalse(timer.isActive)
        assertEquals(-1L, timer.triggerTime)
    }

    @Test
    fun `Stop ends the timer and restores the volume without pausing anything`() {
        val timer = timer().apply {
            fadeEnabled = true
            start(10)
        }
        timer.clear()
        assertFalse(timer.isActive)
        assertEquals(0, finished)
        assertEquals(1f, timer.fadeFactor.value, 0f)
    }

    // ---- what the sheet and the notification say

    @Test
    fun `a timer with a time says the time, and that it waits for the song when it does`() {
        val timer = timer().apply { start(30) }
        val plain = timer.status(now + 10 * minute)!!
        assertNull(plain.end)
        assertEquals(20 * minute, plain.leftMs)
        assertEquals(now + 30 * minute, plain.stopAtMs)
        assertFalse(plain.afterSong)
        timer.letSongFinish = true
        assertTrue(timer.status(now)!!.afterSong)
        timer.clear()
        assertNull(timer.status(now))
    }

    @Test
    fun `the end of an album or a queue says how long to it, or nothing when a length is not known`() {
        queueOf("A-1" to 200, "A-2" to 300, "B-1" to 400)
        fake.durationMs = 200_000L
        fake.positionMs = 100_000L
        val album = timer().apply { start(SleepEnd.ALBUM) }
        assertEquals(400_000L, album.status(now)!!.leftMs)
        assertEquals(now + 400_000L, album.status(now)!!.stopAtMs)
        val queue = timer().apply { start(SleepEnd.QUEUE) }
        assertEquals(800_000L, queue.status(now)!!.leftMs)
        // The song playing has no length yet: nothing is said rather than a wrong time.
        fake.durationMs = C.TIME_UNSET
        assertNull(queue.status(now)!!.leftMs)
        assertNull(queue.status(now)!!.stopAtMs)
    }

    @Test
    fun `the song is named after the time only when it is the one playing then`() {
        fake.durationMs = 200_000L
        fake.positionMs = 100_000L
        val timer = timer().apply { start(30) }
        // 100 s of the song left and half an hour to the time: a later song is the one.
        assertFalse(timer.songOutlastsTime(now))
        // Ten seconds to the time: this one plays through it.
        assertTrue(timer.songOutlastsTime(now + 30 * minute - 10_000L))
    }

    @Test
    fun `the notification is told of a change in the song, a pause or a setting, only while a timer runs`() {
        val timer = timer()
        val idle = timer.changes
        timer.onMediaItemTransition(song("A-1"), auto)
        timer.onIsPlayingChanged(false)
        assertEquals(idle, timer.changes)
        timer.start(30)
        val armed = timer.changes
        timer.onMediaItemTransition(song("A-1"), auto)
        assertTrue(timer.changes > armed)
        val after = timer.changes
        timer.letSongFinish = true
        assertTrue(timer.changes > after)
    }
}
