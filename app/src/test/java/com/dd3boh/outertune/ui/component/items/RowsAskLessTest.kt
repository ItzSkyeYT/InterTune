/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rows of a list ask the database only what they show: a YouTube row its own table, an
 * album's tile the ids of what can be downloaded of it, and a download mark keeps its watch for as
 * long as the row shows the same song. Read from the source, comments taken out, as
 * DatabaseFlowsAreKeptTest does; the query itself is run in LibrarySqlTest.
 */
class RowsAskLessTest {

    private fun source(name: String) = File("src/main/java/com/dd3boh/outertune/ui/component/items/$name").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `a YouTube row asks only the table of what it is`() {
        val items = source("Items.kt")
        assertEquals(2, Regex("""remember\(item\.id\) \{ if \(item is SongItem\) database\.song\(item\.id\) else flowOf\(null\) \}""").findAll(items).count())
        assertEquals(2, Regex("""remember\(item\.id\) \{ if \(item is AlbumItem\) database\.album\(item\.id\) else flowOf\(null\) \}""").findAll(items).count())
    }

    @Test
    fun `an album's tile reads only what can be downloaded of it`() {
        val albums = source("AlbumItems.kt")
        assertFalse(albums.contains("database.albumSongs("))
        assertEquals(2, Regex("""remember\(album\.id\) \{ database\.albumSongsToDownload\(album\.id\) \}""").findAll(albums).count())
    }

    @Test
    fun `a download mark keeps its watch while the row shows the same song`() {
        for (name in listOf("Items.kt", "SongItems.kt")) {
            val text = source(name)
            assertFalse("$name makes a new flow each time a row is composed", Regex("""\.getDownload\([^)]*\)\.collectAsState""").containsMatchIn(text))
            assertTrue(name, Regex("""remember\((mediaMetadata|song)\.id\) \{ downloadUtil\.getDownload\(""").containsMatchIn(text))
        }
    }
}
