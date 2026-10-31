/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.viewmodels.libraryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rows of a list are known by what they are, not by their hash, Home's rows carry keys and kinds,
 * and play and pause reach only the playing row. Read from the source, comments taken out, as
 * DatabaseFlowsAreKeptTest does, with the key itself tried here.
 */
class StableRowsTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `an album keeps its key whatever else about it changes`() {
        val album = Album(AlbumEntity(id = "MPREb_a", title = "A", songCount = 10, duration = 2_000), downloadCount = 0, artists = emptyList())
        assertEquals(libraryKey(album), libraryKey(album.copy(downloadCount = 4)))
        assertNotEquals(libraryKey(album), libraryKey(album.copy(album = album.album.copy(id = "MPREb_b"))))
        assertEquals("album:MPREb_a", libraryKey(album))
    }

    @Test
    fun `no list is keyed or cut down by hash`() {
        for (path in listOf("ui/screens/library/LibraryScreen.kt", "ui/screens/artist/ArtistScreen.kt")) {
            val text = source(path)
            assertFalse(path, text.contains("hashCode()"))
        }
        assertTrue(source("viewmodels/LibraryViewModels.kt").contains("val items = (artists + albums + playlists).distinctBy(::libraryKey)"))
    }

    @Test
    fun `every row of Home has a key and a kind`() {
        val home = source("ui/screens/HomeScreen.kt")
        val list = home.substringAfter("LazyColumn(\n            state = lazylistState,", missingDelimiterValue = "")
            .substringBefore("LazyColumnScrollbar(")
        assertTrue("Home's list could not be found", list.length > 10_000)
        assertFalse("a row with neither:\n" + Regex(""".*\bitem \{.*""").findAll(list).joinToString("\n") { it.value }, Regex("""\bitem \{""").containsMatchIn(list))
        val rows = Regex("""\bitem\(key = "([^"]+)"(, contentType = [^)]+)?\)""").findAll(list).toList()
        assertTrue("found ${rows.size}", rows.size >= 20)
        // Only the banners stand without a kind, each one of a kind of its own.
        val noKind = rows.filter { it.groupValues[2].isEmpty() }.map { it.groupValues[1] }
        assertEquals(setOf("throttle_banner", "welcome_card", "auto_blocker_note", "announcement_banner", "poll_banner", "context_chips"), noKind.toSet())
        for (inner in listOf("items(keepListening, key = ", "itemsIndexed(it.items, key = ", "itemsIndexed(moodAndGenres, key = ")) {
            assertTrue(inner, list.contains(inner))
        }
    }

    @Test
    fun `play and pause reach only the playing row, and a row fading out keeps what it showed`() {
        assertTrue(source("ui/screens/library/LibrarySongsScreen.kt").contains("isPlaying = song.song.id == mediaMetadata?.id && isPlaying,"))
        assertTrue(source("ui/screens/AlbumScreen.kt").contains("isPlaying = song.id == mediaMetadata?.id && isPlaying,"))
        val box = source("ui/component/PlayingIndicator.kt").substringAfter("fun PlayingIndicatorBox(", missingDelimiterValue = "").substringBefore("\n}\n")
        assertTrue(box.contains("if (isActive) shownWhileActive[0] = playWhenReady"))
        assertTrue(box.contains("val playing = if (isActive) playWhenReady else shownWhileActive[0]"))
        assertTrue(box.contains("if (playing) {"))
    }
}
