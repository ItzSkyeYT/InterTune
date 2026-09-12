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
    fun `a widget too small for a list shows none of it, and a tall one is still capped`() {
        assertEquals(0, WidgetLayout.pickCount(110, 6))
        assertEquals(0, WidgetLayout.pickCount(160, 0))
        assertTrue(WidgetLayout.pickCount(240, 6) > 0)
        assertEquals(WidgetLayout.MAX_PICKS, WidgetLayout.pickCount(2000, 20))
        assertEquals(2, WidgetLayout.pickCount(240, 2))
    }

    @Test
    fun `the row grows with the height, never shrinking as it gets taller`() {
        var last = 0
        for (h in 60..600 step 4) {
            val n = WidgetLayout.pickCount(h, WidgetLayout.MAX_PICKS)
            assertTrue("$h dp went backwards", n >= last)
            last = n
        }
    }

    @Test
    fun `a narrow widget keeps play and loses the skips, and a flat one loses the artwork`() {
        assertFalse(WidgetLayout.showsSkipButtons(140))
        assertTrue(WidgetLayout.showsSkipButtons(250))
        assertFalse(WidgetLayout.showsArtwork(60))
        assertTrue(WidgetLayout.showsArtwork(110))
    }
}
