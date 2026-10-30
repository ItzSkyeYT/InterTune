/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.savedtwice.SavedTwice.Copy
import com.dd3boh.outertune.savedtwice.SavedTwice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which saved copies are one song (issue #47), and which one is kept unless somebody picks another.
 *
 * Both ways: what has to be found as saved twice, and what must never be, because taking a live or
 * a sped up version out of somebody's likes as a "copy" of the original loses a song they chose.
 */
class SavedTwiceTest {

    private var next = 0

    private fun copy(
        title: String,
        vararg artists: String,
        duration: Int = 200,
        album: String? = null,
        kind: Kind = if (album != null) Kind.SONG else Kind.MUSIC_VIDEO,
        savedAt: Long? = null,
        songId: String? = null,
    ): Copy {
        val n = next++
        return Copy(
            key = "k$n", songId = songId ?: "s$n", title = title, artists = artists.toList(),
            duration = duration, album = album, kind = kind, savedAt = savedAt,
        )
    }

    private fun same(a: Copy, b: Copy) {
        assertTrue("${a.title} / ${b.title} should be one song", SavedTwice.sameSong(a, b))
        assertTrue("${b.title} / ${a.title} should be one song", SavedTwice.sameSong(b, a))
        assertEquals(1, SavedTwice.groups(listOf(a, b)).size)
    }

    private fun apart(a: Copy, b: Copy) {
        assertFalse("${a.title} / ${b.title} must stay apart", SavedTwice.sameSong(a, b))
        assertFalse("${b.title} / ${a.title} must stay apart", SavedTwice.sameSong(b, a))
        assertTrue(SavedTwice.groups(listOf(a, b)).isEmpty())
    }

    // ---- what is one song ----

    @Test
    fun `the very same song twice is one song, whatever is known of it`() {
        same(copy("Misery Business", "Paramore", duration = -1, songId = "abc"), copy("Misery Business", "Paramore", duration = -1, songId = "abc"))
        same(copy("", songId = "x"), copy("", songId = "x"))
    }

    @Test
    fun `a song and its music video are one song, the video running a little longer`() {
        same(copy("Misery Business", "Paramore", duration = 211, album = "Riot!"), copy("Misery Business (Official Video)", "Paramore", duration = 222))
        same(copy("Zombie", "The Cranberries", duration = 306, album = "No Need to Argue"), copy("Zombie (Official Music Video)", "The Cranberries", duration = 307))
        // Uploads often start with the artist's name, and come from the artist's own channels.
        same(copy("Misery Business", "Paramore", duration = 211, album = "Riot!"), copy("Paramore - Misery Business (Official Video)", "Paramore - Topic", duration = 215))
        same(copy("Misery Business", "Paramore", duration = 211, album = "Riot!"), copy("Misery Business", "ParamoreVEVO", duration = 211))
    }

    @Test
    fun `tags that only describe the upload are ignored`() {
        val song = copy("Bohemian Rhapsody", "Queen", duration = 354, album = "A Night at the Opera")
        for (title in listOf(
            "Bohemian Rhapsody (Official Video)", "Bohemian Rhapsody (Official Music Video)", "Bohemian Rhapsody (Lyrics)",
            "Bohemian Rhapsody [Official Audio]", "Bohemian Rhapsody - Remastered 2011", "Bohemian Rhapsody - Remaster",
            "Bohemian Rhapsody (Remastered 2011)", "Bohemian Rhapsody (2011 Remaster)", "Bohemian Rhapsody (Official Lyric Video)",
            "Bohemian Rhapsody [HD]",
        )) {
            same(song, copy(title, "Queen", duration = 356, album = "Greatest Hits"))
        }
    }

    @Test
    fun `case, accents, apostrophes and the ampersand do not make another song`() {
        same(copy("Café del Mar", "Energy 52", duration = 445, album = "A"), copy("CAFE DEL MAR", "ENERGY 52", duration = 445, album = "B"))
        same(copy("Halo", "Beyoncé", duration = 261, album = "I Am... Sasha Fierce"), copy("Halo", "Beyonce", duration = 262, album = "Halo"))
        same(copy("Don't Stop Me Now", "Queen", duration = 209, album = "Jazz"), copy("Don’t Stop Me Now", "Queen", duration = 210, album = "Hits"))
        same(copy("Don't Stop Me Now", "Queen", duration = 209, album = "Jazz"), copy("Dont Stop Me Now", "Queen", duration = 209, album = "Hits"))
        same(copy("Rock & Roll", "Led Zeppelin", duration = 220, album = "IV"), copy("Rock and Roll", "Led Zeppelin", duration = 221, album = "Mothership"))
    }

    @Test
    fun `one artist in common is the same artist`() {
        same(copy("One Kiss", "Calvin Harris", "Dua Lipa", duration = 214, album = "One Kiss"), copy("One Kiss (Official Video)", "Calvin Harris", duration = 220))
        same(copy("Houdini", "Eminem", duration = 227, album = "The Death of Slim Shady"), copy("Houdini (feat. Someone)", "Eminem, Someone", duration = 227, album = "Houdini"))
    }

    @Test
    fun `the album's track and the single's are one song`() {
        same(copy("Houdini", "Eminem", duration = 227, album = "The Death of Slim Shady"), copy("Houdini", "Eminem", duration = 227, album = "Houdini"))
    }

    @Test
    fun `copies that match through a third are one song, in one group`() {
        val song = copy("Song", "A", duration = 200, album = "Album")
        val video = copy("Song (Official Video)", "A", duration = 212)
        val lyrics = copy("Song (Lyric Video)", "A", duration = 224)
        // The lyric video is 24 s from the song, but 12 from the video.
        assertFalse(SavedTwice.sameSong(song, lyrics))
        val groups = SavedTwice.groups(listOf(lyrics, song, copy("Other", "A"), video))
        assertEquals(1, groups.size)
        assertEquals(setOf(song.key, video.key, lyrics.key), groups.single().copies.map { it.key }.toSet())
    }

    // ---- what is never one song ----

    @Test
    fun `live versions stay apart, said in the title or by the album`() {
        val studio = copy("Zombie", "The Cranberries", duration = 306, album = "No Need to Argue")
        apart(studio, copy("Zombie (Live)", "The Cranberries", duration = 306, album = "Zombie"))
        apart(studio, copy("Zombie - Live at the Point Depot", "The Cranberries", duration = 306, album = "Live"))
        apart(studio, copy("Zombie", "The Cranberries", duration = 307, album = "MTV Unplugged"))
        apart(studio, copy("Zombie", "The Cranberries", duration = 306, album = "Live at the Point Depot"))
        apart(copy("Zombie (Official Music Video)", "The Cranberries", duration = 307), copy("Zombie (Live Video)", "The Cranberries", duration = 307))
        // Unless the name itself has the word: then both copies have it.
        same(copy("Live Forever", "Oasis", duration = 276, album = "Definitely Maybe"), copy("Live Forever (Remastered)", "Oasis", duration = 277, album = "Time Flies"))
    }

    @Test
    fun `remixes stay apart`() {
        val original = copy("Blinding Lights", "The Weeknd", duration = 200, album = "After Hours")
        apart(original, copy("Blinding Lights (Chromatics Remix)", "The Weeknd", duration = 200))
        apart(original, copy("Blinding Lights - Remix", "The Weeknd", duration = 200, album = "Blinding Lights (Remixes)"))
        apart(original, copy("Blinding Lights", "The Weeknd", duration = 200, album = "The Remixes"))
    }

    @Test
    fun `acoustic, instrumental, karaoke and cover versions stay apart`() {
        val original = copy("Someone Like You", "Adele", duration = 285, album = "21")
        apart(original, copy("Someone Like You (Acoustic)", "Adele", duration = 285))
        apart(original, copy("Someone Like You (Instrumental)", "Adele", duration = 285))
        apart(original, copy("Someone Like You (Karaoke Version)", "Adele", duration = 285))
        apart(original, copy("Someone Like You (Cover)", "Adele", duration = 285))
        apart(original, copy("Someone Like You", "Adele", duration = 285, album = "Acoustic Sessions"))
        // A cover by somebody else is somebody else's.
        apart(original, copy("Someone Like You", "Boyce Avenue", duration = 285, album = "Covers Vol. 2"))
    }

    @Test
    fun `sped up and slowed versions stay apart, at any length`() {
        val original = copy("Heather", "Conan Gray", duration = 198, album = "Kid Krow")
        apart(original, copy("Heather (Sped Up)", "Conan Gray", duration = 198))
        apart(original, copy("Heather - sped up", "Conan Gray", duration = 160))
        apart(original, copy("Heather (Slowed + Reverb)", "Conan Gray", duration = 198))
        apart(original, copy("Heather (Nightcore)", "Conan Gray", duration = 198))
        apart(original, copy("Heather (slowed)", "Conan Gray", duration = 230))
    }

    @Test
    fun `the same title by another artist is another song`() {
        apart(copy("Hello", "Adele", duration = 295, album = "25"), copy("Hello", "Lionel Richie", duration = 295, album = "Can't Slow Down"))
        // With no artist to compare, nothing says it is the same song.
        apart(copy("Hello", duration = 295, album = "A"), copy("Hello", duration = 295, album = "B"))
    }

    @Test
    fun `lengths too far apart are other recordings`() {
        // An edit, or another arrangement under the same name.
        apart(copy("Song", "A", duration = 260, album = "Album"), copy("Song (Radio Edit)", "A", duration = 200, album = "Single"))
        apart(copy("Song", "A", duration = 200, album = "Album"), copy("Song", "A", duration = 205, album = "Other"))
        // A video gets a few seconds more, not half a minute.
        apart(copy("Song", "A", duration = 211, album = "Album"), copy("Song (Official Video)", "A", duration = 240))
        // Not known: not the same.
        apart(copy("Song", "A", duration = -1, album = "Album"), copy("Song (Official Video)", "A", duration = 211))
        apart(copy("Song", "A", duration = 0, album = "Album"), copy("Song", "A", duration = 0, album = "Other"))
    }

    @Test
    fun `parts, versions of the artist's own and other names stay apart`() {
        apart(copy("Song (Part 1)", "A", duration = 200, album = "X"), copy("Song (Part 2)", "A", duration = 200, album = "X"))
        apart(copy("Love Story", "Taylor Swift", duration = 235, album = "Fearless"), copy("Love Story (Taylor's Version)", "Taylor Swift", duration = 235, album = "Fearless (Taylor's Version)"))
        apart(copy("Song", "A", duration = 200, album = "X"), copy("Song 2", "A", duration = 200, album = "X"))
        apart(copy("Hey", "A", duration = 200, album = "X"), copy("Hey Hey", "A", duration = 200, album = "X"))
    }

    // ---- which copy is kept ----

    @Test
    fun `the song is kept over its music video, even one saved first`() {
        val video = copy("Misery Business (Official Video)", "Paramore", duration = 222, savedAt = 1)
        val song = copy("Misery Business", "Paramore", duration = 211, album = "Riot!", savedAt = 2)
        val group = SavedTwice.groups(listOf(video, song)).single()
        assertEquals(song, group.keptByDefault)
        assertEquals(listOf(song, video), group.copies)
    }

    @Test
    fun `the album's track is kept over the single's, then the one saved first`() {
        val single = copy("Houdini", "Eminem", duration = 227, album = "Houdini", savedAt = 1)
        val album = copy("Houdini", "Eminem", duration = 227, album = "The Death of Slim Shady", savedAt = 5)
        assertEquals(album, SavedTwice.groups(listOf(single, album)).single().keptByDefault)

        val later = copy("Song", "A", duration = 200, album = "One", savedAt = 9)
        val earlier = copy("Song", "A", duration = 200, album = "Two", savedAt = 3)
        assertEquals(earlier, SavedTwice.groups(listOf(later, earlier)).single().keptByDefault)

        // A track with an album before one whose album is not known.
        val bare = copy("Song", "A", duration = 200, kind = Kind.SONG, savedAt = 1)
        val withAlbum = copy("Song", "A", duration = 200, album = "One", savedAt = 2)
        assertEquals(withAlbum, SavedTwice.groups(listOf(bare, withAlbum)).single().keptByDefault)
    }

    @Test
    fun `nothing known to tell them apart, the list's own order decides, and always the same way`() {
        val a = copy("Song", "A", duration = 200, album = "Album", songId = "same")
        val b = copy("Song", "A", duration = 200, album = "Album", songId = "same")
        assertEquals(a, SavedTwice.groups(listOf(a, b)).single().keptByDefault)
        assertEquals(b, SavedTwice.groups(listOf(b, a)).single().keptByDefault)
    }

    @Test
    fun `a file on the phone counts as a song`() {
        val file = copy("Song", "A", duration = 200, album = "Album", kind = Kind.FILE, savedAt = 5)
        val video = copy("Song (Official Video)", "A", duration = 205, savedAt = 1)
        assertEquals(file, SavedTwice.groups(listOf(video, file)).single().keptByDefault)
    }

    // ---- the counts ----

    @Test
    fun `the button counts songs, the take out button counts copies`() {
        val list = listOf(
            copy("Misery Business", "Paramore", duration = 211, album = "Riot!"),
            copy("Houdini", "Eminem", duration = 227, album = "The Death of Slim Shady"),
            copy("Zombie", "The Cranberries", duration = 306, album = "No Need to Argue"),
            copy("Misery Business (Official Video)", "Paramore", duration = 222),
            copy("Houdini", "Eminem", duration = 227, album = "Houdini"),
            copy("Zombie (Official Music Video)", "The Cranberries", duration = 307),
            copy("Zombie (Lyrics)", "The Cranberries", duration = 306),
            copy("Linger", "The Cranberries", duration = 275, album = "Everybody Else"),
        )
        val groups = SavedTwice.groups(list)
        assertEquals(3, SavedTwice.songCount(groups))
        // One of each kept: Zombie's three leave two.
        assertEquals(4, SavedTwice.toTakeOut(groups).size)
        // In the order of the list: the first copy of each song decides where its group stands.
        assertEquals(listOf("Misery Business", "Houdini", "Zombie"), groups.map { it.keptByDefault.title })
        // Every kept copy stays, every other goes, whichever is picked.
        val zombie = groups[2]
        val video = zombie.copies.first { it.title.contains("Video") }
        val out = SavedTwice.toTakeOut(groups, mapOf(zombie.id to video.key))
        assertEquals(4, out.size)
        assertFalse(video in out)
        assertTrue(zombie.keptByDefault in out)
        // A pick that names no copy of its group keeps the default.
        assertEquals(SavedTwice.toTakeOut(groups), SavedTwice.toTakeOut(groups, mapOf(zombie.id to "nobody")))
    }

    @Test
    fun `nothing saved twice, nothing to count`() {
        assertTrue(SavedTwice.groups(emptyList()).isEmpty())
        assertTrue(SavedTwice.groups(listOf(copy("Song", "A"))).isEmpty())
        assertTrue(SavedTwice.groups(listOf(copy("Song", "A", album = "X"), copy("Other", "A", album = "X"))).isEmpty())
        assertEquals(0, SavedTwice.toTakeOut(emptyList()).size)
    }

    @Test
    fun `a long list is grouped quickly`() {
        val list = (0 until 5000).map { copy("Song $it", "Artist ${it % 50}", duration = 180 + it % 60, album = "Album ${it % 300}") } +
            copy("Song 7", "Artist 7", duration = 187, album = "Elsewhere")
        val started = System.nanoTime()
        val groups = SavedTwice.groups(list)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(1, groups.size)
        assertTrue("took $ms ms", ms < 5000)
    }

    // ---- what a copy is ----

    @Test
    fun `a stored song is a file, a track or a music video`() {
        assertEquals(Kind.FILE, SavedTwice.kindOf(isLocal = true, album = null, thumbnailUrl = null))
        assertEquals(Kind.FILE, SavedTwice.kindOf(isLocal = true, album = "Riot!", thumbnailUrl = null))
        assertEquals(Kind.SONG, SavedTwice.kindOf(isLocal = false, album = "Riot!", thumbnailUrl = "https://lh3.googleusercontent.com/abc=w544-h544"))
        assertEquals(Kind.MUSIC_VIDEO, SavedTwice.kindOf(isLocal = false, album = null, thumbnailUrl = "https://i.ytimg.com/vi/aCyGvGEtOwc/sddefault.jpg"))
        assertEquals(Kind.MUSIC_VIDEO, SavedTwice.kindOf(isLocal = false, album = "", thumbnailUrl = "https://i9.ytimg.com/vi_webp/aCyGvGEtOwc/mqdefault.webp"))
        // No album and square art: a song whose album was not given.
        assertEquals(Kind.SONG, SavedTwice.kindOf(isLocal = false, album = null, thumbnailUrl = "https://lh3.googleusercontent.com/abc=w120-h120"))
        assertEquals(Kind.SONG, SavedTwice.kindOf(isLocal = false, album = null, thumbnailUrl = null))
    }

    @Test
    fun `an album named as the song, or called a single, is the single`() {
        assertTrue(SavedTwice.isSingle(copy("Houdini", "Eminem", album = "Houdini")))
        assertTrue(SavedTwice.isSingle(copy("Houdini", "Eminem", album = "Houdini - Single")))
        assertTrue(SavedTwice.isSingle(copy("Houdini (Official Audio)", "Eminem", album = "Houdini (Single)")))
        assertFalse(SavedTwice.isSingle(copy("Houdini", "Eminem", album = "The Death of Slim Shady")))
        assertFalse(SavedTwice.isSingle(copy("Houdini", "Eminem", album = null)))
    }
}
