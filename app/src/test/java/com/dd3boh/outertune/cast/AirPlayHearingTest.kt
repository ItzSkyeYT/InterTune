/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import com.dd3boh.outertune.cast.AirPlayHearing.News
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the speaker is heard to play, from what was sent where. The stream looks about every 20 ms;
 * a song stretch shorter than that can fall between two looks (8 Nov 2026: a seek into a song's last
 * 20 ms, with the look a little late, and the song never ended).
 */
class AirPlayHearingTest {

    private val packet = 352

    /** [count] packets from [frame], all of song [load] from its [songFrame], the last one [lastFrames] long. */
    private fun AirPlayHearing.song(frame: Long, load: Int, songFrame: Long, count: Int, lastFrames: Int = packet): Long {
        var at = frame
        var from = songFrame
        repeat(count) { i ->
            val frames = if (i == count - 1) lastFrames else packet
            record(at, packet, load, frames, from)
            at += packet
            from += frames
        }
        return at
    }

    private fun AirPlayHearing.silence(frame: Long, count: Int): Long {
        var at = frame
        repeat(count) {
            record(at, packet, 0, 0, 0)
            at += packet
        }
        return at
    }

    @Test
    fun `a song's last few milliseconds passed over between two looks are still heard, then the silence after`() {
        val hearing = AirPlayHearing()
        // 882 frames, 20 ms: two whole packets and 178 frames of a third, then silence.
        val end = hearing.song(0, load = 1, songFrame = 440_118, count = 3, lastFrames = 178)
        hearing.silence(end, 20)
        assertEquals("before the speaker gets there", emptyList<News>(), hearing.listen(-1))
        // The next look comes after the song is over: it was played all the same.
        assertEquals(
            listOf(News(1, 440_118, true, 0), News(1, -1, false, 882)),
            hearing.listen(1_500),
        )
        assertEquals("told once", emptyList<News>(), hearing.listen(2_000))
        assertEquals("where it stopped", 1 to 441_000L, hearing.position(2_000))
    }

    @Test
    fun `a song heard as it starts is told once, and its end once`() {
        val hearing = AirPlayHearing()
        val end = hearing.song(0, load = 1, songFrame = 0, count = 100)
        hearing.silence(end, 10)
        assertEquals(listOf(News(1, 0, true, 0)), hearing.listen(10))
        assertEquals(emptyList<News>(), hearing.listen(20_000))
        assertEquals(listOf(News(1, -1, false, end)), hearing.listen(end + 10))
        assertEquals(emptyList<News>(), hearing.listen(end + 400))
    }

    @Test
    fun `a song already heard is not told again when the speaker moves past it into the next`() {
        val hearing = AirPlayHearing()
        val two = hearing.song(0, load = 1, songFrame = 0, count = 10)
        val end = hearing.song(two, load = 2, songFrame = 0, count = 10)
        hearing.silence(end, 4)
        assertEquals(listOf(News(1, 0, true, 0)), hearing.listen(100))
        assertEquals(listOf(News(2, 0, true, two)), hearing.listen(two + 100))
    }

    @Test
    fun `a short next song passed over between looks is heard, then the one after it`() {
        val hearing = AirPlayHearing()
        val two = hearing.song(0, load = 1, songFrame = 0, count = 10)
        val three = hearing.song(two, load = 2, songFrame = 0, count = 1, lastFrames = 200)
        hearing.silence(three, 1)
        val end = hearing.song(three + packet, load = 3, songFrame = 0, count = 10)
        hearing.silence(end, 4)
        assertEquals(listOf(News(1, 0, true, 0)), hearing.listen(100))
        val news = hearing.listen(three + packet + 50)
        assertEquals(listOf(News(2, 0, true, two), News(3, 0, true, three + packet)), news)
    }

    @Test
    fun `a new stretch starts with nothing heard`() {
        val hearing = AirPlayHearing()
        hearing.song(0, load = 1, songFrame = 0, count = 4)
        hearing.listen(10)
        hearing.clear()
        assertEquals(null, hearing.position(10))
        hearing.song(5_000, load = 1, songFrame = 9_000, count = 4)
        assertTrue("heard again after the seek", hearing.listen(5_010).single().song)
    }
}
