/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import com.dd3boh.outertune.constants.ScannerM3uMatchCriteria
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [parseExtInf], and how the entries it makes compare at LEVEL_2. */
class ImportM3uDialogTest {

    @Test
    fun `an artist - title line splits on the separator`() {
        val entry = parseExtInf("#EXTINF:213,Queen - Bohemian Rhapsody")
        assertEquals(listOf("Queen"), entry.artists)
        assertEquals("Bohemian Rhapsody", entry.title)
    }

    @Test
    fun `several artists are split on the semicolon`() {
        val entry = parseExtInf("#EXTINF:200,One;Two - Duet")
        assertEquals(listOf("One", "Two"), entry.artists)
        assertEquals("Duet", entry.title)
    }

    @Test
    fun `a title-only line has no artists, not the title as its own artist`() {
        val entry = parseExtInf("#EXTINF:213,Bohemian Rhapsody")
        assertEquals(emptyList<String>(), entry.artists)
        assertEquals("Bohemian Rhapsody", entry.title)
    }

    @Test
    fun `a title that itself contains a hyphenated word is still split on the spaced separator`() {
        // ' - ' (with spaces) is the delimiter, not a bare hyphen, so a hyphenated title word is
        // left alone when there is no real artist part before it.
        val entry = parseExtInf("#EXTINF:200,Anti-Hero")
        assertEquals(emptyList<String>(), entry.artists)
        assertEquals("Anti-Hero", entry.title)
    }

    @Test
    fun `InterTune's own line for a song with no artists parses to no artists`() {
        // M3u.playlist joins an empty artist list into nothing, so an untagged local file is
        // written as " - Untitled" after the comma, with a blank name before the separator.
        val entry = parseExtInf("#EXTINF:200, - Untitled")
        assertEquals(emptyList<String>(), entry.artists)
        assertEquals("Untitled", entry.title)
    }

    // Distinct, non-null paths: compareSong's closeEnough() matches on localPath alone, and two
    // nulls would satisfy it regardless of title or artist, hiding exactly what these tests mean
    // to exercise.
    private fun mockSong(entry: ExtInfEntry) = Song(
        song = SongEntity(id = "", title = entry.title, isLocal = true, localPath = "/import/playlist_entry.mp3"),
        artists = entry.artists.map { ArtistEntity("", it) },
    )

    private fun librarySong(title: String, vararg artists: String) = Song(
        song = SongEntity(id = "lib", title = title, isLocal = true, localPath = "/library/existing_file.mp3"),
        artists = artists.map { ArtistEntity(it, it) },
    )

    @Test
    fun `a title-only entry matches an equally artistless library song at Level 2`() {
        // Local files often have no artist tag, and an entry with no artists matches them:
        // compareArtist treats two empty lists as the same artists.
        val mock = mockSong(parseExtInf("#EXTINF:213,Bohemian Rhapsody"))
        val real = librarySong("Bohemian Rhapsody")
        assertTrue(
            LocalMediaScanner.compareM3uSong(mock, real, matchStrength = ScannerM3uMatchCriteria.LEVEL_2)
        )
    }

    @Test
    fun `InterTune's own line for an artistless song matches that song at Level 2`() {
        val mock = mockSong(parseExtInf("#EXTINF:200, - Untitled"))
        val real = librarySong("Untitled")
        assertTrue(
            LocalMediaScanner.compareM3uSong(mock, real, matchStrength = ScannerM3uMatchCriteria.LEVEL_2)
        )
    }
}
