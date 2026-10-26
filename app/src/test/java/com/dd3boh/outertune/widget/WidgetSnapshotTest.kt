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
    fun `the heart of what is playing survives the trip to disk, and a file from before it reads as not liked`() {
        val liked = snapshot().copy(isLiked = true)
        assertEquals(liked, WidgetCodec.decode(WidgetCodec.encode(liked)))
        // Written before the heart: three fields on the first line, not four.
        val old = WidgetCodec.decode("1\nat\t7\t1\nnow\tabc\tRosanna\tToto\t\t\t302\t0\n")
        assertEquals("Rosanna", old.nowPlaying?.title)
        assertTrue(old.isPlaying)
        assertFalse(old.isLiked)
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
    fun `recent stays put while listen history is paused, and records again once it is not`() {
        val played = song.copy(id = "new1", title = "New Song")
        val old = listOf(song.copy(id = "old1"))
        assertEquals(old, nextRecent(old, played, paused = true, maxPicks = 6))
        assertEquals(listOf(played) + old, nextRecent(old, played, paused = false, maxPicks = 6))
    }

    @Test
    fun `the now playing song's big cover counts as in use even with no artPath field of its own`() {
        val now = song.copy(id = "now1", artPath = "/art/now1_192.png")
        val snap = WidgetSnapshot(nowPlaying = now, recent = listOf(now.copy(artPath = "/art/now1_96.png")))
        val inUse = snap.artPathsInUse { id -> "/art/${id}_320.png" }
        assertTrue("/art/now1_320.png" in inUse)
        assertTrue("/art/now1_192.png" in inUse)
    }

    @Test
    fun `nothing playing means no big cover to protect`() {
        assertTrue(WidgetSnapshot().artPathsInUse { "/never/$it" }.isEmpty())
    }

    @Test
    fun `missing artwork is tried once per song, and every time the song changes`() {
        // A new song with artwork missing always gets its try.
        assertTrue(shouldFetchArt(same = false, missing = true, songId = "a", lastAttempt = null))
        assertTrue(shouldFetchArt(same = false, missing = true, songId = "a", lastAttempt = "z"))
        // A song played again after its last try failed still gets a try.
        assertTrue(shouldFetchArt(same = false, missing = true, songId = "a", lastAttempt = "a"))
        // Nothing missing: nothing to fetch, whatever was last tried and whichever song it is.
        assertFalse(shouldFetchArt(same = true, missing = false, songId = "a", lastAttempt = null))
        assertFalse(shouldFetchArt(same = false, missing = false, songId = "a", lastAttempt = "z"))
        // The same song, artwork missing, not tried yet this process: worth a try.
        assertTrue(shouldFetchArt(same = true, missing = true, songId = "a", lastAttempt = null))
        assertTrue(shouldFetchArt(same = true, missing = true, songId = "a", lastAttempt = "b"))
        // The same song, still missing, already tried for this exact id: do not retry on every
        // play and pause.
        assertFalse(shouldFetchArt(same = true, missing = true, songId = "a", lastAttempt = "a"))
    }

    @Test
    fun `a flat widget keeps a small cover until it is too flat for one`() {
        assertTrue(WidgetLayout.plan(400, 60, WidgetSettings(), 6).coverW > 0)
        assertEquals(32, WidgetLayout.plan(400, 40, WidgetSettings(), 6).coverW)
        assertEquals(0, WidgetLayout.plan(400, 36, WidgetSettings(), 6).coverW)
        assertEquals(0, WidgetLayout.plan(400, 60, WidgetSettings(showArtwork = false), 6).coverW)
    }

    @Test
    fun `Up next and the shuffle survive the trip to disk, and a file from before them reads without`() {
        val queued = snapshot().copy(isShuffled = true, upNext = listOf(song.copy(id = "n1", title = "Next"), song.copy(id = "n2", title = "After")))
        assertEquals(queued, WidgetCodec.decode(WidgetCodec.encode(queued)))
        assertEquals(7, queued.songs().size)
        // Written before them: four fields on the first line, and no line of the kind "next".
        val old = WidgetCodec.decode("1\nat\t7\t1\t1\nnow\tabc\tRosanna\tToto\t\t\t302\t0\n")
        assertTrue(old.isLiked)
        assertFalse(old.isShuffled)
        assertTrue(old.upNext.isEmpty())
        // Nothing playing has nothing up next.
        assertTrue(WidgetCodec.decode(WidgetCodec.encode(WidgetSnapshot(upNext = queued.upNext))).upNext.isEmpty())
    }

    @Test
    fun `Up next's covers count as in use`() {
        val snap = WidgetSnapshot(nowPlaying = song, upNext = listOf(song.copy(id = "n1", artPath = "/art/n1_128c.png")))
        assertTrue("/art/n1_128c.png" in snap.artPathsInUse { "/art/${it}_320c.png" })
    }
}
