/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private val song = WidgetSong("abc123", "Rosanna", "Toto", "/data/art/1.png", "https://i.ytimg.com/x.jpg", 302)

    private fun snapshot() = WidgetSnapshot(
        nowPlaying = song,
        isPlaying = true,
        picks = listOf(
            song.copy(id = "p1", title = "Africa"),
            song.copy(id = "p2", title = "Hold the Line", artPath = null, thumbnailUrl = null),
        ),
        forgotten = listOf(song.copy(id = "f1", title = "Georgy Porgy")),
        keepListening = listOf(song.copy(id = "k1", title = "I'll Be Over You")),
        updatedAt = 1_789_000_000_000L,
    )

    @Test
    fun `a snapshot survives the trip to disk and back`() {
        val back = WidgetCodec.decode(WidgetCodec.encode(snapshot()))
        assertEquals(snapshot(), back)
    }

    @Test
    fun `a title holding the separators comes back whole`() {
        val awkward = song.copy(title = "one\ttwo\nthree\\four", artist = "a\tb")
        val back = WidgetCodec.decode(WidgetCodec.encode(WidgetSnapshot(nowPlaying = awkward)))
        assertEquals(awkward.title, back.nowPlaying?.title)
        assertEquals(awkward.artist, back.nowPlaying?.artist)
        assertEquals(1, back.picks.size + if (back.nowPlaying != null) 1 else 0)
    }

    @Test
    fun `nothing playing and no picks is a snapshot too`() {
        val back = WidgetCodec.decode(WidgetCodec.encode(WidgetSnapshot()))
        assertNull(back.nowPlaying)
        assertTrue(back.picks.isEmpty())
        assertFalse(back.isPlaying)
    }

    @Test
    fun `an empty, damaged or newer file reads as nothing rather than throwing`() {
        assertEquals(WidgetSnapshot(), WidgetCodec.decode(null))
        assertEquals(WidgetSnapshot(), WidgetCodec.decode(""))
        assertEquals(WidgetSnapshot(), WidgetCodec.decode("2\nnow\tabc\tRosanna"))
        assertNull(WidgetCodec.decode("1\nnow\t\tRosanna\tToto").nowPlaying)
        val short = WidgetCodec.decode("1\nnow\tabc\tRosanna")
        assertEquals("abc", short.nowPlaying?.id)
        assertEquals("", short.nowPlaying?.artist)
        assertNull(short.nowPlaying?.artPath)
    }

    @Test
    fun `each list is kept apart and read back by name`() {
        val back = WidgetCodec.decode(WidgetCodec.encode(snapshot()))
        assertEquals(listOf("p1", "p2"), back.list(WidgetList.QUICK_PICKS).map { it.id })
        assertEquals(listOf("f1"), back.list(WidgetList.FORGOTTEN_FAVOURITES).map { it.id })
        assertEquals(listOf("k1"), back.list(WidgetList.KEEP_LISTENING).map { it.id })
        assertEquals(5, back.songs().size)
        val swapped = back.withList(WidgetList.FORGOTTEN_FAVOURITES, emptyList())
        assertTrue(swapped.forgotten.isEmpty())
        assertEquals(2, swapped.picks.size)
    }

    @Test
    fun `a file from a version that knew fewer lists still reads`() {
        val old = "1\nat\t7\t1\nnow\tabc\tRosanna\tToto\t\t\t302\t0\npick\tp1\tAfrica\tToto\t\t\t295\t0\n"
        val back = WidgetCodec.decode(old)
        assertEquals("Rosanna", back.nowPlaying?.title)
        assertEquals(1, back.picks.size)
        assertTrue(back.forgotten.isEmpty())
    }

    @Test
    fun `the now playing cover comes from its own file, not recent's smaller copy of the same song`() {
        val now = song.copy(artPath = "/data/art/abc123_192.png")
        val recentCopy = now.copy(artPath = "/data/art/abc123_96.png")
        val snap = WidgetSnapshot(nowPlaying = now, recent = listOf(recentCopy))
        // decode = identity, so the "bitmaps" are just the paths themselves.
        val (art, nowCover) = coversFor(snap, old = emptyMap(), decode = { it })
        // Recent collides on id with now playing and comes after it in songs(), so the id-keyed
        // map keeps recent's smaller path.
        assertEquals(recentCopy.artPath, art[now.id])
        // The now playing cover is read from its own path whatever the map holds for that id.
        assertEquals(now.artPath, nowCover)
    }

    @Test
    fun `a flat widget keeps a small cover until it is too flat for one`() {
        assertTrue(WidgetLayout.plan(400, 60, WidgetSettings(), 6).cover > 0)
        assertEquals(0, WidgetLayout.plan(400, 40, WidgetSettings(), 6).cover)
        assertEquals(0, WidgetLayout.plan(400, 60, WidgetSettings(showArtwork = false), 6).cover)
    }
}
