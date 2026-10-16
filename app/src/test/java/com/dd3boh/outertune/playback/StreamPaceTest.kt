/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A connection that brings a song in slower than it plays is given up, so the player asks again
 * on a fresh one.
 *
 * On a phone on 16 Oct 2026 two songs in half an hour came in at a third and two thirds of their
 * own pace, with the screen off: a second of silence, a pause while the player waited, a few
 * seconds of music, over and over. The phone, its Wi-Fi and the app's own network all fetched the
 * same servers at 20 to 30 MB/s in the same minutes. One of the two connections finally went quiet
 * for ten seconds, the read timed out, and the fresh connection the player asked on brought the
 * rest of the song in about a second. A trickle never times out, so the other song stuttered
 * until it was skipped.
 */
class StreamPaceTest {
    private val second = 1_000_000_000L

    private fun meter(need: Double = 20_000.0) = StreamPace.Meter(need)

    @Test
    fun `the pace a song needs is its length over its duration, from its address`() {
        val url = "https://rr3---sn-2o25g5-5v.googlevideo.com/videoplayback?expire=1&itag=251&clen=2600000&dur=130.000&lmt=1"
        assertEquals(20_000.0, StreamPace.bytesPerSecond(url)!!, 0.001)
    }

    @Test
    fun `an address without a length or a duration needs a pace it cannot be blamed for`() {
        assertNull(StreamPace.bytesPerSecond("https://rr1---sn-x.googlevideo.com/videoplayback?itag=251&dur=130.0"))
        assertNull(StreamPace.bytesPerSecond("https://rr1---sn-x.googlevideo.com/videoplayback?itag=251&clen=2600000"))
        assertNull(StreamPace.bytesPerSecond("https://rr1---sn-x.googlevideo.com/videoplayback?clen=abc&dur=130"))
        assertNull(StreamPace.bytesPerSecond("https://rr1---sn-x.googlevideo.com/videoplayback?clen=2600000&dur=0"))
        assertEquals(StreamPace.UNKNOWN_NEED, StreamPace.need("https://rr1---sn-x.googlevideo.com/videoplayback?itag=251"), 0.0)
    }

    @Test
    fun `only YouTube's stream servers are watched`() {
        assertTrue(StreamPace.isStream("https://rr3---sn-2o25g5-5v.googlevideo.com/videoplayback?itag=251"))
        assertFalse(StreamPace.isStream("file:///storage/emulated/0/Music/song.flac"))
        assertFalse(StreamPace.isStream("https://music.youtube.com/watch?v=abc"))
        assertFalse(StreamPace.isStream("sabr:abc"))
        assertFalse(StreamPace.isStream("https://evilgooglevideo.com.example/videoplayback"))
    }

    @Test
    fun `a stream far faster than the song is never cut`() {
        val m = meter()
        // 64 KB a read, a millisecond each: 64 MB/s.
        repeat(2_000) { assertFalse(m.slow(65_536, second / 1000)) }
    }

    @Test
    fun `a stream slower than the song is cut once two seconds of waiting have shown it`() {
        val m = meter(need = 20_000.0)
        // 6.5 KB/s, as the second song on the phone came in: a few hundred bytes every 50 ms.
        var waited = 0L
        var cut = false
        while (!cut && waited < 10 * second) {
            cut = m.slow(325, second / 20)
            waited += second / 20
        }
        assertTrue("never cut", cut)
        assertEquals("cut after two seconds of waiting", 2 * second, waited)
    }

    @Test
    fun `time the player spends not reading is not the connection's`() {
        val m = meter(need = 20_000.0)
        // Reads that return at once with what the connection already brought, as when the player
        // comes back after its buffer was full: however long it was away, nothing is waited for.
        repeat(1_000) { assertFalse(m.slow(16_384, 50_000L)) }
    }

    @Test
    fun `a stream at the song's own pace or a little above is left alone`() {
        val m = meter(need = 20_000.0)
        repeat(400) { assertFalse(m.slow(1_050, second / 20)) } // 21 KB/s, twenty seconds of waiting
    }

    @Test
    fun `a connection that stops for good is cut as slow too`() {
        val m = meter(need = 20_000.0)
        assertTrue(m.slow(0, 2 * second))
    }

    @Test
    fun `each window is judged on its own, so a slow start is not held against a stream that recovered`() {
        val m = meter(need = 20_000.0)
        assertFalse(m.slow(30_000, 1 * second)) // 30 KB in the first second
        assertFalse(m.slow(50_000, 1 * second)) // 80 KB in two seconds: 40 KB/s, fine
        assertFalse(m.slow(10_000, 1 * second))
        assertTrue(m.slow(10_000, 1 * second)) // the next two seconds: 10 KB/s
    }

    @Test
    fun `after three cuts in a row a slow network is left to play as it can`() {
        val cuts = StreamPace.Cuts()
        assertTrue(cuts.mayCut())
        cuts.cut(); cuts.cut()
        assertTrue(cuts.mayCut())
        cuts.cut()
        assertFalse("a fourth fresh connection would be as slow as the last three", cuts.mayCut())
    }

    @Test
    fun `a connection that kept pace earns the cuts back`() {
        val cuts = StreamPace.Cuts()
        cuts.cut(); cuts.cut(); cuts.cut()
        cuts.keptPace()
        assertTrue(cuts.mayCut())
    }

    @Test
    fun `a cut is the network's, so the song's cached part is not taken for a broken copy`() {
        // BadCachedCopy drops a song's cached part after three failed loads that are not the
        // network's, and a slow network can be cut three times in a row.
        assertTrue(HttpDataSource.HttpDataSourceException::class.java.isAssignableFrom(SlowStreamException::class.java))
    }

    @Test
    fun `the player's stream connections go through the guard`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val start = service.indexOf("private fun createCacheDataSource")
        assertTrue("the player's data source was not found", start >= 0)
        val body = service.substring(start, service.indexOf("\n    }\n", start))
        assertTrue("the player fetches streams without the pace guard", "PaceGuardDataSource.Factory(" in body)
        assertTrue("the guard no longer wraps the stream fetch", Regex("""PaceGuardDataSource\.Factory\((\s*//[^\n]*)*\s*OkHttpDataSource\.Factory\(""").containsMatchIn(body))
    }
}
