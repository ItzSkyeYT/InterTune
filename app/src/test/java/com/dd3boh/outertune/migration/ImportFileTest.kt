/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files people actually have: one per exporter, written the way that exporter writes them,
 * in src/test/resources/import.
 */
class ImportFileTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/import/$name")) { "missing fixture $name" }
            .readBytes().toString(Charsets.UTF_8)

    private fun parsed(name: String, fallback: String = "From file"): ImportParse.Parsed {
        val result = ImportFile.parse(fixture(name), fallback)
        assertTrue("expected $name to parse, got $result", result is ImportParse.Parsed)
        return result as ImportParse.Parsed
    }

    @Test
    fun `exportify app reads with its BOM, CRLF and quoted commas`() {
        val result = parsed("exportify_app.csv", fallback = "Liked Songs")
        assertEquals(ExportFormat.EXPORTIFY, result.format)
        assertEquals(1, result.playlists.size)
        assertEquals("Liked Songs", result.playlists[0].name)

        val tracks = result.playlists[0].tracks
        assertEquals(5, tracks.size)
        // The local file with no title is the one row that cannot be searched for.
        assertEquals(1, result.skippedRows)

        val lucky = tracks[0]
        assertEquals("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", lucky.title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), lucky.artists)
        assertEquals("Random Access Memories", lucky.album)
        assertEquals(370, lucky.durationSeconds)
        assertEquals("USQX91300108", lucky.isrc)

        assertEquals("Hello, Goodbye - Remastered 2009", tracks[3].title)
        assertEquals("The \"Real\" Slim Shady", tracks[4].title)
    }

    @Test
    fun `an escaped comma stays inside the artist's name`() {
        val tyler = parsed("exportify_app.csv").playlists[0].tracks[1]
        assertEquals(listOf("Tyler, The Creator"), tyler.artists)
        assertEquals(190, tyler.durationSeconds)
    }

    @Test
    fun `exportify app in French reads the same`() {
        val file = "\"URI du titre\",\"Nom du titre\",\"Nom(s) de l'artiste\",\"Nom de l'album\",\"Durée du titre (ms)\"\r\n" +
            "\"spotify:track:1\",\"La Vie en rose\",\"Édith Piaf\",\"La Vie en rose\",\"186000\"\r\n"
        val result = ImportFile.parse(file, "Chansons") as ImportParse.Parsed
        assertEquals(ExportFormat.EXPORTIFY, result.format)
        val track = result.playlists.single().tracks.single()
        assertEquals("La Vie en rose", track.title)
        assertEquals(listOf("Édith Piaf"), track.artists)
        assertEquals(186, track.durationSeconds)
    }

    @Test
    fun `exportify net joins artists with semicolons and leaves commas alone`() {
        val result = parsed("exportify_net.csv", fallback = "Gym")
        assertEquals(ExportFormat.EXPORTIFY_NET, result.format)
        val tracks = result.playlists.single().tracks
        assertEquals(
            listOf("Blinding Lights", "Shape of You", "Rather Be (feat. Jess Glynne)", "Hey Jude"),
            tracks.map { it.title },
        )
        assertEquals(200, tracks[0].durationSeconds)
        assertEquals("÷ (Deluxe)", tracks[1].album)
        assertEquals(listOf("Clean Bandit", "Jess Glynne"), tracks[2].artists)
        assertEquals(listOf("The Beatles", "Tyler, The Creator"), tracks[3].artists)
        assertNull(tracks[0].isrc)
    }

    @Test
    fun `TuneMyMusic's playlist column makes several playlists in the order they first appear`() {
        val result = parsed("tunemymusic.csv")
        assertEquals(ExportFormat.TUNEMYMUSIC, result.format)
        assertEquals(listOf("Road trip", "Calm"), result.playlists.map { it.name })

        val roadTrip = result.playlists[0].tracks
        // A row for Road trip after the Calm rows still lands in Road trip, after its others.
        assertEquals(listOf("Bohemian Rhapsody", "September", "Dancing Queen", "Under Pressure"), roadTrip.map { it.title })
        assertEquals(listOf("Clair de Lune", "Gymnopédie No. 1", "Bohemian Rhapsody"), result.playlists[1].tracks.map { it.title })

        // Two artists are two. A band with a comma in its name cannot be told from two artists
        // without a list of bands, so it splits too; the matcher's joined score still sees every
        // word of it. Pinned here so that changing it is a decision.
        assertEquals(listOf("Queen", "David Bowie"), roadTrip[3].artists)
        assertEquals(listOf("Earth", "Wind & Fire"), roadTrip[1].artists)
        assertEquals("Earth, Wind & Fire", roadTrip[1].artist)
        assertNull(roadTrip[0].durationSeconds)
        assertEquals("GBUM71029604", roadTrip[0].isrc)
    }

    @Test
    fun `Soundiiz reads its lower case header and its lengths in seconds`() {
        val result = parsed("soundiiz.csv", fallback = "Soundiiz export")
        assertEquals(ExportFormat.SOUNDIIZ, result.format)
        val tracks = result.playlists.single().tracks
        assertEquals("Soundiiz export", result.playlists.single().name)
        assertEquals(listOf("Smells Like Teen Spirit", "Thunderstruck", "Despacito"), tracks.map { it.title })
        assertEquals(listOf(301, 292, 229), tracks.map { it.durationSeconds })
        // A slash is not a separator.
        assertEquals(listOf("AC/DC"), tracks[1].artists)
        assertEquals(listOf("Luis Fonsi", "Daddy Yankee"), tracks[2].artists)
        assertEquals("USUM71700626", tracks[2].isrc)
    }

    @Test
    fun `a Soundiiz album list is turned away with its own answer`() {
        val result = ImportFile.parse(fixture("soundiiz_albums.csv"), "x")
        assertEquals(ImportParse.Problem.NOT_SONGS, (result as ImportParse.Failed).problem)
    }

    @Test
    fun `Apple Music's UTF-16 tab separated playlist export reads`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/import/apple_music.txt")).readBytes()
        val result = ImportFile.parse(ImportFile.decode(bytes), "Favourites") as ImportParse.Parsed
        assertEquals(ExportFormat.APPLE_MUSIC, result.format)
        val tracks = result.playlists.single().tracks
        assertEquals(listOf("Hyperballad", "Teardrop"), tracks.map { it.title })
        assertEquals(listOf("Björk"), tracks[0].artists)
        assertEquals(321, tracks[0].durationSeconds)
        assertEquals("Mezzanine", tracks[1].album)
    }

    @Test
    fun `Apple Music's library XML becomes the library and the playlists someone made`() {
        val result = parsed("apple_library.xml", fallback = "Library")
        assertEquals(ExportFormat.APPLE_MUSIC_LIBRARY, result.format)
        // Apple's own Library and Music lists and the folder are left out; the podcast is not music.
        assertEquals(listOf("Library", "Trip hop"), result.playlists.map { it.name })
        assertEquals(listOf("Hyperballad", "Teardrop", "Glory Box"), result.playlists[0].tracks.map { it.title })
        assertEquals(listOf("Glory Box", "Teardrop"), result.playlists[1].tracks.map { it.title })
        assertEquals(1, result.skippedRows)

        val first = result.playlists[0].tracks[0]
        assertEquals(listOf("Björk"), first.artists)
        assertEquals("Post", first.album)
        assertEquals(321, first.durationSeconds)
    }

    @Test
    fun `broken XML is an answer, not a crash`() {
        val result = ImportFile.parse("<?xml version=\"1.0\"?>\n<!DOCTYPE plist>\n<plist><dict><key>Tracks</key>", "x")
        assertTrue("got $result", result is ImportParse.Failed)
    }

    @Test
    fun `a zip of playlists becomes one playlist per file, named after it`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("liked_songs.csv", fixture("exportify_app.csv"))
            put("__MACOSX/._liked_songs.csv", "junk")
            put("road_trip.csv", "\"Track Name\",\"Artist Name(s)\",\"Track Duration (ms)\"\n\"Africa\",\"TOTO\",\"295893\"\n")
            put("cover.jpg", "not a playlist")
        }
        val bytes = out.toByteArray()
        assertTrue(ImportFile.isZip(bytes))
        val result = ImportFile.parseArchive(bytes) as ImportParse.Parsed
        assertEquals(ExportFormat.EXPORTIFY, result.format)
        assertEquals(listOf("Liked songs", "Road trip"), result.playlists.map { it.name })
        assertEquals(5, result.playlists[0].tracks.size)
        assertEquals(296, result.playlists[1].tracks.single().durationSeconds)
    }

    @Test
    fun `file names read back as playlist names`() {
        assertEquals("Road trip", ImportFile.nameFromFile("road_trip.csv"))
        assertEquals("Chill Vibes 2024", ImportFile.nameFromFile("Chill Vibes 2024.csv"))
        assertEquals("Library", ImportFile.nameFromFile("Library.xml"))
    }

    @Test
    fun `a spreadsheet's semicolons are read as the delimiter`() {
        val result = ImportFile.parse("Title;Artist;Duration\r\n\"Hey Jude\";The Beatles;7:11\r\n", "x") as ImportParse.Parsed
        val track = result.playlists.single().tracks.single()
        assertEquals("Hey Jude", track.title)
        assertEquals(listOf("The Beatles"), track.artists)
        assertEquals(431, track.durationSeconds)
    }

    @Test
    fun `a file with no title column says so rather than failing`() {
        val result = ImportFile.parse(fixture("not_an_export.csv"), "x")
        assertTrue(result is ImportParse.Failed)
        result as ImportParse.Failed
        assertEquals(ImportParse.Problem.NO_TITLE_COLUMN, result.problem)
        assertEquals("Date, Amount, Description", result.detail)
    }

    @Test
    fun `empty, header only and binary files each get their own answer`() {
        assertEquals(ImportParse.Problem.EMPTY, (ImportFile.parse("", "x") as ImportParse.Failed).problem)
        assertEquals(ImportParse.Problem.EMPTY, (ImportFile.parse("﻿\r\n\r\n", "x") as ImportParse.Failed).problem)
        assertEquals(
            ImportParse.Problem.NO_TRACKS,
            (ImportFile.parse("Track Name,Artist Name(s)\r\n", "x") as ImportParse.Failed).problem
        )
        assertEquals(
            ImportParse.Problem.NOT_TEXT,
            (ImportFile.parse("PK\u0003\u0004\u0000\u0000binary", "x") as ImportParse.Failed).problem
        )
    }

    @Test
    fun `quoted fields keep delimiters, doubled quotes and line breaks`() {
        val rows = ImportFile.readCsv("a,\"b,c\",\"say \"\"hi\"\"\"\n\"two\nlines\",x,\n")
        assertEquals(listOf(listOf("a", "b,c", "say \"hi\""), listOf("two\nlines", "x", "")), rows)
    }

    @Test
    fun `a plain CSV with a title column still imports`() {
        val result = ImportFile.parse("Song,Artist,Length\nHey Jude,The Beatles,7:11\n", "Mine")
        result as ImportParse.Parsed
        assertEquals(ExportFormat.CSV, result.format)
        assertEquals(431, result.playlists.single().tracks.single().durationSeconds)
    }

    @Test
    fun `a list of names with no artist is not taken for songs`() {
        val result = ImportFile.parse("name,platform,url\nDaft Punk,spotify,https://example.org\n", "x")
        assertEquals(ImportParse.Problem.NO_TITLE_COLUMN, (result as ImportParse.Failed).problem)
    }

    @Test
    fun `lengths read as clock time, seconds or milliseconds`() {
        assertEquals(205, ImportFile.lengthToSeconds("3:25"))
        assertEquals(3723, ImportFile.lengthToSeconds("1:02:03"))
        assertEquals(205, ImportFile.lengthToSeconds("205"))
        assertEquals(205, ImportFile.lengthToSeconds("205000"))
        assertEquals(157, ImportFile.lengthToSeconds("157s"))
        assertNull(ImportFile.lengthToSeconds("soon"))
    }

    @Test
    fun `artists split the way each tool joins them`() {
        assertEquals(
            listOf("Simon & Garfunkel"),
            ImportFile.splitArtists("Simon & Garfunkel", ExportFormat.TUNEMYMUSIC)
        )
        assertEquals(
            listOf("Tyler, The Creator", "Kali Uchis"),
            ImportFile.splitArtists("Tyler, The Creator, Kali Uchis", ExportFormat.SOUNDIIZ)
        )
        assertEquals(
            listOf("Tyler, The Creator", "Kali Uchis"),
            ImportFile.splitArtists("Tyler\\, The Creator,Kali Uchis", ExportFormat.EXPORTIFY)
        )
        assertEquals(emptyList<String>(), ImportFile.splitArtists("  ", ExportFormat.CSV))
    }
}
