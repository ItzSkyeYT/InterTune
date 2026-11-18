/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library search with "stupid titles" (Ti-Die, #general, 18 Nov 2026, two screenshots): a song
 * called "c" by Toe came ninth when searching "c", under Cicada, Crushxd, Apple Cider and the rest,
 * and "c toe" found nothing. Their library's songs from the first screenshot, in the order shown.
 */
class LibrarySearchTest {
    private data class Row(val title: String, val artists: List<String>, val album: String? = null)

    private val shown = listOf(
        Row("Cicada", listOf("Good Kid")),
        Row("Crushxd", listOf("Crumb")),
        Row("Apple Cider", listOf("Early Eyes")),
        Row("Chemicals", listOf("Early Eyes")),
        Row("Comfortable", listOf("Early Eyes")),
        Row("Attracted to You", listOf("PinkPantheress")),
        Row("Shut up My Moms Calling", listOf("Hotel Ugly")),
        Row("Clementine", listOf("grentperez")),
        Row("c", listOf("Toe")),
    )

    private fun search(query: String, rows: List<Row> = shown) =
        LibrarySearch.order(query, rows, { it.title }, { it.artists + listOfNotNull(it.album) }).map { it.title }

    @Test
    fun `a song called just what was typed comes first`() {
        assertEquals("c", search("c").first())
        assertEquals("c", search("C").first())
        assertEquals("c", search(" c ").first())
    }

    @Test
    fun `then titles that start with it, then a word that does, then the rest`() {
        assertEquals(
            listOf("c", "Cicada", "Crushxd", "Chemicals", "Comfortable", "Clementine", "Apple Cider", "Shut up My Moms Calling", "Attracted to You"),
            search("c"),
        )
    }

    @Test
    fun `a title and its artist in one search find the song, in either order`() {
        // What the database hands back for "toe": the songs by Toe, and any title with toe in it.
        val forToe = listOf(Row("Tiptoe Cricket", listOf("Someone")), Row("Goon", listOf("Toe")), Row("c", listOf("Toe")))
        assertEquals(listOf("c", "Tiptoe Cricket"), search("c toe", forToe))
        // "Tiptoe Cricket" holds "toe c" as it is typed, across two of its words: the song whose title and
        // artist are the two words still comes first.
        assertEquals(listOf("c", "Tiptoe Cricket"), search("toe c", forToe))
        assertEquals("a song without one of the words is left out", emptyList<String>(), search("c toe", listOf(Row("Cicada", listOf("Good Kid")))))
    }

    @Test
    fun `an album counts for a song's words too`() {
        val rows = listOf(Row("Come Together", listOf("The Beatles"), album = "Abbey Road"), Row("Come Back", listOf("Pearl Jam")))
        assertEquals(listOf("Come Together"), search("come abbey", rows))
    }

    @Test
    fun `the database is asked for each word, a lone letter only when nothing is longer`() {
        assertEquals(listOf("c"), LibrarySearch.asked("c"))
        assertEquals(listOf("toe"), LibrarySearch.asked("c toe"))
        assertEquals(listOf("good", "kid"), LibrarySearch.asked("good kid"))
        assertEquals(listOf("a"), LibrarySearch.asked("a b"))
        assertFalse(LibrarySearch.apart("cicada"))
        assertFalse("a word twice is one word", LibrarySearch.apart("go Go"))
        assertTrue(LibrarySearch.apart("cicada  good"))
    }

    @Test
    fun `an artist named as typed puts their songs before titles that only hold the word`() {
        val rows = listOf(Row("Tiptoe", listOf("Someone")), Row("Goon", listOf("Toe")))
        assertEquals(listOf("Goon", "Tiptoe"), search("toe", rows))
    }

    @Test
    fun `the library search uses it, and says where it looked when it finds nothing`() {
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/LocalSearchViewModel.kt").readText()
        assertTrue(model.contains("LibrarySearch.asked(query)"))
        assertTrue(model.contains("LibrarySearch.order(query, items, { it.title }, { othersOf(it) })"))
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/search/LocalSearchScreen.kt").readText()
        val empty = screen.substringAfter("key = \"no_result\"")
        assertTrue(empty.contains("R.string.library_search_none"))
        assertTrue(empty.contains("onSearchOnline(result.query)"))
        val bar = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()
        assertTrue("the bar hands the library its own search on YouTube Music", bar.contains("onSearchOnline = searchOnline,"))
        // Only those words, once: the bar keeps opening on the search that was picked.
        val online = bar.substringAfter("val searchOnline: (String) -> Unit = {").substringBefore("val onSearch")
        assertFalse(online.contains("searchSource ="))
    }
}
