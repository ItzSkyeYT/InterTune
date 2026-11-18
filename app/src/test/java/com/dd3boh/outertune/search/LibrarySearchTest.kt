/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import com.dd3boh.outertune.viewmodels.LocalFilter
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
    fun `the database is asked for each word, and lone letters together as typed`() {
        assertEquals(listOf("c"), LibrarySearch.asked("c"))
        assertEquals(listOf("toe"), LibrarySearch.asked("c toe"))
        assertEquals(listOf("good", "kid"), LibrarySearch.asked("good kid"))
        assertFalse(LibrarySearch.apart("cicada"))
        assertFalse("a word twice is one word", LibrarySearch.apart("go Go"))
        assertTrue(LibrarySearch.apart("cicada  good"))
    }

    @Test
    fun `lone letters are asked together, as typed, not the first letter alone`() {
        // "a" alone is in most of a library: every title with an a and every song of an artist with one.
        // "c t" is on the way to "c toe" for anyone who stops typing for a moment after the t.
        assertEquals(listOf("a b"), LibrarySearch.asked("a b"))
        assertEquals(listOf("c t"), LibrarySearch.asked("c t"))
        assertFalse(LibrarySearch.apart("c t"))
        assertEquals("the overview's three, as for one word", 3, LibrarySearch.limit("c t", 3))
        // One more letter and the words go apart again, the lone letter kept for the filter.
        assertEquals(listOf("to"), LibrarySearch.asked("c to"))
        assertTrue(LibrarySearch.apart("c to"))
        // What the phrase finds has every letter, so the filter drops none of it.
        val phrase = listOf(Row("Magic Touch", listOf("Someone")), Row("Electric Tears", listOf("Toe")))
        assertEquals(listOf("Electric Tears", "Magic Touch"), search("c t", phrase).sorted())
    }

    @Test
    fun `each word apart comes back a few hundred rows at most, words together as before`() {
        val cap = LibrarySearch.ROWS_PER_WORD
        assertTrue("a few hundred", cap in 100..500)
        assertEquals(cap, LibrarySearch.limit("c toe", 3))
        assertEquals(cap, LibrarySearch.limit("never gonna give you up", Int.MAX_VALUE))
        assertEquals("one word: the overview's three", 3, LibrarySearch.limit("c", 3))
        assertEquals("one word: a chip's whole list, as before", Int.MAX_VALUE, LibrarySearch.limit("c", Int.MAX_VALUE))
        // The view model asks every query with a limit from there: none asks for every row of a word.
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/LocalSearchViewModel.kt").readText()
        assertTrue(model.contains("val preview = LibrarySearch.limit(query, PREVIEW_SIZE)"))
        assertTrue(model.contains("val listed = LibrarySearch.limit(query, Int.MAX_VALUE)"))
        val asks = Regex("""found\((\w+), ([\w.]+)\) \{ term, n -> database\.search\w+\(term, n\) \}""").findAll(model)
            .map { it.groupValues[1] + ", " + it.groupValues[2] }.toList()
        assertEquals("the overview's five, the chips' five", 10, asks.size)
        for (ask in asks) assertTrue(ask, ask == "preview, PREVIEW_SIZE" || ask == "listed, Int.MAX_VALUE")
        // Words apart, the phrase as typed is asked too with one word's limit: a two-word artist's songs come whole.
        assertTrue(model.contains("asked.map { search(it, perWord) } + search(query.trim(), phrase)"))
    }

    @Test
    fun `each result is ranked once, and off the main thread`() {
        var named = 0
        LibrarySearch.order("c", shown, { named++; it.title }, { it.artists })
        assertEquals(shown.size, named)
        named = 0
        val forToe = listOf(Row("Tiptoe Cricket", listOf("Someone")), Row("Goon", listOf("Toe")), Row("c", listOf("Toe")))
        LibrarySearch.order("c toe", forToe, { named++; it.title }, { it.artists })
        assertEquals(forToe.size, named)
        // The queries' rows put together and ranked on Dispatchers.Default, not on the main thread
        // that collects the result.
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/LocalSearchViewModel.kt").readText()
        val flowOn = model.indexOf(".flowOn(Dispatchers.Default)")
        assertTrue(flowOn > model.indexOf("LibrarySearch.order(query, items"))
        assertTrue(flowOn in 0 until model.indexOf(".stateIn(viewModelScope"))
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

    @Test
    fun `an empty library search keeps No results found in every language, and its button is translated too`() {
        // "No results found" is in 41 languages: replaced by a text held from translators, 40 of them
        // read English. It stays the line; the place it looked goes under it.
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/search/LocalSearchScreen.kt").readText()
        val empty = screen.substringAfter("key = \"no_result\"").substringBefore("LazyColumnScrollbar(")
        assertTrue(empty.contains("EmptyPlaceholder(\n                            icon = Icons.Rounded.Search,\n                            text = stringResource(R.string.no_results_found),"))
        assertTrue(empty.indexOf("R.string.no_results_found") < empty.indexOf("R.string.library_search_none"))
        // The button in the search bar's own words, translated wherever the bar is.
        assertTrue(empty.contains("Text(searchOnlineLabel(stringResource(R.string.search_yt_music)))"))
        val res = File("src/main/res")
        val held = File(res, "values/strings-ot.xml").readText()
        assertFalse("no held text where a translated one says the same", held.contains("name=\"library_search_online\""))
        assertEquals("Search YouTube Music", com.dd3boh.outertune.ui.screens.search.searchOnlineLabel("Search YouTube Music…"))
        assertEquals("Søk gjennom YouTube Music", com.dd3boh.outertune.ui.screens.search.searchOnlineLabel("Søk gjennom YouTube Music …"))
        assertEquals("Search YouTube Music", com.dd3boh.outertune.ui.screens.search.searchOnlineLabel("Search YouTube Music..."))
        // Every translation of the bar's words makes a whole button.
        val bar = Regex("""<string name="search_yt_music">([^<]+)</string>""")
        val labels = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .mapNotNull { File(it, "strings.xml").takeIf { f -> f.isFile } }
            .mapNotNull { bar.find(it.readText())?.groupValues?.get(1) }
        assertTrue(labels.size > 40)
        for (label in labels) {
            val button = com.dd3boh.outertune.ui.screens.search.searchOnlineLabel(label)
            assertTrue(label, button.isNotBlank() && !button.endsWith("…") && !button.endsWith("."))
        }
    }

    @Test
    fun `Not in your library only where it is true, under All and Songs`() {
        // Under Albums, Artists or Playlists a song that is in the library is not found ("c" under
        // Albums): "No results found" alone there.
        assertTrue(com.dd3boh.outertune.ui.screens.search.saysNotInLibrary(LocalFilter.ALL))
        assertTrue(com.dd3boh.outertune.ui.screens.search.saysNotInLibrary(LocalFilter.SONG))
        for (filter in listOf(LocalFilter.ALBUM, LocalFilter.ARTIST, LocalFilter.PLAYLIST)) {
            assertFalse(filter.name, com.dd3boh.outertune.ui.screens.search.saysNotInLibrary(filter))
        }
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/search/LocalSearchScreen.kt").readText()
        val empty = screen.substringAfter("key = \"no_result\"").substringBefore("LazyColumnScrollbar(")
        val line = empty.substringAfter("if (saysNotInLibrary(result.filter)) {").substringBefore("}")
        assertTrue("the line is the one under that test", line.contains("text = stringResource(R.string.library_search_none),"))
        assertEquals(1, Regex("""R\.string\.library_search_none""").findAll(empty).count())
        // The button stays under every chip.
        assertFalse(empty.substringBefore("Text(searchOnlineLabel(").substringAfter("if (onSearchOnline != null) {").contains("filter"))
    }
}
