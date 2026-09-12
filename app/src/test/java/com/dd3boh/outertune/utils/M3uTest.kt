/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The .m3u text is pinned line by line: the importer and other players read it, so it must not drift. */
class M3uTest {

    private fun local(id: String, title: String, duration: Int, path: String, vararg artists: String) = Song(
        song = SongEntity(id = id, title = title, duration = duration, isLocal = true, localPath = path),
        artists = artists.map { ArtistEntity(id = "LA$it", name = it, isLocal = true) },
    )

    private fun remote(id: String, title: String, duration: Int, vararg artists: String) = Song(
        song = SongEntity(id = id, title = title, duration = duration, localPath = null),
        artists = artists.map { ArtistEntity(id = "UC$it", name = it) },
    )

    @Test
    fun `an empty playlist is only the header`() {
        assertEquals("#EXTM3U\n", M3u.playlist(emptyList()))
    }

    @Test
    fun `a local song is its id and path after its EXTINF line`() {
        val text = M3u.playlist(listOf(local("LSabcdefgh", "Song", 200, "/storage/emulated/0/Music/song.mp3", "Artist")))
        assertEquals("#EXTM3U\n#EXTINF:200,Artist - Song\nLSabcdefgh, /storage/emulated/0/Music/song.mp3\n", text)
    }

    @Test
    fun `a YouTube song is a watch link`() {
        val text = M3u.playlist(listOf(remote("dQw4w9WgXcQ", "Song", 180, "Artist")))
        assertEquals("#EXTM3U\n#EXTINF:180,Artist - Song\nhttps://youtube.com/watch?v=dQw4w9WgXcQ\n", text)
    }

    @Test
    fun `two artists are joined by a semicolon on the EXTINF line`() {
        val text = M3u.playlist(listOf(remote("abc", "Duet", 240, "One", "Two")))
        assertEquals("#EXTINF:240,One;Two - Duet", text.lines()[1])
    }

    @Test
    fun `songs keep their order, two lines each, after the header`() {
        val text = M3u.playlist(
            listOf(
                remote("first", "First", 1, "A"),
                local("LSsecond", "Second", 2, "/music/second.flac", "B"),
                remote("third", "Third", 3, "C"),
            )
        )
        assertEquals(
            listOf(
                "#EXTM3U",
                "#EXTINF:1,A - First",
                "https://youtube.com/watch?v=first",
                "#EXTINF:2,B - Second",
                "LSsecond, /music/second.flac",
                "#EXTINF:3,C - Third",
                "https://youtube.com/watch?v=third",
                "",
            ),
            text.lines()
        )
    }

    @Test
    fun `a plain name only gains the extension`() {
        assertEquals("Road trip.m3u", M3u.fileName("Road trip", emptySet()))
    }

    @Test
    fun `characters no file name may hold become spaces`() {
        assertEquals("a b c.m3u", M3u.fileName("a/b:c", emptySet()))
        assertEquals("what is this.m3u", M3u.fileName("what*is?this", emptySet()))
        assertEquals("tab here.m3u", M3u.fileName("tab\there", emptySet()))
        assertEquals("quote pipe angle.m3u", M3u.fileName("quote\"pipe|angle<>", emptySet()))
    }

    @Test
    fun `spaces are trimmed and runs collapsed`() {
        assertEquals("a b.m3u", M3u.fileName("  a   /  b  ", emptySet()))
    }

    @Test
    fun `a name with nothing left in it falls back to Playlist`() {
        assertEquals("Playlist.m3u", M3u.fileName("", emptySet()))
        assertEquals("Playlist.m3u", M3u.fileName("   ", emptySet()))
        assertEquals("Playlist.m3u", M3u.fileName("???", emptySet()))
    }

    @Test
    fun `a name already handed out gets a counter, whatever its case`() {
        assertEquals("Mix (2).m3u", M3u.fileName("Mix", setOf("mix.m3u")))
        assertEquals("Mix (3).m3u", M3u.fileName("Mix", setOf("Mix.m3u", "MIX (2).m3u")))
        assertEquals("Mix.m3u", M3u.fileName("Mix", setOf("Mix (2).m3u")))
    }

    @Test
    fun `two playlists that sanitise to the same name get different files`() {
        val used = HashSet<String>()
        val first = M3u.fileName("a/b", used)
        used += first
        val second = M3u.fileName("a:b", used)
        used += second
        val third = M3u.fileName("A\\B", used)
        assertEquals("a b.m3u", first)
        assertEquals("a b (2).m3u", second)
        assertEquals("A B (3).m3u", third)
    }
}
