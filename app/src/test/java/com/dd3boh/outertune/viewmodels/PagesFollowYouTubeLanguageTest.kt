/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.utils.ContentLocale
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App language: the pages left open below Settings (an artist, an album, a playlist, search results, Moods and
 * genres, a browse page, History, the account's library) kept YouTube's words in the old language until they were
 * opened again; only Home and Explore loaded again. Every change of what YouTube is asked in now goes through one
 * place, which says so, and each of those pages loads again on it.
 */
class PagesFollowYouTubeLanguageTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = File(sources, path).readText()

    @Test
    fun `a change of YouTube's language is said once, and only when it is one`() = runBlocking {
        val before = YouTube.locale
        try {
            val start = YouTubeLocale(gl = "NL", hl = "nl")
            YouTube.locale = start
            var said = 0
            val watch = launch { ContentLocale.changes.collect { said++ } }
            yield()
            ContentLocale.use(start)
            yield()
            assertEquals("the same language again is no change", 0, said)
            ContentLocale.use(start.copy(hl = "en"))
            yield()
            assertEquals(1, said)
            assertEquals(YouTubeLocale(gl = "NL", hl = "en"), YouTube.locale)
            ContentLocale.use(YouTube.locale)
            yield()
            assertEquals(1, said)
            watch.cancel()
        } finally {
            YouTube.locale = before
        }
    }

    @Test
    fun `YouTube's language is set in one place, which says so`() {
        val setters = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""YouTube\.locale\s*=[^=]""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(sources).path }.toList()
        assertEquals(listOf("utils/ContentLocale.kt"), setters)
        val use = source("utils/ContentLocale.kt").substringAfter("fun use(locale: YouTubeLocale) {").substringBefore("\n    }\n")
        assertTrue("if (locale == YouTube.locale) return" in use && "changed.tryEmit(Unit)" in use)
        val rows = source("ui/screens/settings/fragments/InterfaceFrag.kt").substringAfter("fun ColumnScope.LocalizationFrag()")
        assertEquals(2, Regex("""ContentLocale\.use\(YouTube\.locale\.copy\(""").findAll(rows).count())
    }

    /** Each page loaded from YouTube, with what loads it again. */
    private val pages = mapOf(
        "ArtistViewModel.kt" to "fetchArtistsFromYTM()",
        "ArtistItemsViewModel.kt" to "load()",
        "AlbumViewModel.kt" to "load()",
        "OnlinePlaylistViewModel.kt" to "load()",
        "MoodAndGenresViewModel.kt" to "load()",
        "YouTubeBrowseViewModel.kt" to "load()",
        "BrowseViewModel.kt" to "load()",
        "HistoryViewModel.kt" to "fetchRemoteHistory()",
        "AccountViewModel.kt" to "load()",
    )

    @Test
    fun `each page loaded from YouTube loads again in the new language`() {
        for ((file, reload) in pages) {
            val init = source("viewmodels/$file").substringAfter("    init {").substringBefore("\n    }\n")
            assertTrue("$file: $init", "viewModelScope.launch { ContentLocale.changes.collect { $reload } }" in init)
        }
    }

    @Test
    fun `search results drop what they kept and ask again for the chip on screen`() {
        val search = source("viewmodels/OnlineSearchViewModel.kt")
        val watch = search.substringAfter("ContentLocale.changes.collect {").substringBefore("\n            }\n")
        for (kept in listOf("summaryAsSent = null", "summaryAll = null", "summaryPage = null", "pagesAsSent.clear()", "itemsAll.clear()", "viewStateMap.clear()")) {
            assertTrue("$kept: $watch", kept in watch)
        }
        assertTrue("if (blockedNamed == null) load(filter.value)" in watch)
    }

    @Test
    fun `a playlist loaded again stops a load all still walking its pages first`() {
        // Its pages went after the new first page, and its old continuation came back at its end: songs were lost.
        val load = source("viewmodels/OnlinePlaylistViewModel.kt").substringAfter("private fun load() {").substringBefore("\n    }\n")
        assertTrue(load, load.indexOf("loadJob?.cancel()") in 0 until load.indexOf("viewModelScope.launch"))
    }
}
