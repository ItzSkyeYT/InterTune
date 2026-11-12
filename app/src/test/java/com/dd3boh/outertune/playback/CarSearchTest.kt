/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A search typed into the car finds songs on YouTube Music in a release too, not only in the
 * library (#112: 0.12.9 in a Ford SYNC 3 found nothing for any artist typed, Spotify's search
 * did). The question to YouTube Music sat behind Unreleased.AUTO_REWORK, which only the debug and
 * preview builds have on, so the debug app in the head unit always looked fine.
 *
 * What is decided is CarSearch's and is tested as it is. What MediaLibrarySessionCallback does with
 * it needs a car, a device or a browser of its own (tools/auto/browser-probe), so the wiring is read
 * from the source, as CarSearchRadioTest does.
 */
class CarSearchTest {

    @Test
    fun `YouTube Music is asked for anything of two letters or more the library has too little of`() {
        assertTrue(CarSearch.asksOnline("Daft Punk", inLibrary = 0))
        assertTrue("U2 is a band", CarSearch.asksOnline("U2", inLibrary = 0))
        assertTrue(CarSearch.asksOnline("  U2 ", inLibrary = CarSearch.MOST - 1))
        assertFalse("one letter is not a question yet", CarSearch.asksOnline("u", inLibrary = 0))
        assertFalse(CarSearch.asksOnline("   ", inLibrary = 0))
        assertFalse("a library that answers in full is not added to", CarSearch.asksOnline("Daft Punk", inLibrary = CarSearch.MOST))
    }

    @Test
    fun `only a question asked and not answered says why, and offline is told apart`() {
        assertNull(CarSearch.miss(asked = false, answered = false, online = false))
        assertNull(CarSearch.miss(asked = true, answered = true, online = false))
        assertEquals(CarSearch.Miss.OFFLINE, CarSearch.miss(asked = true, answered = false, online = false))
        assertEquals(CarSearch.Miss.NO_ANSWER, CarSearch.miss(asked = true, answered = false, online = true))
    }

    @Test
    fun `the row that searches again names its query, slashes and all, and is never taken for a song to play`() {
        val id = CarSearch.againId("AC/DC thunderstruck")
        assertEquals("AC/DC thunderstruck", CarSearch.againQuery(id))
        assertNull(CarSearch.againQuery("${CarSearch.AGAIN}/"))
        assertNull(CarSearch.againQuery("${MusicService.SEARCH}/AC/DC/song"))
        assertNull(CarSearch.againQuery(AutoBrowse.HOME))
        // It opens; were it ever sent to be played, it names nothing to play.
        assertNull(PlayRequest.parse(id))
        assertNull(PlayRequest.parse(CarSearch.againId("daft punk")))
    }

    @Test
    fun `an answer is cut to the page asked for, and a browser that names none gets it all`() {
        val all = (1..72).toList()
        assertEquals(all, CarSearch.page(all, 0, Int.MAX_VALUE))
        // The car's own screens ask for 50, and media3 refuses an answer longer than the page.
        assertEquals((1..50).toList(), CarSearch.page(all, 0, 50))
        assertEquals((51..72).toList(), CarSearch.page(all, 1, 50))
        assertEquals(emptyList<Int>(), CarSearch.page(all, 2, 50))
        assertEquals("no overflow past the first page of everything", emptyList<Int>(), CarSearch.page(all, 1, Int.MAX_VALUE))
        assertEquals(all, CarSearch.page(all, -1, 50))
        assertEquals(all, CarSearch.page(all, 0, 0))
    }

    // The wiring, read from the source.

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val callback = source("playback/MediaLibrarySessionCallback.kt")

    /** The function [name] declares in [text], from its name to the next member of the class (its comment or itself). */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("no function $name", start >= 0)
        val next = Regex("\n    (/\\*\\*|(private |override |internal )?(suspend )?fun |private class )").find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, next)
    }

    @Test
    fun `a release asks YouTube Music too, whatever the car's screens are`() {
        val find = body(callback, "findForCar")
        assertFalse("the car's search hangs on a flag again:\n$find", "Unreleased." in find)
        assertTrue("the car's search no longer decides by CarSearch whether to ask", "CarSearch.asksOnline(query, library.size)" in find)
        assertTrue("the car's search no longer asks YouTube Music's songs", "YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)" in find)
        // The search said to the car and the tapped result go through the same search.
        assertTrue(body(callback, "searchCar").contains("findForCar(query).songs"))
    }

    @Test
    fun `a search that took too long is told from one that failed, and neither is cancelled into a failure`() {
        val find = body(callback, "findForCar")
        assertTrue("the wait is no longer bounded", "withTimeoutOrNull(SEARCH_ONLINE_MS)" in find)
        // A runCatching around a suspending call swallows the timeout's own cancellation.
        assertTrue("a cancellation is caught as a failure", "catch (e: CancellationException) {\n                    throw e" in find)
        assertTrue("a failed search is cached as an answer", "if (found.isNotEmpty()) searchedOnline = query to found" in find)
        assertTrue("why is no longer handed on", "CarSearch.miss(asked = true, answered = answered" in find)
    }

    @Test
    fun `the log says what the search did, and never what was searched`() {
        val find = body(callback, "findForCar")
        val logged = Regex("""Log\.[iw]\(TAG, "[^"]*"""").findAll(find).map { it.value }.toList()
        assertEquals("three lines: too long, failed, answered", 3, logged.size)
        for (line in logged) assertFalse("a report's log would carry the query: $line", "\$query" in line || "{query" in line)
        assertTrue("a failure is logged with its addresses", "ErrorText.forLog(it)" in find)
    }

    @Test
    fun `the car's list of results pages, and ends on the row that says why when YouTube Music gave nothing`() {
        val results = body(callback, "onGetSearchResult")
        assertTrue("the results are no longer cut to the page asked for", "CarSearch.page(carSearchItems(query), page, pageSize)" in results)
        val items = body(callback, "carSearchItems")
        assertTrue("the songs are no longer playable and not browsable", "isPlayable = true, isBrowsable = false" in items)
        assertTrue("the row that says why is gone", "found.miss?.let { searchAgainRow(query, it) }" in items)
        val row = body(callback, "searchAgainRow")
        for (name in listOf("R.string.auto_search_offline", "R.string.auto_search_no_answer", "R.string.auto_search_again")) {
            assertTrue("the row no longer says $name", name in row)
        }
        // A list that opens on the search again, in the tree and in the car's own screens.
        assertTrue("the row is no longer a list", "browsableMediaItem(" in row && "CarSearch.againId(query)" in row)
        val children = body(callback, "onGetChildren")
        assertTrue("the row opens on nothing", "parentId.startsWith(\"\${CarSearch.AGAIN}/\") -> CarSearch.againQuery(parentId)?.let { carSearchItems(it) }" in children)
    }

    @Test
    fun `the row's words are there in English and in French`() {
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val text = File(path).readText()
            for (name in listOf("auto_search_offline", "auto_search_no_answer", "auto_search_again")) {
                assertTrue("$path has no $name", "<string name=\"$name\">" in text)
            }
        }
    }
}
